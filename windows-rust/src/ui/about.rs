//! 关于页：版本、后端说明、数据目录、仓库链接。

use super::{card, muted, BLUE, TEXT_MUTED};
use crate::app::S3App;

pub fn render(app: &mut S3App, ui: &mut egui::Ui) {
    egui::ScrollArea::vertical().show(ui, |ui| {
        card(ui, "关于", |ui| {
            egui::Grid::new("about").num_columns(2).spacing([10.0, 8.0]).show(ui, |ui| {
                ui.label("软件名称");
                ui.label("S3FileSync（Rust 桌面端）");
                ui.end_row();
                ui.label("版本");
                ui.label(crate::config::APP_VERSION);
                ui.end_row();
                ui.label("实现");
                ui.label("Rust + egui 原生 GUI，单文件 exe，无运行时依赖");
                ui.end_row();
                ui.label("后端");
                ui.label(crate::config::BACKEND_NOTE);
                ui.end_row();
                ui.label("对应 Python 端");
                ui.label("功能对等（同步引擎 / 浏览 / 托盘 / 自动同步），共用同一份配置与状态");
                ui.end_row();
            });
        });

        ui.add_space(8.0);
        card(ui, "数据与配置", |ui| {
            egui::Grid::new("paths").num_columns(2).spacing([10.0, 8.0]).show(ui, |ui| {
                ui.label("配置目录");
                ui.label(crate::config::app_dir().to_string_lossy().to_string());
                ui.end_row();
                ui.label("同步状态");
                ui.label(crate::state::state_path().to_string_lossy().to_string());
                ui.end_row();
                ui.label("日志文件");
                ui.label(crate::logger::log_path().to_string_lossy().to_string());
                ui.end_row();
            });
            ui.add_space(4.0);
            ui.horizontal(|ui| {
                if ui.button("打开配置目录").clicked() {
                    let _ = std::fs::create_dir_all(crate::config::app_dir());
                    let _ = std::process::Command::new("explorer")
                        .arg(crate::config::app_dir())
                        .spawn();
                }
            });
            muted(ui, "SK 明文保存在本机 config.json，勿用于公用电脑；与 Python 端 / 安卓端共用此目录。");
        });

        ui.add_space(8.0);
        card(ui, "帮助", |ui| {
            ui.horizontal(|ui| {
                if ui
                    .add(egui::Link::new("Gitee 仓库（S3FileSync）"))
                    .clicked()
                {
                    let _ = webbrowser::open("https://gitee.com/frwuc/S3FileSync");
                }
            });
            ui.horizontal(|ui| {
                if ui
                    .add(egui::Link::new("使用说明（Windows）"))
                    .clicked()
                {
                    let _ = webbrowser::open("https://gitee.com/frwuc/S3FileSync/blob/main/windows/README.md");
                }
            });
            ui.label(
                egui::RichText::new("CLI 调试：S3FileSync.exe --once [--mode upload|download|both]")
                    .small()
                    .color(TEXT_MUTED),
            );
            ui.label(
                egui::RichText::new("关闭窗口 = 隐藏到托盘；退出请用托盘菜单或 --silent 参数")
                    .small()
                    .color(TEXT_MUTED),
            );
        });
        let _ = (BLUE, &app.cfg);
    });
}
