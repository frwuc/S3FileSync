//! 七牛 etag（与 list/stat 返回的 hash 直接比对）。
//!
//! 算法（qiniu-python-sdk `qiniu.utils.etag_stream`）：
//! - 4MB 分块，每块 SHA-1
//! - 单块（含空文件）：`b64url(0x16 || sha1(data))`
//! - 多块：`b64url(0x96 || sha1(concat(每块 sha1)))`

use base64::engine::general_purpose::URL_SAFE as B64_URL;
use base64::Engine;
use sha1::{Digest, Sha1};
use std::io::Read;
use std::path::Path;

pub const BLOCK_SIZE: usize = 4 * 1024 * 1024;

#[cfg_attr(not(test), allow(dead_code))] // 单测黄金向量使用
pub fn etag_data(data: &[u8]) -> String {
    let blocks: Vec<Vec<u8>> = if data.is_empty() {
        vec![sha1(b"")]
    } else {
        data.chunks(BLOCK_SIZE).map(sha1).collect()
    };
    let digest: Vec<u8> = if blocks.len() == 1 {
        let mut out = vec![0x16];
        out.extend_from_slice(&blocks[0]);
        out
    } else {
        let joined: Vec<u8> = blocks.concat();
        let mut out = vec![0x96];
        out.extend_from_slice(&sha1(&joined));
        out
    };
    B64_URL.encode(digest)
}

pub fn etag_file(path: &Path) -> std::io::Result<String> {
    let mut f = std::fs::File::open(path)?;
    let mut blocks_sha: Vec<Vec<u8>> = Vec::new();
    let mut buf = vec![0u8; 256 * 1024];
    let mut block = Vec::with_capacity(BLOCK_SIZE);
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        block.extend_from_slice(&buf[..n]);
        if block.len() >= BLOCK_SIZE {
            blocks_sha.push(sha1(&block));
            block.clear();
        }
    }
    if !block.is_empty() {
        blocks_sha.push(sha1(&block));
    }
    if blocks_sha.is_empty() {
        blocks_sha.push(sha1(b""));
    }
    let digest: Vec<u8> = if blocks_sha.len() == 1 {
        let mut out = vec![0x16];
        out.extend_from_slice(&blocks_sha[0]);
        out
    } else {
        let joined: Vec<u8> = blocks_sha.concat();
        let mut out = vec![0x96];
        out.extend_from_slice(&sha1(&joined));
        out
    };
    Ok(B64_URL.encode(digest))
}

fn sha1(data: &[u8]) -> Vec<u8> {
    let mut h = Sha1::new();
    h.update(data);
    h.finalize().to_vec()
}

#[cfg(test)]
mod tests {
    use super::*;

    // 黄金向量：由 python qiniu.utils.etag_stream 生成后固化（两端必须逐字符一致）。

    #[test]
    fn golden_empty() {
        assert_eq!(etag_data(b""), "Fto5o-5ea0sNMlW_75VgGJCv2AcJ");
    }

    #[test]
    fn golden_hello() {
        assert_eq!(etag_data(b"hello"), "Fqr0xh3cxeii2r7eDztILNmuqUNN");
    }

    #[test]
    fn golden_multiblock() {
        // 恰好 4MB：仍是单块（0x16 前缀，b64 后以 'F' 开头）
        let one = vec![0xABu8; BLOCK_SIZE];
        assert_eq!(etag_data(&one).chars().take(8).collect::<String>(), "FqW6CNA1");
        // 4MB+1：双块（0x96 前缀，b64 后以 'l' 开头）
        let two = vec![0xABu8; BLOCK_SIZE + 1];
        assert_eq!(etag_data(&two).chars().take(8).collect::<String>(), "lh8GJlcJ");
        // 8MB：双块
        let eight = vec![0u8; BLOCK_SIZE * 2];
        assert_eq!(etag_data(&eight).chars().take(8).collect::<String>(), "lsCVE24-");
    }
}
