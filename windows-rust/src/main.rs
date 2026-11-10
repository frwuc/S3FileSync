//! S3FileSync（Rust 桌面端）入口。
//!
//! - 无参数：GUI（托盘常驻 + 自动同步）
//! - `--silent`：GUI 但启动时隐藏到托盘
//! - `--once [--mode upload|download|both]`：按配置同步一轮后退出（CLI 调试）
//! - `--watch`：按配置间隔循环同步
//! - `--version`：版本信息

mod app;
mod autostart;
mod config;
mod logger;
mod qiniu;
mod state;
mod sync;
mod tray;
mod ui;

use std::time::Duration;

use eframe::egui;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.iter().any(|a| a == "--version" || a == "-V") {
        println!("S3FileSync {}（Rust 桌面端）", config::APP_VERSION);
        println!("{}", config::BACKEND_NOTE);
        return;
    }
    if args.iter().any(|a| a == "--once" || a == "--watch") {
        cli_main(&args);
        return;
    }
    let silent = args.iter().any(|a| a == "--silent" || a == "--tray");

    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([960.0, 640.0])
            .with_min_inner_size([820.0, 560.0])
            .with_title("S3FileSync — 文件同步")
            .with_visible(!silent),
        ..Default::default()
    };
    eframe::run_native(
        "S3FileSync",
        options,
        Box::new(move |cc| {
            load_cjk_fonts(&cc.egui_ctx);
            let (mut app, task_tx) = app::S3App::new();
            app.tray = tray::build_tray();
            if !app.cfg.is_ready() {
                logger::info("首次使用: 请在「设置」页填写 AK/SK/桶名/本地目录后保存。");
            }
            app.schedule_next(false);
            logger::info(&format!(
                "程序已启动（Rust 桌面端 {}，配置: {}）",
                config::APP_VERSION,
                if app.cfg.is_ready() { "完整" } else { "不完整，请先到设置页填写" }
            ));
            let _ = task_tx;
            Ok(Box::new(EguiApp(app)))
        }),
    )
    .expect("eframe 运行失败");
}

struct EguiApp(app::S3App);

impl eframe::App for EguiApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.0.update_ui(ctx);
    }
}

/// egui 默认字体不含 CJK，加载系统中文字体（微软雅黑）。
fn load_cjk_fonts(ctx: &egui::Context) {
    let mut fonts = egui::FontDefinitions::default();
    let candidates = [
        "C:/Windows/Fonts/msyh.ttc",
        "C:/Windows/Fonts/msyh.ttf",
        "C:/Windows/Fonts/simhei.ttf",
        "C:/Windows/Fonts/simsun.ttc",
    ];
    for path in candidates {
        if let Ok(bytes) = std::fs::read(path) {
            fonts
                .font_data
                .insert("cjk".into(), egui::FontData::from_owned(bytes).into());
            // 追加到末尾：拉丁字符仍用默认字体，中文回落到 CJK 字体
            for family in [egui::FontFamily::Proportional, egui::FontFamily::Monospace] {
                if let Some(list) = fonts.families.get_mut(&family) {
                    list.push("cjk".into());
                }
            }
            break;
        }
    }
    ctx.set_fonts(fonts);
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
