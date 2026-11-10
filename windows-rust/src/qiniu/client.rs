//! 七牛 Kodo 操作封装（list / delete / 私有下载 / 表单上传），对齐 Python 端行为。
//!
//! 网络模式与 Python 端一致：
//! - `auto`   先探测直连七牛，能直连就忽略系统代理（默认）
//! - `direct` 始终直连
//! - `system` 沿用环境变量代理
//!
//! 上传端点先经 UC `/v4/query` 查空间真实区域（配置的 region 可能填错），
//! 上传收到 `incorrect region` 时再按报错提示切换重试。

use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde::Deserialize;

use super::auth;
use super::etag;
use crate::config::up_host_for_region;

pub const UC_HOST: &str = "uc.qbox.me";
const DEFAULT_RSF_HOST: &str = "rsf.qbox.me";
const DEFAULT_RS_HOST: &str = "rs.qbox.me";
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const READ_TIMEOUT: Duration = Duration::from_secs(30);
const UPLOAD_TIMEOUT: Duration = Duration::from_secs(180);
/// rsf/list 响应体上限（防异常响应吃内存）
const MAX_RESPONSE_BYTES: usize = 8 * 1024 * 1024;

pub type QErr = String;

#[derive(Debug, Clone)]
pub struct ListItem {
    pub key: String,
    pub hash: String,
    pub fsize: i64,
    /// 七牛 putTime，单位 100 纳秒
    pub put_time: i64,
}

#[derive(Deserialize, Debug, Default)]
struct UcQuery {
    #[serde(default)]
    hosts: Vec<UcHost>,
}

#[derive(Deserialize, Debug, Default)]
struct UcHost {
    #[serde(default)]
    rs: UcService,
    #[serde(default)]
    rsf: UcService,
    #[serde(default)]
    up: UcService,
}

#[derive(Deserialize, Debug, Default)]
struct UcService {
    #[serde(default)]
    domains: Vec<String>,
}

#[derive(Deserialize, Debug)]
struct ListResp {
    #[serde(default)]
    items: Vec<serde_json::Value>,
    #[serde(default)]
    marker: String,
}

pub struct Kodo {
    pub ak: String,
    pub sk: String,
    pub bucket: String,
    pub region: String,
    pub download_domain: String,
    /// 是否直连（忽略系统代理）
    pub direct: bool,
    agent: ureq::Agent,
    up_host: String,
    rs_host: String,
    rsf_host: String,
    hosts_resolved: bool,
    /// 上传时发现的真实区域（与配置不符时用于自动纠正）
    pub detected_region: Option<String>,
}

impl Kodo {
    pub fn new(ak: &str, sk: &str, bucket: &str, region: &str,
               download_domain: &str, proxy_mode: &str) -> Self {
        let mode = match proxy_mode {
            "direct" | "system" => proxy_mode.to_string(),
            _ => "auto".to_string(),
        };
        let direct = if mode == "direct" {
            true
        } else if mode == "system" {
            false
        } else {
            probe_direct()
        };
        let agent = build_agent(direct);
        Kodo {
            ak: ak.to_string(),
            sk: sk.to_string(),
            bucket: bucket.to_string(),
            region: region.to_string(),
            download_domain: download_domain.trim().trim_end_matches('/').to_string(),
            direct,
            agent,
            up_host: up_host_for_region(region).to_string(),
            rs_host: DEFAULT_RS_HOST.to_string(),
            rsf_host: DEFAULT_RSF_HOST.to_string(),
            hosts_resolved: false,
            detected_region: None,
        }
    }

