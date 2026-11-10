//! UI 渲染：顶部页签 + 状态栏 + 四个页面 + 模态弹窗。
//! 亮色主题，主色与 Python 端一致（#2563EB）。

mod about;
mod browser;
mod logs;
mod settings;

use chrono::TimeZone as _;
use egui::{Color32, Context, RichText, Stroke};

use crate::app::{S3App, Tab};

pub const BLUE: Color32 = Color32::from_rgb(37, 99, 235);
pub const TEXT_MUTED: Color32 = Color32::from_rgb(110, 118, 130);
pub const RED: Color32 = Color32::from_rgb(220, 70, 70);
pub const GREEN: Color32 = Color32::from_rgb(22, 140, 90);

pub fn human_size(n: i64) -> String {
    if n < 1024 {
        return format!("{} B", n);
    }
    let mut v = n as f64;
    for unit in ["KB", "MB", "GB", "TB"] {
        v /= 1024.0;
        if v.abs() < 1024.0 {
            return format!("{:.1} {}", v, unit);
        }
    }
    format!("{:.1} PB", v / 1024.0)
}

/// putTime（100ns 单位）→ 本地时间字符串。
pub fn fmt_put_time(put_time: i64) -> String {
    if put_time <= 0 {
        return "—".into();
    }
    let ms = put_time / 10_000;
    let secs = ms / 1000;
    chrono::Local
        .timestamp_opt(secs, 0)
        .single()
        .map(|t| t.format("%Y-%m-%d %H:%M").to_string())
        .unwrap_or_else(|| "—".into())
}

pub fn render(app: &mut S3App, ctx: &Context) {
    // 关闭按钮 → 拦截关闭并隐藏到托盘（真正退出走托盘菜单）
    // egui 0.31：on_close_requested 已移除，用 input.viewport().close_requested() 检测
    if ctx.input(|i| i.viewport().close_requested()) && !app.quit_requested {
        ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
        ctx.send_viewport_cmd(egui::ViewportCommand::Visible(false));
        crate::logger::info("窗口已隐藏到托盘（通过托盘菜单退出程序）");
    }

    render_top(app, ctx);
    render_status_bar(app, ctx);
    egui::CentralPanel::default().show(ctx, |ui| {
        match app.tab {
            Tab::Settings => settings::render(app, ui),
            Tab::Browser => browser::render(app, ui),
            Tab::Logs => logs::render(app, ui),
            Tab::About => about::render(app, ui),
        }
    });
    render_modal(app, ctx);

    if app.quit_requested {
        crate::logger::info("程序退出");
        ctx.send_viewport_cmd(egui::ViewportCommand::Close);
    }
}

fn render_top(app: &mut S3App, ctx: &Context) {
    egui::TopBottomPanel::top("tabs").show(ctx, |ui| {
        ui.add_space(4.0);
        ui.horizontal(|ui| {
            ui.label(RichText::new("S3FileSync").color(BLUE).strong().size(18.0));
            ui.separator();
            let tabs = [
                (Tab::Settings, "⚙ 设置"),
                (Tab::Browser, "📁 文件浏览"),
                (Tab::Logs, "📋 日志"),
                (Tab::About, "ℹ 关于"),
            ];
            for (tab, label) in tabs {
                let selected = app.tab == tab;
                if ui
                    .selectable_label(selected, RichText::new(label).size(15.0))
                    .clicked()
                {
                    app.tab = tab;
                }
            }
        });
        ui.add_space(4.0);
    });
}

fn render_status_bar(app: &mut S3App, ctx: &Context) {
    egui::TopBottomPanel::bottom("status").show(ctx, |ui| {
        ui.add_space(4.0);
        ui.horizontal(|ui| {
            // 运行徽标
            let (badge, color) = if !app.cfg.is_ready() {
                ("● 配置待完善", crate::ui::RED)
            } else if app.paused {
                ("● 自动同步已暂停", crate::ui::RED)
            } else {
                ("● 自动同步运行中", crate::ui::GREEN)
            };
            ui.label(RichText::new(badge).color(color));
            ui.separator();
            let mode_label = crate::config::sync_mode_label(&app.cfg.sync_mode)
                .split('（')
                .next()
                .unwrap_or("")
                .to_string();
            ui.label(RichText::new(format!("默认：{}", mode_label)).color(TEXT_MUTED));
            ui.separator();
            ui.label(
                RichText::new(&app.status).color(if app.status.contains("失败") || app.status.contains("错误") {
                    RED
                } else {
                    Color32::from_gray(60)
                }),
            );
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if app.sync_busy {
                    ui.add(egui::Spinner::new());
                    ui.label("同步中…");
                } else {
                    // 单跑菜单
                    let btn = ui.menu_button("单跑 ▾", |ui| {
                        let enabled = app.cfg.is_ready();
                        ui.set_min_width(180.0);
                        if ui
                            .add_enabled(enabled, egui::Button::new("按设置的方向（一轮）"))
                            .clicked()
                        {
                            app.start_sync(None);
                        }
                        if ui.add_enabled(enabled, egui::Button::new("仅上传一轮")).clicked() {
                            app.start_sync(Some("upload"));
                        }
                        if ui.add_enabled(enabled, egui::Button::new("仅下载一轮")).clicked() {
                            app.start_sync(Some("download"));
                        }
                        if ui.add_enabled(enabled, egui::Button::new("双向一轮")).clicked() {
                            app.start_sync(Some("both"));
                        }
                    });
                    let _ = btn;
                }
                if ui
                    .button(if app.paused { "恢复自动" } else { "暂停自动" })
                    .clicked()
                {
                    app.paused = !app.paused;
                    crate::logger::info(&format!("自动同步已{}", if app.paused { "暂停" } else { "恢复" }));
                    app.schedule_next(false);
                }
                if ui.button("立即同步").clicked() {
                    app.start_sync(None);
                }
            });
        });
        ui.add_space(4.0);
    });
}

fn render_modal(app: &mut S3App, ctx: &Context) {
    let Some((title, msg)) = app.test_result.clone() else {
        return;
    };
    let mut open = true;
    egui::Window::new(&title)
        .open(&mut open)
        .collapsible(false)
        .resizable(false)
        .min_width(360.0)
        .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
        .show(ctx, |ui| {
            ui.set_min_width(420.0);
            ui.add_space(4.0);
            ui.label(&msg);
            ui.add_space(8.0);
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui.button("知道了").clicked() {
                    app.test_result = None;
                }
                // 一键复制内容
                if ui.button("复制").clicked() {
                    ui.output_mut(|o| {
                        o.commands
                            .push(egui::OutputCommand::CopyText(msg.clone()));
                    });
                }
            });
        });
    if !open {
        app.test_result = None;
    }
}

/// 卡片容器
pub fn card(ui: &mut egui::Ui, title: &str, add: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::new()
        .fill(Color32::from_rgb(249, 250, 251))
        .stroke(Stroke::new(1.0_f32, Color32::from_rgb(225, 228, 232)))
        .corner_radius(6.0f32)
        .inner_margin(12.0)
        .show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.label(RichText::new(title).strong().color(BLUE));
            ui.add_space(6.0);
            add(ui);
        });
}

pub fn muted(ui: &mut egui::Ui, text: impl Into<String>) {
    ui.label(RichText::new(text.into()).small().color(TEXT_MUTED));
}
