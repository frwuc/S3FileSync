//! 全局运行时：配置 + 调度状态 + 单工作线程（同步任务串行）+ 定时器线程 + 事件推送。
//!
//! 线程模型与 egui 版一致：
//! - 工作线程：串行执行同步任务（`Task::Sync`），结果写回运行时并推送给前端
//! - 定时器线程：每 500ms 检查下次同步时间，到点触发同步
//! - 前端（WebView）：监听 `state` 事件 + 轻量轮询，只读快照
//! - 交互命令（测试 / 列举 / 上传下载）：async 命令 + 线程池，不经过工作线程

use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use tauri::{AppHandle, Emitter};

use crate::config::{self, Config};
use crate::state;
use crate::sync::{self, Summary};

/// 下次同步时间在快照里用 epoch 毫秒表示，前端自行格式化与倒计时。
fn now_ms() -> i64 {
    chrono::Local::now().timestamp_millis()
}

pub enum Task {
    Sync { cfg: Config, mode: String },
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct Snapshot {
    /// 已保存的运行配置
    pub cfg: Config,
    pub paused: bool,
    pub sync_busy: bool,
    /// 状态栏文本（最近一次动作结果）
    pub status: String,
    /// 下次自动同步时间（epoch 毫秒）；null = 已暂停
    pub next_sync_at: Option<i64>,
    pub last_sync: Option<Summary>,
    pub version: String,
    pub provider_label: String,
    pub sync_mode_label: String,
    pub is_ready: bool,
    pub autostart: bool,
    pub app_dir: String,
    pub state_path: String,
    pub log_path: String,
}

#[derive(Debug)]
pub struct RuntimeState {
    pub cfg: Config,
    pub paused: bool,
    pub sync_busy: bool,
    pub status: String,
    pub next_sync_at: Option<i64>,
    pub last_sync: Option<Summary>,
}

pub struct Ctx {
    pub task_tx: Sender<Task>,
    pub rt: Mutex<RuntimeState>,
}

static CTX: OnceLock<Ctx> = OnceLock::new();
static APP: OnceLock<AppHandle> = OnceLock::new();

pub fn init(handle: AppHandle) {
    let cfg = config::load();
    let _ = APP.set(handle);
    let (task_tx, task_rx) = mpsc::channel::<Task>();
    let rt = RuntimeState {
        paused: false,
        sync_busy: false,
        status: "准备就绪".into(),
        next_sync_at: None,
        last_sync: None,
        cfg: cfg.clone(),
    };
    let _ = CTX.set(Ctx { task_tx: task_tx.clone(), rt: Mutex::new(rt) });
    std::thread::spawn(move || worker_loop(task_rx));
    std::thread::spawn(timer_loop);
    if !cfg.is_ready() {
        crate::logger::info("首次使用: 请在「设置」页填写 AK/SK/桶名/本地目录后保存。");
    }
    crate::logger::info(&format!(
        "程序已启动（Tauri 桌面端 {}，后端: {}，配置: {}）",
        config::APP_VERSION,
        config::provider_label(&cfg.provider),
        if cfg.is_ready() { "完整" } else { "不完整，请先到设置页填写" }
    ));
    with_rt(|rt| schedule_next(rt));
    emit_state();
}

fn with_rt<T>(f: impl FnOnce(&mut RuntimeState) -> T) -> T {
    let ctx = CTX.get().expect("runtime 未初始化");
    let mut guard = ctx.rt.lock().unwrap_or_else(|p| p.into_inner());
    f(&mut guard)
}

/// 构建快照（锁内一次性采集）。
fn build_snapshot(rt: &RuntimeState) -> Snapshot {
    Snapshot {
        cfg: rt.cfg.clone(),
        paused: rt.paused,
        sync_busy: rt.sync_busy,
        status: rt.status.clone(),
        next_sync_at: rt.next_sync_at,
        last_sync: rt.last_sync.clone(),
        version: config::APP_VERSION.to_string(),
        provider_label: config::provider_label(&rt.cfg.provider).to_string(),
        sync_mode_label: config::sync_mode_label(&rt.cfg.sync_mode).split('（').next().unwrap_or("").to_string(),
        is_ready: rt.cfg.is_ready(),
        autostart: crate::autostart::is_enabled(),
        app_dir: config::app_dir().to_string_lossy().to_string(),
        state_path: state::state_path().to_string_lossy().to_string(),
        log_path: crate::logger::log_path().to_string_lossy().to_string(),
    }
}

/// 推送当前状态到前端（state 事件）。
pub fn emit_state() {
    let Some(app) = APP.get() else { return };
    let snap = with_rt(|rt| build_snapshot(rt));
    let _ = app.emit("state", snap);
}

/// 请求推送（可在任意线程调用，锁内不做耗时操作）。
// ---------- 同步调度 ----------

/// 触发一轮同步（命令 / 托盘 / 定时器共用）。返回错误文案（给命令层）。
pub fn start_sync(mode: Option<&str>) -> Result<(), String> {
    let (cfg, mode) = with_rt(|rt| {
        if rt.sync_busy {
            return Err("上一轮同步尚未结束，跳过本次触发".to_string());
        }
        if !rt.cfg.is_ready() {
            return Err("配置不完整，请先到设置页填写".to_string());
        }
        let mode = mode
            .filter(|m| config::valid_sync_mode(m))
            .unwrap_or(&rt.cfg.sync_mode)
            .to_string();
        rt.sync_busy = true;
        rt.status = format!("正在同步（{}）…", config::sync_mode_label(&mode));
        Ok((rt.cfg.clone(), mode))
    })?;
    crate::logger::info(&format!("开始同步：{}", config::sync_mode_label(&mode)));
    let ctx = CTX.get().unwrap();
    let _ = ctx.task_tx.send(Task::Sync { cfg, mode });
    emit_state();
    Ok(())
}

/// 计算下次同步时间（保存设置 / 同步完成 / 暂停切换后调用，调用方持锁）。
fn schedule_next(rt: &mut RuntimeState) {
    if rt.paused {
        rt.next_sync_at = None;
        return;
    }
    if !rt.cfg.is_ready() {
        rt.next_sync_at = Some(now_ms() + 30_000);
        return;
    }
    let interval = (rt.cfg.interval_minutes.max(1) as i64) * 60_000;
    rt.next_sync_at = Some(now_ms() + interval);
}

/// 暂停 / 恢复自动同步。
pub fn toggle_pause() -> bool {
    let paused = with_rt(|rt| {
        rt.paused = !rt.paused;
        rt.status = if rt.paused { "自动同步已暂停".into() } else { "自动同步已恢复".into() };
        schedule_next(rt);
        rt.paused
    });
    crate::logger::info(&format!("自动同步已{}", if paused { "暂停" } else { "恢复" }));
    emit_state();
    paused
}

/// 保存配置（前端「保存设置」命令）：规范化 + 落盘 + 重排调度。
pub fn save_config(cfg: Config) -> Result<(), String> {
    let mut data = cfg;
    data.prefix = sync::norm_prefix(&data.prefix);
    data.interval_minutes = data.interval_minutes.max(1);
    if !config::valid_sync_mode(&data.sync_mode) {
        data.sync_mode = "download".into();
    }
    if !config::valid_provider(&data.provider) {
        data.provider = "qiniu".into();
    }
    config::save(&data).map_err(|e| format!("保存失败: {}", e))?;
    crate::logger::info(&format!(
        "设置已保存（后端: {}，空间: {}，云端目录: {}，方向: {}，间隔: {} 分钟，网络: {}）",
        config::provider_label(&data.provider),
        data.bucket,
        if data.prefix.is_empty() { "整个空间".to_string() } else { data.prefix.clone() },
        config::sync_mode_label(&data.sync_mode),
        data.interval_minutes,
        data.proxy_mode
    ));
    with_rt(|rt| {
        rt.cfg = data;
        rt.status = "设置已保存".into();
        schedule_next(rt);
    });
    emit_state();
    Ok(())
}

/// 七牛区域自动纠正（测试上传 / 同步后发现实际区域与设置不同）。
pub fn apply_detected_region(reg: &str) {
    if reg.is_empty() {
        return;
    }
    let changed = with_rt(|rt| {
        if reg == rt.cfg.region {
            return false;
        }
        crate::logger::warn(&format!(
            "空间实际区域为 {}（原设置 {}），已自动更新配置",
            reg, rt.cfg.region
        ));
        rt.cfg.region = reg.to_string();
        let _ = config::save(&rt.cfg);
        true
    });
    if changed {
        emit_state();
    }
}

// ---------- 工作线程 ----------

fn worker_loop(rx: Receiver<Task>) {
    while let Ok(task) = rx.recv() {
        match task {
            Task::Sync { cfg, mode } => {
                let summary = sync::run_once(&cfg, Some(&mode));
                let label = config::sync_mode_label(&summary.mode).split('（').next().unwrap_or(&summary.mode).to_string();
                if let Some(e) = &summary.error {
                    crate::logger::error(&format!("同步出错: {}", e));
                }
                crate::logger::info(&format!(
                    "同步完成[{}]: 上传 {} / 下载 {} / 跳过 {} / 失败 {} / 云端 {} / 本地 {}",
                    label, summary.uploaded.len(), summary.downloaded.len(), summary.skipped,
                    summary.failed.len(), summary.total, summary.local_total
                ));
                for (key, err) in &summary.failed {
                    crate::logger::warn(&format!("  失败: {} ({})", key, err));
                }
                let detected = summary.detected_region.clone();
                with_rt(|rt| {
                    rt.sync_busy = false;
                    rt.status = format!(
                        "同步完成 {} [{}]: 上传 {} / 下载 {} / 失败 {}",
                        chrono::Local::now().format("%H:%M:%S"),
                        label,
                        summary.uploaded.len(),
                        summary.downloaded.len(),
                        summary.failed.len()
                    );
                    rt.last_sync = Some(summary);
                    schedule_next(rt);
                });
                if let Some(reg) = detected {
                    apply_detected_region(&reg);
                }
                emit_state();
            }
        }
    }
}

// ---------- 定时器线程 ----------

fn timer_loop() {
    loop {
        std::thread::sleep(Duration::from_millis(500));
        let due = with_rt(|rt| {
            if rt.paused || rt.sync_busy {
                return false;
            }
            match rt.next_sync_at {
                Some(at) => now_ms() >= at,
                None => {
                    // 意外丢失（如初始化竞态）→ 补排
                    schedule_next(rt);
                    false
                }
            }
        });
        if due {
            let _ = start_sync(None);
        }
    }
}

/// 当前快照（命令层用）。
pub fn snapshot() -> Snapshot {
    with_rt(|rt| build_snapshot(rt))
}

/// 当前运行配置。
pub fn current_cfg() -> Config {
    with_rt(|rt| rt.cfg.clone())
}
