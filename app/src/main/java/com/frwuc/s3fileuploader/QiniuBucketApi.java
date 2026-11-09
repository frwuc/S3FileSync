package com.frwuc.s3fileuploader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 七牛 Kodo 空间「列举」接口封装，用于在 App 内浏览 bucket 下的目录与文件。
 *
 * <p>用的是官方管理接口 {@code GET https://rsf.qbox.me/list}：
 *
 * <pre>
 * bucket=空间名 &amp; prefix=当前前缀 &amp; delimiter=/ &amp; limit=1000 &amp; marker=翻页游标
 * </pre>
 *
 * 带 {@code delimiter=/} 时，服务端把「当前前缀下的直接子目录」放进
 * {@code commonPrefixes}（如 {@code daka_files/2024/}），把「直接子文件」放进
 * {@code items}；不带 delimiter 会递归返回所有层级的 key，页面展示会乱，所以必须带。
 *
 * <p>该接口需要管理凭证。这里做两层处理：
 * <ul>
 *   <li>签名用「Qiniu」方案（{@link QiniuToken#qiniuMacToken}，现行官方 SDK 的方案，
 *       部分较新的账号只认这一种）；收到 401 时自动回退旧版「QBox」方案
 *       （{@link QiniuToken#managementToken}）再试一次，兼容老账号；</li>
 *   <li>请求主机先通过 UC 的 {@code /v4/query} 查出 bucket 所在区域的 rsf 域名，
 *       避免写死 rsf.qbox.me（z0）后非华东空间的 bucket 列举失败。</li>
 * </ul>
 *
 * 所有方法都是同步阻塞的，调用方需放在后台线程。
 * 接口文档：https://developer.qiniu.com/kodo/1284/list
 */
public final class QiniuBucketApi {

    private static final String LIST_PATH = "/list";
    /** 兜底 rsf 主机（华东 z0） */
    private static final String DEFAULT_RSF_HOST = "rsf.qbox.me";
    /** 兜底 rs 主机（华东 z0），删除等文件管理接口用 */
    private static final String DEFAULT_RS_HOST = "rs.qbox.me";
    /** UC 区域查询（无需签名）：返回 bucket 所在区域的 rs/rsf 等域名 */
    private static final String UC_QUERY_PATH = "/v4/query";
    private static final String UC_QUERY_HOST = "uc.qbox.me";

    /** 单页条数，七牛上限 1000 */
    private static final int PAGE_LIMIT = 1000;
    /** 最多翻多少页，防止超大空间把手机拖死 */
    private static final int MAX_PAGES = 20;

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;
    /** 1000 条/页的目录元数据正常远低于该值；限制异常响应造成的内存消耗。 */
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    /** GET /list 不带 Content-Type（与官方 SDK 一致），也不参与「Qiniu」签名；
     *  若签名里写了 Content-Type 而请求未发送该头，服务端重算签名必然 401 bad token */
    private static final String REQUEST_CONTENT_TYPE = "";

    /** rsf 主机缓存：key = ak@bucket。区域信息带 ttl，进程内缓存即可 */
    private static final Map<String, String> RSF_HOST_CACHE = new ConcurrentHashMap<>();

    /** rs 主机缓存：key = ak@bucket。删除等管理接口用 */
    private static final Map<String, String> RS_HOST_CACHE = new ConcurrentHashMap<>();

    private QiniuBucketApi() {
    }

    /**
     * 列举某个「目录」下的直接子目录与文件。
     *
     * @param prefix 目录前缀，可空（表示根目录）；不以 "/" 结尾时自动补上
     * @return 目录项（dir=true）+ 文件项，未排序
     */
    public static List<BrowseEntry> list(String accessKey, String secretKey,
                                         String bucket, String prefix) throws IOException {
        if (isBlank(accessKey) || isBlank(secretKey)) {
            throw new IOException("请先填写七牛云 AK / SK");
        }
        if (isBlank(bucket)) {
            throw new IOException("请先填写 Bucket 空间名称");
        }

        String host = resolveRsfHost(accessKey, bucket);
        String dirPrefix = normalizePrefix(prefix);
        List<BrowseEntry> result = new ArrayList<>();
        String marker = "";

        for (int page = 0; page < MAX_PAGES; page++) {
            String query = buildQuery(bucket, dirPrefix, "/", marker);
            JSONObject json = request(host, query, accessKey, secretKey);

            // 子目录：commonPrefixes 里是完整前缀，如 "daka_files/2024/"
            JSONArray dirs = json.optJSONArray("commonPrefixes");
            if (dirs != null) {
                for (int i = 0; i < dirs.length(); i++) {
                    String full = dirs.optString(i, "");
                    String name = leafName(full, dirPrefix);
                    if (name.isEmpty()) continue;
                    result.add(new BrowseEntry(name, full, true, 0L, 0L));
                }
            }

            // 文件：key 去掉前缀后就是文件名
            JSONArray items = json.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    String key = item.optString("key", "");
                    if (key.isEmpty()) continue;
                    String name = leafName(key, dirPrefix);
                    if (name.isEmpty()) continue;
                    // putTime 单位是 100 纳秒
                    long putTime = item.optLong("putTime", 0L);
                    long mtimeMs = putTime > 0 ? putTime / 10000L : 0L;
                    result.add(new BrowseEntry(name, key, false,
                            item.optLong("fsize", 0L), mtimeMs));
                }
            }

            String next = optNextMarker(json);
            if (isBlank(next) || next.equals(marker)) break;
            marker = next;
        }

        return result;
    }

    // ---------------------------------------------------------------- 删除文件

    /**
     * 删除 bucket 下的一个文件（不可恢复）。
     *
     * <p>用官方管理接口 {@code POST https://rs.qbox.me/delete/<urlSafeBase64(key)>}，
     * 需要「Qiniu」管理凭证，401 时回退旧版「QBox」签名（与列举接口同一套兼容策略）。
     * 接口文档：https://developer.qiniu.com/kodo/1257/delete
     *
     * @param key 完整的文件 key，如 {@code daka_files/2024/xxx.jpg}
     */
    public static void delete(String accessKey, String secretKey, String bucket, String key)
            throws IOException {
        if (isBlank(accessKey) || isBlank(secretKey)) {
            throw new IOException("请先填写七牛云 AK / SK");
        }
        if (isBlank(bucket)) {
            throw new IOException("请先填写 Bucket 空间名称");
        }
        if (isBlank(key)) {
            throw new IOException("文件 key 为空，无法删除");
        }

        String host = resolveRsHost(accessKey, bucket);
        String path = "/delete/" + urlSafeBase64NoPadding(key.getBytes("UTF-8"));

        String xQiniuDate = utcTimestamp();
        // POST /delete/ 无请求体，也不带 Content-Type，签名里同样不能写
        String qiniuToken = QiniuToken.qiniuMacToken(accessKey, secretKey, "POST",
                path, "", host, "", xQiniuDate);
        Response resp = postRequest(host, path, "Qiniu " + qiniuToken, xQiniuDate);
        if (resp.code == HttpURLConnection.HTTP_OK) return;
        if (resp.code != 401) {
            throw new IOException("删除云端文件失败（HTTP " + resp.code + "）："
                    + explain(resp.code, resp.body));
        }

        // 401 回退旧版「QBox」签名再试一次，兼容老账号
        String qboxToken = QiniuToken.managementToken(accessKey, secretKey, path, "");
        Response retry = postRequest(host, path, "QBox " + qboxToken, null);
        if (retry.code == HttpURLConnection.HTTP_OK) return;
        throw new IOException("删除云端文件失败（HTTP " + retry.code + "，新旧两种签名均被拒绝）："
                + explain(retry.code, retry.body));
    }

    /** POST 一个无请求体的管理接口（与 doRequest 同构，仅方法与路径不同）。 */
    private static Response postRequest(String host, String path, String authorization,
                                        String xQiniuDate) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://" + host + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            if (authorization != null) {
                conn.setRequestProperty("Authorization", authorization);
            }
            if (xQiniuDate != null) {
                conn.setRequestProperty("X-Qiniu-Date", xQiniuDate);
            }
            int code = conn.getResponseCode();
            String body = readAll(code == HttpURLConnection.HTTP_OK
                    ? conn.getInputStream() : conn.getErrorStream());
            return new Response(code, body);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** URL 安全 Base64（无 "=" 填充），与七牛查询接口（如 /delete）要求一致；纯 Java 实现，可单测。 */
    static String urlSafeBase64NoPadding(byte[] data) {
        final String ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                + "abcdefghijklmnopqrstuvwxyz0123456789-_";
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        for (int i = 0; i < data.length; i += 3) {
            boolean has2 = i + 1 < data.length;
            boolean has3 = i + 2 < data.length;
            int b1 = data[i] & 0xFF;
            int b2 = has2 ? data[i + 1] & 0xFF : 0;
            int b3 = has3 ? data[i + 2] & 0xFF : 0;
            int triple = (b1 << 16) | (b2 << 8) | b3;
            sb.append(ALPHA.charAt((triple >> 18) & 0x3F));
            sb.append(ALPHA.charAt((triple >> 12) & 0x3F));
            if (has2) sb.append(ALPHA.charAt((triple >> 6) & 0x3F));
            if (has3) sb.append(ALPHA.charAt(triple & 0x3F));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 预览下载

    /**
     * 按「访问域名」拉取云端文件内容，供 App 内预览。
     *
     * <p>先按公开空间直接 GET；被拒（401/403）时按私有空间自动附加下载凭证重试
     * （{@link QiniuToken#downloadToken}，有效期 10 分钟）。私有空间不需要用户手动开关。
     *
     * @param accessKey  附加私有下载凭证用
     * @param secretKey  同上
     * @param domain     bucket 绑定的访问域名（如 {@code https://cdn.example.com}），
     *                   支持填测试域名。请到七牛控制台 → 空间设置 → 域名管理确认
     * @param key        文件完整 key
     * @param maxBytes   内容字节上限，超出按失败处理（预览场景防内存溢出）
     * @return 文件内容字节
     */
    public static byte[] fetchFile(String accessKey, String secretKey,
                                   String domain, String key, int maxBytes) throws IOException {
        if (isBlank(domain)) {
            throw new IOException("请先在「设置 → 七牛云账号」填写空间绑定的访问域名"
                    + "（可填七牛测试域名，到七牛控制台 → 空间设置 → 域名管理可查看）");
        }
        if (isBlank(key)) {
            throw new IOException("文件 key 为空，无法预览");
        }
        // 拼接下载 URL：与上传成功日志 recordFileUrl 同一规则（保留 "/"，编码 " # ? % 等）
        android.net.Uri.Builder builder = new android.net.Uri.Builder()
                .scheme(schemeOf(trimDomain(domain)))
                .encodedAuthority(authorityOf(trimDomain(domain)))
                .appendEncodedPath(encodePathForUrl(key));
        String base = builder.build().toString();

        Response resp = getBinary(base, maxBytes);
        if (resp.code != HttpURLConnection.HTTP_OK
                && (resp.code == 401 || resp.code == 403)) {
            // 公开 GET 被拒：按私有空间处理，附 10 分钟有效期的下载凭证重试
            long deadline = System.currentTimeMillis() / 1000L + 600L;
            String sep = base.contains("?") ? "&" : "?";
            String signed = base + sep + "e=" + deadline
                    + "&token=" + QiniuToken.downloadToken(accessKey, secretKey, base + sep + "e=" + deadline);
            resp = getBinary(signed, maxBytes);
        }
        if (resp.code != HttpURLConnection.HTTP_OK) {
            throw new IOException("下载云端文件失败（HTTP " + resp.code + "）："
                    + (resp.body == null || resp.body.isEmpty()
                    ? "请检查访问域名是否与七牛空间绑定一致" : brief(resp.body)));
        }
        return resp.data;
    }

    /** URL 地址规范化：去首尾空格与结尾斜杠，剩「scheme://host[:port]/path?query」中的一部分 */
    static String trimDomain(String domain) {
        String d = domain == null ? "" : domain.trim();
        while (d.endsWith("/")) d = d.substring(0, d.length() - 1);
        return d;
    }

    /** 域名带 scheme 时拆出 scheme 部分；不带 scheme（只有主机名）时默认 https */
    private static String schemeOf(String domain) {
        if (domain == null) return "https";
        String lower = domain.toLowerCase(Locale.US);
        int idx = lower.indexOf("://");
        return (idx > 0 && idx <= 8) ? domain.substring(0, idx) : "https";
    }

    /** 域名拆掉 scheme 后的 authority（主机[:端口]）部分；没有 scheme 时原样返回 */
    private static String authorityOf(String domain) {
        if (domain == null) return "";
        String lower = domain.toLowerCase(Locale.US);
        int idx = lower.indexOf("://");
        return (idx > 0 && idx <= 8) ? domain.substring(idx + 3) : domain;
    }

    /**
     * key 拼到下载 URL 时逐段编码：保留 "/"（七牛路径层次符号），其余按七牛官方
     * 「保留竖线之外特定字符」规则用 Uri.encode 即可（与 QiniuUploader.buildFileUrl 一致）。
     */
    private static String encodePathForUrl(String key) {
        return android.net.Uri.encode(key, "/");
    }

    /** GET 下载一个文件，返回字节与状态码；响应超过 maxBytes 时中止。 */
    private static Response getBinary(String url, int maxBytes) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            InputStream in = code == HttpURLConnection.HTTP_OK
                    ? conn.getInputStream() : conn.getErrorStream();
            byte[] data = new byte[0];
            if (in != null) {
                try (InputStream is = in) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    int total = 0;
                    while ((n = is.read(buf)) != -1) {
                        total += n;
                        if (total > maxBytes) {
                            throw new IOException("文件内容超过 "
                                    + (maxBytes / 1024 / 1024) + " MB，预览已中止");
                        }
                        bos.write(buf, 0, n);
                    }
                    data = bos.toByteArray();
                }
            }
            return new Response(code, data);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------------------------------------------------------- 区域解析

    /**
     * 查 bucket 所在区域的 rsf 主机：GET https://uc.qbox.me/v4/query?ak=..&amp;bucket=..
     * （无需签名）。查询失败时回退华东 rsf.qbox.me。
     */
    static String resolveRsfHost(String accessKey, String bucket) {
        String cacheKey = accessKey + "@" + bucket;
        String cached = RSF_HOST_CACHE.get(cacheKey);
        if (cached != null) return cached;

        HttpURLConnection conn = null;
        try {
            String query = "ak=" + encode(accessKey) + "&bucket=" + encode(bucket);
            conn = openGet(UC_QUERY_HOST, UC_QUERY_PATH, query, null, null);
            int code = conn.getResponseCode();
            String body = readAll(code == HttpURLConnection.HTTP_OK
                    ? conn.getInputStream() : conn.getErrorStream());
            if (code == HttpURLConnection.HTTP_OK) {
                JSONObject json = new JSONObject(body);
                JSONArray hosts = json.optJSONArray("hosts");
                if (hosts != null && hosts.length() > 0) {
                    JSONObject rsf = hosts.optJSONObject(0).optJSONObject("rsf");
                    if (rsf != null) {
                        JSONArray domains = rsf.optJSONArray("domains");
                        if (domains != null && domains.length() > 0) {
                            String host = domains.optString(0, "").trim().toLowerCase(Locale.US);
                            // 只接受七牛官方域名的纯主机名，防止异常 UC 响应把管理 token 带到任意主机。
                            if (isTrustedRsfHost(host)) {
                                RSF_HOST_CACHE.put(cacheKey, host);
                                return host;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 查不到区域就用默认主机，不影响主流程
        } finally {
            if (conn != null) conn.disconnect();
        }
        return DEFAULT_RSF_HOST;
    }

    /**
     * 查 bucket 所在区域的 rs 主机（删除等文件管理接口用）：UC /v4/query 返回的 hosts[0].rs。
     * 查询失败时回退华东 rs.qbox.me。与 rsf 同一信任域名白名单。
     */
    static String resolveRsHost(String accessKey, String bucket) {
        String cacheKey = accessKey + "@" + bucket;
        String cached = RS_HOST_CACHE.get(cacheKey);
        if (cached != null) return cached;

        HttpURLConnection conn = null;
        try {
            String query = "ak=" + encode(accessKey) + "&bucket=" + encode(bucket);
            conn = openGet(UC_QUERY_HOST, UC_QUERY_PATH, query, null, null);
            int code = conn.getResponseCode();
            String body = readAll(code == HttpURLConnection.HTTP_OK
                    ? conn.getInputStream() : conn.getErrorStream());
            if (code == HttpURLConnection.HTTP_OK) {
                JSONObject json = new JSONObject(body);
                JSONArray hosts = json.optJSONArray("hosts");
                if (hosts != null && hosts.length() > 0) {
                    JSONObject rs = hosts.optJSONObject(0).optJSONObject("rs");
                    if (rs != null) {
                        JSONArray domains = rs.optJSONArray("domains");
                        if (domains != null && domains.length() > 0) {
                            String host = domains.optString(0, "").trim().toLowerCase(Locale.US);
                            if (isTrustedRsfHost(host)) {
                                RS_HOST_CACHE.put(cacheKey, host);
                                return host;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 查不到区域就用默认主机，不影响主流程
        } finally {
            if (conn != null) conn.disconnect();
        }
        return DEFAULT_RS_HOST;
    }

    // ---------------------------------------------------------------- 请求与签名

    /** 简单响应体 */
    private static final class Response {
        final int code;
        final String body;
        /** 二进制响应体（下载接口用），文本接口为 null */
        final byte[] data;

        Response(int code, String body) {
            this(code, body, null);
        }

        Response(int code, byte[] data) {
            this(code, "", data);
        }

        Response(int code, String body, byte[] data) {
            this.code = code;
            this.body = body;
            this.data = data;
        }
    }

    private static JSONObject request(String host, String query, String accessKey, String secretKey)
            throws IOException {
        String xQiniuDate = utcTimestamp();

        // 1) 新版「Qiniu」签名：现行官方 SDK 的方案，较新的账号只接受这一种。
        //    注意：签名必须与实际发送的请求一致——GET 不发 Content-Type，签名里也不能带（踩过坑：
        //    曾把 Content-Type 签进去而请求未发送该头，导致服务端重算不一致报 401 bad token）
        String qiniuToken = QiniuToken.qiniuMacToken(accessKey, secretKey, "GET",
                LIST_PATH, query, host, REQUEST_CONTENT_TYPE, xQiniuDate);
        Response resp = doRequest(host, query, "Qiniu " + qiniuToken, xQiniuDate);
        if (resp.code == HttpURLConnection.HTTP_OK) {
            return parseBody(resp.body);
        }
        if (resp.code != 401) {
            throw new IOException("列举云端目录失败（HTTP " + resp.code + "）："
                    + explain(resp.code, resp.body));
        }

        // 2) 401 时回退旧版「QBox」签名再试一次，兼容老账号
        String qboxToken = QiniuToken.managementToken(accessKey, secretKey, LIST_PATH, query);
        Response retry = doRequest(host, query, "QBox " + qboxToken, null);
        if (retry.code == HttpURLConnection.HTTP_OK) {
            return parseBody(retry.body);
        }
        throw new IOException("列举云端目录失败（HTTP " + retry.code + "，新旧两种签名均被拒绝）："
                + explain(retry.code, retry.body));
    }

    private static Response doRequest(String host, String query, String authorization,
                                      String xQiniuDate) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = openGet(host, LIST_PATH, query, authorization, xQiniuDate);
            int code = conn.getResponseCode();
            String body = readAll(code == HttpURLConnection.HTTP_OK
                    ? conn.getInputStream() : conn.getErrorStream());
            return new Response(code, body);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection openGet(String host, String path, String query,
                                             String authorization, String xQiniuDate)
            throws IOException {
        URL url = new URL("https://" + host + path + (query.isEmpty() ? "" : "?" + query));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        // 管理 token 只对当前主机签名；禁止自动跟随重定向，避免凭证被转发到其它主机。
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        if (authorization != null) {
            conn.setRequestProperty("Authorization", authorization);
        }
        if (xQiniuDate != null) {
            conn.setRequestProperty("X-Qiniu-Date", xQiniuDate);
        }
        return conn;
    }

    /** X-Qiniu-Date 头：UTC 时间，格式 yyyyMMdd'T'HHmmss'Z'（与官方 SDK 一致）。 */
    private static String utcTimestamp() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    private static JSONObject parseBody(String body) throws IOException {
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            throw new IOException("云端返回内容无法解析：" + brief(body), e);
        }
    }

    /** 取翻页游标：新版返回 cursor，老版返回 marker，两者都兼容。 */
    private static String optNextMarker(JSONObject json) {
        String cursor = json.optString("cursor", "");
        if (!isBlank(cursor)) return cursor;
        return json.optString("marker", "");
    }

    /** 把状态码翻成用户能看懂的提示（保留服务器返回的原始错误，便于定位）。 */
    private static String explain(int code, String body) {
        String detail = hintOf(body);
        if (code == 401) {
            // 新旧两种签名都被拒绝，基本可以断定是密钥本身的问题
            return "管理凭证校验失败，请到七牛控制台「密钥管理」确认 AK / SK 是否有效"
                    + "（若最近更换过 SK，需到设置页重新粘贴并保存）。服务器返回：" + detail;
        }
        if (code == 403) return "没有该空间的操作权限，请检查 Bucket 是否属于此账号。服务器返回：" + detail;
        if (code == 404) return "空间不存在：" + detail;
        if (code == 400) return "请求参数有误：" + detail;
        if (code == 631) return "空间不存在（当前区域查不到该 Bucket）：" + detail;
        if (code >= 500) return "七牛服务暂时不可用，稍后再试。服务器返回：" + detail;
        return detail;
    }

    private static String hintOf(String body) {
        if (body == null || body.isEmpty()) return "无返回内容";
        try {
            JSONObject json = new JSONObject(body);
            String err = json.optString("error", "");
            if (!err.isEmpty()) return err;
        } catch (Exception ignored) {
            // 非 JSON，直接把原文截断展示
        }
        return brief(body);
    }

    private static String brief(String body) {
        if (body == null) return "";
        String s = body.trim();
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("云端响应超过 8 MB，已中止读取");
                }
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        }
    }

    /** 仅允许 UC 返回七牛官方域名下的纯主机名；带点后缀边界，拒绝 evilqbox.me。 */
    static boolean isTrustedRsfHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.US);
        if (h.isEmpty() || h.length() > 253 || h.contains("..")
                || !h.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?")) return false;
        String[] officialSuffixes = {"qbox.me", "qiniuio.com", "qiniucn.com", "qiniu.com", "qiniuapi.com"};
        for (String suffix : officialSuffixes) {
            if (h.equals(suffix) || h.endsWith("." + suffix)) return true;
        }
        return false;
    }

    /** 目录前缀规范化：空 -> ""，非空则保证以 "/" 结尾。 */
    static String normalizePrefix(String prefix) {
        if (prefix == null) return "";
        String p = prefix.trim();
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) return "";
        return p.endsWith("/") ? p : p + "/";
    }

    /** 取相对名：完整 key/前缀 去掉目录前缀与末尾 "/"。 */
    static String leafName(String full, String dirPrefix) {
        if (full == null) return "";
        String name = full;
        if (!dirPrefix.isEmpty() && name.startsWith(dirPrefix)) {
            name = name.substring(dirPrefix.length());
        }
        while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        return name;
    }

    /**
     * 构造请求参数串：参数名按字典序、值做 URL 编码。
     *
     * <p>七牛要求「被签名的 query」与真实请求完全一致，因此签名与请求共用这一个方法的结果。
     */
    static String buildQuery(String bucket, String prefix, String delimiter, String marker) {
        List<String[]> params = new ArrayList<>();
        params.add(new String[]{"bucket", bucket});
        if (delimiter != null && !delimiter.isEmpty()) {
            params.add(new String[]{"delimiter", delimiter});
        }
        params.add(new String[]{"limit", String.valueOf(PAGE_LIMIT)});
        if (!isBlank(marker)) params.add(new String[]{"marker", marker});
        if (!isBlank(prefix)) params.add(new String[]{"prefix", prefix});

        Collections.sort(params, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return a[0].compareTo(b[0]);
            }
        });

        StringBuilder sb = new StringBuilder();
        for (String[] p : params) {
            if (sb.length() > 0) sb.append('&');
            sb.append(p[0]).append('=').append(encode(p[1]));
        }
        return sb.toString();
    }

    private static String encode(String value) {
        try {
            // URLEncoder 会把空格编成 "+"，query 里应为 %20
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
