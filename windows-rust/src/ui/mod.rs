//! UI 渲染：左侧导航栏 + 状态栏 + 四个页面 + 模态弹窗。
//! 亮色主题：浅灰画布 + 白色卡片，主色与 Python 端一致（#2563EB）。

mod about;
mod browser;
mod logs;
mod settings;

use chrono::TimeZone as _;
use egui::{Color32, Context, RichText, Stroke};

use crate::app::{S3App, Tab};

pub const BLUE: Color32 = Color32::from_rgb(37, 99, 235);
pub const TEXT_MUTED: Color32 = Color32::from_rgb(110, 118, 130);
pub const TEXT_DARK: Color32 = Color32::from_rgb(0x33, 0x3A, 0x45);
pub const RED: Color32 = Color32::from_rgb(220, 70, 70);
pub const GREEN: Color32 = Color32::from_rgb(22, 140, 90);
/// 画布底色（浅灰，与白色卡片形成层次）
pub const CANVAS: Color32 = Color32::from_rgb(0xF5, 0xF6, 0xF8);
pub const BORDER: Color32 = Color32::from_rgb(0xE4, 0xE7, 0xEC);

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

    render_nav(app, ctx);
    render_status_bar(app, ctx);
    egui::CentralPanel::default()
        .frame(
            egui::Frame::new()
                .fill(CANVAS)
                .inner_margin(egui::Margin::same(12)),
        )
        .show(ctx, |ui| {
            match app.tab {
                Tab::Settings => settings::render(app, ui),
                Tab::Browser => browser::render(app, ui),
                Tab::Logs => logs::render(app, ui),
                Tab::About => about::render(app, ui),
            }
        });
    render_modal(app, ctx);
    render_prefix_picker(app, ctx);

    if app.quit_requested {
        crate::logger::info("程序退出");
        ctx.send_viewport_cmd(egui::ViewportCommand::Close);
    }
}

/// 顶栏/底栏通用的白色条样式（浅灰画布上的白色横条，底部/顶部带分隔线）。
fn bar_frame() -> egui::Frame {
    egui::Frame::new()
        .fill(Color32::WHITE)
        .inner_margin(egui::Margin::symmetric(12, 8))
        .stroke(Stroke::new(1.0_f32, Color32::from_rgb(0xE8, 0xEB, 0xEF)))
}

fn render_nav(app: &mut S3App, ctx: &Context) {
    egui::SidePanel::left("nav")
        .frame(
            egui::Frame::new()
                .fill(Color32::WHITE)
                .inner_margin(egui::Margin::same(12))
                .stroke(Stroke::new(1.0_f32, BORDER)),
        )
        .exact_width(170.0)
        .resizable(false)
        .show(ctx, |ui| {
            // 品牌区：蓝色 Logo 块 + 名称
            ui.add_space(6.0);
            ui.horizontal(|ui| {
                egui::Frame::new()
                    .fill(BLUE)
                    .corner_radius(8.0f32)
                    .inner_margin(egui::Margin::symmetric(10, 7))
                    .show(ui, |ui| {
                        ui.label(RichText::new("S3").strong().color(Color32::WHITE).size(15.0));
                    });
                ui.label(RichText::new("S3FileSync").strong().size(17.0).color(TEXT_DARK));
            });
            ui.label(
                RichText::new(if app.cfg.is_s3() { "S3 兼容存储同步" } else { "七牛云 Kodo 同步" })
                    .small()
                    .color(TEXT_MUTED),
            );
            ui.add_space(14.0);

            // 导航项：选中 = 蓝底白字，未选中 = 透明底深字
            let items = [
                (Tab::Settings, "⚙", "设置"),
                (Tab::Browser, "📁", "文件浏览"),
                (Tab::Logs, "📋", "日志"),
                (Tab::About, "ℹ", "关于"),
            ];
            for (tab, icon, label) in items {
                let selected = app.tab == tab;
                let text = RichText::new(format!("{}  {}", icon, label))
                    .size(15.0)
                    .color(if selected {
                        Color32::WHITE
                    } else {
                        TEXT_DARK
                    });
                let text = if selected { text.strong() } else { text };
                let btn = egui::Button::new(text)
                .fill(if selected { BLUE } else { Color32::TRANSPARENT })
                .min_size(egui::vec2(ui.available_width(), 38.0));
                if ui.add(btn).clicked() {
                    app.tab = tab;
                }
                ui.add_space(4.0);
            }

            // 底部：版本 + 配置状态
            ui.with_layout(egui::Layout::bottom_up(egui::Align::LEFT), |ui| {
                let ready = app.cfg.is_ready();
                ui.label(
                    RichText::new(if ready { "● 已配置" } else { "● 待配置" })
                        .small()
                        .color(if ready { GREEN } else { RED }),
                );
                ui.label(
                    RichText::new(format!("v{}", crate::config::APP_VERSION))
                        .small()
                        .color(TEXT_MUTED),
                );
            });
        });
}

