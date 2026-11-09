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
        // scope 含用户文件名，必须做 JSON 转义；否则引号、反斜杠或换行会破坏 policy，
        // 使上传凭证失效，甚至改变服务端看到的字段含义。
        String policyJson = "{\"scope\":" + quoteJson(scope) + ",\"deadline\":" + deadline + "}";

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

    /** JSON 字符串转义：与 org.json.JSONObject.quote 的必要规则一致，普通文件名输出不变。 */
    static String quoteJson(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    // JSON 不允许未转义的控制字符；U+2028/U+2029 也避免被某些解析器当换行。
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            int digit = (c >> shift) & 0xF;
                            out.append((char) (digit < 10 ? '0' + digit : 'a' + digit - 10));
                        }
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }

    /** 待签名原文：path [+ "?" + query] + "\n"。抽成独立方法便于单测校验格式。 */
    static String signingString(String path, String query) {
        StringBuilder sb = new StringBuilder(path == null ? "" : path);
        if (query != null && !query.isEmpty()) {
            sb.append('?').append(query);
        }
        return sb.append('\n').toString();
    }

    /**
     * 生成七牛「Qiniu」管理凭证（QiniuMacAuth，现行官方 SDK 对管理接口使用的方案，
     * 部分较新的账号只接受这一种签名，旧版 {@link #managementToken}（QBox）会报 401 bad token）。
     *
     * <p>对应官方 Python SDK 的 {@code QiniuMacAuth.token_of_request}，签名原文为
     * （各段之间存在则拼接，最后固定补两个换行）：
     *
     * <pre>
     * &lt;METHOD&gt; &lt;path&gt;[?&lt;query&gt;]
     * Host: &lt;host&gt;
     * Content-Type: &lt;contentType&gt;      （请求带该头时才签）
     * X-Qiniu-Date: &lt;xQiniuDate&gt;       （请求带该头时才签，多个 X-Qiniu-* 头按名称排序）
     * （空行）
     * （空行）
     * </pre>
     *
     * 注意与 QBox 凭证不同：token 只有两段（ak:sign），Header 形如 {@code Authorization: Qiniu ak:sign}。
     *
     * @param method      HTTP 方法，如 {@code GET}
     * @param path        接口路径，如 {@code /list}
     * @param query       请求参数串（不含 "?"），无参数时传 null 或 ""
     * @param host        请求的 Host（不含端口），如 {@code rsf.qbox.me}
     * @param contentType 请求的 Content-Type；请求不带该头时传 null 或 ""
     * @param xQiniuDate  请求头 X-Qiniu-Date 的值（UTC 时间，格式 yyyyMMdd'T'HHmmss'Z'）；不带时传 null 或 ""
     * @return 「Qiniu」管理凭证字符串（不含 "Qiniu " 前缀）
     */
    public static String qiniuMacToken(String accessKey, String secretKey, String method,
                                       String path, String query, String host,
                                       String contentType, String xQiniuDate) {
        StringBuilder data = new StringBuilder(method == null ? "" : method)
                .append(' ').append(path == null ? "" : path);
        if (query != null && !query.isEmpty()) {
            data.append('?').append(query);
        }
        data.append('\n').append("Host: ").append(host == null ? "" : host);
        if (contentType != null && !contentType.isEmpty()) {
            data.append('\n').append("Content-Type: ").append(contentType);
        }
        if (xQiniuDate != null && !xQiniuDate.isEmpty()) {
            data.append('\n').append("X-Qiniu-Date: ").append(xQiniuDate);
        }
        data.append("\n\n");

        byte[] sign = hmacSha1(secretKey.getBytes(StandardCharsets.UTF_8),
                data.toString().getBytes(StandardCharsets.UTF_8));
        return accessKey + ":" + urlSafeBase64(sign);
    }

    /**
     * 生成七牛「下载凭证」（私有空间的签名下载 URL 用）。
     *
     * <p>算法参考：https://developer.qiniu.com/kodo/1655/private：
     *
     * <pre>
     * sign  = HmacSHA1(secretKey, 待签名 URL)
     * token = AK ":" urlSafeBase64(sign)
     * 最终下载 URL = 待签名 URL + "&amp;token=" + token
     * </pre>
     *
     * 与上传/管理凭证不同，下载凭证只对 URL 本身签名（不含 Host 头、不含换行）。
     *
     * @param urlNoToken 不含 token 参数的完整下载 URL（域名 + 已编码的 key，可带 e 查询参数）
     * @return 下载凭证字符串（AK:sign），拼接时需加 "&amp;token=" 前缀
     */
    public static String downloadToken(String accessKey, String secretKey, String urlNoToken) {
        byte[] sign = hmacSha1(secretKey.getBytes(StandardCharsets.UTF_8),
                urlNoToken.getBytes(StandardCharsets.UTF_8));
        return accessKey + ":" + urlSafeBase64(sign);
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
