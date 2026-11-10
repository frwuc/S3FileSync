//! S3 协议兼容存储客户端（AWS S3 / MinIO 等）。
//!
//! - 签名：AWS Signature V4（HMAC-SHA256 链），上传用 UNSIGNED-PAYLOAD
//! - 列举：ListObjectsV2（支持 prefix / delimiter / continuation-token 分页）
//! - 上传：单请求 PUT（流式，Content-Length 显式声明，文件不整体进内存）
//! - 下载：GET 流式写 .part 再改名；删除：DELETE
//! - 寻址：AWS 端点默认虚拟主机式（bucket.子域）；自定义端点默认路径式（/bucket/key），
//!   均可用配置强制切换；bucket 名不适合做子域名时自动退回路径式
//!
//! ETag 说明：S3 的 ETag 是 MD5（带引号）——单次上传的对象可与本地 MD5 精确比对；
//! 分块上传的对象 ETag 带 `-N` 后缀，不是内容哈希，此时退化为大小比对。

use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::Duration;

use hmac::{Hmac, Mac};
use md5::Digest;

use crate::config::Config;
use crate::qiniu::client::{err_text, limite_reader, ListItem};

use sha2::Sha256;

const READ_TIMEOUT: Duration = Duration::from_secs(30);
const UPLOAD_TIMEOUT: Duration = Duration::from_secs(300);

/// SHA-256("")，GET/DELETE/HEAD 的空载荷哈希
const EMPTY_SHA256: &str = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

pub type QErr = String;

// ---------------- SigV4 签名 ----------------

type HmacSha256 = Hmac<Sha256>;

fn hmac_sha256(key: &[u8], data: &[u8]) -> Vec<u8> {
    let mut mac = HmacSha256::new_from_slice(key).expect("hmac key");
    mac.update(data);
    mac.finalize().into_bytes().to_vec()
}

fn hex(b: &[u8]) -> String {
    let mut s = String::with_capacity(b.len() * 2);
    for x in b {
        s.push_str(&format!("{:02x}", x));
    }
    s
}