    fn resolve_hosts(&mut self) {
        if self.hosts_resolved {
            return;
        }
        self.hosts_resolved = true;
        let query = format!("ak={}&bucket={}", enc(&self.ak), enc(&self.bucket));
        let url = format!("https://{}/v4/query?{}", UC_HOST, query);
        if let Ok(resp) = self.agent.get(&url).timeout(CONNECT_TIMEOUT).call() {
            if let Ok(q) = serde_json::from_reader::<_, UcQuery>(limite_reader(resp.into_reader())) {
                if let Some(h) = q.hosts.first() {
                    if let Some(host) = h.rs.domains.first() {
                        let host = host.trim().to_lowercase();
                        if is_trusted_host(&host) {
                            self.rs_host = host;
                        }
                    }
                    if let Some(host) = h.rsf.domains.first() {
                        let host = host.trim().to_lowercase();
                        if is_trusted_host(&host) {
                            self.rsf_host = host;
                        }
                    }
                    if let Some(host) = h.up.domains.first() {
                        let host = host.trim().to_lowercase();
                        if is_trusted_host(&host) {
                            self.up_host = format!("https://{}", host);
                        }
                    }
                }
            }
        }
    }

    /// 管理请求：GET，带 Qiniu 凭证，401 时回退 QBox（老账号兼容）。
    fn mgmt_get(&mut self, host: &str, path: &str, query: &str) -> Result<String, QErr> {
        let date = utc_now_stamp();
        let token = auth::qiniu_mac_token(&self.ak, &self.sk, "GET", path, query, host, "", &date);
        let url = if query.is_empty() {
            format!("https://{}{}", host, path)
        } else {
            format!("https://{}{}?{}", host, path, query)
        };
        if let Ok(resp) = self
            .agent
            .get(&url)
            .set("Authorization", &format!("Qiniu {}", token))
            .set("X-Qiniu-Date", &date)
            .timeout(READ_TIMEOUT)
            .call()
        {
            return read_body(resp);
        }
        // 回退 QBox
        let qbox = auth::qbox_token(&self.ak, &self.sk, path, query);
        match self
            .agent
            .get(&url)
            .set("Authorization", &format!("QBox {}", qbox))
            .timeout(READ_TIMEOUT)
            .call()
        {
            Ok(resp) => read_body(resp),
            Err(e) => Err(format!("HTTP {}: {}", status_of(&e), err_text(e))),
        }
    }

    fn mgmt_post(&mut self, host: &str, path: &str, query: &str) -> Result<String, QErr> {
        let date = utc_now_stamp();
        let token = auth::qiniu_mac_token(&self.ak, &self.sk, "POST", path, query, host, "", &date);
        let url = if query.is_empty() {
            format!("https://{}{}", host, path)
        } else {
            format!("https://{}{}?{}", host, path, query)
        };
        if let Ok(resp) = self
            .agent
            .post(&url)
            .set("Authorization", &format!("Qiniu {}", token))
            .set("X-Qiniu-Date", &date)
            .timeout(READ_TIMEOUT)
            .call()
        {
            return read_body(resp);
        }
        let qbox = auth::qbox_token(&self.ak, &self.sk, path, query);
        match self
            .agent
            .post(&url)
            .set("Authorization", &format!("QBox {}", qbox))
            .timeout(READ_TIMEOUT)
            .call()
        {
            Ok(resp) => read_body(resp),
            Err(e) => Err(format!("HTTP {}: {}", status_of(&e), err_text(e))),
        }
    }

    // ---------- 列举（同步引擎用，不带 delimiter，递归全部层级） ----------

    /// 返回 (items, next_marker)
    pub fn list_page(&mut self, prefix: &str, marker: Option<&str>, limit: u32)
                     -> Result<(Vec<ListItem>, Option<String>), QErr> {
        self.resolve_hosts();
        let mut params: Vec<(&str, String)> = vec![("bucket", self.bucket.clone())];
        if let Some(m) = marker {
            if !m.is_empty() {
                params.push(("marker", m.to_string()));
            }
        }
        params.push(("limit", limit.to_string()));
        if !prefix.is_empty() {
            params.push(("prefix", prefix.to_string()));
        }
        let query = join_query(&params);
        let body = self.mgmt_get(&self.rsf_host.clone(), "/list", &query)?;
        let resp: ListResp = serde_json::from_str(&body)
            .map_err(|e| format!("云端返回内容无法解析: {} / {}", e, truncate(&body, 120)))?;
        let mut items = Vec::with_capacity(resp.items.len());
        for v in resp.items {
            let key = v["key"].as_str().unwrap_or_default().to_string();
            if key.is_empty() {
                continue;
            }
            items.push(ListItem {
                key,
                hash: v["hash"].as_str().unwrap_or_default().to_string(),
                fsize: v["fsize"].as_i64().unwrap_or(0),
                put_time: v["putTime"].as_i64().unwrap_or(0),
            });
        }
        let next = if resp.marker.is_empty() { None } else { Some(resp.marker) };
        Ok((items, next))
    }

