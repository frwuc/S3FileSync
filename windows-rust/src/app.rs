//! GUI 主体：状态机 + 单工作线程（网络任务串行）+ 托盘 + 定时调度。
//!
//! 线程模型与 Python 端一致：
//! - 主线程：egui 渲染循环（唯一操作 UI 状态的线程）
//! - 工作线程：串行执行全部七牛网络任务，结果经 channel 回主线程
//! - 托盘：全局事件 receiver，主线程每帧取走

use std::path::PathBuf;
use std::sync::mpsc::{self, Receiver, Sender};
use std::time::{Duration, Instant};

use egui::Context;

use crate::config::{self, Config};
use crate::qiniu::client::{Kodo, ListItem};
use crate::state;
use crate::sync::{self, Summary};
use crate::ui;
use crate::{autostart, logger, tray};

pub enum Task {
    Sync { cfg: Config, mode: String },
    TestConnection { cfg: Config },
    TestUpload { cfg: Config },
    List { cfg: Config, prefix: String, marker: Option<String> },
    DeleteKeys { cfg: Config, keys: Vec<String> },
    DownloadKeys { cfg: Config, entries: Vec<ListItem>, root: String },
    UploadFiles { cfg: Config, specs: Vec<(PathBuf, String)>, prefix: String },
}

pub enum WorkerEvent {
    SyncDone(Summary),
    TestDone { title: String, ok: bool, msg: String },
    ListDone { items: Vec<ListItem>, next_marker: Option<String>, req_marker: Option<String> },
    ListFailed { msg: String },
    OpDone { title: String, msg: String, refresh_browser: bool },
    RegionDetected(String),
}

#[derive(Clone, Copy, PartialEq)]
pub enum Tab {
    Settings,
    Browser,
    Logs,
    About,
}

/// 浏览页状态
pub struct BrowserState {
    pub prefix_input: String,
    pub loaded_prefix: String,
    pub items: Vec<ListItem>,
    pub next_marker: Option<String>,
    pub marker_stack: Vec<Option<String>>,
    pub page_start: Option<String>,
    pub selected: std::collections::HashSet<String>,
    pub sort_key: SortKey,
    pub sort_desc: bool,
    pub busy: bool,
    pub status: String,
}

#[derive(Clone, Copy, PartialEq)]
pub enum SortKey {
    Name,
    Size,
    Time,
}

impl Default for BrowserState {
    fn default() -> Self {
        BrowserState {
            prefix_input: String::new(),
            loaded_prefix: String::new(),
            items: Vec::new(),
            next_marker: None,
            marker_stack: Vec::new(),
            page_start: None,
            selected: Default::default(),
            sort_key: SortKey::Name,
            sort_desc: false,
            busy: false,
            status: "尚未列举".into(),
        }
    }
}

pub struct S3App {
    pub cfg: Config,
    // 设置页表单（编辑中，保存前不影响运行配置）
    pub f_ak: String,
    pub f_sk: String,
    pub f_bucket: String,
    pub f_region: String,
    pub f_domain: String,
    pub f_dir: String,
    pub f_prefix: String,
    pub f_mode: String,
    pub f_interval: i64,
    pub f_overwrite: bool,
    pub f_proxy: String,
    pub show_sk: bool,
    pub autostart_on: bool,

    pub tab: Tab,
    pub status: String,
    pub sync_busy: bool,
    pub paused: bool,
    pub next_sync_at: Option<Instant>,
    pub last_sync_summary: Option<Summary>,
    pub test_result: Option<(String, String)>, // (title, msg) 弹窗
    pub logs_follow: bool,
    pub logs_cursor: usize,

    pub browser: BrowserState,

    pub task_tx: Sender<Task>,
    pub ev_rx: Receiver<WorkerEvent>,
    pub tray: Option<tray_icon::TrayIcon>,
    pub quit_requested: bool,
}