/// 路径段 percent-encode：仅保留 unreserved 与 '/'（用于 canonical URI 的 key 部分）。
fn uri_encode_path(s: &str) -> String {
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

/// 组件 percent-encode（不保留 '/'），用于 query 键值。
fn uri_encode_component(s: &str) -> String {
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

/// SigV4：给定请求要素，返回 (canonical_query, Authorization 头)。
/// query_pairs 无需预先排序（内部排序）；host 为 Host 头原值（非默认端口含端口）。
pub(crate) fn sign_v4(
    ak: &str,
    sk: &str,
    region: &str,
    method: &str,
    host: &str,
    canonical_uri: &str,
    query_pairs: &[(&str, &str)],
    payload_sha: &str,
    amz_date: &str, // 20130524T000000Z
) -> (String, String) {
    // canonical query：按键值排序（先键后值）
    let mut pairs: Vec<(String, String)> = query_pairs
        .iter()
        .map(|(k, v)| (uri_encode_component(k), uri_encode_component(v)))
        .collect();
    pairs.sort();
    let canonical_query = pairs
        .iter()
        .map(|(k, v)| format!("{}={}", k, v))
        .collect::<Vec<_>>()
        .join("&");

    let canonical_headers = format!(
        "host:{}\nx-amz-content-sha256:{}\nx-amz-date:{}\n",
        host, payload_sha, amz_date
    );
    let signed_headers = "host;x-amz-content-sha256;x-amz-date";

    let canonical_request = format!(
        "{}\n{}\n{}\n{}\n{}\n{}",
        method, canonical_uri, canonical_query, canonical_headers, signed_headers, payload_sha
    );

    let date_short = &amz_date[..8];
    let scope = format!("{}/{}/s3/aws4_request", date_short, region);
    let string_to_sign = format!(
        "AWS4-HMAC-SHA256\n{}\n{}\n{}",
        amz_date,
        scope,
        hex(&sha2::Sha256::digest(canonical_request.as_bytes()))
    );

    // 签名密钥链：HMAC("AWS4"+sk, date) → region → service → aws4_request
    let k_date = hmac_sha256(format!("AWS4{}", sk).as_bytes(), date_short.as_bytes());
    let k_region = hmac_sha256(&k_date, region.as_bytes());
    let k_service = hmac_sha256(&k_region, b"s3");
    let k_signing = hmac_sha256(&k_service, b"aws4_request");
    let signature = hex(&hmac_sha256(&k_signing, string_to_sign.as_bytes()));

    let authorization = format!(
        "AWS4-HMAC-SHA256 Credential={}/{}, SignedHeaders={}, Signature={}",
        ak, scope, signed_headers, signature
    );
    (canonical_query, authorization)
}

// ---------------- XML 解析（ListObjectsV2 等固定结构） ----------------

/// XML 实体解码：&amp; &lt; &gt; &quot; &apos; 以及 &#NN; &#xNN;
pub(crate) fn xml_decode(s: &str) -> String {
    if !s.contains('&') {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(i) = rest.find('&') {
        out.push_str(&rest[..i]);
        rest = &rest[i..];
        let end = rest.find(';');
        let replacement: Option<char> = match end {
            Some(e) => {
                let ent = &rest[1..e]; // '&' 与 ';' 之间
                match ent {
                    "amp" => Some('&'),
                    "lt" => Some('<'),
                    "gt" => Some('>'),
                    "quot" => Some('"'),
                    "apos" => Some('\''),
                    _ => {
                        let code = ent
                            .strip_prefix("#x")
                            .and_then(|h| u32::from_str_radix(h, 16).ok())
                            .or_else(|| ent.strip_prefix('#').and_then(|d| d.parse::<u32>().ok()));
                        code.and_then(char::from_u32)
                    }
                }
            }
            None => None,
        };
        match (end, replacement) {
            (Some(e), Some(c)) => {
                out.push(c);
                rest = &rest[e + 1..];
            }
            _ => {
                // 非法/未闭合实体：原样输出 '&'
                out.push('&');
                rest = &rest[1..];
            }
        }
    }
    out.push_str(rest);
    out
}

/// 取所有 `<tag>…</tag>` 块的内部文本（标签不嵌套同名，S3 响应保证）。
fn xml_blocks(xml: &str, tag: &str) -> Vec<String> {
    let open = format!("<{}>", tag);
    let close = format!("</{}>", tag);
    let mut out = Vec::new();
    let mut rest = xml;
    while let Some(i) = rest.find(&open) {
        let after = &rest[i + open.len()..];
        if let Some(j) = after.find(&close) {
            out.push(xml_decode(&after[..j]));
            rest = &after[j + close.len()..];
        } else {
            break;
        }
    }
    out
}

/// 取第一个 `<tag>…</tag>` 的内部文本。
fn xml_first(xml: &str, tag: &str) -> Option<String> {
    xml_blocks(xml, tag).into_iter().next()
}

/// LastModified（ISO8601）→ qiniu 风格 putTime（100ns 单位）。
fn parse_time(s: &str) -> i64 {
    chrono::DateTime::parse_from_rfc3339(s)
        .map(|t| t.timestamp() * 10_000_000 + (t.timestamp_subsec_nanos() / 100) as i64)
        .unwrap_or(0)
}

#[derive(Default, Debug)]
struct ListResult {
    items: Vec<ListItem>,
    prefixes: Vec<String>,
    next_token: Option<String>,
}

fn parse_list(xml: &str) -> Result<ListResult, String> {
    let truncated = xml_first(xml, "IsTruncated")
        .map(|v| v.eq_ignore_ascii_case("true"))
        .unwrap_or(false);
    let next_token = xml_first(xml, "NextContinuationToken").filter(|t| !t.is_empty());
    let next_token = if truncated {
        // 有些实现用 NextMarker
        next_token.or_else(|| xml_first(xml, "NextMarker").filter(|t| !t.is_empty()))
    } else {
        None
    };

    let mut items = Vec::new();
    // 按块切出 <Contents>，再在块内取字段（避免跨对象错配）
    let open = "<Contents>";
    let close = "</Contents>";
    let mut rest = xml;
    while let Some(i) = rest.find(open) {
        let after = &rest[i + open.len()..];
        let Some(j) = after.find(close) else { break };
        let block = &after[..j];
        rest = &after[j + close.len()..];
        let Some(key) = xml_first(block, "Key") else { continue };
        if key.is_empty() {
            continue;
        }
        let etag = xml_first(block, "ETag")
            .map(|e| e.trim().trim_matches('"').to_string())
            .unwrap_or_default();
        let fsize = xml_first(block, "Size")
            .and_then(|s| s.parse::<i64>().ok())
            .unwrap_or(0);
        let put_time = xml_first(block, "LastModified").map(|t| parse_time(&t)).unwrap_or(0);
        items.push(ListItem {
            key,
            hash: etag,
            fsize,
            put_time,
        });
    }

    let mut prefixes = xml_blocks(xml, "CommonPrefixes")
        .into_iter()
        .flat_map(|p| xml_blocks(&p, "Prefix"))
        .filter(|p| !p.is_empty())
        .collect::<Vec<_>>();
    prefixes.sort();
    prefixes.dedup();

    Ok(ListResult {
        items,
        prefixes,
        next_token,
    })
}

// ---------------- 客户端 ----------------

pub struct S3Client {
    pub ak: String,
    pub sk: String,
    pub bucket: String,
    pub region: String,
    /// 初始化错误（endpoint 非法等）：所有操作直接返回该错误
    init_err: Option<String>,
    scheme: String,
    host: String,
    port: Option<u16>,
    path_style: bool,
    /// 是否直连（忽略系统代理）
    pub direct: bool,
    agent: ureq::Agent,
}

/// 解析端点字符串 → (scheme, host, port)。无 scheme 默认 https。
fn parse_endpoint(endpoint: &str) -> Result<(String, String, Option<u16>), String> {
    let s = endpoint.trim();
    if s.is_empty() {
        return Err("S3 服务端点为空".into());
    }
    let (scheme, rest) = if let Some(r) = s.strip_prefix("https://") {
        ("https", r)
    } else if let Some(r) = s.strip_prefix("http://") {
        ("http", r)
    } else {
        ("https", s)
    };
    // 去掉路径部分
    let hostport = rest.split('/').next().unwrap_or(rest);
    let hostport = hostport.trim();
    if hostport.is_empty() {
        return Err(format!("端点里的主机名为空: {}", endpoint));
    }
    let (host, port) = match hostport.rsplit_once(':') {
        Some((h, p)) if p.chars().all(|c| c.is_ascii_digit()) && !p.is_empty() => {
            (h.to_string(), p.parse::<u16>().ok())
        }
        _ => (hostport.to_string(), None),
    };
    let host = host.to_lowercase();
    if host.is_empty()
        || host.len() > 253
        || host.contains("..")
        || !host.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-')
    {
        return Err(format!("端点主机名不合法: {}", host));
    }
    let port = match port {
        Some(p) if p > 0 => Some(p),
        _ => None,
    };
    Ok((scheme.to_string(), host, port))
}

/// bucket 名可安全用作子域名（虚拟主机式寻址的前提）。
fn bucket_dns_safe(bucket: &str) -> bool {
    (3..=63).contains(&bucket.len())
        && bucket
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
        && !bucket.starts_with('-')
        && !bucket.ends_with('-')
        && !bucket.contains("..")
}

impl S3Client {
    pub fn new(cfg: &Config) -> Self {
        let mode = match cfg.proxy_mode.as_str() {
            "direct" | "system" => cfg.proxy_mode.clone(),
            _ => "auto".to_string(),
        };
        let (scheme, host, port) = match parse_endpoint(&cfg.s3_endpoint) {
            Ok(v) => v,
            Err(_) => ("".into(), "".into(), None),
        };
        let init_err = if host.is_empty() {
            Some(format!("S3 服务端点无效: {}", cfg.s3_endpoint))
        } else {
            None
        };
        let direct = if mode == "direct" {
            true
        } else if mode == "system" {
            false
        } else if host.is_empty() {
            true
        } else {
            let probe_url = format!("{}://{}", scheme, host);
            crate::qiniu::client::probe_host_direct(&probe_url)
        };
        let agent = crate::qiniu::client::build_agent(direct);
        // AWS 官方端点默认虚拟主机式；自定义端点（MinIO 等）默认路径式
        let is_aws = host.ends_with(".amazonaws.com") || host.ends_with(".amazonaws.com.cn");
        let path_style = cfg.s3_path_style
            || !is_aws
            || !bucket_dns_safe(&cfg.bucket);
        S3Client {
            ak: cfg.ak.clone(),
            sk: cfg.sk.clone(),
            bucket: cfg.bucket.clone(),
            region: if cfg.s3_region.trim().is_empty() {
                "us-east-1".to_string()
            } else {
                cfg.s3_region.trim().to_string()
            },
            init_err,
            scheme,
            host,
            port,
            path_style,
            direct,
            agent,
        }
    }

    fn check(&self) -> Result<(), QErr> {
        match &self.init_err {
            Some(e) => Err(e.clone()),
            None => Ok(()),
        }
    }

    /// Host 头（虚拟主机式带 bucket 子域；非默认端口带端口）。
    fn request_host(&self) -> String {
        let use_vh = !self.path_style;
        let base = if use_vh {
            format!("{}.{}", self.bucket, self.host)
        } else {
            self.host.clone()
        };
        match self.port {
            Some(p) if !((self.scheme == "https" && p == 443) || (self.scheme == "http" && p == 80)) => {
                format!("{}:{}", base, p)
            }
            _ => base,
        }
    }

    /// 对象 key 的 canonical URI（含 bucket 段与否取决于寻址方式）。
    fn key_uri(&self, key: &str) -> String {
        if self.path_style {
            if key.is_empty() {
                format!("/{}", self.bucket)
            } else {
                format!("/{}/{}", self.bucket, uri_encode_path(key))
            }
        } else if key.is_empty() {
            "/".to_string()
        } else {
            format!("/{}", uri_encode_path(key))
        }
    }

    /// 签名并组装请求要素：返回 (完整 URL, 需设置的请求头列表)。
    fn signed_request(
        &self,
        method: &str,
        canonical_uri: &str,
        query_pairs: &[(&str, &str)],
        payload_sha: &str,
    ) -> (String, Vec<(String, String)>) {
        let host_hdr = self.request_host();
        let amz_date = chrono::Utc::now().format("%Y%m%dT%H%M%SZ").to_string();
        let (query, auth) = sign_v4(
            &self.ak,
            &self.sk,
            &self.region,
            method,
            &host_hdr,
            canonical_uri,
            query_pairs,
            payload_sha,
            &amz_date,
        );
        let mut url = format!("{}://{}{}", self.scheme, host_hdr, canonical_uri);
        if !query.is_empty() {
            url.push('?');
            url.push_str(&query);
        }
        let headers = vec![
            ("Authorization".to_string(), auth),
            ("x-amz-date".to_string(), amz_date),
            ("x-amz-content-sha256".to_string(), payload_sha.to_string()),
        ];
        (url, headers)
    }

    fn call_simple(
        &self,
        method: &str,
        url: &str,
        headers: &[(String, String)],
    ) -> Result<ureq::Response, QErr> {
        let req = self.agent.request(method, url);
        let req = headers
            .iter()
            .fold(req, |r, (k, v)| r.set(k, v));
        match req.timeout(READ_TIMEOUT).call() {
            Ok(resp) => Ok(resp),
            Err(e) => Err(format!("HTTP {}: {}", crate::qiniu::client::status_of(&e), err_text(e))),
        }
    }

    fn read_body(resp: ureq::Response) -> Result<String, QErr> {
        let mut reader = limite_reader(resp.into_reader());
        let mut out = String::new();
        reader
            .read_to_string(&mut out)
            .map_err(|e| format!("读取响应失败: {}", e))?;
        Ok(out)
    }

    // ---------------- 列举 ----------------

    /// ListObjectsV2。返回 (对象, 公共前缀(目录), 下一页 token)。
    fn list_objects(
        &self,
        prefix: &str,
        token: Option<&str>,
        limit: u32,
        delimiter: bool,
    ) -> Result<(Vec<ListItem>, Vec<String>, Option<String>), QErr> {
        self.check()?;
        let mut pairs: Vec<(&str, String)> = vec![
            ("list-type", "2".to_string()),
            ("max-keys", limit.to_string()),
        ];
        if delimiter {
            pairs.push(("delimiter", "/".to_string()));
        }
        if !prefix.is_empty() {
            pairs.push(("prefix", prefix.to_string()));
        }
        if let Some(t) = token {
            if !t.is_empty() {
                pairs.push(("continuation-token", t.to_string()));
            }
        }
        let pairs_ref: Vec<(&str, &str)> = pairs.iter().map(|(k, v)| (*k, v.as_str())).collect();
        let canonical_uri = if self.path_style {
            format!("/{}", self.bucket)
        } else {
            "/".to_string()
        };
        let (url, headers) = self.signed_request("GET", &canonical_uri, &pairs_ref, EMPTY_SHA256);
        let resp = self.call_simple("GET", &url, &headers)?;
        let body = Self::read_body(resp)?;
        let r = parse_list(&body).map_err(|e| format!("列举响应无法解析: {} / {}", e, truncate(&body, 120)))?;
        Ok((r.items, r.prefixes, r.next_token))
    }

    /// 浏览页/连接测试用：返回 (items, next_token)。
    pub fn list_page(
        &self,
        prefix: &str,
        token: Option<&str>,
        limit: u32,
    ) -> Result<(Vec<ListItem>, Option<String>), QErr> {
        let (items, _, next) = self.list_objects(prefix, token, limit, false)?;
        Ok((items, next))
    }

    /// 同步引擎用：递归列出前缀下全部对象。
    pub fn list_all(&self, prefix: &str) -> Result<Vec<ListItem>, QErr> {
        let mut all = Vec::new();
        let mut token: Option<String> = None;
        loop {
            let (page, _, next) = self.list_objects(prefix, token.as_deref(), 1000, false)?;
            all.extend(page);
            match next {
                Some(t) => token = Some(t),
                None => break,
            }
        }
        Ok(all)
    }

    /// 目录浏览用：列某前缀下的一级子目录（返回完整前缀，均以 / 结尾）。
    pub fn list_dirs(&self, prefix: &str) -> Result<Vec<String>, QErr> {
        self.check()?;
        let mut browse = prefix.trim().to_string();
        if !browse.is_empty() && !browse.ends_with('/') {
            browse.push('/');
        }
        let mut dirs = Vec::new();
        let mut token: Option<String> = None;
        loop {
            let (_, prefixes, next) = self.list_objects(&browse, token.as_deref(), 1000, true)?;
            dirs.extend(prefixes);
            match next {
                Some(t) => token = Some(t),
                None => break,
            }
        }
        dirs.sort();
        dirs.dedup();
        Ok(dirs)
    }

    // ---------------- 删除 ----------------

    pub fn delete(&self, key: &str) -> Result<(), QErr> {
        self.check()?;
        let (url, headers) = self.signed_request("DELETE", &self.key_uri(key), &[], EMPTY_SHA256);
        self.call_simple("DELETE", &url, &headers)?;
        Ok(())
    }

    // ---------------- 上传 ----------------

    /// 单请求 PUT 上传，返回云端 ETag（去引号）。
    pub fn upload_file(&self, local_path: &Path, key: &str) -> Result<String, QErr> {
        self.check()?;
        let meta = fs::metadata(local_path).map_err(|e| format!("读取 {}: {}", local_path.display(), e))?;
        let size = meta.len();
        let (url, headers) = self.signed_request("PUT", &self.key_uri(key), &[], "UNSIGNED-PAYLOAD");
        let file = fs::File::open(local_path).map_err(|e| format!("打开 {}: {}", local_path.display(), e))?;
        let mut req = self.agent.put(&url);
        for (k, v) in &headers {
            req = req.set(k, v);
        }
        let resp = req
            .set("Content-Length", &size.to_string())
            .timeout(UPLOAD_TIMEOUT)
            .send(file)
            .map_err(|e| {
                format!(
                    "上传 {} 失败: {}",
                    key,
                    truncate(&err_text(e), 200)
                )
            })?;
        let etag = resp
            .header("ETag")
            .map(|e| e.trim().trim_matches('"').to_string())
            .unwrap_or_default();
        Ok(etag)
    }

    // ---------------- 下载 ----------------

    /// 流式下载到 dest（.part + 改名，与七牛端一致）。
    pub fn download(&self, key: &str, dest: &Path) -> Result<PathBuf, QErr> {
        self.check()?;
        let (url, headers) = self.signed_request("GET", &self.key_uri(key), &[], EMPTY_SHA256);
        let mut req = self.agent.get(&url);
        for (k, v) in &headers {
            req = req.set(k, v);
        }
        let resp = req
            .timeout(Duration::from_secs(120))
            .call()
            .map_err(|e| format!("下载 {} 失败: {}", key, truncate(&err_text(e), 200)))?;
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

    /// HEAD 探测对象可得性，返回 HTTP 状态码。
    pub fn probe_status(&self, key: &str) -> Result<u16, QErr> {
        self.check()?;
        let (url, headers) = self.signed_request("HEAD", &self.key_uri(key), &[], EMPTY_SHA256);
        let resp = self.call_simple("HEAD", &url, &headers)?;
        Ok(resp.status())
    }

    /// 当前生效的请求端点（测试信息展示）。
    pub fn endpoint_display(&self) -> String {
        if self.init_err.is_some() {
            return "无效端点".to_string();
        }
        let style = if self.path_style { "路径式" } else { "虚拟主机式" };
        format!("{}://{}（{}）", self.scheme, self.request_host(), style)
    }

    /// 本地文件内容哈希（S3 = MD5 小写 hex，与单请求上传的 ETag 可比）。
    pub fn local_hash(&self, path: &Path) -> Result<String, QErr> {
        let mut f = fs::File::open(path).map_err(|e| format!("打开 {}: {}", path.display(), e))?;
        let mut h = md5::Md5::new();
        let mut buf = vec![0u8; 128 * 1024];
        loop {
            let n = f.read(&mut buf).map_err(|e| e.to_string())?;
            if n == 0 {
                break;
            }
            h.update(&buf[..n]);
        }
        Ok(hex(&h.finalize()))
    }
}

/// 远端记录与本地内容是否一致：
/// - ETag 是纯 MD5 hex → 与本地 MD5 精确比对
/// - ETag 带分块后缀（`-N`）或非十六进制 → 退化为大小比对
pub fn content_matches(remote_etag: &str, local_md5: &str, remote_size: i64, local_size: u64) -> bool {
    let clean = remote_etag.trim().trim_matches('"');
    if clean.len() == 32 && clean.bytes().all(|b| b.is_ascii_hexdigit()) {
        return clean.eq_ignore_ascii_case(local_md5);
    }
    remote_size >= 0 && remote_size as u64 == local_size
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
    fn uri_encoding_rules() {
        // 路径：保留 '/'
        assert_eq!(uri_encode_path("a/b c.txt"), "a/b%20c.txt");
        assert_eq!(uri_encode_path("照片/2026"), "%E7%85%A7%E7%89%87/2026");
        assert_eq!(uri_encode_path("AZaz09-_.~"), "AZaz09-_.~");
        // 组件：'/' 编码
        assert_eq!(uri_encode_component("照片/2026"), "%E7%85%A7%E7%89%87%2F2026");
    }

    #[test]
    fn sigv4_official_structure() {
        // AWS 文档风格的要素（自建向量，签名值与 Python 独立计算对齐，见 sigv4_golden）
        let (query, auth) = sign_v4(
            "AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            "us-east-1",
            "GET",
            "examplebucket.s3.amazonaws.com",
            "/photos/2006/January/sample.jpg",
            &[("list-type", "2"), ("prefix", "照片/")],
            EMPTY_SHA256,
            "20130524T000000Z",
        );
        // canonical query 必须排序且值严格编码
        assert_eq!(query, "list-type=2&prefix=%E7%85%A7%E7%89%87%2F");
        assert!(auth.starts_with("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"));
        assert!(auth.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date,"));
        assert!(auth.ends_with(&format!("Signature={}", SIGV4_GOLDEN)));
    }

    /// Python 独立计算的期望签名（输入同 sigv4_official_structure）。
    const SIGV4_GOLDEN: &str = "203916d5ad2cb914e5b041860e1fb81c0a19d6fbb1267d96933aa22d3d58df24";

    #[test]
    fn xml_entities_and_blocks() {
        assert_eq!(xml_decode("a&amp;b"), "a&b");
        assert_eq!(xml_decode("x&lt;y&gt;z&quot;w&apos;"), "x<y>z\"w'");
        assert_eq!(xml_decode("&#66;&#x43;"), "BC");
        assert_eq!(xml_decode("a&b"), "a&b"); // 非法实体原样
        let blocks = xml_blocks("<r><K>a</K><K>b&amp;c</K></r>", "K");
        assert_eq!(blocks, vec!["a".to_string(), "b&c".to_string()]);
    }

    #[test]
    fn parse_list_v2() {
        let xml = r#"<?xml version="1.0" encoding="UTF-8"?>
<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
  <Name>bucket</Name>
  <Prefix>photos/</Prefix>
  <KeyCount>2</KeyCount>
  <MaxKeys>1000</MaxKeys>
  <IsTruncated>true</IsTruncated>
  <NextContinuationToken>1ueGcxLPRa1A/</NextContinuationToken>
  <Contents>
    <Key>photos/2006/1.jpg</Key>
    <LastModified>2009-10-12T17:50:30.000Z</LastModified>
    <ETag>&quot;fba9a20c30b0a823ba6f4dfbd67a11cb&quot;</ETag>
    <Size>4</Size>
  </Contents>
  <Contents>
    <Key>photos/你好&amp;世界.txt</Key>
    <LastModified>2026-01-02T03:04:05.678Z</LastModified>
    <ETag>"aaaa1111"</ETag>
    <Size>18</Size>
  </Contents>
  <CommonPrefixes><Prefix>photos/2026/</Prefix></CommonPrefixes>
  <CommonPrefixes><Prefix>photos/2027/</Prefix></CommonPrefixes>
</ListBucketResult>"#;
        let r = parse_list(xml).unwrap();
        assert_eq!(r.items.len(), 2);
        assert_eq!(r.items[0].key, "photos/2006/1.jpg");
        assert_eq!(r.items[0].hash, "fba9a20c30b0a823ba6f4dfbd67a11cb");
        assert_eq!(r.items[0].fsize, 4);
        // putTime 是 100ns 单位
        assert_eq!(r.items[0].put_time, 1255369830 * 10_000_000);
        assert_eq!(r.items[1].key, "photos/你好&世界.txt");
        assert_eq!(r.prefixes, vec!["photos/2026/".to_string(), "photos/2027/".to_string()]);
        assert_eq!(r.next_token.as_deref(), Some("1ueGcxLPRa1A/"));
    }

    #[test]
    fn parse_list_not_truncated() {
        let xml = "<ListBucketResult><IsTruncated>false</IsTruncated><NextContinuationToken>xyz</NextContinuationToken><Contents><Key>k</Key><Size>1</Size><ETag>\"d\"</ETag></Contents></ListBucketResult>";
        let r = parse_list(xml).unwrap();
        assert!(r.next_token.is_none(), "未截断时忽略 token");
        assert_eq!(r.items.len(), 1);
    }

    #[test]
    fn endpoint_parsing() {
        assert_eq!(
            parse_endpoint("https://s3.us-east-1.amazonaws.com").unwrap(),
            ("https".into(), "s3.us-east-1.amazonaws.com".into(), None)
        );
        assert_eq!(
            parse_endpoint("http://127.0.0.1:9000").unwrap(),
            ("http".into(), "127.0.0.1".into(), Some(9000))
        );
        // 无 scheme 默认 https；带路径取主机
        assert_eq!(
            parse_endpoint("s3.example.com/base").unwrap(),
            ("https".into(), "s3.example.com".into(), None)
        );
        assert!(parse_endpoint("").is_err());
        assert!(parse_endpoint("https://").is_err());
        assert!(parse_endpoint("https://a..b").is_err());
    }

    #[test]
    fn bucket_dns_safety() {
        assert!(bucket_dns_safe("my-bucket"));
        assert!(bucket_dns_safe("abc123"));
        assert!(!bucket_dns_safe("My_Bucket"), "大写与下划线不可做子域名");
        assert!(!bucket_dns_safe("ab"), "过短");
        assert!(!bucket_dns_safe("-lead"));
    }

    #[test]
    fn etag_match_policy() {
        let local_md5 = "d41d8cd98f00b204e9800998ecf8427e";
        // 纯 MD5：内容比对
        assert!(content_matches(local_md5, local_md5, 0, 0));
        assert!(!content_matches("fba9a20c30b0a823ba6f4dfbd67a11cb", local_md5, 0, 0));
        // 引号容忍
        assert!(content_matches("\"d41d8cd98f00b204e9800998ecf8427e\"", local_md5, 0, 0));
        // 分块 ETag：大小比对
        assert!(content_matches("3858f62230ac3c915f300c664312c11f-9", local_md5, 12, 12));
        assert!(!content_matches("3858f62230ac3c915f300c664312c11f-9", local_md5, 12, 13));
    }

    #[test]
    fn local_hash_is_md5() {
        let dir = std::env::temp_dir().join("s3fs_md5_test");
        fs::create_dir_all(&dir).unwrap();
        let p = dir.join("f.bin");
        fs::write(&p, b"hello").unwrap();
        let c = S3Client {
            ak: String::new(),
            sk: String::new(),
            bucket: String::new(),
            region: String::new(),
            init_err: None,
            scheme: "https".into(),
            host: String::new(),
            port: None,
            path_style: true,
            direct: true,
            agent: crate::qiniu::client::build_agent(true),
        };
        assert_eq!(c.local_hash(&p).unwrap(), "5d41402abc4b2a76b9719d911017c592");
        // 空文件
        let p2 = dir.join("empty.bin");
        fs::write(&p2, b"").unwrap();
        assert_eq!(c.local_hash(&p2).unwrap(), "d41d8cd98f00b204e9800998ecf8427e");
        fs::remove_dir_all(&dir).ok();
    }
}
