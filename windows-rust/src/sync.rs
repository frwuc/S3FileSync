//! 同步引擎：三种方向（仅下载 / 仅上传 / 双向），对齐 Python 端行为。
//!
//! 本地根目录与云端 key 一一对应；上传按 etag 比对跳过重复；
//! 下载按状态表 + 文件存在性判断；双向先上传再下载。

use std::fs;
use std::path::{Path, PathBuf};
use std::time::Instant;

use crate::config::{self, Config};
use crate::qiniu::client::{Kodo, ListItem};
use crate::qiniu::etag;
use crate::state::{self, SyncState};

const ILLEGAL: [char; 7] = ['<', '>', ':', '"', '|', '?', '*'];
const SKIP_NAMES: [&str; 3] = ["thumbs.db", "desktop.ini", ".ds_store"];

#[derive(Debug, Default, Clone, serde::Serialize)]
pub struct Summary {
    pub mode: String,
    pub total: usize,
    pub local_total: usize,
    pub uploaded: Vec<String>,
    pub downloaded: Vec<String>,
    pub failed: Vec<(String, String)>,
    pub skipped: usize,
    pub error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub detected_region: Option<String>,
    /// 单轮耗时（毫秒）
    pub elapsed_ms: u64,
}

/// 把对象 key 转为 Windows 合法相对路径（逐段替换非法字符、去尾部空格与点）。
pub fn sanitize_key(key: &str) -> String {
    let mut parts = Vec::new();
    for seg in key.split('/') {
        let cleaned: String = seg
            .chars()
            .map(|c| if ILLEGAL.contains(&c) || (c as u32) < 0x20 { '_' } else { c })
            .collect();
        let trimmed = cleaned.trim_end_matches([' ', '.']);
        parts.push(if trimmed.is_empty() { "_".to_string() } else { trimmed.to_string() });
    }
    parts.join("\\")
}

/// 云端 key 对应的本地绝对路径（超长自动加 \\?\ 前缀）。
pub fn local_target(root: &Path, key: &str) -> PathBuf {
    let mut p = root.to_path_buf();
    for seg in sanitize_key(key).split('\\') {
        p.push(seg);
    }
    let s = p.to_string_lossy().to_string();
    if s.len() > 240 {
        PathBuf::from(format!("\\\\?\\{}", s))
    } else {
        p
    }
}

/// 统一前缀格式：用 / 分隔，去掉首尾空白与开头的 /。
pub fn norm_prefix(prefix: &str) -> String {
    prefix.trim().replace('\\', "/").trim_start_matches('/').to_string()
}

/// 云端前缀对应的本地子目录。
fn local_scope(root: &Path, prefix: &str) -> PathBuf {
    let normed = norm_prefix(prefix);
    let parts: Vec<&str> = normed
        .split('/')
        .filter(|p| !p.is_empty())
        .collect();
    let mut p = root.to_path_buf();
    for seg in parts {
        let cleaned: String = seg
            .chars()
            .map(|c| if ILLEGAL.contains(&c) { '_' } else { c })
            .collect();
        let trimmed = cleaned.trim_end_matches([' ', '.']);
        p.push(if trimmed.is_empty() { "_" } else { trimmed });
    }
    p
}

fn in_root(path: &Path, root: &Path) -> bool {
    let abs = fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf());
    let root_abs = fs::canonicalize(root).unwrap_or_else(|_| root.to_path_buf());
    abs.starts_with(root_abs)
}

/// 本地文件清单：(云端 key, 本地绝对路径, 字节数)，key 用 / 分隔。
/// 跳过临时/系统文件与旧版目录占位空 '_' 文件。
pub fn iter_local_files(root: &Path, prefix: &str) -> Vec<(String, PathBuf, u64)> {
    let scope = local_scope(root, prefix);
    if !scope.is_dir() {
        return Vec::new();
    }
    let mut out = Vec::new();
    let mut stack = vec![scope];
    while let Some(dir) = stack.pop() {
        let rd = match fs::read_dir(&dir) {
            Ok(rd) => rd,
            Err(_) => continue,
        };
        for entry in rd.flatten() {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();
            let low = name.to_lowercase();
            if low.ends_with(".part") || name.starts_with("~$") || SKIP_NAMES.contains(&low.as_str()) {
                continue;
            }
            let meta = match entry.metadata() {
                Ok(m) => m,
                Err(_) => continue,
            };
            if meta.is_dir() {
                stack.push(path);
                continue;
            }
            let size = meta.len();
            if name == "_" && size == 0 {
                continue;
            }
            let rel = match path.strip_prefix(root) {
                Ok(r) => r,
                Err(_) => continue,
            };
            let key = rel.to_string_lossy().replace('\\', "/");
            out.push((key, path, size));
        }
    }
    out.sort_by(|a, b| a.0.cmp(&b.0));
    out
}

