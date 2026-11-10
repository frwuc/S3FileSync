//! 同步状态：`%APPDATA%\S3FileSync\sync_state.json`。
//!
//! 结构与 Python 端一致：`{key: {etag, fsize, local_path, synced_at}}`，
//! 两端共用同一份状态，切换客户端不会重复传输。

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::config::app_dir;

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct StateEntry {
    pub etag: String,
    pub fsize: i64,
    pub local_path: String,
    pub synced_at: i64,
}

pub type SyncState = HashMap<String, StateEntry>;

pub fn state_path() -> std::path::PathBuf {
    app_dir().join("sync_state.json")
}

pub fn load() -> SyncState {
    let path = state_path();
    match fs::read_to_string(&path) {
        Ok(text) => serde_json::from_str(&text).unwrap_or_default(),
        Err(_) => HashMap::new(),
    }
}

pub fn save(state: &SyncState) -> std::io::Result<()> {
    let dir = app_dir();
    fs::create_dir_all(&dir)?;
    let tmp = dir.join("sync_state.json.tmp");
    let text = serde_json::to_string(state).unwrap_or_default();
    fs::write(&tmp, text)?;
    fs::rename(&tmp, &dir.join("sync_state.json"))
}

pub fn record(state: &mut SyncState, key: &str, etag: &str, fsize: i64, local_path: &str) {
    let synced_at = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    state.insert(
        key.to_string(),
        StateEntry {
            etag: etag.to_string(),
            fsize,
            local_path: local_path.to_string(),
            synced_at,
        },
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn python_state_is_compatible() {
        let py = r#"{"a/b.txt":{"etag":"FrUHI","fsize":12,"local_path":"D:\\x\\a\\b.txt","synced_at":1700000000}}"#;
        let s: SyncState = serde_json::from_str(py).unwrap();
        assert_eq!(s.len(), 1);
        assert_eq!(s["a/b.txt"].etag, "FrUHI");
        assert_eq!(s["a/b.txt"].fsize, 12);
    }

    #[test]
    fn record_and_roundtrip() {
        let mut s: SyncState = HashMap::new();
        record(&mut s, "k", "etag1", 100, "D:/p/k");
        assert_eq!(s["k"].etag, "etag1");
        assert!(s["k"].synced_at > 1700000000);
        let text = serde_json::to_string(&s).unwrap();
        let back: SyncState = serde_json::from_str(&text).unwrap();
        assert_eq!(back["k"].local_path, "D:/p/k");
    }
}
