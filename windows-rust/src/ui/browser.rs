//! 文件浏览页：云端目录列举、排序、多选、分页、上传/下载/删除。

use egui::{Color32, RichText, TextEdit};

use super::{human_size, fmt_put_time, card, muted};
use crate::app::{S3App, SortKey};

pub fn render(app: &mut S3App, ui: &mut egui::Ui) {
    if !app.cfg.is_ready() {
        ui.centered_and_justified(|ui| {
            ui.label("请先在「设置」页保存 AK / SK / 空间名，然后回到这里浏览云端文件。");
        });
        return;
    }

    let mut action = None;
    card(ui, "云端目录", |ui| {
        ui.horizontal(|ui| {
            ui.label("目录");
            ui.add(TextEdit::singleline(&mut app.browser.prefix_input).desired_width(280.0).hint_text("Key 前缀，留空 = 根目录"));
            if ui.button("列举").clicked() {
                action = Some(Action::Refresh);
            }
            if ui.button("全部文件").clicked() {
                app.browser.prefix_input.clear();
                action = Some(Action::Refresh);
            }
            if ui.button("上级").clicked() {
                action = Some(Action::Up);
            }
            if ui.button("打开云端").clicked() {
                if let Err(e) = app.open_cloud() {
                    app.test_result = Some(("打开云端".into(), e));
                }
            }
        });
        muted(ui, "双击以 / 结尾的目录占位可进入该目录；分页每页 100 条");
    });

    ui.add_space(8.0);
    card(ui, "对象列表", |ui| {
        // 表头（点击排序）
        ui.horizontal(|ui| {
            sort_header(ui, app, "名称", SortKey::Name);
            ui.separator();
            sort_header(ui, app, "大小", SortKey::Size);
            ui.separator();
            sort_header(ui, app, "修改时间", SortKey::Time);
            ui.separator();
            ui.label(RichText::new("Key").strong());
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui
                    .button(if app.browser.selected.is_empty() { "全选本页" } else { "全不选" })
                    .clicked()
                {
                    if app.browser.selected.is_empty() {
                        for it in &app.browser.items {
                            if !it.key.ends_with('/') {
                                app.browser.selected.insert(it.key.clone());
                            }
                        }
                    } else {
                        app.browser.selected.clear();
                    }
                }
            });
        });
        ui.separator();

        // 排序后的条目
        let mut items = app.browser.items.clone();
        sort_items(&mut items, app.browser.sort_key, app.browser.sort_desc);

        egui::ScrollArea::vertical()
            .id_salt("browser_rows")
            .auto_shrink([false, false])
            .max_height(ui.available_height() - 120.0)
            .show(ui, |ui| {
                if items.is_empty() && !app.browser.busy {
                    ui.label(
                        RichText::new(if app.browser.loaded_prefix.is_empty() {
                            "点击「列举」查看云端文件"
                        } else {
                            "该目录下没有对象"
                        })
                        .color(crate::ui::TEXT_MUTED),
                    );
                }
                for (i, it) in items.iter().enumerate() {
                    // 斑马纹行背景（隔行浅灰）
                    let zebra = if i % 2 == 1 {
                        Color32::from_rgb(0xF8, 0xF9, 0xFB)
                    } else {
                        Color32::TRANSPARENT
                    };
                    egui::Frame::new()
                        .fill(zebra)
                        .corner_radius(4.0f32)
                        .inner_margin(egui::Margin::symmetric(4, 3))
                        .show(ui, |ui| {
                            row(ui, app, it, &mut action);
                        });
                }
            });

        ui.separator();
        // 分页与操作
        ui.horizontal(|ui| {
            let has_prev = !app.browser.marker_stack.is_empty();
            let has_next = app.browser.next_marker.is_some();
            if ui.add_enabled(has_prev, egui::Button::new("上一页")).clicked() {
                action = Some(Action::PrevPage);
            }
            if ui.button("刷新").clicked() {
                action = Some(Action::Refresh);
            }
            let next = ui.add_enabled(has_next, egui::Button::new("下一页"));
            if next.clicked() {
                action = Some(Action::NextPage);
            }
            ui.label(
                RichText::new(&app.browser.status)
                    .small()
                    .color(crate::ui::TEXT_MUTED),
            );
        });
        ui.add_space(4.0);
        ui.horizontal(|ui| {
            let n = app.browser.selected.len();
            let enabled = n > 0 && !app.browser.busy;
            let dl = ui.add_enabled(enabled, egui::Button::new(format!("下载所选 ({})", n)));
            if dl.clicked() {
                action = Some(Action::Download);
            }
            let del = ui.add_enabled(enabled, egui::Button::new(format!("删除所选 ({})", n)));
            if del.clicked() {
                action = Some(Action::Delete);
            }
            ui.separator();
            if ui.button("上传文件…").clicked() {
                if let Some(p) = rfd::FileDialog::new()
                    .set_directory(default_upload_root(app))
                    .pick_files()
                {
                    app.browser_upload_files(p);
                }
            }
            if ui.button("上传文件夹…").clicked() {
                if let Some(p) = rfd::FileDialog::new().pick_folder() {
                    app.browser_upload_dir(p);
                }
            }
        });
    });

    match action {
        Some(Action::Refresh) => app.browser_refresh(),
        Some(Action::NextPage) => app.browser_next_page(),
        Some(Action::PrevPage) => app.browser_prev_page(),
        Some(Action::Up) => app.browser_up_level(),
        Some(Action::Download) => app.browser_download_selected(),
        Some(Action::Delete) => app.browser_delete_selected(),
        None => {}
    }
}

