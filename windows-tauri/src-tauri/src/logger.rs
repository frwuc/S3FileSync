//! 日志：内存环形缓冲（GUI 日志页显示）+ 追加写 app.log（轮转 1MB × 2）。
//! 写盘与 GUI 读取互不阻塞。

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::Mutex;

use crate::config::app_dir;

const MAX_LINES: usize = 4000;

struct LogState {
    lines: VecDeque<String>,
    file: Option<std::fs::File>,
}

use std::collections::VecDeque;
use std::sync::OnceLock;

static LOG: OnceLock<Mutex<LogState>> = OnceLock::new();

fn init() -> Mutex<LogState> {
    let dir = app_dir();
    let _ = fs::create_dir_all(&dir);
    let path = dir.join("app.log");
    rotate_if_large(&path);
    let file = OpenOptions::new().create(true).append(true).open(&path).ok();
    Mutex::new(LogState {
        lines: VecDeque::new(),
        file,
    })
}

fn rotate_if_large(path: &PathBuf) {
    if let Ok(meta) = fs::metadata(path) {
        if meta.len() > 1024 * 1024 {
            let _ = fs::rename(path, path.with_extension("log.1"));
            // 已有 .1 → 顶替（最多保留 1 个备份，与 Python 端 backupCount=2 接近）
            let _ = fs::remove_file(path.with_extension("log.2"));
            let _ = fs::rename(path.with_extension("log.1"), path.with_extension("log.2"));
            let _ = fs::rename(path.with_extension("log.2"), path.with_extension("log.1"));
        }
    }
}

fn push(level: &str, msg: &str) {
    let mut guard = match LOG.get_or_init(init).lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    let now = chrono::Local::now().format("%H:%M:%S");
    let line = format!("{} {} {}", now, level, msg);
    if guard.lines.len() >= MAX_LINES {
        guard.lines.pop_front();
    }
    guard.lines.push_back(line.clone());
    if let Some(f) = guard.file.as_mut() {
        let full = format!("{} {}\n", chrono::Local::now().format("%Y-%m-%d %H:%M:%S"), line);
        let _ = f.write_all(full.as_bytes());
    }
    // CLI 模式直接打到 stderr
    if cli_mode() {
        eprintln!("{}", line);
    }
}

fn cli_mode() -> bool {
    // 无 GUI 时（--once/--watch）由 main 里设置
    std::env::var("S3FSYNC_CLI").map(|v| v == "1").unwrap_or(false)
}

pub fn enable_cli() {
    std::env::set_var("S3FSYNC_CLI", "1");
}

pub fn info(msg: &str) {
    push("INFO", msg);
}

pub fn warn(msg: &str) {
    push("WARN", msg);
}

pub fn error(msg: &str) {
    push("ERROR", msg);
}

/// GUI 日志页快照（浅拷贝）。
pub fn snapshot() -> Vec<String> {
    let guard = match LOG.get_or_init(init).lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    guard.lines.iter().cloned().collect()
}

pub fn log_path() -> PathBuf {
    app_dir().join("app.log")
}

/// 把日志导出到指定文件。
pub fn export_to(path: &std::path::Path) -> std::io::Result<u64> {
    let lines = snapshot();
    let mut text = String::new();
    for l in &lines {
        text.push_str(l);
        text.push('\n');
    }
    fs::write(path, text)?;
    Ok(lines.len() as u64)
}

/// 清空内存显示（文件日志保留）。
pub fn clear_memory() {
    let mut guard = match LOG.get_or_init(init).lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    guard.lines.clear();
}
