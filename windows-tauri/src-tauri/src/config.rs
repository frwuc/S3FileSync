//! 配置读写：`%APPDATA%\S3FileSync\config.json`。
//!
//! 与 Python 桌面端（windows/）共用同一份配置与状态文件，
//! 两个客户端可互相替代、无缝切换。`S3FILESYNC_HOME` 可覆盖数据目录（测试用）。

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

pub const APP_NAME: &str = "S3FileSync";
pub const APP_VERSION: &str = env!("CARGO_PKG_VERSION");
pub const BACKEND_NOTE: &str = "支持后端：七牛云 Kodo 与 S3 协议兼容存储（AWS S3 / MinIO 等）";

/// 存储服务商
pub const PROVIDERS: [(&str, &str); 2] = [
    ("qiniu", "七牛云 Kodo"),
    ("s3", "S3 兼容存储（AWS / MinIO 等）"),
];

pub fn provider_label(p: &str) -> &'static str {
    PROVIDERS
        .iter()
        .find(|(k, _)| *k == p)
        .map(|(_, v)| *v)
        .unwrap_or("七牛云 Kodo")
}

pub fn valid_provider(p: &str) -> bool {
    PROVIDERS.iter().any(|(k, _)| *k == p)
}

pub const SYNC_MODES: [(&str, &str); 3] = [
    ("download", "仅下载（云端 → 本地）"),
    ("upload", "仅上传（本地 → 云端）"),
    ("both", "双向同步（上传 + 下载）"),
];

#[allow(dead_code)] // 前端 JS 侧有同表；保留供 CLI / 未来扩展使用
pub const REGIONS: [(&str, &str); 6] = [
    ("z0", "华东"),
    ("cn-east-2", "华东-浙江2"),
    ("z1", "华北"),
    ("z2", "华南"),
    ("na0", "北美"),
    ("as0", "东南亚"),
];

/// 各区域上传域名兜底映射（UC 查询失败时用）
pub fn up_host_for_region(region: &str) -> &'static str {
    match region {
        "z0" => "https://up-z0.qiniup.com",
        "cn-east-2" => "https://up-cn-east-2.qiniup.com",
        "z1" => "https://up-z1.qiniup.com",
        "z2" => "https://up-z2.qiniup.com",
        "na0" => "https://up-na0.qiniup.com",
        "as0" => "https://up-as0.qiniup.com",
        _ => "https://up.qiniup.com",
    }
}

pub fn sync_mode_label(mode: &str) -> &'static str {
    SYNC_MODES
        .iter()
        .find(|(k, _)| *k == mode)
        .map(|(_, v)| *v)
        .unwrap_or("仅下载（云端 → 本地）")
}

pub fn valid_sync_mode(mode: &str) -> bool {
    SYNC_MODES.iter().any(|(k, _)| *k == mode)
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default)]
pub struct Config {
    /// 存储服务商：qiniu（默认，兼容旧配置）/ s3
    pub provider: String,
    pub ak: String,
    pub sk: String,
    pub bucket: String,
    /// z0 / cn-east-2 / z1 / z2 / na0 / as0（七牛区域）
    pub region: String,
    /// 桶绑定域名；留空自动获取（七牛）
    pub download_domain: String,
    /// S3 兼容存储：服务端点，如 https://s3.us-east-1.amazonaws.com 或 http://127.0.0.1:9000
    pub s3_endpoint: String,
    /// S3 兼容存储：区域名
    pub s3_region: String,
    /// S3 兼容存储：是否强制路径寻址（bucket 放路径而非子域）
    pub s3_path_style: bool,
    /// 本地同步根目录（下载目标，也是上传来源）
    pub download_dir: String,
    /// 自动同步间隔（分钟）
    pub interval_minutes: i64,
    /// 同步的 key 前缀；留空 = 整个空间
    pub prefix: String,
    /// download / upload / both
    pub sync_mode: String,
    /// 自动上传时是否覆盖云端同名但内容不同的对象
    pub upload_overwrite: bool,
    /// auto / direct / system，规避失效的本地代理
    pub proxy_mode: String,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            provider: "qiniu".to_string(),
            ak: String::new(),
            sk: String::new(),
            bucket: String::new(),
            region: "z0".to_string(),
            download_domain: String::new(),
            s3_endpoint: String::new(),
            s3_region: "us-east-1".to_string(),
            s3_path_style: false,
            download_dir: String::new(),
            interval_minutes: 30,
            prefix: String::new(),
            sync_mode: "download".to_string(),
            upload_overwrite: false,
            proxy_mode: "auto".to_string(),
        }
    }
}

