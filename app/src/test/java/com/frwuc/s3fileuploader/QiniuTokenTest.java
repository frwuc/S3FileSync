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
    public void uploadTokenStructure() {
        String token = QiniuToken.uploadToken("ak", "sk", "bucket", null, 3600);
        String[] parts = token.split(":");
        assertEquals(3, parts.length);
        String policy = new String(Base64.getUrlDecoder().decode(parts[2]),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(true, policy.startsWith("{\"scope\":\"bucket\",\"deadline\":"));
    }
}