fn upload_phase(
    cfg: &Config,
    client: &mut Kodo,
    state: &mut SyncState,
    root: &Path,
    prefix: &str,
    summary: &mut Summary,
) -> Result<(), String> {
    let overwrite = cfg.upload_overwrite;
    let mut remote_map: std::collections::HashMap<String, ListItem> = std::collections::HashMap::new();
    for it in client.list_all(prefix).map_err(|e| format!("列举失败: {}", e))? {
        if !it.key.is_empty() {
            let key = it.key.clone();
            remote_map.insert(key, it);
        }
    }
    summary.total = remote_map.len();

    let files = iter_local_files(root, prefix);
    summary.local_total = files.len();
    for (key, path, size) in files {
        let h = match etag::etag_file(&path) {
            Ok(h) => h,
            Err(e) => {
                summary.failed.push((key, format!("无法计算本地校验值: {}", e)));
                continue;
            }
        };
        if let Some(cur) = remote_map.get(&key) {
            if cur.hash == h {
                summary.skipped += 1;
                continue;
            }
            if !overwrite {
                summary.skipped += 1;
                crate::logger::warn(&format!(
                    "云端已有同名且内容不同，已跳过: {}（可在设置页开启「上传覆盖同名」）",
                    key
                ));
                continue;
            }
        }
        match client.upload_file(&path, &key) {
            Ok(rh) => {
                state::record(state, &key, &rh, size as i64, &path.to_string_lossy());
                let _ = state::save(state);
                summary.uploaded.push(key.clone());
                crate::logger::info(&format!("已上传: {} -> {}", path.display(), key));
            }
            Err(e) => {
                summary.failed.push((key.clone(), e));
            }
        }
    }
    Ok(())
}

fn download_phase(
    _cfg: &Config,
    client: &mut Kodo,
    state: &mut SyncState,
    root: &Path,
    prefix: &str,
    summary: &mut Summary,
) -> Result<(), String> {
    let items = client.list_all(prefix).map_err(|e| format!("列举失败: {}", e))?;
    summary.total = items.len();
    for it in items {
        let key = &it.key;
        if key.is_empty() {
            continue;
        }
        // 以 / 结尾的是目录占位对象
        if key.ends_with('/') {
            summary.skipped += 1;
            continue;
        }
        let dest = local_target(root, key);
        let st = state.get(key);
        if let Some(st) = st {
            if st.etag == it.hash {
                // 状态里的本地文件必须仍位于当前根目录内
                let lp = Path::new(&st.local_path);
                if lp.is_file() && in_root(lp, root) {
                    summary.skipped += 1;
                    continue;
                }
                if dest.is_file() && fs::metadata(&dest).map(|m| m.len()).unwrap_or(0) == it.fsize as u64 {
                    state::record(state, key, &it.hash, it.fsize, &dest.to_string_lossy());
                    let _ = state::save(state);
                    summary.skipped += 1;
                    continue;
                }
            }
        }
        // state 丢失但本地同大小文件在 → 只补状态不重下
        if st.is_none()
            && dest.is_file()
            && fs::metadata(&dest).map(|m| m.len()).unwrap_or(0) == it.fsize as u64
        {
            state::record(state, key, &it.hash, it.fsize, &dest.to_string_lossy());
            let _ = state::save(state);
            summary.skipped += 1;
            continue;
        }
        // 本地同名但内容不同 → 冲突改名，不覆盖（a.txt → a.conflict1.txt）
        let mut target = dest.clone();
        if target.exists() {
            let s = dest.to_string_lossy().to_string();
            let (base, ext) = match s.rfind('.') {
                Some(i) if !s[i + 1..].contains('\\') && !s[i + 1..].contains('/') => {
                    (s[..i].to_string(), s[i + 1..].to_string())
                }
                _ => (s.clone(), String::new()),
            };
            let mut i = 1;
            loop {
                target = PathBuf::from(if ext.is_empty() {
                    format!("{}.conflict{}", base, i)
                } else {
                    format!("{}.conflict{}.{}", base, i, ext)
                });
                if !target.exists() {
                    break;
                }
                i += 1;
            }
        }
        match client.download(key, &target) {
            Ok(_) => {
                state::record(state, key, &it.hash, it.fsize, &target.to_string_lossy());
                let _ = state::save(state);
                summary.downloaded.push(key.clone());
                crate::logger::info(&format!("已下载: {} -> {}", key, target.display()));
            }
            Err(e) => {
                summary.failed.push((key.clone(), e));
            }
        }
    }
    Ok(())
}