fn render_status_bar(app: &mut S3App, ctx: &Context) {
    egui::TopBottomPanel::bottom("status")
        .frame(bar_frame())
        .show(ctx, |ui| {
            ui.horizontal(|ui| {
            // 运行状态芯片（浅色底 + 彩色字）
            let (badge, fg, bg) = if !app.cfg.is_ready() {
                ("●  配置待完善", RED, Color32::from_rgb(0xFD, 0xEC, 0xEC))
            } else if app.paused {
                ("●  自动同步已暂停", RED, Color32::from_rgb(0xFD, 0xEC, 0xEC))
            } else {
                ("●  自动同步运行中", GREEN, Color32::from_rgb(0xE6, 0xF5, 0xEE))
            };
            chip(ui, badge, fg, bg);
            ui.add_space(4.0);
            let mode_label = crate::config::sync_mode_label(&app.cfg.sync_mode)
                .split('（')
                .next()
                .unwrap_or("")
                .to_string();
            chip(ui, &format!("方向：{}", mode_label), TEXT_MUTED, Color32::from_rgb(0xF0, 0xF2, 0xF5));
            ui.add_space(4.0);
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
                // 主操作按钮：蓝底白字加高
                if ui
                    .add(
                        egui::Button::new(
                            RichText::new("立即同步").color(Color32::WHITE).size(14.0),
                        )
                        .fill(BLUE)
                        .min_size(egui::vec2(96.0, 30.0)),
                    )
                    .clicked()
                {
                    app.start_sync(None);
                }
            });
        });
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

/// 小型信息芯片（浅色底 + 彩色字），用于状态栏徽标。
fn chip(ui: &mut egui::Ui, text: &str, fg: Color32, bg: Color32) {
    egui::Frame::new()
        .fill(bg)
        .corner_radius(10.0f32)
        .inner_margin(egui::Margin::symmetric(9, 3))
        .show(ui, |ui| {
            ui.label(RichText::new(text).small().color(fg).strong());
        });
}

/// 卡片容器：浅灰画布上的白色圆角卡片。
pub fn card(ui: &mut egui::Ui, title: &str, add: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::new()
        .fill(Color32::WHITE)
        .stroke(Stroke::new(1.0_f32, Color32::from_rgb(0xE4, 0xE7, 0xEC)))
        .corner_radius(8.0f32)
        .inner_margin(egui::Margin::same(14))
        .show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.label(RichText::new(title).strong().color(BLUE).size(15.0));
            ui.add_space(8.0);
            add(ui);
        });
}

pub fn muted(ui: &mut egui::Ui, text: impl Into<String>) {
    ui.label(RichText::new(text.into()).small().color(TEXT_MUTED));
}