    pub fn list_all(&mut self, prefix: &str) -> Result<Vec<ListItem>, QErr> {
        let mut all = Vec::new();
        let mut marker: Option<String> = None;
        loop {
            let (page, next) = self.list_page(prefix, marker.as_deref(), 1000)?;
            all.extend(page);
            match next {
                Some(m) => marker = Some(m),
                None => break,
            }
        }
        Ok(all)
    }

    // ---------- 删除 ----------

    pub fn delete(&mut self, key: &str) -> Result<(), QErr> {
        self.resolve_hosts();
        let entry = b64_entry(&self.bucket, key);
        let path = format!("/delete/{}", entry);
        self.mgmt_post(&self.rs_host.clone(), &path, "")?;
        Ok(())
    }

    // ---------- 域名 / 私有下载 ----------

    pub fn domains(&mut self) -> Result<Vec<String>, QErr> {
        let query = format!("tbl={}", enc(&self.bucket));
        let body = self.mgmt_get(UC_HOST, "/v2/domains", &query)?;
        let v: serde_json::Value = serde_json::from_str(&body)
            .map_err(|e| format!("域名接口返回无法解析: {}", e))?;
        Ok(v
            .as_array()
            .map(|arr| {
                arr.iter()
                    .filter_map(|d| d.as_str().map(|s| s.to_string()))
                    .collect()
            })
            .unwrap_or_default())
    }

    pub fn resolve_domain(&mut self) -> Result<String, QErr> {
        if !self.download_domain.is_empty() {
            return Ok(self.download_domain.clone());
        }
        let ds = self.domains()?;
        if ds.is_empty() {
            return Err("未配置下载域名，且该桶未绑定任何域名（请到七牛控制台查看空间域名）".into());
        }
        self.download_domain = ds[0].clone();
        Ok(ds[0].clone())
    }

    /// 私有空间签名下载 URL（中文 key 按 UTF-8 percent-encode 后再签名）。
    pub fn private_url(&mut self, key: &str, expires: i64) -> Result<String, QErr> {
        let domain = self.resolve_domain()?;
        let mut d = domain;
        if !d.starts_with("http://") && !d.starts_with("https://") {
            d = format!("http://{}", d);
        }
        let base = format!("{}/{}", d.trim_end_matches('/'), quote_path(key));
        let deadline = auth::now_secs() + expires;
        let with_e = format!("{}?e={}", base, deadline);
        let token = auth::download_token(&self.ak, &self.sk, &with_e);
        Ok(format!("{}&token={}", with_e, token))
    }

