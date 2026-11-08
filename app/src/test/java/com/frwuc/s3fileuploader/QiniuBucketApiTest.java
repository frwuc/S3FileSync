package com.frwuc.s3fileuploader;

import static org.junit.Assert.assertEquals;

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
    public void signingStringEndsWithNewline() {
        assertEquals("/list?bucket=b\n", QiniuToken.signingString("/list", "bucket=b"));
        assertEquals("/list\n", QiniuToken.signingString("/list", null));
        assertEquals("/list\n", QiniuToken.signingString("/list", ""));
    }
}
