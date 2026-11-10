// 发布版不弹黑色控制台窗口（GUI 为主入口；--once/--watch 的输出 debug 构建下仍可见，
// release 下日志写入 %APPDATA%\S3FileSync\app.log）
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

//! S3FileSync（Tauri 桌面端）入口。
//!
//! - 无参数：GUI（托盘常驻 + 自动同步，Web 前端）
//! - `--silent`：GUI 但启动时隐藏到托盘
//! - `--once [--mode upload|download|both]`：按配置同步一轮后退出（CLI 调试）
//! - `--watch`：按配置间隔循环同步
//! - `--version`：版本信息

mod autostart;
mod commands;
mod config;
mod logger;
mod qiniu;
mod runtime;
mod state;
mod storage;
mod sync;
mod tray;

use std::time::Duration;

use tauri::Manager;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.iter().any(|a| a == "--version" || a == "-V") {
        println!("S3FileSync {}（Tauri 桌面端）", config::APP_VERSION);
        println!("{}", config::BACKEND_NOTE);
        return;
    }
    if args.iter().any(|a| a == "--once" || a == "--watch") {
        cli_main(&args);
        return;
    }
    let silent = args.iter().any(|a| a == "--silent" || a == "--tray");

    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.show();
                let _ = w.unminimize();
                let _ = w.set_focus();
            }
        }))
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![
            commands::get_state,
            commands::get_logs,
            commands::clear_logs,
            commands::export_logs,
            commands::save_config,
            commands::start_sync,
            commands::toggle_pause,
            commands::set_autostart,
            commands::test_connection,
            commands::test_upload,
            commands::list_page,
            commands::list_dirs,
            commands::delete_keys,
            commands::download_keys,
            commands::upload_paths,
            commands::upload_dir,
            commands::open_local_dir,
            commands::reveal_path,
            commands::open_url,
            commands::open_cloud
        ])
        .setup(move |app| {
            runtime::init(app.handle().clone());
            tray::build(app.handle())?;
            if !silent {
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.show();
                    let _ = w.set_focus();
                }
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            // 关闭按钮 → 隐藏到托盘（真正退出走托盘菜单）
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                if window.label() == "main" {
                    api.prevent_close();
                    let _ = window.hide();
                    logger::info("窗口已隐藏到托盘（通过托盘菜单退出程序）");
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("tauri 运行失败");
}

// ---------------- CLI ----------------

fn cli_main(args: &[String]) {
    logger::enable_cli();
    let cfg = config::load();
    if !cfg.is_ready() {
        eprintln!(
            "配置不完整（ak/sk/bucket/download_dir），请先在 GUI 设置页填写，或编辑 {}",
            config::config_path().display()
        );
        std::process::exit(2);
    }
    let mode = args
        .iter()
        .position(|a| a == "--mode")
        .and_then(|i| args.get(i + 1))
        .cloned();
    if let Some(m) = &mode {
        if !config::valid_sync_mode(m) {
            eprintln!("--mode 只接受 upload / download / both");
            std::process::exit(2);
        }
    }

    if args.iter().any(|a| a == "--watch") {
        let interval = Duration::from_secs((cfg.interval_minutes.max(1) as u64) * 60);
        loop {
            let s = sync::run_once(&cfg, mode.as_deref());
            sync::print_summary(&s);
            std::thread::sleep(interval);
        }
    } else {
        let s = sync::run_once(&cfg, mode.as_deref());
        sync::print_summary(&s);
        if s.error.is_some() {
            std::process::exit(1);
        }
    }
}