    /// 流式下载到 dest（先写 .part 再改名，中断不留半个文件）。
    pub fn download(&mut self, key: &str, dest: &Path) -> Result<PathBuf, QErr> {
        let url = self.private_url(key, 3600)?;
        let resp = self
            .agent
            .get(&url)
            .timeout(Duration::from_secs(120))
            .call()
            .map_err(|e| format!("下载 {} 失败: {}", key, err_text(e)))?;
        if !(200..300).contains(&resp.status()) {
            return Err(format!("下载 {} 失败: HTTP {}", key, resp.status()));
        }
        if let Some(parent) = dest.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("无法创建目录 {}: {}", parent.display(), e))?;
        }
        let part = dest.with_extension(format!(
            "{}.part",
            dest.extension().and_then(|e| e.to_str()).unwrap_or("")
        ));
        let mut reader = resp.into_reader();
        let mut buf = vec![0u8; 64 * 1024];
        let mut out = fs::File::create(&part).map_err(|e| e.to_string())?;
        use std::io::Write;
        loop {
            let n = reader.read(&mut buf).map_err(|e| e.to_string())?;
            if n == 0 {
                break;
            }
            out.write_all(&buf[..n]).map_err(|e| e.to_string())?;
        }
        fs::rename(&part, dest).map_err(|e| {
            let _ = fs::remove_file(&part);
            e.to_string()
        })?;
        Ok(dest.to_path_buf())
    }

    /// 测试私有下载链接，返回 HTTP 状态码（不读文件体）。
    pub fn probe_status(&mut self, key: &str) -> Result<u16, QErr> {
        let url = self.private_url(key, 600)?;
        let resp = self
            .agent
            .get(&url)
            .timeout(Duration::from_secs(30))
            .call()
            .map_err(|e| format!("HTTP {}: {}", status_of(&e), err_text(e)))?;
        Ok(resp.status())
    }

    /// 当前生效的上传端点（自检展示用）。
    pub fn up_host_display(&self) -> String {
        self.up_host.clone()
    }

    // ---------- 表单上传 ----------

    /// 上传一个本地文件到指定 key，返回云端 hash。
    /// 传输层不做覆盖判断（是否覆盖由同步引擎决定）。
    pub fn upload_file(&mut self, local_path: &Path, key: &str) -> Result<String, QErr> {
        if !self.hosts_resolved {
            self.resolve_hosts();
        }
        let meta = fs::metadata(local_path).map_err(|e| format!("读取 {}: {}", local_path.display(), e))?;
        let size = meta.len();
        let filename = key.rsplit('/').next().unwrap_or("file");

        for attempt in 0..2 {
            let token = auth::upload_token(&self.ak, &self.sk, &self.bucket, key, 3600);
            let url = format!("{}/", self.up_host);
            let file = fs::File::open(local_path).map_err(|e| e.to_string())?;
            let body = MultipartBody::new(&token, key, filename, file, size);
            let total = body.total_len();
            let result = self
                .agent
                .post(&url)
                .set("Content-Type", &format!("multipart/form-data; boundary={}", body.boundary()))
                .set("Content-Length", &total.to_string())
                .timeout(UPLOAD_TIMEOUT)
                .send(body);
            match result {
                Ok(resp) => {
                    let body = read_body(resp)?;
                    let v: serde_json::Value = serde_json::from_str(&body)
                        .map_err(|e| format!("上传 {} 返回无法解析: {} / {}", key, e, truncate(&body, 160)))?;
                    if let Some(err) = v["error"].as_str() {
                        if resp_status_of_err(err) == 400 && err.contains("incorrect region") && attempt == 0 {
                            if let Some(host) = extract_up_host(err) {
                                self.up_host = host;
                                if let Some(reg) = region_of_up_host(&self.up_host) {
                                    if reg != self.region {
                                        self.detected_region = Some(reg);
                                    }
                                }
                                continue;
                            }
                        }
                        return Err(format!("上传 {} 失败: {}", key, err));
                    }
                    let hash = v["hash"].as_str().map(|s| s.to_string());
                    return Ok(match hash {
                        Some(h) if !h.is_empty() => h,
                        _ => etag::etag_file(local_path).unwrap_or_default(),
                    });
                }
                Err(e) => {
                    // ureq 把非 2xx 也归为 Err
                    let status = status_of(&e);
                    let text = err_text(e);
                    if status != 0 {
                        if status == 400 && text.contains("incorrect region") && attempt == 0 {
                            if let Some(host) = extract_up_host(&text) {
                                self.up_host = host;
                                if let Some(reg) = region_of_up_host(&self.up_host) {
                                    if reg != self.region {
                                        self.detected_region = Some(reg);
                                    }
                                }
                                continue;
                            }
                        }
                        return Err(format!("上传 {} 失败: HTTP {}: {}", key, status, truncate(&text, 160)));
                    }
                    return Err(format!("上传 {} 失败: {}", key, text));
                }
            }
        }
        Err(format!("上传 {} 失败: 区域自动切换后仍失败", key))
    }
}

// ---------------- multipart 流式请求体 ----------------

/// 手拼 multipart/form-data（ureq 无内建 multipart，且大文件不能整体进内存）。
struct MultipartBody {
    boundary_str: String,
    prefix: Vec<u8>,
    file: Option<std::fs::File>,
    file_remaining: u64,
    suffix: Vec<u8>,
    pos: usize,      // prefix 已发送字节数
    suffix_pos: usize,
}

