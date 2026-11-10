//! 托盘图标与菜单（Tauri 2 内置 tray-icon）。菜单事件直接在回调里处理。

use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, Runtime};

pub const MENU_SHOW: &str = "show";
pub const MENU_SYNC_NOW: &str = "sync_now";
pub const MENU_SYNC_UPLOAD: &str = "sync_upload";
pub const MENU_SYNC_DOWNLOAD: &str = "sync_download";
pub const MENU_SYNC_BOTH: &str = "sync_both";
pub const MENU_TOGGLE_PAUSE: &str = "toggle_pause";
pub const MENU_OPEN_DIR: &str = "open_dir";
pub const MENU_OPEN_CLOUD: &str = "open_cloud";
pub const MENU_QUIT: &str = "quit";

fn show_window<R: Runtime>(app: &AppHandle<R>) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

/// 创建托盘（setup 阶段调用一次）。
pub fn build<R: Runtime>(app: &AppHandle<R>) -> tauri::Result<()> {
    let menu = Menu::with_items(
        app,
        &[
            &MenuItem::with_id(app, MENU_SHOW, "打开主窗口", true, None::<&str>)?,
            &MenuItem::with_id(app, MENU_SYNC_NOW, "立即同步（按设置的方向）", true, None::<&str>)?,
            &MenuItem::with_id(app, MENU_SYNC_UPLOAD, "仅上传一轮", true, None::<&str>)?,
            &MenuItem::with_id(app, MENU_SYNC_DOWNLOAD, "仅下载一轮", true, None::<&str>)?,
            &MenuItem::with_id(app, MENU_SYNC_BOTH, "双向一轮", true, None::<&str>)?,
            &MenuItem::with_id(app, MENU_TOGGLE_PAUSE, "暂停 / 恢复自动同步", true, None::<&str>)?,
            &PredefinedMenuItem::separator(app)?,
            &MenuItem::with_id(app, MENU_OPEN_DIR, "打开本地目录", true, None::<&str>)?,
            &MenuItem::with_id(app, MENU_OPEN_CLOUD, "打开云端目录", true, None::<&str>)?,
            &PredefinedMenuItem::separator(app)?,
            &MenuItem::with_id(app, MENU_QUIT, "退出", true, None::<&str>)?,
        ],
    )?;

    let icon = app
        .default_window_icon()
        .cloned()
        .expect("缺省窗口图标缺失");

    TrayIconBuilder::with_id("main")
        .icon(icon)
        .tooltip("S3FileSync")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id().as_ref() {
            MENU_SHOW => show_window(app),
            MENU_SYNC_NOW => {
                let _ = crate::runtime::start_sync(None);
            }
            MENU_SYNC_UPLOAD => {
                let _ = crate::runtime::start_sync(Some("upload"));
            }
            MENU_SYNC_DOWNLOAD => {
                let _ = crate::runtime::start_sync(Some("download"));
            }
            MENU_SYNC_BOTH => {
                let _ = crate::runtime::start_sync(Some("both"));
            }
            MENU_TOGGLE_PAUSE => {
                crate::runtime::toggle_pause();
            }
            MENU_OPEN_DIR => {
                let cfg = crate::runtime::current_cfg();
                if !cfg.download_dir.is_empty() {
                    let _ = crate::commands::open_local_dir(cfg.download_dir.clone());
                }
            }
            MENU_OPEN_CLOUD => {
                if let Err(e) = crate::commands::open_cloud() {
                    crate::logger::warn(&e);
                }
            }
            MENU_QUIT => {
                crate::logger::info("程序退出");
                app.exit(0);
            }
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                show_window(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(())
}