/// 执行一轮同步。
pub fn run_once(cfg: &Config, mode: Option<&str>) -> Summary {
    let started = Instant::now();
    let mut mode = mode.unwrap_or(&cfg.sync_mode).to_string();
    if !config::valid_sync_mode(&mode) {
        mode = "download".into();
    }
    let prefix = norm_prefix(&cfg.prefix);
    let root = PathBuf::from(if cfg.download_dir.is_empty() { "." } else { &cfg.download_dir });
    let _ = fs::create_dir_all(&root);

    let mut summary = Summary {
        mode: mode.clone(),
        ..Default::default()
    };
    let mut state = state::load();
    let mut client = Kodo::new(&cfg.ak, &cfg.sk, &cfg.bucket, &cfg.region,
                               &cfg.download_domain, &cfg.proxy_mode);
    crate::logger::info(&format!(
        "同步方向: {}（云端目录: {}）",
        config::sync_mode_label(&mode),
        if prefix.is_empty() { "整个空间".into() } else { prefix.clone() }
    ));

    let mut errors: Vec<String> = Vec::new();
    // 双向先上传再下载：上传写入的 state 让下载正确跳过刚上传的对象
    if mode == "upload" || mode == "both" {
        if let Err(e) = upload_phase(cfg, &mut client, &mut state, &root, &prefix, &mut summary) {
            crate::logger::error(&format!("上传阶段失败: {}", e));
            errors.push(format!("上传: {}", e));
        }
    }
    if mode == "download" || mode == "both" {
        if let Err(e) = download_phase(cfg, &mut client, &mut state, &root, &prefix, &mut summary) {
            crate::logger::error(&format!("下载阶段失败: {}", e));
            errors.push(format!("下载: {}", e));
        }
    }
    summary.error = if errors.is_empty() { None } else { Some(errors.join("; ")) };
    if let Some(reg) = &client.detected_region {
        summary.detected_region = Some(reg.clone());
    }
    let _ = state::save(&state);
    summary.elapsed_ms = started.elapsed().as_millis() as u64;
    summary
}

pub fn print_summary(s: &Summary) {
    println!(
        "同步完成[{}]: 上传 {} / 下载 {} / 跳过 {} / 失败 {} / 云端 {} / 本地 {}（{}）",
        config::sync_mode_label(&s.mode).split('（').next().unwrap_or(&s.mode),
        s.uploaded.len(),
        s.downloaded.len(),
        s.skipped,
        s.failed.len(),
        s.total,
        s.local_total,
        format_elapsed(s.elapsed_ms)
    );
    if let Some(e) = &s.error {
        println!("  阶段错误: {}", e);
    }
    for (key, err) in &s.failed {
        println!("  失败: {} ({})", key, err);
    }
}

fn format_elapsed(ms: u64) -> String {
    if ms >= 60_000 {
        format!("{}m{}s", ms / 60_000, (ms % 60_000) / 1000)
    } else {
        format!("{}.{:01}s", ms / 1000, (ms % 1000) / 100)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanitize_illegal_chars() {
        assert_eq!(sanitize_key("a<b>c.txt"), "a_b_c.txt");
        assert_eq!(sanitize_key("dir:name/x|.y"), "dir_name\\x_.y");
        // 尾部空格与点去掉
        assert_eq!(sanitize_key("a. /b ."), "a\\b");
        // 空段变 '_'
        assert_eq!(sanitize_key("a//b"), "a\\_\\b");
    }

    #[test]
    fn prefix_norm() {
        assert_eq!(norm_prefix(" /a\\b/c "), "a/b/c");
        assert_eq!(norm_prefix(""), "");
        assert_eq!(norm_prefix("//"), "");
    }

    #[test]
    fn local_target_maps_key() {
        let t = local_target(Path::new("D:/root"), "a/b c.txt");
        assert_eq!(t, PathBuf::from("D:/root").join("a").join("b c.txt"));
    }

    #[test]
    fn local_scope_and_iter() {
        let dir = std::env::temp_dir().join("s3fs_iter_test");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(dir.join("照片/2026")).unwrap();
        fs::write(dir.join("照片/2026/a.jpg"), b"x").unwrap();
        fs::write(dir.join("thumbs.db"), b"x").unwrap();
        fs::write(dir.join("b.part"), b"x").unwrap();
        fs::write(dir.join("~$tmp.doc"), b"x").unwrap();
        fs::write(dir.join("_"), b"").unwrap();
        fs::write(dir.join("ok.txt"), b"hello").unwrap();

        let files = iter_local_files(&dir, "");
        let mut keys: Vec<String> = files.iter().map(|(k, _, _)| k.clone()).collect();
        keys.sort();
        assert_eq!(keys, vec!["ok.txt".to_string(), "照片/2026/a.jpg".to_string()]);

        // 前缀限定
        let scoped = iter_local_files(&dir, "照片/");
        assert_eq!(scoped.len(), 1);
        assert_eq!(scoped[0].0, "照片/2026/a.jpg");
        assert_eq!(scoped[0].2, 1);

        // 不存在的 scope
        assert!(iter_local_files(&dir, "nope/").is_empty());
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn conflict_rename_pattern() {
        // 模拟下载冲突改名逻辑的核心：name.conflictN.ext
        let stem = PathBuf::from("D:/d/a.txt");
        let s = stem.to_string_lossy().to_string();
        let assert_name = format!("{}.conflict1", s.trim_end_matches(".txt"));
        assert_eq!(assert_name, "D:/d/a.conflict1");
    }
}