impl Config {
    pub fn is_s3(&self) -> bool {
        self.provider == "s3"
    }

    pub fn is_ready(&self) -> bool {
        let base = !self.ak.is_empty() && !self.sk.is_empty() && !self.bucket.is_empty() && !self.download_dir.is_empty();
        if self.is_s3() {
            base && !self.s3_endpoint.trim().is_empty()
        } else {
            base
        }
    }
}

/// 数据目录：`S3FILESYNC_HOME` 覆盖，默认 `%APPDATA%\S3FileSync`。
pub fn app_dir() -> PathBuf {
    if let Ok(home) = std::env::var("S3FILESYNC_HOME") {
        if !home.trim().is_empty() {
            return PathBuf::from(home.trim());
        }
    }
    let base = std::env::var("APPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(|_| dirs_home());
    base.join(APP_NAME)
}

fn dirs_home() -> PathBuf {
    std::env::var("USERPROFILE")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("."))
}

pub fn config_path() -> PathBuf {
    app_dir().join("config.json")
}

pub fn load() -> Config {
    let path = config_path();
    let mut cfg: Config = match fs::read_to_string(&path) {
        Ok(text) => serde_json::from_str(&text).unwrap_or_default(),
        Err(_) => Config::default(),
    };
    if !valid_sync_mode(&cfg.sync_mode) {
        cfg.sync_mode = "download".into();
    }
    if !valid_provider(&cfg.provider) {
        cfg.provider = "qiniu".into();
    }
    cfg
}

pub fn save(cfg: &Config) -> std::io::Result<()> {
    let dir = app_dir();
    fs::create_dir_all(&dir)?;
    let path = config_path();
    let tmp = dir.join("config.json.tmp");
    let text = serde_json::to_string_pretty(cfg).unwrap_or_default();
    fs::write(&tmp, text)?;
    fs::rename(&tmp, &path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_config_roundtrip() {
        let c = Config::default();
        let s = serde_json::to_string(&c).unwrap();
        let back: Config = serde_json::from_str(&s).unwrap();
        assert_eq!(back.region, "z0");
        assert_eq!(back.sync_mode, "download");
        assert_eq!(back.interval_minutes, 30);
        assert!(!back.is_ready());
    }

    #[test]
    fn python_config_is_compatible() {
        // Python 端写出的 config.json 片段
        let py = r#"{
            "ak": "A", "sk": "B", "bucket": "bkt", "region": "z1",
            "download_dir": "D:/x", "interval_minutes": 15,
            "prefix": "a/", "sync_mode": "both", "upload_overwrite": true,
            "proxy_mode": "direct"
        }"#;
        let c: Config = serde_json::from_str(py).unwrap();
        assert_eq!(c.region, "z1");
        assert_eq!(c.sync_mode, "both");
        assert!(c.upload_overwrite);
        assert!(c.is_ready());
    }

    #[test]
    fn unknown_fields_and_missing_defaults() {
        let py = r#"{"ak":"a","sk":"s","bucket":"b","download_dir":"d","extra_field":123}"#;
        let c: Config = serde_json::from_str(py).unwrap();
        assert_eq!(c.region, "z0");
        assert!(!c.upload_overwrite);
        // 旧配置无 provider 字段 → 默认七牛
        assert_eq!(c.provider, "qiniu");
    }

    #[test]
    fn s3_config_roundtrip() {
        let c = Config {
            provider: "s3".into(),
            ak: "AK".into(),
            sk: "SK".into(),
            bucket: "bkt".into(),
            s3_endpoint: "http://127.0.0.1:9000".into(),
            s3_region: "us-east-1".into(),
            s3_path_style: true,
            download_dir: "D:/x".into(),
            ..Default::default()
        };
        let s = serde_json::to_string(&c).unwrap();
        let back: Config = serde_json::from_str(&s).unwrap();
        assert!(back.is_s3());
        assert!(back.is_ready());
        assert_eq!(back.s3_endpoint, "http://127.0.0.1:9000");
        assert!(back.s3_path_style);
    }

    #[test]
    fn s3_ready_requires_endpoint() {
        let mut c = Config::default();
        c.provider = "s3".into();
        c.ak = "a".into();
        c.sk = "s".into();
        c.bucket = "b".into();
        c.download_dir = "d".into();
        assert!(!c.is_ready(), "S3 模式必须有 endpoint");
        c.s3_endpoint = "https://s3.amazonaws.com".into();
        assert!(c.is_ready());
    }
}