impl S3App {
    pub fn new() -> (Self, Sender<Task>) {
        let (task_tx, task_rx) = mpsc::channel::<Task>();
        let (ev_tx, ev_rx) = mpsc::channel::<WorkerEvent>();
        // 工作线程：串行执行所有网络任务
        std::thread::spawn(move || worker_loop(task_rx, ev_tx));

        let cfg = config::load();
        let app = S3App {
            f_ak: cfg.ak.clone(),
            f_sk: cfg.sk.clone(),
            f_bucket: cfg.bucket.clone(),
            f_region: cfg.region.clone(),
            f_domain: cfg.download_domain.clone(),
            f_dir: cfg.download_dir.clone(),
            f_prefix: cfg.prefix.clone(),
            f_mode: cfg.sync_mode.clone(),
            f_interval: cfg.interval_minutes,
            f_overwrite: cfg.upload_overwrite,
            f_proxy: cfg.proxy_mode.clone(),
            show_sk: false,
            autostart_on: autostart::is_enabled(),
            tab: Tab::Settings,
            status: "准备就绪".into(),
            sync_busy: false,
            paused: false,
            next_sync_at: None,
            last_sync_summary: None,
            test_result: None,
            logs_follow: true,
            logs_cursor: 0,
            browser: BrowserState {
                prefix_input: crate::sync::norm_prefix(&cfg.prefix),
                ..Default::default()
            },
            task_tx: task_tx.clone(),
            ev_rx,
            tray: None,
            quit_requested: false,
            cfg,
        };
        (app, task_tx)
    }

    // ---------- 事件与任务 ----------

    fn submit(&self, task: Task) {
        let _ = self.task_tx.send(task);
    }

    pub fn start_sync(&mut self, mode: Option<&str>) {
        if self.sync_busy {
            logger::info("上一轮同步尚未结束，跳过本次触发");
            return;
        }
        if !self.cfg.is_ready() {
            self.status = "配置不完整，请先到设置页填写".into();
            return;
        }
        let mode = mode.unwrap_or(&self.cfg.sync_mode).to_string();
        self.sync_busy = true;
        let label = config::sync_mode_label(&mode);
        self.status = format!("正在同步（{}）…", label);
        logger::info(&format!("开始同步：{}", label));
        self.submit(Task::Sync {
            cfg: self.cfg.clone(),
            mode,
        });
    }

    fn collect_form(&self) -> Config {
        Config {
            ak: self.f_ak.trim().to_string(),
            sk: self.f_sk.trim().to_string(),
            bucket: self.f_bucket.trim().to_string(),
            region: self.f_region.trim().to_string(),
            download_domain: self.f_domain.trim().to_string(),
            download_dir: self.f_dir.trim().to_string(),
            interval_minutes: self.f_interval.max(1),
            prefix: crate::sync::norm_prefix(&self.f_prefix),
            sync_mode: self.f_mode.clone(),
            upload_overwrite: self.f_overwrite,
            proxy_mode: self.f_proxy.clone(),
        }
    }

    pub fn save_settings(&mut self) {
        let data = self.collect_form();
        let old_prefix = crate::sync::norm_prefix(&self.cfg.prefix);
        self.cfg = data.clone();
        if let Err(e) = config::save(&data) {
            self.status = format!("保存失败: {}", e);
            logger::error(&self.status.clone());
            return;
        }
        logger::info(&format!(
            "设置已保存（空间: {}，区域: {}，方向: {}，云端目录: {}，间隔: {} 分钟，网络: {}）",
            data.bucket,
            data.region,
            config::sync_mode_label(&data.sync_mode),
            if data.prefix.is_empty() { "整个空间".to_string() } else { data.prefix.clone() },
            data.interval_minutes,
            data.proxy_mode
        ));
        let new_prefix = crate::sync::norm_prefix(&data.prefix);
        if new_prefix != old_prefix {
            self.browser.prefix_input = new_prefix.clone();
            self.browser.loaded_prefix = String::new(); // 强制重新列举
            self.browser.items.clear();
        }
        self.schedule_next(true);
        self.status = "设置已保存".into();
    }

    pub fn test_connection(&mut self) {
        let data = self.collect_form();
        if !data.is_ready() && (data.ak.is_empty() || data.sk.is_empty() || data.bucket.is_empty()) {
            self.test_result = Some((
                "测试连接".into(),
                "请先填写 AK / SK / 空间名".into(),
            ));
            return;
        }
        self.status = "正在测试连接…".into();
        self.submit(Task::TestConnection { cfg: data });
    }

    pub fn test_upload(&mut self) {
        let data = self.collect_form();
        self.status = "正在测试上传…".into();
        self.submit(Task::TestUpload { cfg: data });
    }

    // ---------- 浏览 ----------

