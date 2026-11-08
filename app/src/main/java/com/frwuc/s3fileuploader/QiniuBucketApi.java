package com.frwuc.s3fileuploader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
 * <p>该接口需要管理凭证（QBox token），用 AK/SK 本地签发，见
 * {@link QiniuToken#managementToken}。所有方法都是同步阻塞的，调用方需放在后台线程。
 *
 * 接口文档：https://developer.qiniu.com/kodo/1284/list
 */
public final class QiniuBucketApi {

    private static final String LIST_URL = "https://rsf.qbox.me/list";
    private static final String LIST_PATH = "/list";

    /** 单页条数，七牛上限 1000 */
    private static final int PAGE_LIMIT = 1000;
    /** 最多翻多少页，防止超大空间把手机拖死 */
    private static final int MAX_PAGES = 20;

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;

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

        String dirPrefix = normalizePrefix(prefix);
        List<BrowseEntry> result = new ArrayList<>();
        String marker = "";

        for (int page = 0; page < MAX_PAGES; page++) {
            String query = buildQuery(bucket, dirPrefix, "/", marker);
            JSONObject json = request(query, accessKey, secretKey);

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

    /** 取翻页游标：新版返回 cursor，老版返回 marker，两者都兼容。 */
    private static String optNextMarker(JSONObject json) {
        String cursor = json.optString("cursor", "");
        if (!isBlank(cursor)) return cursor;
        return json.optString("marker", "");
    }

    private static JSONObject request(String query, String accessKey, String secretKey)
            throws IOException {
        String token = QiniuToken.managementToken(accessKey, secretKey, LIST_PATH, query);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(LIST_URL + "?" + query);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Authorization", "QBox " + token);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                String body = readAll(conn.getInputStream());
                try {
                    return new JSONObject(body);
                } catch (Exception e) {
                    throw new IOException("云端返回内容无法解析：" + brief(body), e);
                }
            }

            String error = readAll(conn.getErrorStream());
            throw new IOException("列举云端目录失败（HTTP " + code + "）：" + explain(code, error));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 把状态码翻成用户能看懂的提示。 */
    private static String explain(int code, String body) {
        if (code == 401) return "AK / SK 不正确或已失效";
        if (code == 403) return "没有该空间的操作权限，请检查 Bucket 是否属于此账号";
        if (code == 404) return "空间不存在：" + hintOf(body);
        if (code == 400) return "请求参数有误：" + hintOf(body);
        if (code >= 500) return "七牛服务暂时不可用，稍后再试";
        return hintOf(body);
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
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
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