/// 云端目录选择器弹窗（设置页「浏览…」）：逐级浏览云端子目录，选中回填前缀字段。
fn render_prefix_picker(app: &mut S3App, ctx: &Context) {
    let Some(picker) = app.prefix_picker.as_mut() else { return };
    let mut open = true;
    let cancel = std::cell::Cell::new(false);
    let mut enter: Option<String> = None;
    let mut choose: Option<String> = None;
    let mut action_up = false;
    let mut action_refresh = false;

    let current = picker.current.clone();
    let busy = picker.busy;
    let dirs = picker.dirs.clone();
    let status = picker.status.clone();

    egui::Window::new("选择云端目录")
        .open(&mut open)
        .collapsible(false)
        .resizable(false)
        .min_width(460.0)
        .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
        .show(ctx, |ui| {
            ui.set_min_width(480.0);

            // 当前位置与操作
            ui.horizontal(|ui| {
                ui.label(
                    RichText::new(if current.is_empty() {
                        "当前：桶根目录".to_string()
                    } else {
                        format!("当前：{}/", current)
                    })
                    .strong()
                    .color(TEXT_DARK),
                );
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.button("刷新").clicked() {
                        action_refresh = true;
                    }
                    if ui
                        .add_enabled(!current.is_empty() && !busy, egui::Button::new("⬆ 上级"))
                        .clicked()
                    {
                        action_up = true;
                    }
                });
            });
            ui.separator();

            // 子目录列表
            egui::ScrollArea::vertical()
                .id_salt("prefix_picker_dirs")
                .max_height(280.0)
                .show(ui, |ui| {
                    if busy {
                        ui.horizontal(|ui| {
                            ui.add(egui::Spinner::new());
                            ui.label("正在列举子目录…");
                        });
                    } else if dirs.is_empty() {
                        ui.label(
                            RichText::new("该目录下没有子目录，可以直接使用当前目录")
                                .color(TEXT_MUTED),
                        );
                    }
                    for dir in &dirs {
                        // 显示相对当前目录的一级名称
                        let rel = if current.is_empty() {
                            dir.trim_matches('/')
                        } else {
                            dir.strip_prefix(&format!("{}/", current.trim_end_matches('/')))
                                .unwrap_or(dir)
                                .trim_end_matches('/')
                        };
                        egui::Frame::new()
                            .fill(Color32::from_rgb(0xF8, 0xF9, 0xFB))
                            .corner_radius(4.0f32)
                            .inner_margin(egui::Margin::symmetric(6, 4))
                            .show(ui, |ui| {
                                ui.horizontal(|ui| {
                                    ui.label(RichText::new(format!("📁 {}", rel)).color(BLUE));
                                    ui.with_layout(
                                        egui::Layout::right_to_left(egui::Align::Center),
                                        |ui| {
                                            if ui.button("选择").clicked() {
                                                choose = Some(dir.clone());
                                            }
                                            if ui.button("进入").clicked() {
                                                enter = Some(dir.clone());
                                            }
                                        },
                                    );
                                });
                            });
                        ui.add_space(2.0);
                    }
                });
            ui.separator();

            // 状态与底部按钮
            ui.horizontal(|ui| {
                ui.label(RichText::new(&status).small().color(TEXT_MUTED));
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.button("取消").clicked() {
                        cancel.set(true);
                    }
                    if ui
                        .add(
                            egui::Button::new(
                                RichText::new("使用当前目录").color(Color32::WHITE),
                            )
                            .fill(BLUE)
                            .min_size(egui::vec2(110.0, 30.0)),
                        )
                        .clicked()
                    {
                        choose = Some(current.clone());
                    }
                });
            });
        });

    // 事件统一处理（借用已释放）
    let closed = cancel.get() || !open;
    if !closed {
        if let Some(d) = enter {
            app.prefix_picker_enter(&d);
        }
        if action_up {
            app.prefix_picker_up();
        }
        if action_refresh {
            app.prefix_picker_refresh();
        }
        if let Some(d) = choose {
            app.prefix_picker_choose(&d);
        }
    } else {
        app.prefix_picker = None;
    }
}