    pub fn browser_refresh(&mut self) {
        if self.browser.busy || !self.cfg.is_ready() {
            if !self.cfg.is_ready() {
                self.browser.status = "请先在设置页保存 AK / SK / 空间名".into();
            }
            return;
        }
        let prefix = crate::sync::norm_prefix(&self.browser.prefix_input);
        self.browser.busy = true;
        self.browser.status = "正在列举…".into();
        self.browser.selected.clear();
        self.browser.marker_stack.clear();
        self.submit(Task::List {
            cfg: self.cfg.clone(),
            prefix,
            marker: None,
        });
    }

    pub fn browser_next_page(&mut self) {
        if self.browser.busy || self.browser.next_marker.is_none() {
            return;
        }
        let prefix = self.browser.loaded_prefix.clone();
        self.browser.busy = true;
        self.browser.status = "正在加载下一页…".into();
        self.browser.marker_stack.push(self.browser.page_start.clone());
        self.submit(Task::List {
            cfg: self.cfg.clone(),
            prefix,
            marker: self.browser.next_marker.clone(),
        });
    }

    pub fn browser_prev_page(&mut self) {
        if self.browser.busy || self.browser.marker_stack.is_empty() {
            return;
        }
        let prefix = self.browser.loaded_prefix.clone();
        self.browser.busy = true;
        self.browser.status = "正在加载上一页…".into();
        let marker = self.browser.marker_stack.pop().flatten();
        self.submit(Task::List {
            cfg: self.cfg.clone(),
            prefix,
            marker,
        });
    }

    pub fn browser_up_level(&mut self) {
        let p = crate::sync::norm_prefix(&self.browser.prefix_input);
        let mut parts: Vec<&str> = p.split('/').filter(|s| !s.is_empty()).collect();
        parts.pop();
        self.browser.prefix_input = parts.join("/");
        self.browser_refresh();
    }

    pub fn browser_delete_selected(&mut self) {
        let keys: Vec<String> = self.browser.selected.iter().cloned().collect();
        if keys.is_empty() {
            return;
        }
        self.browser.busy = true;
        self.browser.status = format!("正在删除 {} 个对象…", keys.len());
        self.submit(Task::DeleteKeys {
            cfg: self.cfg.clone(),
            keys,
        });
    }

    pub fn browser_download_selected(&mut self) {
        let entries: Vec<ListItem> = self
            .browser
            .items
            .iter()
            .filter(|it| self.browser.selected.contains(&it.key))
            .cloned()
            .collect();
        if entries.is_empty() {
            return;
        }
        let root = if self.cfg.download_dir.is_empty() {
            dirs_home()
        } else {
            self.cfg.download_dir.clone()
        };
        self.browser.busy = true;
        self.browser.status = format!("正在下载 {} 个对象…", entries.len());
        self.submit(Task::DownloadKeys {
            cfg: self.cfg.clone(),
            entries,
            root,
        });
    }

    pub fn browser_upload_files(&mut self, paths: Vec<PathBuf>) {
        if paths.is_empty() || !self.cfg.is_ready() {
            return;
        }
        let prefix = crate::sync::norm_prefix(&self.browser.prefix_input);
        let pfx = if prefix.is_empty() { String::new() } else { format!("{}/", prefix) };
        let root = PathBuf::from(&self.cfg.download_dir);
        let specs: Vec<(PathBuf, String)> = paths
            .into_iter()
            .map(|p| {
                let rel = p.strip_prefix(&root).unwrap_or(&p);
                let key = format!("{}{}", pfx, rel.to_string_lossy().replace('\\', "/"));
                (p, key)
            })
            .collect();
        self.browser.busy = true;
        self.browser.status = format!("正在上传 {} 个文件…", specs.len());
        self.submit(Task::UploadFiles {
            cfg: self.cfg.clone(),
            specs,
            prefix,
        });
    }

