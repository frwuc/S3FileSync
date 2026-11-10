//! 设置页：账号 / 同步 / 网络，测试连接、测试上传、保存、开机自启。

use egui::{RichText, TextEdit};

use super::{card, muted, BLUE, TEXT_MUTED};
use crate::app::S3App;

pub fn render(app: &mut S3App, ui: &mut egui::Ui) {
    egui::ScrollArea::vertical().show(ui, |ui| {
        card(ui, "七牛云账号（Kodo）", |ui| {
            muted(ui, crate::config::BACKEND_NOTE);
            ui.add_space(4.0);
            egui::Grid::new("acct").num_columns(2).spacing([10.0, 8.0]).show(ui, |ui| {
                ui.label("Access Key (AK)");
                ui.add(TextEdit::singleline(&mut app.f_ak).desired_width(340.0).hint_text("七牛控制台「密钥管理」"));
                ui.end_row();

                ui.label("Secret Key (SK)");
                ui.horizontal(|ui| {
                    ui.add(TextEdit::singleline(&mut app.f_sk).desired_width(300.0).password(!app.show_sk).hint_text("仅保存在本机"));
                    if ui.button(if app.show_sk { "隐藏" } else { "显示" }).clicked() {
                        app.show_sk = !app.show_sk;
                    }
                });
                ui.end_row();

                ui.label("Bucket 空间名");
                ui.add(TextEdit::singleline(&mut app.f_bucket).desired_width(340.0));
                ui.end_row();

                ui.label("存储区域");
                ui.horizontal(|ui| {
                    egui::ComboBox::from_id_salt("region")
                        .selected_text(if app.f_region.is_empty() { "z0".to_string() } else { app.f_region.clone() })
                        .width(160.0)
                        .show_ui(ui, |ui| {
                            for (code, name) in crate::config::REGIONS {
                                ui.selectable_value(&mut app.f_region, code.to_string(), format!("{}（{}）", code, name));
                            }
                        });
                    ui.add(TextEdit::singleline(&mut app.f_region).desired_width(120.0).hint_text("可手动输入"));
                });
                ui.end_row();

                ui.label("下载域名 (可选)");
                ui.add(TextEdit::singleline(&mut app.f_domain).desired_width(340.0).hint_text("留空自动获取"));
                ui.end_row();
            });
        });

        ui.add_space(8.0);
        card(ui, "同步设置", |ui| {
            egui::Grid::new("sync").num_columns(2).spacing([10.0, 8.0]).show(ui, |ui| {
                ui.label("同步方向");
                ui.horizontal(|ui| {
                    for (code, label) in crate::config::SYNC_MODES {
                        if ui
                            .selectable_label(app.f_mode == code, RichText::new(label))
                            .clicked()
                        {
                            app.f_mode = code.to_string();
                        }
                    }
                });
                ui.end_row();

                ui.label("自动同步间隔");
                ui.horizontal(|ui| {
                    ui.add(egui::DragValue::new(&mut app.f_interval).range(1..=1440).suffix(" 分钟"));
                });
                ui.end_row();

                ui.label("本地目录");
                ui.horizontal(|ui| {
                    ui.add(TextEdit::singleline(&mut app.f_dir).desired_width(260.0).hint_text("D:\\data\\qiniu"));
                    if ui.button("浏览…").clicked() {
                        if let Some(p) = rfd::FileDialog::new().pick_folder() {
                            app.f_dir = p.to_string_lossy().to_string();
                        }
                    }
                    if ui.button("打开目录").clicked() {
                        let d = std::mem::take(&mut app.f_dir);
                        app.f_dir = d.clone();
                        if d.trim().is_empty() {
                            app.test_result = Some(("本地目录".into(), "请先填写本地目录。".into()));
                        } else {
                            let path = std::path::PathBuf::from(&d);
                            let _ = std::fs::create_dir_all(&path);
                            let _ = std::process::Command::new("explorer").arg(&d).spawn();
                        }
                    }
                });
                ui.end_row();

                ui.label("云端目录（Key 前缀）");
                ui.horizontal(|ui| {
                    ui.add(TextEdit::singleline(&mut app.f_prefix).desired_width(260.0).hint_text("留空 = 整个桶"));
                    muted(ui, "留空 = 整个桶");
                });
                ui.end_row();
            });
            ui.add_space(4.0);
            ui.checkbox(&mut app.f_overwrite, "上传时覆盖云端同名文件（默认不覆盖，内容不同会跳过）");
        });

        ui.add_space(8.0);
        card(ui, "网络与启动", |ui| {
            egui::Grid::new("net").num_columns(2).spacing([10.0, 8.0]).show(ui, |ui| {
                ui.label("网络代理");
                egui::ComboBox::from_id_salt("proxy")
                    .selected_text(proxy_label(&app.f_proxy))
                    .width(220.0)
                    .show_ui(ui, |ui| {
                        ui.selectable_value(&mut app.f_proxy, "auto".into(), "自动（推荐）");
                        ui.selectable_value(&mut app.f_proxy, "direct".into(), "直连（忽略系统代理）");
                        ui.selectable_value(&mut app.f_proxy, "system".into(), "系统代理");
                    });
                ui.end_row();
                ui.label("开机自启");
                ui.horizontal(|ui| {
                    if ui
                        .add(egui::Checkbox::new(&mut app.autostart_on, "登录 Windows 后自动在托盘运行"))
                        .changed()
                    {
                        let r = if app.autostart_on {
                            crate::autostart::enable()
                        } else {
                            crate::autostart::disable()
                        };
                        if let Err(e) = r {
                            app.autostart_on = !app.autostart_on;
                            app.test_result = Some(("开机自启".into(), format!("设置失败: {}", e)));
                        }
                    }
                });
                ui.end_row();
            });
            muted(ui, "代理软件没开时选「直连」；数据目录：见「关于」页");
        });

        ui.add_space(10.0);
        ui.horizontal(|ui| {
            let ok = ui
                .add(
                    egui::Button::new(
                        RichText::new("保存设置")
                            .strong()
                            .color(egui::Color32::WHITE),
                    )
                    .fill(BLUE)
                    .min_size(egui::vec2(110.0, 32.0)),
                )
                .clicked();
            if ok {
                app.save_settings();
            }
            if ui.button("测试连接").clicked() {
                app.test_connection();
            }
            if ui.button("测试上传").clicked() {
                app.test_upload();
            }
            ui.label(
                RichText::new("修改后需保存；「测试上传」会创建并自动删除一个临时对象")
                    .small()
                    .color(TEXT_MUTED),
            );
        });
        ui.add_space(6.0);
    });
}

fn proxy_label(mode: &str) -> String {
    match mode {
        "direct" => "直连（忽略系统代理）".into(),
        "system" => "系统代理".into(),
        _ => "自动（推荐）".into(),
    }
}
