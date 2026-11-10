//! 日志页：滚动查看 + 自动跟随 + 导出 + 清空（内存）。

use egui::RichText;

use super::card;
use crate::app::S3App;

pub fn render(app: &mut S3App, ui: &mut egui::Ui) {
    card(ui, "运行日志", |ui| {
        ui.horizontal(|ui| {
            if ui
                .checkbox(&mut app.logs_follow, "自动跟随（最新在最下）")
                .changed()
                && app.logs_follow
            {
                app.logs_cursor = usize::MAX;
            }
            if ui.button("刷新").clicked() {
                app.logs_cursor = usize::MAX;
            }
            if ui.button("导出…").clicked() {
                if let Some(p) = rfd::FileDialog::new()
                    .set_file_name("S3FileSync-log.txt")
                    .save_file()
                {
                    match crate::logger::export_to(&p) {
                        Ok(n) => {
                            app.test_result =
                                Some(("导出日志".into(), format!("已导出 {} 行到 {}", n, p.display())));
                        }
                        Err(e) => {
                            app.test_result = Some(("导出日志".into(), format!("导出失败: {}", e)));
                        }
                    }
                }
            }
            if ui.button("打开日志文件").clicked() {
                let _ = std::process::Command::new("explorer")
                    .arg(format!("/select,{}", crate::logger::log_path().display()))
                    .spawn();
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui.button("清空显示").clicked() {
                    crate::logger::clear_memory();
                    app.logs_cursor = 0;
                }
            });
        });
        ui.add_space(6.0);
    });

    ui.add_space(8.0);
    let lines = crate::logger::snapshot();
    let follow = app.logs_follow;
    let mut cursor = app.logs_cursor;
    egui::ScrollArea::vertical()
        .id_salt("log_lines")
        .auto_shrink([false, false])
        .stick_to_bottom(follow)
        .show(ui, |ui| {
            if follow {
                cursor = usize::MAX;
            }
            let start = if cursor == usize::MAX {
                lines.len().saturating_sub(600)
            } else {
                cursor.min(lines.len().saturating_sub(600))
            };
            for line in &lines[start.min(lines.len())..] {
                let color = if line.contains("ERROR") {
                    crate::ui::RED
                } else if line.contains("WARN") {
                    egui::Color32::from_rgb(190, 120, 0)
                } else {
                    egui::Color32::from_gray(70)
                };
                ui.label(RichText::new(line).monospace().size(12.5).color(color));
            }
        });
    if cursor == usize::MAX {
        app.logs_cursor = usize::MAX;
    }
}
