package com.frwuc.s3fileuploader;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Base64;

/**
 * 管理凭证（QBox token）与七牛官方算法的逐字节一致性校验。
 *
 * <p>期望值由官方算法的 Python 参考实现离线算出（HmacSHA1(SK, "path?query\n")
 * -> urlsafe base64 -> ak:sign:base64(ak:sign)），本地实现必须与其完全一致，
 * 否则 rsf.qbox.me 会返回 401 bad token。
 */
public class QiniuTokenTest {

    @Before
    public void setUp() {
        // android.util.Base64 在 JVM 单测里不可用，注入逐字节等价的 java.util.Base64
        QiniuToken.setUrlSafeBase64EncoderForTest(
                data -> Base64.getUrlEncoder().encodeToString(data));
    }

    @After
    public void tearDown() {
        QiniuToken.setUrlSafeBase64EncoderForTest(null);
    }

    @Test
    public void managementTokenMatchesOfficialAlgorithm() {
        String token = QiniuToken.managementToken("test_access_key", "test_secret_key",
                "/list", "bucket=mybucket&delimiter=%2F&limit=1000&prefix=abc%2Fdef%2F");
        // Python 参考实现（官方算法）计算结果：
        //   signingStr = "/list?bucket=mybucket&delimiter=%2F&limit=1000&prefix=abc%2Fdef%2F\n"
        //   sign       = urlsafe_b64(HmacSHA1("test_secret_key", signingStr))
        assertEquals(
                "test_access_key:SKCnS6j-fAeC7FVYMKg13vo8HCk="
                        + ":dGVzdF9hY2Nlc3Nfa2V5OlNLQ25TNmotZkFlQzdGVllNS2cxM3ZvOEhDaz0=",
                token);
    }

    @Test
    public void managementTokenWithoutQuery() {
        // 无 query 时签名原文只有 path + "\n"
        String token = QiniuToken.managementToken("ak", "sk", "/list", "");
        String[] parts = token.split(":");
        assertEquals(3, parts.length);
        assertEquals("ak", parts[0]);
        // sign 与 encodedToken 可互相验证：urlsafe_b64decode(encodedToken) == "ak:sign"
        byte[] decoded = Base64.getUrlDecoder().decode(parts[2]);
        assertEquals("ak:" + parts[1], new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    public void qiniuMacTokenMatchesOfficialAlgorithm() {
        // Python 参考实现（官方 QiniuMacAuth 算法）计算结果：
        //   data = "GET /list?bucket=mybucket&delimiter=%2F&limit=1000&prefix=abc%2Fdef%2F\n"
        //        + "Host: rsf.qbox.me"
        //        + "\nContent-Type: application/x-www-form-urlencoded"
        //        + "\nX-Qiniu-Date: 20261108T120000Z"
        //        + "\n\n"
        //   token = ak + ":" + urlsafe_b64(HmacSHA1(sk, data))   （只有两段）
        String token = QiniuToken.qiniuMacToken("test_access_key", "test_secret_key",
                "GET", "/list", "bucket=mybucket&delimiter=%2F&limit=1000&prefix=abc%2Fdef%2F",
                "rsf.qbox.me", "application/x-www-form-urlencoded", "20261108T120000Z");
        assertEquals("test_access_key:3nMlCeWO5jistkTcnjH1U_L2kQw=", token);
    }

    @Test
    public void qiniuMacTokenWithoutOptionalHeaders() {
        // 不带 Content-Type / X-Qiniu-Date 时：data = "GET /list?bucket=b\nHost: rsf.qbox.me\n\n"
        String token = QiniuToken.qiniuMacToken("ak", "sk", "GET", "/list", "bucket=b",
                "rsf.qbox.me", null, null);
        String sign = token.substring("ak:".length());
        byte[] expected = hmacSha1("sk",
                "GET /list?bucket=b\nHost: rsf.qbox.me\n\n");
        assertEquals(Base64.getUrlEncoder().encodeToString(expected), sign);
    }

    private static byte[] hmacSha1(String secretKey, String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA1"));
            return mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void qiniuMacTokenGetWithoutContentTypeMatchesOfficial() {
        // 真实列举请求是 GET 且不带 Content-Type（官方 SDK _get_with_qiniu_mac 只有 User-Agent 头）。
        // 曾因签名原文里写了 Content-Type 而请求未发送该头，服务端重算不一致报 401 bad token。
        // Python 参考实现（官方算法）计算结果：
        //   data = "GET /list?bucket=mybucket&delimiter=%2F&limit=1000&prefix=abc%2Fdef%2F\n"
        //        + "Host: rsf.qbox.me"
        //        + "\nX-Qiniu-Date: 20261108T120000Z"
        //        + "\n\n"
        String token = QiniuToken.qiniuMacToken("test_access_key", "test_secret_key",
                "GET", "/list", "bucket=mybucket&delimiter=%2F&limit=1000&prefix=abc%2Fdef%2F",
                "rsf.qbox.me", "", "20261108T120000Z");
        assertEquals("test_access_key:U1LWK5PRSq3xFj7gTfZ433WSOvw=", token);
    }

    @Test
    public void uploadTokenStructure() {
        String token = QiniuToken.uploadToken("ak", "sk", "bucket", null, 3600);
        String[] parts = token.split(":");
        assertEquals(3, parts.length);
        String policy = new String(Base64.getUrlDecoder().decode(parts[2]),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(true, policy.startsWith("{\"scope\":\"bucket\",\"deadline\":"));
    }
}