impl MultipartBody {
    fn new(token: &str, key: &str, filename: &str, file: std::fs::File, size: u64) -> Self {
        let boundary_str = "----S3FileSyncBoundary7d1a2c3f".to_string();
        let mut prefix = Vec::new();
        prefix.extend_from_slice(format!("--{}\r\n", boundary_str).as_bytes());
        prefix.extend_from_slice(b"Content-Disposition: form-data; name=\"token\"\r\n\r\n");
        prefix.extend_from_slice(token.as_bytes());
        prefix.extend_from_slice(b"\r\n");
        prefix.extend_from_slice(format!("--{}\r\n", boundary_str).as_bytes());
        prefix.extend_from_slice(b"Content-Disposition: form-data; name=\"key\"\r\n\r\n");
        prefix.extend_from_slice(key.as_bytes());
        prefix.extend_from_slice(b"\r\n");
        prefix.extend_from_slice(format!("--{}\r\n", boundary_str).as_bytes());
        prefix.extend_from_slice(
            format!("Content-Disposition: form-data; name=\"file\"; filename=\"{}\"\r\n", filename).as_bytes(),
        );
        prefix.extend_from_slice(b"Content-Type: application/octet-stream\r\n\r\n");

        let mut suffix = Vec::new();
        suffix.extend_from_slice(b"\r\n");
        suffix.extend_from_slice(format!("--{}--\r\n", boundary_str).as_bytes());

        MultipartBody {
            boundary_str,
            prefix,
            file: Some(file),
            file_remaining: size,
            suffix,
            pos: 0,
            suffix_pos: 0,
        }
    }

    fn boundary(&self) -> &str {
        &self.boundary_str
    }

    fn total_len(&self) -> u64 {
        self.prefix.len() as u64 + self.file_remaining + self.suffix.len() as u64
    }
}

impl Read for MultipartBody {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let mut written = 0;
        while written < buf.len() {
            if self.pos < self.prefix.len() {
                let n = (buf.len() - written).min(self.prefix.len() - self.pos);
                buf[written..written + n].copy_from_slice(&self.prefix[self.pos..self.pos + n]);
                self.pos += n;
                written += n;
            } else if self.file_remaining > 0 {
                let want = (buf.len() - written).min(self.file_remaining as usize);
                let file = self.file.as_mut().expect("file part");
                let n = file.read(&mut buf[written..written + want])?;
                if n == 0 {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::UnexpectedEof,
                        "文件在发送途中变短",
                    ));
                }
                self.file_remaining -= n as u64;
                written += n;
            } else if self.suffix_pos < self.suffix.len() {
                let n = (buf.len() - written).min(self.suffix.len() - self.suffix_pos);
                buf[written..written + n].copy_from_slice(&self.suffix[self.suffix_pos..self.suffix_pos + n]);
                self.suffix_pos += n;
                written += n;
            } else {
                break;
            }
        }
        Ok(written)
    }
}

// ---------------- 工具函数 ----------------

fn build_agent(direct: bool) -> ureq::Agent {
    let mut builder = ureq::AgentBuilder::new()
        .timeout_connect(CONNECT_TIMEOUT)
        .timeout_read(READ_TIMEOUT);
    if !direct {
        // system / auto 探测失败：沿用环境变量代理
        for var in ["HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"] {
            if let Ok(p) = std::env::var(var) {
                let p = p.trim().to_string();
                if p.is_empty() {
                    continue;
                }
                let url = if p.contains("://") { p } else { format!("http://{}", p) };
                if let Ok(proxy) = ureq::Proxy::new(&url) {
                    builder = builder.proxy(proxy);
                }
                break;
            }
        }
    }
    builder.build()
}

fn probe_direct() -> bool {
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_millis(2500))
        .build();
    // 任何 HTTP 响应都视为连通
    matches!(agent.get("https://uc.qbox.me").call(), Ok(_)) || agent.get("https://uc.qbox.me").call().is_ok()
}

