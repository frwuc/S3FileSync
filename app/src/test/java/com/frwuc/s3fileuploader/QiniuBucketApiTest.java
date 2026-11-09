package com.frwuc.s3fileuploader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 七牛列举接口的请求构造：参数必须按名称字典序、值做 URL 编码，
 * 且签名原文必须与真实 query 完全一致（否则服务端 401）。
 */
public class QiniuBucketApiTest {

    @Test
    public void queryParamsAreSortedAndEncoded() {
        String query = QiniuBucketApi.buildQuery("my-bucket", "daka files/2024", "/", "");
        assertEquals("bucket=my-bucket&delimiter=%2F&limit=1000&prefix=daka%20files%2F2024", query);
    }

    @Test
    public void queryIncludesMarkerWhenPaging() {
        String query = QiniuBucketApi.buildQuery("b", "daka/", "/", "abc/def=");
        assertEquals("bucket=b&delimiter=%2F&limit=1000&marker=abc%2Fdef%3D&prefix=daka%2F", query);
    }

    @Test
    public void querySkipsEmptyPrefixForRoot() {
        assertEquals("bucket=b&delimiter=%2F&limit=1000",
                QiniuBucketApi.buildQuery("b", "", "/", null));
    }

    @Test
    public void prefixAlwaysEndsWithSlash() {
        assertEquals("daka_files/", QiniuBucketApi.normalizePrefix("daka_files"));
        assertEquals("daka_files/", QiniuBucketApi.normalizePrefix("/daka_files/"));
        assertEquals("a/b/", QiniuBucketApi.normalizePrefix("a/b"));
        assertEquals("", QiniuBucketApi.normalizePrefix(""));
        assertEquals("", QiniuBucketApi.normalizePrefix(null));
    }

    @Test
    public void leafNameStripsPrefixAndTrailingSlash() {
        assertEquals("2024", QiniuBucketApi.leafName("daka_files/2024/", "daka_files/"));
        assertEquals("a.png", QiniuBucketApi.leafName("daka_files/a.png", "daka_files/"));
        assertEquals("root.png", QiniuBucketApi.leafName("root.png", ""));
    }

    @Test
    public void rsfHostAllowlistRejectsLookalikeAndMalformedHosts() {
        assertTrue(QiniuBucketApi.isTrustedRsfHost("rsf.qbox.me"));
        assertTrue(QiniuBucketApi.isTrustedRsfHost("RSF-Z0.QINIUIO.COM"));
        assertTrue(QiniuBucketApi.isTrustedRsfHost("rsf-cn-east-2.qiniucn.com"));
        assertFalse(QiniuBucketApi.isTrustedRsfHost("evilqbox.me"));
        assertFalse(QiniuBucketApi.isTrustedRsfHost("rsf.qbox.me.evil.example"));
        assertFalse(QiniuBucketApi.isTrustedRsfHost("https://rsf.qbox.me/list"));
        assertFalse(QiniuBucketApi.isTrustedRsfHost("user@rsf.qbox.me"));
        assertFalse(QiniuBucketApi.isTrustedRsfHost("rsf.qbox.me:443"));
        assertFalse(QiniuBucketApi.isTrustedRsfHost(""));
    }

    @Test
    public void signingStringEndsWithNewline() {
        assertEquals("/list?bucket=b\n", QiniuToken.signingString("/list", "bucket=b"));
        assertEquals("/list\n", QiniuToken.signingString("/list", null));
        assertEquals("/list\n", QiniuToken.signingString("/list", ""));
    }

    // -------------------------------------------------- v1.8.0 删除 / 预览

    @Test
    public void urlSafeBase64NoPaddingMatchesQiniuFormat() {
        // 与七牛官方 SDK 的 UrlSafeBase64.encodeToString(asUrlSafeStringNoPadding) 对齐：
        // 标准 Base64 对应位值一致，'+'/'/' 换成 '-_'，去掉 '='。
        // "abc" -> YWJj；"ab" -> YWI（= A2 -> 4 位去 1）；"a" -> YQ（A1 -> 4 位去 2）
        assertEquals("YWJj", QiniuBucketApi.urlSafeBase64NoPadding(
                new byte[]{'a', 'b', 'c'}));
        assertEquals("YWI", QiniuBucketApi.urlSafeBase64NoPadding(
                new byte[]{'a', 'b'}));
        assertEquals("YQ", QiniuBucketApi.urlSafeBase64NoPadding(
                new byte[]{'a'}));
    }

    @Test
    public void trimDomainStripsTrailingSlashes() {
        assertEquals("https://cdn.example.com", QiniuBucketApi.trimDomain("https://cdn.example.com/"));
        assertEquals("cdn.example.com", QiniuBucketApi.trimDomain("cdn.example.com///"));
        assertEquals("", QiniuBucketApi.trimDomain(null));
        assertEquals("", QiniuBucketApi.trimDomain("   "));
    }
}
