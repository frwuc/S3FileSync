//! Tauri 命令层：前端 invoke 入口。
//!
//! 同步调度类（start_sync / toggle_pause / save_config）走 runtime 全局状态；
//! 网络 IO 类（测试 / 列举 / 上传 / 下载 / 删除）为 async 命令，在线程池执行，
//! 不与同步工作线程互斥（与 egui 版单工作线程串行模型相比，仅交互操作并发）。

use std::path::{Path, PathBuf};

use tauri::async_runtime::spawn_blocking;

use crate::config::{self, Config};
use crate::qiniu::client::ListItem;
use crate::runtime::{self, Snapshot};
use crate::state;
use crate::storage::Backend;
use crate::sync;

#[derive(serde::Serialize)]
pub struct TestResult {
    pub ok: bool,
    pub title: String,
    pub msg: String,
}

#[derive(serde::Serialize)]
pub struct PageResult {
    pub items: Vec<ListItem>,
    pub next_marker: Option<String>,
}

#[derive(serde::Deserialize)]
pub struct DownloadEntry {
    pub key: String,
    pub hash: String,
    pub fsize: i64,
}

fn norm(p: &str) -> String {
    sync::norm_prefix(p)
}

// ---------- 状态 ----------

#[tauri::command]
pub fn get_state() -> Snapshot {
    runtime::snapshot()
}

#[tauri::command]
pub fn get_logs() -> Vec<String> {
    crate::logger::snapshot()
}

#[tauri::command]
pub fn clear_logs() {
    crate::logger::clear_memory();
}

#[tauri::command]
pub fn export_logs(path: String) -> Result<u64, String> {
    crate::logger::export_to(Path::new(&path)).map_err(|e| e.to_string())
}

// ---------- 配置与调度 ----------

#[tauri::command]
pub fn save_config(cfg: Config) -> Result<Snapshot, String> {
    runtime::save_config(cfg)?;
    Ok(runtime::snapshot())
}

#[tauri::command]
pub fn start_sync(mode: Option<String>) -> Result<Snapshot, String> {
    runtime::start_sync(mode.as_deref())?;
    Ok(runtime::snapshot())
}

#[tauri::command]
pub fn toggle_pause() -> Snapshot {
    runtime::toggle_pause();
    runtime::snapshot()
}

#[tauri::command]
pub fn set_autostart(enabled: bool) -> Result<(), String> {
    let r = if enabled {
        crate::autostart::enable()
    } else {
        crate::autostart::disable()
    };
    r.map_err(|e| format!("设置失败: {}", e))
}

// ---------- 测试 ----------