fn read_body(resp: ureq::Response) -> Result<String, QErr> {
    let mut reader = limite_reader(resp.into_reader());
    let mut out = String::new();
    reader
        .read_to_string(&mut out)
        .map_err(|e| format!("读取响应失败: {}", e))?;
    Ok(out)
}

struct Limited<R: Read> {
    inner: R,
    remaining: usize,
}

impl<R: Read> Read for Limited<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if self.remaining == 0 {
            return Ok(0);
        }
        let max = buf.len().min(self.remaining);
        let n = self.inner.read(&mut buf[..max])?;
        self.remaining -= n;
        Ok(n)
    }
}

fn limite_reader<R: Read>(r: R) -> Limited<R> {
    Limited {
        inner: r,
        remaining: MAX_RESPONSE_BYTES,
    }
}

/// 取错误的可读文本；ureq 2.x 里 into_reader 需要 Response 所有权，所以接收 e 本体。
fn err_text(e: ureq::Error) -> String {
    let mut text = e.to_string();
    if let ureq::Error::Status(_, resp) = e {
        let mut body = String::new();
        let _ = resp.into_reader().take(1024).read_to_string(&mut body);
        if !body.is_empty() {
            text = body;
        }
    }
    text
}

/// ureq 2.x 的 Error 没有 status() 方法，模式匹配取 HTTP 状态码（非状态错误返回 0）。
fn status_of(e: &ureq::Error) -> u16 {
    match e {
        ureq::Error::Status(code, _) => *code,
        _ => 0,
    }
}

fn resp_status_of_err(_err: &str) -> u16 {
    400
}

fn join_query(params: &[(&str, String)]) -> String {
    params
        .iter()
        .map(|(k, v)| format!("{}={}", k, enc(v)))
        .collect::<Vec<_>>()
        .join("&")
}

/// query 参数 URL 编码：空格用 %20（不用 +）。
pub fn enc(s: &str) -> String {
    let mut out = String::new();
    for b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*b as char)
            }
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

/// 路径编码（保留 '/'），与 python quote(key, safe='/') 一致。
fn quote_path(s: &str) -> String {
    let mut out = String::new();
    for b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' | b'/' => {
                out.push(*b as char)
            }
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

/// entry：b64url("bucket:key")
fn b64_entry(bucket: &str, key: &str) -> String {
    auth::urlsafe_b64(format!("{}:{}", bucket, key).as_bytes())
}

fn utc_now_stamp() -> String {
    chrono::Utc::now().format("%Y%m%dT%H%M%SZ").to_string()
}

/// 七牛官方域名白名单（防 UC 异常响应把管理凭证带去任意主机）。
fn is_trusted_host(host: &str) -> bool {
    let h = host.trim().to_lowercase();
    if h.is_empty() || h.len() > 253 || h.contains("..") {
        return false;
    }
    if !h.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-') {
        return false;
    }
    const SUFFIXES: [&str; 6] = [
        "qbox.me",
        "qiniup.com",
        "qiniuio.com",
        "qiniucn.com",
        "qiniu.com",
        "qiniuapi.com",
    ];
    SUFFIXES.iter().any(|s| h == *s || h.ends_with(&format!(".{}", s)))
}

/// 从 incorrect region 报错里提取正确上传域名（如 up-z2.qiniup.com）。
fn extract_up_host(err: &str) -> Option<String> {
    let lower = err.to_lowercase();
    let idx = lower.find("up-")?;
    let rest = &err[idx..];
    let end = rest
        .find(|c: char| !(c.is_ascii_alphanumeric() || c == '-' || c == '.'))
        .unwrap_or(rest.len());
    let host = &rest[..end];
    let pure_host = host.trim_end_matches('.');
    let pure_host = if pure_host.starts_with("https://") {
        pure_host.to_string()
    } else if pure_host.starts_with("http://") {
        pure_host.replacen("http://", "https://", 1)
    } else {
        format!("https://{}", pure_host)
    };
    // 只接受七牛官方域名，防止错误报文注入
    let bare = pure_host.trim_start_matches("https://").to_lowercase();
    if is_trusted_host(&bare) && bare.starts_with("up") {
        Some(pure_host)
    } else {
        None
    }
}

