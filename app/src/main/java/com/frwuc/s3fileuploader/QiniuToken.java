package com.frwuc.s3fileuploader;

import android.util.Base64;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 七牛上传凭证（upload token）本地签发工具。
 *
 * 背景：com.qiniu:qiniu-android-sdk（7.x / 8.x 所有版本）都不包含 Auth 类，
 * 官方建议上传凭证由服务端签发。本应用为个人工具，AK/SK 保存在客户端，
 * 因此按官方「上传凭证」算法在本地签发，效果等价于 Java SDK 的
 * com.qiniu.util.Auth.create(ak, sk).uploadToken(bucket, key, expires)：
 *
 * <pre>
 * policy         = {"scope":"bucket:key","deadline":秒级时间戳}
 * encodedPolicy  = urlSafeBase64(policy JSON)
 * sign           = HmacSHA1(secretKey, encodedPolicy)
 * token          = AK ":" urlSafeBase64(sign) ":" encodedPolicy
 * </pre>
 *
 * 算法参考：https://developer.qiniu.com/kodo/1208/upload-token
 */
public final class QiniuToken {

    private static final String HMAC_SHA1 = "HmacSHA1";

    private QiniuToken() {
    }

    /**
     * 生成七牛上传凭证。
     *
     * @param accessKey     七牛 AccessKey
     * @param secretKey     七牛 SecretKey
     * @param bucket        目标空间名
     * @param key           上传后保存的文件名；非空时 scope 为 bucket:key（指定 key 上传），
     *                      为空时 scope 为 bucket（服务端自动命名）
     * @param expiresSecond 凭证有效期（秒）
     * @return 上传凭证字符串
     */
    public static String uploadToken(String accessKey, String secretKey,
                                     String bucket, String key, long expiresSecond) {
        long deadline = System.currentTimeMillis() / 1000L + expiresSecond;
        String scope = (key == null || key.isEmpty()) ? bucket : bucket + ":" + key;
        String policyJson = "{\"scope\":\"" + scope + "\",\"deadline\":" + deadline + "}";

        String encodedPolicy = urlSafeBase64(policyJson.getBytes(StandardCharsets.UTF_8));
        byte[] sign = hmacSha1(secretKey.getBytes(StandardCharsets.UTF_8),
                encodedPolicy.getBytes(StandardCharsets.UTF_8));
        return accessKey + ":" + urlSafeBase64(sign) + ":" + encodedPolicy;
    }

    /**
     * 生成七牛「管理凭证」（QBox token），用于调用 rsf.qbox.me 等管理类接口
     * （本应用用来列举 bucket 下的目录与文件，见 {@link QiniuBucketApi}）。
     *
     * <pre>
     * signingStr = path [ "?" query ] "\n"
     * sign       = HmacSHA1(secretKey, signingStr)
     * token      = AK ":" urlSafeBase64(sign) ":" urlSafeBase64(AK ":" urlSafeBase64(sign))
     * </pre>
     *
     * 与上传凭证不同，管理凭证把「被签名的原文」也算进了请求，所以 query 必须与真实请求
     * 逐字节一致（参数按名称字典序、值做 URL 编码）。
     *
     * 算法参考：https://developer.qiniu.com/kodo/1201/access-token
     *
     * @param path  接口路径，如 {@code /list}
     * @param query 请求参数串（不含 "?"），无参数时传 null 或 ""
     * @return 管理凭证字符串（不含 "QBox " 前缀）
     */
    public static String managementToken(String accessKey, String secretKey,
                                         String path, String query) {
        String signingStr = signingString(path, query);
        byte[] sign = hmacSha1(secretKey.getBytes(StandardCharsets.UTF_8),
                signingStr.getBytes(StandardCharsets.UTF_8));
        String encodedSign = urlSafeBase64(sign);
        String encodedToken = urlSafeBase64(
                (accessKey + ":" + encodedSign).getBytes(StandardCharsets.UTF_8));
        return accessKey + ":" + encodedSign + ":" + encodedToken;
    }

    /** 待签名原文：path [+ "?" + query] + "\n"。抽成独立方法便于单测校验格式。 */
    static String signingString(String path, String query) {
        StringBuilder sb = new StringBuilder(path == null ? "" : path);
        if (query != null && !query.isEmpty()) {
            sb.append('?').append(query);
        }
        return sb.append('\n').toString();
    }

    private static byte[] hmacSha1(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA1);
            mac.init(new SecretKeySpec(key, HMAC_SHA1));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 签名失败", e);
        }
    }

    /**
     * URL 安全 Base64：'+' -> '-'、'/' -> '_'，保留 '=' 填充，与七牛 UrlSafeBase64 一致。
     * 注意必须用 android.util.Base64：java.util.Base64 需要 API 26+，本项目 minSdk 21。
     */
    private static String urlSafeBase64(byte[] data) {
        return encoder.encode(data);
    }

    /** Base64 编码钩子：生产环境用 android.util.Base64，JVM 单测注入等价的 java 实现。 */
    interface UrlSafeBase64Encoder {
        String encode(byte[] data);
    }

    private static final UrlSafeBase64Encoder ANDROID_ENCODER = new UrlSafeBase64Encoder() {
        @Override
        public String encode(byte[] data) {
            return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP);
        }
    };

    private static UrlSafeBase64Encoder encoder = ANDROID_ENCODER;

    /** 仅供 JVM 单测注入与 android.util.Base64 逐字节等价的实现；传 null 恢复生产默认。 */
    static void setUrlSafeBase64EncoderForTest(UrlSafeBase64Encoder e) {
        encoder = e != null ? e : ANDROID_ENCODER;
    }
}