#[tauri::command]
pub async fn test_connection(cfg: Config) -> Result<TestResult, String> {
    spawn_blocking(move || {
        let missing = if cfg.is_s3() {
            "请先填写 AK / SK / 桶名 / S3 服务端点"
        } else {
            "请先填写 AK / SK / 空间名"
        };
        if cfg.ak.is_empty() || cfg.sk.is_empty() || cfg.bucket.is_empty() || (cfg.is_s3() && cfg.s3_endpoint.is_empty()) {
            return TestResult { ok: false, title: "测试连接".into(), msg: missing.into() };
        }
        let mut backend = Backend::new(&cfg);
        let prefix = norm(&cfg.prefix);
        match backend.list_page(&prefix, None, 5) {
            Ok((items, _)) => {
                let mut msg = format!(
                    "服务商: {}\nBucket: {}\n当前云端目录: {}\n本页对象数: {}",
                    config::provider_label(&cfg.provider),
                    cfg.bucket,
                    if prefix.is_empty() { "整个桶".to_string() } else { prefix.clone() },
                    items.len()
                );
                msg.push_str(&format!(
                    "\n网络模式: {}",
                    if backend.is_direct() { "直连（忽略系统代理）" } else { "系统代理" }
                ));
                msg.push_str(&format!("\n请求端点: {}", backend.endpoint_display()));
                msg.push_str(&format!("\n同步方向: {}", config::sync_mode_label(&cfg.sync_mode)));
                if let Backend::Kodo(k) = &mut backend {
                    match k.resolve_domain() {
                        Ok(domain) => {
                            msg.push_str(&format!("\n下载域名: {}", domain));
                            if let Some(first) = items.first() {
                                match k.probe_status(&first.key) {
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
                } else if let Some(first) = items.first() {
                    match backend.probe_status(&first.key) {
                        Ok(code) => {
                            msg.push_str(&format!(
                                "\n对象探测: {}",
                                if (200..300).contains(&code) { "OK".to_string() } else { format!("HTTP {}", code) }
                            ));
                        }
                        Err(e) => msg.push_str(&format!("\n对象探测: 失败 {}", e)),
                    }
                }
                TestResult { ok: true, title: "测试连接".into(), msg }
            }
            Err(e) => TestResult {
                ok: false,
                title: "测试连接".into(),
                msg: format!("连接失败: {}\n\n若提示代理错误，可到设置页把「网络代理」改为「直连」。", e),
            },
        }
    })
    .await
    .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn test_upload(cfg: Config) -> Result<TestResult, String> {
    spawn_blocking(move || {
        let prefix = norm(&cfg.prefix);
        let key = format!("{}_selftest_s3filesync.tmp", prefix);
        let tmp = std::env::temp_dir().join(format!("qsync_{}.txt", std::process::id()));
        if let Err(e) = std::fs::write(&tmp, "s3filesync selftest") {
            return TestResult { ok: false, title: "上传自检".into(), msg: format!("写临时文件失败: {}", e) };
        }
        let mut backend = Backend::new(&cfg);
        let result = match backend.upload_file(&tmp, &key) {
            Ok(rh) => {
                if let Some(reg) = backend.detected_region() {
                    runtime::apply_detected_region(&reg);
                }
                let cleaned = backend.delete(&key).is_ok();
                if !cleaned {
                    crate::logger::warn("清理上传自检对象失败，请到控制台手动删除");
                }
                TestResult {
                    ok: true,
                    title: "上传自检".into(),
                    msg: format!(
                        "上传成功。\n测试对象: {}\n云端内容标识: {}\n请求端点: {}{}\n测试对象已清理: {}",
                        key,
                        rh,
                        backend.endpoint_display(),
                        if cfg.is_s3() { String::new() } else {
                            format!(
                                "\n实际区域: {}（设置值 {}）",
                                backend.detected_region().unwrap_or_else(|| cfg.region.clone()),
                                cfg.region
                            )
                        },
                        if cleaned { "是" } else { "否（请到控制台手动删除）" }
                    ),
                }
            }
            Err(e) => TestResult { ok: false, title: "上传自检".into(), msg: format!("上传测试失败: {}", e) },
        };
        let _ = std::fs::remove_file(&tmp);
        result
    })
    .await
    .map_err(|e| e.to_string())
}

// ---------- 浏览 / 手动操作 ----------

#[tauri::command]
pub async fn list_page(cfg: Config, prefix: String, marker: Option<String>, limit: Option<u32>) -> Result<PageResult, String> {
    spawn_blocking(move || {
        let mut backend = Backend::new(&cfg);
        let (items, next) = backend
            .list_page(&norm(&prefix), marker.as_deref(), limit.unwrap_or(100).clamp(1, 500))?;
        Ok(PageResult { items, next_marker: next })
    })
    .await
    .map_err(|e| e.to_string())
    .and_then(|r| r)
}

#[tauri::command]
pub async fn list_dirs(cfg: Config, prefix: String) -> Result<Vec<String>, String> {
    spawn_blocking(move || {
        let mut backend = Backend::new(&cfg);
        backend.list_dirs(&norm(&prefix))
    })
    .await
    .map_err(|e| e.to_string())
    .and_then(|r| r)
}

#[tauri::command]
pub async fn delete_keys(cfg: Config, keys: Vec<String>) -> Result<String, String> {
    spawn_blocking(move || {
        let mut backend = Backend::new(&cfg);
        let mut errs = Vec::new();
        for k in &keys {
            if let Err(e) = backend.delete(k) {
                errs.push(format!("{}: {}", k, e));
            }
        }
        crate::logger::info(&format!("删除完成: 成功 {} / 失败 {}", keys.len() - errs.len(), errs.len()));
        for e in &errs {
            crate::logger::warn(&format!("  删除失败: {}", e));
        }
        Ok(format!("成功 {} 个，失败 {} 个。", keys.len() - errs.len(), errs.len()))
    })
    .await
    .map_err(|e| e.to_string())
    .and_then(|r| r)
}

#[tauri::command]
pub async fn download_keys(cfg: Config, entries: Vec<DownloadEntry>) -> Result<String, String> {
    spawn_blocking(move || {
        let mut backend = Backend::new(&cfg);
        let root = if cfg.download_dir.is_empty() {
            std::env::var("USERPROFILE").unwrap_or_else(|_| ".".to_string())
        } else {
            cfg.download_dir.clone()
        };
        let root = PathBuf::from(root);
        let mut saved = Vec::new();
        let mut errs = Vec::new();
        for it in &entries {
            let item = ListItem { key: it.key.clone(), hash: it.hash.clone(), fsize: it.fsize, put_time: 0 };
            let dest = sync::local_target(&root, &item.key);
            match backend.download(&item.key, &dest) {
                Ok(p) => saved.push(p.to_string_lossy().to_string()),
                Err(e) => errs.push(format!("{}: {}", it.key, e)),
            }
        }
        crate::logger::info(&format!("手动下载完成: 成功 {} / 失败 {}", saved.len(), errs.len()));
        for e in &errs {
            crate::logger::warn(&format!("  下载失败: {}", e));
        }
        let mut msg = format!("成功 {} 个:\n{}", saved.len(), saved.iter().take(8).cloned().collect::<Vec<_>>().join("\n"));
        if saved.len() > 8 {
            msg.push_str("\n…");
        }
        if !errs.is_empty() {
            msg.push_str(&format!("\n\n失败 {} 个（详见日志）", errs.len()));
        }
        Ok(msg)
    })
    .await
    .map_err(|e| e.to_string())
    .and_then(|r| r)
}

#[tauri::command]
pub async fn upload_paths(cfg: Config, paths: Vec<String>, prefix: String) -> Result<String, String> {
    spawn_blocking(move || {
        let prefix = norm(&prefix);
        let pfx = if prefix.is_empty() { String::new() } else { format!("{}/", prefix) };
        let root = PathBuf::from(&cfg.download_dir);
        let specs: Vec<(PathBuf, String)> = paths
            .into_iter()
            .map(|p| {
                let p = PathBuf::from(&p);
                let rel = p.strip_prefix(&root).unwrap_or(&p);
                let key = format!("{}{}", pfx, rel.to_string_lossy().replace('\\', "/"));
                (p, key)
            })
            .collect();
        upload_specs(cfg, specs, prefix)
    })
    .await
    .map_err(|e| e.to_string())
    .and_then(|r| r)
}

#[tauri::command]
pub async fn upload_dir(cfg: Config, dir: String, prefix: String) -> Result<String, String> {
    spawn_blocking(move || {
        let prefix = norm(&prefix);
        let pfx = if prefix.is_empty() { String::new() } else { format!("{}/", prefix) };
        let dir = PathBuf::from(&dir);
        let base = dir.clone();
        let mut specs = Vec::new();
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
            return Ok("该文件夹里没有可上传的文件".into());
        }
        upload_specs(cfg, specs, prefix)
    })
    .await
    .map_err(|e| e.to_string())
    .and_then(|r| r)
}

/// 手动上传公共实现：逐个上传 + 记录同步状态。
fn upload_specs(cfg: Config, specs: Vec<(PathBuf, String)>, prefix: String) -> Result<String, String> {
    let mut backend = Backend::new(&cfg);
    let mut st = state::load();
    let mut keys = Vec::new();
    let mut errs = Vec::new();
    for (path, key) in &specs {
        match backend.upload_file(path, key) {
            Ok(rh) => {
                let size = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
                state::record(&mut st, key, &rh, size as i64, &path.to_string_lossy());
                keys.push(key.clone());
                crate::logger::info(&format!("手动上传: {} -> {}", path.display(), key));
            }
            Err(e) => {
                errs.push(format!("{}: {}", key, e));
                crate::logger::warn(&format!("手动上传失败: {} ({})", key, e));
            }
        }
    }
    let _ = state::save(&st);
    crate::logger::info(&format!("手动上传完成: 成功 {} / 失败 {}", keys.len(), errs.len()));
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
    Ok(msg)
}

// ---------- 打开外部资源 ----------

/// 打开本地目录（不存在则创建）。
#[tauri::command]
pub fn open_local_dir(path: String) -> Result<(), String> {
    if path.trim().is_empty() {
        return Err("目录为空".into());
    }
    let p = PathBuf::from(&path);
    if !p.is_dir() {
        std::fs::create_dir_all(&p).map_err(|e| format!("无法创建目录: {}", e))?;
    }
    let _ = std::process::Command::new("explorer").arg(&path).spawn();
    Ok(())
}

/// 在资源管理器中定位文件。
#[tauri::command]
pub fn reveal_path(path: String) {
    let _ = std::process::Command::new("explorer")
        .arg(format!("/select,{}", path))
        .spawn();
}

#[tauri::command]
pub fn open_url(url: String) {
    let _ = webbrowser::open(&url);
}

/// 浏览器打开云端目录（七牛用下载域名拼地址；S3 用端点 + 桶路径）。
#[tauri::command]
pub fn open_cloud() -> Result<(), String> {
    let cfg = runtime::current_cfg();
    let p = norm(&cfg.prefix);
    let url = if cfg.is_s3() {
        let ep = cfg.s3_endpoint.trim().trim_end_matches('/');
        if ep.is_empty() {
            return Err("尚未配置 S3 服务端点。请到设置页填写后重试。".into());
        }
        let base = if ep.starts_with("http://") || ep.starts_with("https://") {
            ep.to_string()
        } else {
            format!("https://{}", ep)
        };
        let mut url = format!("{}/{}", base, crate::qiniu::client::enc(&cfg.bucket));
        if !p.is_empty() {
            url.push('/');
            url.push_str(&crate::qiniu::client::enc(&p));
        }
        url
    } else {
        let domain = cfg.download_domain.trim().trim_end_matches('/');
        if domain.is_empty() {
            return Err("尚未配置「下载域名 / 访问域名」。请到设置页填写后重试。".into());
        }
        let d = if domain.starts_with("http://") || domain.starts_with("https://") {
            domain.to_string()
        } else {
            format!("https://{}", domain)
        };
        let mut url = d;
        if !p.is_empty() {
            url.push('/');
            url.push_str(&crate::qiniu::client::enc(&p));
        }
        url
    };
    let _ = webbrowser::open(&url);
    crate::logger::info(&format!("已在浏览器打开云端地址: {}", url));
    Ok(())
}