fn region_of_up_host(host: &str) -> Option<String> {
    let h = host.trim_start_matches("https://");
    let rest = h.strip_prefix("up-")?;
    let reg = rest.split('.').next()?;
    if reg.is_empty() {
        None
    } else {
        Some(reg.to_string())
    }
}

fn truncate(s: &str, n: usize) -> String {
    if s.chars().count() <= n {
        s.to_string()
    } else {
        s.chars().take(n).collect::<String>() + "..."
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn query_enc() {
        assert_eq!(enc("a b"), "a%20b");
        // query 值里的 / 也编码（与 python urlencode 一致）
        assert_eq!(enc("照片/2026"), "%E7%85%A7%E7%89%87%2F2026");
        assert_eq!(enc("AZaz09-_.~"), "AZaz09-_.~");
    }

    #[test]
    fn path_quote_keeps_slash() {
        assert_eq!(quote_path("a/b c.txt"), "a/b%20c.txt");
        assert_eq!(quote_path("照片/2026"), "%E7%85%A7%E7%89%87/2026");
    }

    #[test]
    fn entry_encoding() {
        assert_eq!(b64_entry("b", "k"), auth::urlsafe_b64(b"b:k"));
        assert_eq!(b64_entry("b", ""), auth::urlsafe_b64(b"b:"));
    }

    #[test]
    fn trusted_host_whitelist() {
        assert!(is_trusted_host("rsf.qbox.me"));
        assert!(is_trusted_host("UP-z2.qiniup.com"));
        assert!(!is_trusted_host("evilqbox.me"));
        assert!(!is_trusted_host("qbox.me.evil.com"));
        assert!(!is_trusted_host(""));
        assert!(!is_trusted_host("a b"));
    }

    #[test]
    fn up_host_extraction() {
        assert_eq!(
            extract_up_host("incorrect region, please use up-z2.qiniup.com to upload"),
            Some("https://up-z2.qiniup.com".into())
        );
        assert_eq!(
            extract_up_host("see https://upload.qbox.me"),
            None // 不以 up- 开头的不接受
        );
        assert_eq!(
            extract_up_host("use up-cn-east-2.qiniup.com."),
            Some("https://up-cn-east-2.qiniup.com".into())
        );
        // 注入防护
        assert_eq!(extract_up_host("use up-evil.com now"), None);
    }

    #[test]
    fn region_of_host() {
        assert_eq!(region_of_up_host("https://up-z2.qiniup.com"), Some("z2".into()));
        assert_eq!(
            region_of_up_host("https://up-cn-east-2.qiniup.com"),
            Some("cn-east-2".into())
        );
        assert_eq!(region_of_up_host("https://rs.qbox.me"), None);
    }

    #[test]
    fn multipart_body_length_matches_read() {
        let dir = std::env::temp_dir().join("s3fs_multipart_test");
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("data.bin");
        let content: Vec<u8> = (0..1000u32).map(|i| (i % 251) as u8).collect();
        std::fs::write(&p, &content).unwrap();
        let f = std::fs::File::open(&p).unwrap();
        let mut body = MultipartBody::new("TOKEN", "dir/file.bin", "file.bin", f, content.len() as u64);
        let total = body.total_len();
        let mut all = Vec::new();
        body.read_to_end(&mut all).unwrap();
        assert_eq!(all.len() as u64, total);
        assert!(all.starts_with(b"------S3FileSyncBoundary7d1a2c3f\r\nContent-Disposition"));
        // token / key 字段在场
        let s = String::from_utf8_lossy(&all);
        assert!(s.contains("name=\"token\"\r\n\r\nTOKEN\r\n"));
        assert!(s.contains("name=\"key\"\r\n\r\ndir/file.bin\r\n"));
        assert!(s.contains("filename=\"file.bin\""));
        // 文件内容完整嵌在中间
        let start = s.find("application/octet-stream\r\n\r\n").unwrap()
            + "application/octet-stream\r\n\r\n".len();
        assert_eq!(&all[start..start + content.len()], &content[..]);
        assert!(all.ends_with(b"\r\n------S3FileSyncBoundary7d1a2c3f--\r\n"));
        std::fs::remove_dir_all(&dir).ok();
    }
}