    /// 上传整个文件夹（递归，保留子目录结构，同名覆盖）。
    pub fn browser_upload_dir(&mut self, dir: PathBuf) {
        if !self.cfg.is_ready() {
            return;
        }
        let prefix = crate::sync::norm_prefix(&self.browser.prefix_input);
        let pfx = if prefix.is_empty() { String::new() } else { format!("{}/", prefix) };
        let mut specs = Vec::new();
        let base = dir.clone();
        let mut stack = vec![dir];
        while let Some(d) = stack.pop() {
            if let Ok(rd) = std::fs::read_dir(&d) {
                for e in rd.flatten() {
                    let p = e.path();
                    if p.is_dir() {
                        stack.push(p);
                    } else {
                        let name = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
                        let low = name.to_lowercase();
                        if low == "thumbs.db" || low == "desktop.ini" || low.ends_with(".part") {
                            continue;
                        }
                        let rel = p.strip_prefix(&base).unwrap_or(&p);
                        let key = format!("{}{}", pfx, rel.to_string_lossy().replace('\\', "/"));
                        specs.push((p, key));
                    }
                }
            }
        }
        if specs.is_empty() {
            self.browser.status = "该文件夹里没有可上传的文件".into();
            return;
        }
        self.browser.busy = true;
        self.browser.status = format!("正在上传 {} 个文件…", specs.len());
        self.submit(Task::UploadFiles {
            cfg: self.cfg.clone(),
            specs,
            prefix,
        });
    }

    // ---------- 调度 ----------

    pub fn schedule_next(&mut self, keep_status: bool) {
        if self.paused {
            self.next_sync_at = None;
            self.status = "自动同步已暂停".into();
            return;
        }
        if !self.cfg.is_ready() {
            self.next_sync_at = Some(Instant::now() + Duration::from_secs(30));
            return;
        }
        let interval = Duration::from_secs((self.cfg.interval_minutes.max(1) as u64) * 60);
        self.next_sync_at = Some(Instant::now() + interval);
        if !self.sync_busy && !keep_status {
            let nxt = chrono::Local::now() + chrono::Duration::from_std(interval).unwrap_or_default();
            self.status = format!("下次自动同步: {}", nxt.format("%H:%M:%S"));
        }
    }

    // ---------- 主循环 ----------

    pub fn update_ui(&mut self, ctx: &Context) {
        // 1. 取工作线程事件
        while let Ok(ev) = self.ev_rx.try_recv() {
            self.handle_worker_event(ev);
        }
        // 2. 取托盘事件
        self.poll_tray_events(ctx);
        // 3. 定时触发
        if !self.paused && !self.sync_busy {
            if let Some(at) = self.next_sync_at {
                if Instant::now() >= at {
                    self.start_sync(None);
                }
            }
        }
        // 4. 渲染
        ui::render(self, ctx);
        // 5. 每秒唤醒（状态栏时钟 / 定时器）
        ctx.request_repaint_after(Duration::from_millis(500));
    }

    fn handle_worker_event(&mut self, ev: WorkerEvent) {
        match ev {
            WorkerEvent::SyncDone(s) => {
                self.sync_busy = false;
                let label = config::sync_mode_label(&s.mode).split('（').next().unwrap_or(&s.mode);
                if let Some(e) = &s.error {
                    logger::error(&format!("同步出错: {}", e));
                }
                logger::info(&format!(
                    "同步完成[{}]: 上传 {} / 下载 {} / 跳过 {} / 失败 {} / 云端 {} / 本地 {}",
                    label, s.uploaded.len(), s.downloaded.len(), s.skipped, s.failed.len(), s.total, s.local_total
                ));
                for (key, err) in &s.failed {
                    logger::warn(&format!("  失败: {} ({})", key, err));
                }
                self.status = format!(
                    "同步完成 {} [{}]: 上传 {} / 下载 {} / 失败 {}",
                    chrono::Local::now().format("%H:%M:%S"),
                    label,
                    s.uploaded.len(),
                    s.downloaded.len(),
                    s.failed.len()
                );
                if let Some(reg) = &s.detected_region {
                    self.apply_detected_region(reg);
                }
                self.last_sync_summary = Some(s);
                self.schedule_next(true);
            }
            WorkerEvent::TestDone { title, ok, msg } => {
                self.test_result = Some((title.clone(), format!("{}\n\n{}", if ok { "✓ 成功" } else { "✗ 失败" }, msg)));
                self.status = format!("{}：{}", title, if ok { "成功" } else { "失败" });
            }
            WorkerEvent::ListDone { items, next_marker, req_marker } => {
                self.browser.busy = false;
                self.browser.items = items;
                self.browser.next_marker = next_marker;
                self.browser.page_start = req_marker;
                self.browser.status = if self.browser.next_marker.is_some() {
                    format!("{} 个对象，还有更多", self.browser.items.len())
                } else {
                    format!("{} 个对象，已到末页", self.browser.items.len())
                };
            }
            WorkerEvent::ListFailed { msg } => {
                self.browser.busy = false;
                self.browser.status = format!("列举失败: {}", msg);
                logger::error(&format!("列举对象失败: {}", msg));
            }
            WorkerEvent::OpDone { title, msg, refresh_browser } => {
                self.browser.busy = false;
                self.browser.status = msg.clone();
                self.test_result = Some((title, msg));
                if refresh_browser {
                    self.browser_refresh();
                }
            }
            WorkerEvent::RegionDetected(reg) => {
                self.apply_detected_region(&reg);
            }
        }
    }

