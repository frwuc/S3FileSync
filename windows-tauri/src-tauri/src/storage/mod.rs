//! 存储后端统一抽象：七牛 Kodo 与 S3 协议兼容存储共用同一套操作面，
//! 同步引擎 / 浏览页 / 设置页只面对 [`Backend`]，不感知具体协议差异。

pub mod s3;

use std::path::{Path, PathBuf};

use crate::config::Config;
use crate::qiniu::client::{Kodo, ListItem};

use s3::S3Client;

pub enum Backend {
    Kodo(Kodo),
    S3(S3Client),
}

impl Backend {
    pub fn new(cfg: &Config) -> Backend {
        if cfg.is_s3() {
            Backend::S3(S3Client::new(cfg))
        } else {
            Backend::Kodo(Kodo::new(
                &cfg.ak,
                &cfg.sk,
                &cfg.bucket,
                &cfg.region,
                &cfg.download_domain,
                &cfg.proxy_mode,
            ))
        }
    }

    /// 浏览页分页列举（无 delimiter，递归）。
    pub fn list_page(
        &mut self,
        prefix: &str,
        marker: Option<&str>,
        limit: u32,
    ) -> Result<(Vec<ListItem>, Option<String>), String> {
        match self {
            Backend::Kodo(c) => c.list_page(prefix, marker, limit),
            Backend::S3(c) => c.list_page(prefix, marker, limit),
        }
    }

    /// 同步引擎用：递归列出前缀下全部对象。
    pub fn list_all(&mut self, prefix: &str) -> Result<Vec<ListItem>, String> {
        match self {
            Backend::Kodo(c) => c.list_all(prefix),
            Backend::S3(c) => c.list_all(prefix),
        }
    }

    /// 目录浏览用：列出某前缀下的一级子目录（完整前缀，以 / 结尾）。
    pub fn list_dirs(&mut self, prefix: &str) -> Result<Vec<String>, String> {
        match self {
            Backend::Kodo(c) => c.list_dirs(prefix),
            Backend::S3(c) => c.list_dirs(prefix),
        }
    }

    pub fn delete(&mut self, key: &str) -> Result<(), String> {
        match self {
            Backend::Kodo(c) => c.delete(key),
            Backend::S3(c) => c.delete(key),
        }
    }

    /// 上传文件，返回云端内容标识（七牛 etag / S3 ETag）。
    pub fn upload_file(&mut self, local_path: &Path, key: &str) -> Result<String, String> {
        match self {
            Backend::Kodo(c) => c.upload_file(local_path, key),
            Backend::S3(c) => c.upload_file(local_path, key),
        }
    }

    pub fn download(&mut self, key: &str, dest: &Path) -> Result<PathBuf, String> {
        match self {
            Backend::Kodo(c) => c.download(key, dest),
            Backend::S3(c) => c.download(key, dest),
        }
    }

    /// 探测对象可得性，返回 HTTP 状态码。
    pub fn probe_status(&mut self, key: &str) -> Result<u16, String> {
        match self {
            Backend::Kodo(c) => c.probe_status(key),
            Backend::S3(c) => c.probe_status(key),
        }
    }

    /// 本地文件内容哈希（与远端记录可比的标识）。
    /// 七牛 = qiniu etag（分块 SHA-1）；S3 = MD5（与单请求上传 ETag 可比）。
    pub fn local_hash(&self, path: &Path) -> Result<String, String> {
        match self {
            Backend::Kodo(_) => crate::qiniu::etag::etag_file(path).map_err(|e| e.to_string()),
            Backend::S3(c) => c.local_hash(path),
        }
    }

    /// 远端记录与本地内容是否一致（S3 分块 ETag 退化为大小比对）。
    pub fn content_matches(&self, remote_hash: &str, local_hash: &str, remote_size: i64, local_size: u64) -> bool {
        match self {
            Backend::Kodo(_) => !remote_hash.is_empty() && remote_hash == local_hash,
            Backend::S3(_) => s3::content_matches(remote_hash, local_hash, remote_size, local_size),
        }
    }

    /// 是否直连（忽略系统代理）——测试信息展示用。
    pub fn is_direct(&self) -> bool {
        match self {
            Backend::Kodo(c) => c.direct,
            Backend::S3(c) => c.direct,
        }
    }

    /// 当前请求端点（测试信息展示用）。
    pub fn endpoint_display(&self) -> String {
        match self {
            Backend::Kodo(c) => c.up_host_display(),
            Backend::S3(c) => c.endpoint_display(),
        }
    }

    /// 七牛区域自动纠正结果。
    pub fn detected_region(&self) -> Option<String> {
        match self {
            Backend::Kodo(c) => c.detected_region.clone(),
            Backend::S3(_) => None,
        }
    }
}