enum Action {
    Refresh,
    NextPage,
    PrevPage,
    Up,
    Download,
    Delete,
}

fn default_upload_root(app: &S3App) -> std::path::PathBuf {
    let dir = std::path::PathBuf::from(&app.cfg.download_dir);
    if dir.is_dir() {
        dir
    } else {
        std::env::var("USERPROFILE").map(std::path::PathBuf::from).unwrap_or_default()
    }
}

fn sort_header(ui: &mut egui::Ui, app: &mut S3App, label: &str, key: SortKey) {
    let marker = if app.browser.sort_key == key {
        if app.browser.sort_desc { " ▼" } else { " ▲" }
    } else {
        ""
    };
    if ui
        .add(egui::Button::new(RichText::new(format!("{}{}", label, marker)).strong()).frame(false))
        .clicked()
    {
        if app.browser.sort_key == key {
            app.browser.sort_desc = !app.browser.sort_desc;
        } else {
            app.browser.sort_key = key;
            app.browser.sort_desc = false;
        }
    }
}

fn sort_items(items: &mut [crate::qiniu::client::ListItem], key: SortKey, desc: bool) {
    items.sort_by(|a, b| {
        let ord = match key {
            SortKey::Name => a.key.cmp(&b.key),
            SortKey::Size => a.fsize.cmp(&b.fsize),
            SortKey::Time => a.put_time.cmp(&b.put_time),
        };
        if desc { ord.reverse() } else { ord }
    });
}

fn row(ui: &mut egui::Ui, app: &mut S3App, it: &crate::qiniu::client::ListItem, action: &mut Option<Action>) {
    let is_dir = it.key.ends_with('/');
    let mut selected = app.browser.selected.contains(&it.key);
    ui.horizontal(|ui| {
        if !is_dir {
            if ui.checkbox(&mut selected, "").changed() {
                if selected {
                    app.browser.selected.insert(it.key.clone());
                } else {
                    app.browser.selected.remove(&it.key);
                }
            }
        } else {
            ui.add_space(18.0);
        }
        let name = leaf_name(&it.key);
        let name_text = if is_dir {
            RichText::new(format!("📁 {}", name)).color(crate::ui::BLUE)
        } else {
            RichText::new(if name.is_empty() { "(空)" } else { &name })
        };
        let resp = ui.add(
            egui::Button::new(name_text)
                .frame(false)
                .wrap_mode(egui::TextWrapMode::Truncate),
        );
        if resp.clicked() {
            if is_dir {
                // 进入目录
                app.browser.prefix_input = it.key.clone();
                *action = Some(Action::Refresh);
            } else {
                // 双击下载（单机也提示）
                app.browser.selected.clear();
                app.browser.selected.insert(it.key.clone());
                *action = Some(Action::Download);
            }
        }
        ui.separator();
        ui.label(if is_dir { "—".to_string() } else { human_size(it.fsize) });
        ui.separator();
        ui.label(fmt_put_time(it.put_time));
        ui.separator();
        ui.label(
            RichText::new(&it.key)
                .small()
                .color(crate::ui::TEXT_MUTED)
                .weak(),
        );
        if ui
            .small_button("复制Key")
            .clicked()
        {
            ui.output_mut(|o| {
                o.commands
                    .push(egui::OutputCommand::CopyText(it.key.clone()));
            });
        }
    });
}

fn leaf_name(key: &str) -> String {
    let k = key.trim_end_matches('/');
    k.rsplit('/').next().unwrap_or(k).to_string()
}