    fn apply_detected_region(&mut self, reg: &str) {
        if reg.is_empty() || reg == self.cfg.region {
            return;
        }
        logger::warn(&format!(
            "空间实际区域为 {}（原设置 {}），已自动更新配置",
            reg, self.cfg.region
        ));
        self.cfg.region = reg.to_string();
        self.f_region = reg.to_string();
        let _ = config::save(&self.cfg);
    }

    fn poll_tray_events(&mut self, ctx: &Context) {
        use tray_icon::menu::MenuEvent;
        use tray_icon::TrayIconEvent;

        // 菜单点击
        if let Ok(rx) = MenuEvent::receiver().try_recv() {
            match rx.id.0.as_str() {
                tray::MENU_SHOW => show_window(ctx),
                tray::MENU_SYNC_NOW => self.start_sync(None),
                tray::MENU_SYNC_UPLOAD => self.start_sync(Some("upload")),
                tray::MENU_SYNC_DOWNLOAD => self.start_sync(Some("download")),
                tray::MENU_TOGGLE_PAUSE => {
                    self.paused = !self.paused;
                    logger::info(&format!("自动同步已{}", if self.paused { "暂停" } else { "恢复" }));
                    self.schedule_next(false);
                }
                tray::MENU_OPEN_DIR => self.open_local_dir(),
                tray::MENU_OPEN_CLOUD => {
                    if let Err(e) = self.open_cloud() {
                        self.test_result = Some(("打开云端".into(), e));
                    }
                }
                tray::MENU_QUIT => {
                    self.quit_requested = true;
                }
                _ => {}
            }
        }
        // 左键/双击图标 → 显示窗口
        if let Ok(ev) = TrayIconEvent::receiver().try_recv() {
            let show = matches!(
                ev,
                tray_icon::TrayIconEvent::Click { button: tray_icon::MouseButton::Left, button_state: tray_icon::MouseButtonState::Up, .. }
            );
            if show {
                show_window(ctx);
            }
        }
    }

    pub fn open_local_dir(&mut self) {
        let d = if self.cfg.download_dir.is_empty() { return } else { self.cfg.download_dir.clone() };
        let path = PathBuf::from(&d);
        if !path.is_dir() {
            match std::fs::create_dir_all(&path) {
                Ok(_) => {}
                Err(e) => {
                    self.test_result = Some(("本地目录".into(), format!("无法创建目录: {}", e)));
                    return;
                }
            }
        }
        let _ = std::process::Command::new("explorer").arg(&d).spawn();
    }

    /// 浏览器打开云端目录（用配置的下载域名拼访问地址）。
    pub fn open_cloud(&self) -> Result<(), String> {
        let domain = self.cfg.download_domain.trim().trim_end_matches('/');
        if domain.is_empty() {
            return Err("尚未配置「下载域名 / 访问域名」。请到设置页填写后重试。".into());
        }
        let d = if domain.starts_with("http://") || domain.starts_with("https://") {
            domain.to_string()
        } else {
            format!("https://{}", domain)
        };
        let p = crate::sync::norm_prefix(&self.cfg.prefix);
        let mut url = d;
        if !p.is_empty() {
            url.push('/');
            url.push_str(&crate::qiniu::client::enc(&p));
        }
        let _ = webbrowser::open(&url);
        logger::info(&format!("已在浏览器打开云端地址: {}", url));
        Ok(())
    }
}

fn show_window(ctx: &Context) {
    ctx.send_viewport_cmd(egui::ViewportCommand::Visible(true));
    ctx.send_viewport_cmd(egui::ViewportCommand::Focus);
}

fn dirs_home() -> String {
    std::env::var("USERPROFILE").unwrap_or_else(|_| ".".to_string())
}

// ---------------- 工作线程 ----------------

fn worker_loop(rx: Receiver<Task>, ev: Sender<WorkerEvent>) {
    while let Ok(task) = rx.recv() {
        match task {
            Task::Sync { cfg, mode } => {
                let summary = sync::run_once(&cfg, Some(&mode));
                let _ = ev.send(WorkerEvent::SyncDone(summary));
            }
            Task::TestConnection { cfg } => {
                let mut c = Kodo::new(&cfg.ak, &cfg.sk, &cfg.bucket, &cfg.region, &cfg.download_domain, &cfg.proxy_mode);
                let prefix = sync::norm_prefix(&cfg.prefix);
                match c.list_page(&prefix, None, 5) {
                    Ok((items, _)) => {
                        let mut msg = format!(
                            "Bucket: {}\n当前云端目录: {}\n本页对象数: {}",
                            cfg.bucket,
                            if prefix.is_empty() { "整个桶".to_string() } else { prefix.clone() },
                            items.len()
                        );
                        msg.push_str(&format!(
                            "\n网络模式: {}",
                            if c.direct { "直连（忽略系统代理）" } else { "系统代理" }
                        ));
                        msg.push_str(&format!("\n同步方向: {}", config::sync_mode_label(&cfg.sync_mode)));
                        match c.resolve_domain() {
                            Ok(domain) => {
                                msg.push_str(&format!("\n下载域名: {}", domain));
                                if let Some(first) = items.first() {
                                    match c.probe_status(&first.key) {
                                        Ok(code) => {
                                            msg.push_str(&format!(
                                                "\n私有下载测试: {}",
                                                if code == 200 { "OK".to_string() } else { format!("失败 HTTP {}", code) }
                                            ));
                                        }
                                        Err(e) => msg.push_str(&format!("\n私有下载测试: 失败 {}", e)),
                                    }
                                }
                            }
                            Err(e) => msg.push_str(&format!("\n下载域名获取失败: {}", e)),
                        }
                        let _ = ev.send(WorkerEvent::TestDone {
                            title: "测试连接".into(),
                            ok: true,
                            msg,
                        });
                    }
                    Err(e) => {
                        let _ = ev.send(WorkerEvent::TestDone {
                            title: "测试连接".into(),
                            ok: false,
                            msg: format!(
                                "连接失败: {}\n\n若提示代理错误，可到设置页把「网络代理」改为「直连」。",
                                e
                            ),
                        });
                    }
                }
            }
            Task::TestUpload { cfg } => {
                let prefix = sync::norm_prefix(&cfg.prefix);
                let key = format!("{}_selftest_qiniu_sync.tmp", prefix);
                let tmp = std::env::temp_dir().join(format!("qsync_{}.txt", std::process::id()));
                if let Err(e) = std::fs::write(&tmp, "qiniu file sync selftest") {
                    let _ = ev.send(WorkerEvent::TestDone {
                        title: "上传自检".into(),
                        ok: false,
                        msg: format!("写临时文件失败: {}", e),
                    });
                    continue;
                }
                let mut c = Kodo::new(&cfg.ak, &cfg.sk, &cfg.bucket, &cfg.region, &cfg.download_domain, &cfg.proxy_mode);
                match c.upload_file(&tmp, &key) {
                    Ok(rh) => {
                        if let Some(reg) = c.detected_region.clone() {
                            let _ = ev.send(WorkerEvent::RegionDetected(reg));
                        }
                        let cleaned = c.delete(&key).is_ok();
                        if !cleaned {
                            logger::warn("清理上传自检对象失败，请到控制台手动删除");
                        }
                        let _ = ev.send(WorkerEvent::TestDone {
                            title: "上传自检".into(),
                            ok: true,
                            msg: format!(
                                "上传成功。\n测试对象: {}\n云端 hash: {}\n上传端点: {}\n实际区域: {}（设置值 {}）\n测试对象已清理: {}",
                                key,
                                rh,
                                c.up_host_display(),
                                c.detected_region.clone().unwrap_or_else(|| cfg.region.clone()),
                                cfg.region,
                                if cleaned { "是" } else { "否（请到控制台手动删除）" }
                            ),
                        });
                    }
                    Err(e) => {
                        let _ = ev.send(WorkerEvent::TestDone {
                            title: "上传自检".into(),
                            ok: false,
                            msg: format!("上传测试失败: {}", e),
                        });
                    }
                }
                let _ = std::fs::remove_file(&tmp);
            }
            Task::List { cfg, prefix, marker } => {
                let mut c = Kodo::new(&cfg.ak, &cfg.sk, &cfg.bucket, &cfg.region, &cfg.download_domain, &cfg.proxy_mode);
                match c.list_page(&prefix, marker.as_deref(), 100) {
                    Ok((items, next)) => {
                        let _ = ev.send(WorkerEvent::ListDone {
                            items,
                            next_marker: next,
                            req_marker: marker,
                        });
                    }
                    Err(e) => {
                        let _ = ev.send(WorkerEvent::ListFailed { msg: e });
                    }
                }
            }
            Task::DeleteKeys { cfg, keys } => {
                let mut c = Kodo::new(&cfg.ak, &cfg.sk, &cfg.bucket, &cfg.region, &cfg.download_domain, &cfg.proxy_mode);
                let mut errs = Vec::new();
                for k in &keys {
                    if let Err(e) = c.delete(k) {
                        errs.push(format!("{}: {}", k, e));
                    }
                }
                logger::info(&format!("删除完成: 成功 {} / 失败 {}", keys.len() - errs.len(), errs.len()));
                for e in &errs {
                    logger::warn(&format!("  删除失败: {}", e));
                }
                let _ = ev.send(WorkerEvent::OpDone {
                    title: "删除".into(),
                    msg: format!("成功 {} 个，失败 {} 个。", keys.len() - errs.len(), errs.len()),
                    refresh_browser: true,
                });
            }
            Task::DownloadKeys { cfg, entries, root } => {
                let mut c = Kodo::new(&cfg.ak, &cfg.sk, &cfg.bucket, &cfg.region, &cfg.download_domain, &cfg.proxy_mode);
                let root = PathBuf::from(root);
                let mut saved = Vec::new();
                let mut errs = Vec::new();
                for it in &entries {
                    let dest = sync::local_target(&root, &it.key);
                    match c.download(&it.key, &dest) {
                        Ok(p) => saved.push(p.to_string_lossy().to_string()),
                        Err(e) => errs.push(format!("{}: {}", it.key, e)),
                    }
                }
                logger::info(&format!("手动下载完成: 成功 {} / 失败 {}", saved.len(), errs.len()));
                for e in &errs {
                    logger::warn(&format!("  下载失败: {}", e));
                }
                let mut msg = format!("成功 {} 个:\n{}", saved.len(), saved.iter().take(8).cloned().collect::<Vec<_>>().join("\n"));
                if saved.len() > 8 {
                    msg.push_str("\n…");
                }
                if !errs.is_empty() {
                    msg.push_str(&format!("\n\n失败 {} 个（详见日志）", errs.len()));
                }
                let _ = ev.send(WorkerEvent::OpDone {
                    title: "下载".into(),
                    msg,
                    refresh_browser: false,
                });
            }
            Task::UploadFiles { cfg, specs, prefix } => {
                let mut c = Kodo::new(&cfg.ak, &cfg.sk, &cfg.bucket, &cfg.region, &cfg.download_domain, &cfg.proxy_mode);
                let mut state = state::load();
                let mut keys = Vec::new();
                let mut errs = Vec::new();
                for (path, key) in &specs {
                    match c.upload_file(path, key) {
                        Ok(rh) => {
                            let size = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
                            state::record(&mut state, key, &rh, size as i64, &path.to_string_lossy());
                            keys.push(key.clone());
                            logger::info(&format!("手动上传: {} -> {}", path.display(), key));
                        }
                        Err(e) => {
                            errs.push(format!("{}: {}", key, e));
                            logger::warn(&format!("手动上传失败: {} ({})", key, e));
                        }
                    }
                }
                let _ = state::save(&state);
                logger::info(&format!("手动上传完成: 成功 {} / 失败 {}", keys.len(), errs.len()));
                let mut msg = format!(
                    "成功上传 {} 个对象到 {}:\n{}",
                    keys.len(),
                    if prefix.is_empty() { "桶根目录".to_string() } else { prefix.clone() },
                    keys.iter().take(8).cloned().collect::<Vec<_>>().join("\n")
                );
                if keys.len() > 8 {
                    msg.push_str("\n…");
                }
                if !errs.is_empty() {
                    msg.push_str(&format!("\n\n失败 {} 个（详见日志）", errs.len()));
                }
                let _ = ev.send(WorkerEvent::OpDone {
                    title: "上传".into(),
                    msg,
                    refresh_browser: true,
                });
            }
        }
    }
}
