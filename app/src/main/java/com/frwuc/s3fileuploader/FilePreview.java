package com.frwuc.s3fileuploader;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文件内容预览：本地文件与已下载的云端文件共用一套查看逻辑。
 *
 * <ul>
 *   <li><b>图片</b>（jpg / png / gif / webp / bmp 等）：App 内按屏幕比例缩放显示；</li>
 *   <li><b>文本</b>（txt / log / md / json / xml / 代码等）：App 内可直接选中、滚动查看，
 *       最多读 {@link #MAX_TEXT_BYTES}，超出提示省略；</li>
 *   <li><b>其它类型</b>（PDF / Word / 压缩包 / 视频等）：交给系统里能打开该类型的应用，
 *       文件经 {@link androidx.core.content.FileProvider} 暴露，不需要存储权限。</li>
 * </ul>
 *
 * 云端文件预览先经 {@link QiniuBucketApi#fetchFile} 拉到内存
 * （公开空间直接 GET，私有空间自动附下载凭证），再走同一套展示逻辑。
 * 所有 IO 都在单线程池执行，结果回主线程。
 */
final class FilePreview {

    /** 文本内容最多读 256 KB，够看大半篇文档又不会把内存吃满 */
    private static final int MAX_TEXT_BYTES = 256 * 1024;
    /** 云端下载上限 16 MB：超过按失败提示（预览不支持超大视频等） */
    private static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final Set<String> IMAGE_EXTS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"));
    private static final Set<String> TEXT_EXTS = new HashSet<>(Arrays.asList(
            "txt", "log", "md", "json", "xml", "csv", "html", "htm", "js", "ts", "java",
            "py", "c", "cpp", "h", "hpp", "css", "ini", "conf", "properties", "srt",
            "lrc", "sql", "yaml", "yml", "sh", "bat", "dart", "go", "rs", "gradle", "svg"));

    private FilePreview() {
    }

    /** 预览一个本地文件。 */
    public static void previewLocal(Context context, String path, String name) {
        IO.execute(() -> {
            try {
                File file = new File(path);
                if (!file.exists() || !file.isFile()) {
                    fail(context, "文件不存在，可能已被移动或删除：" + path);
                    return;
                }
                // 图片按更大上限读入（供等比解码）；文本 / 未知类型用小上限做 sniff 判定
                int cap = IMAGE_EXTS.contains(extOf(name)) ? 32 * 1024 * 1024
                        : MAX_TEXT_BYTES + 64 * 1024;
                byte[] bytes = readCapped(file, cap);
                MAIN.post(() -> routeWhich(context, bytes, name, () -> openWithSystem(context, file)));
            } catch (Exception e) {
                fail(context, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    /**
     * 预览一个云端文件：经「访问域名」下载后再展示。
     *
     * @param domain 七牛空间绑定的下载域名（设置页「上传域名」一栏，可填测试域名）
     * @param key    云端完整 key
     */
    public static void previewCloud(Context context, String accessKey, String secretKey,
                                    String domain, String key, String name) {
        toast(context, "正在下载云端文件…");
        IO.execute(() -> {
            try {
                byte[] bytes = QiniuBucketApi.fetchFile(accessKey, secretKey, domain, key,
                        MAX_DOWNLOAD_BYTES);
                // 能直接展示的（图片 / 文本）不落盘；其它类型写进缓存目录交给系统应用打开
                MAIN.post(() -> {
                    if (isImageExt(name)) {
                        showImage(context, bytes, name);
                        return;
                    }
                    if (isLikelyText(extOf(name), bytes)) {
                        showText(context, bytes, name, false);
                        return;
                    }
                    try {
                        File cache = newTempFile(context, name);
                        try (FileOutputStream out = new FileOutputStream(cache)) {
                            out.write(bytes);
                        }
                        openWithSystem(context, cache);
                    } catch (Exception e) {
                        fail(context, e.getMessage() == null ? e.toString() : e.getMessage());
                    }
                });
            } catch (Exception e) {
                fail(context, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- 展示路由与弹窗

    /**
     * 分流展示：数据字节数组是「已下载到内存」的事实，file 才是系统打开用的落点。
     * 本地预览时 {@code fallback} 直接指向原文件；云端预览时落点文件由调用方准备。
     */
    private static void routeWhich(Context context, byte[] bytes, String name,
                                   Runnable fallback) {
        if (isImageExt(name)) {
            Bitmap bmp = decodeScaled(bytes);
            if (bmp != null) {
                showImage(context, bmp);
                return;
            }
            // 编解码失败（如 HEIC 老设备不支持）就交给系统应用
            fallback.run();
            return;
        }
        if (isLikelyText(extOf(name), bytes)) {
            showText(context, bytes, name, bytes.length > MAX_TEXT_BYTES);
            return;
        }
        fallback.run();
    }

    /** 文本预览：AlertDialog 内包一层 ScrollView + 可选中 TextView。 */
    private static void showText(Context context, byte[] bytes, String name, boolean truncated) {
        int len = Math.min(bytes.length, MAX_TEXT_BYTES);
        String raw = new String(bytes, 0, len, java.nio.charset.StandardCharsets.UTF_8);
        // \r\n 统一成 \n；单独的 \r 也折行，避免老 txt 出现整行覆盖。
        // 实际给 lambda 用的是这里的 final 变量（raw 被赋过值，lambda 引用会报错）
        final String content = raw.replace("\r\n", "\n").replace('\r', '\n');

        TextView tv = new TextView(context);
        tv.setText(content);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(12f);
        tv.setTextColor(0xFF333333);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(tv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout host = new LinearLayout(context);
        host.setOrientation(LinearLayout.VERTICAL);

        TextView hint = new TextView(context);
        hint.setText(truncated ? "内容过长，仅显示前 256 KB" : "正文可长按选中复制");
        hint.setTextSize(11f);
        hint.setTextColor(0xFF2196F3);
        hint.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), 0);
        host.addView(hint, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        host.addView(scroll);

        new AlertDialog.Builder(context)
                .setTitle("预览：" + name)
                .setView(host)
                .setPositiveButton("复制全部", (d, w) -> copyText(context, content))
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 图片预览：ImageView 包 ScrollView（图片较高时可纵向滚动）。 */
    private static void showImage(Context context, Bitmap bitmap) {
        ImageView iv = new ImageView(context);
        iv.setImageBitmap(bitmap);
        iv.setAdjustViewBounds(true);
        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int maxW = (int) (screenW * 0.9f);
        FrameLayout host = new FrameLayout(context);
        int pad = dp(context, 16);
        host.setPadding(pad, pad, pad, pad);
        host.addView(iv, new ViewGroup.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("图片预览")
                .setView(host)
                .setNegativeButton("关闭", null)
                .create();
        dialog.show();
        // AlertDialog 默认宽度不够放超宽图，覆盖到 0.92 屏宽
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout((int) (screenW * 0.94f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static void showImage(Context context, byte[] bytes, String name) {
        Bitmap bmp = decodeScaled(bytes);
        if (bmp == null) {
            fail(context, "图片解析失败（可能是 HEIC 等该设备不支持的格式）");
            return;
        }
        showImage(context, bmp);
    }

    // ---------------------------------------------------------------- 系统应用打开

    private static void openWithSystem(Context context, File file) {
        Uri uri = androidx.core.content.FileProvider.getUriForFile(
                context, context.getPackageName() + ".fileprovider", file);
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(file.getName()))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(view);
        } catch (ActivityNotFoundException e) {
            fail(context, "手机里没有能打开 " + extOf(file.getName())
                    + " 类型的应用，可先安装 WPS / 视频播放器等试试");
        } catch (Exception e) {
            fail(context, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static String mimeOf(String name) {
        String ext = extOf(name);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime == null ? "application/octet-stream" : mime.toLowerCase(Locale.US);
    }

    // ---------------------------------------------------------------- 小工具

    private static boolean isImageExt(String name) {
        return IMAGE_EXTS.contains(extOf(name));
    }

    /**
     * 判断数据是否适合按文本展示：扩展名在文本集合里直接判是；
     * 图片扩展名判否；其它类型若体积小于 64 KB 且前 2 KB 内没有 NUL /
     * 不可打印控制字符，也当成文本（很多无后缀的代码 / 脚本 / 备注文件）。
     */
    private static boolean isLikelyText(String ext, byte[] bytes) {
        if (TEXT_EXTS.contains(ext)) return true;
        if (IMAGE_EXTS.contains(ext)) return false;
        if (ext.isEmpty() || bytes.length <= 64 * 1024) {
            int head = Math.min(bytes.length, 2048);
            for (int i = 0; i < head; i++) {
                byte b = bytes[i];
                if (b == 0x00) return false;
                // \t \n \v \f \r 之外的 ASCII 控制字符视为二进制
                if ((b < 0x09) || (b > 0x0D && b < 0x20)) return false;
            }
            return true;
        }
        return false;
    }

    /** 按屏幕尺寸等比解码，防止几千万像素的照片直接 OOM。 */
    private static Bitmap decodeScaled(byte[] bytes) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
            bounds.inSampleSize = 1;
            while (maxSide / bounds.inSampleSize > 2048) {
                bounds.inSampleSize *= 2;
            }
            bounds.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读文件到内存，超过 cap 字节即停（文本预览够用，且异常体积也看得出来）。 */
    private static byte[] readCapped(File file, int cap) throws Exception {
        java.io.FileInputStream fin = new java.io.FileInputStream(file);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fin.read(buf)) != -1) {
                if (bos.size() + n > cap) {
                    bos.write(buf, 0, cap - bos.size());
                    return bos.toByteArray();
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            fin.close();
        }
    }

    /** 预览缓存文件：preview_时间戳_原名（存在 App 私有 cache，卸载/清数据即清）。 */
    private static File newTempFile(Context context, String name) {
        String safe = name == null ? "file" : name.replace('/', '_');
        return new File(context.getCacheDir(), "preview_" + System.currentTimeMillis() + "_" + safe);
    }

    private static void copyText(Context context, String text) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(android.content.ClipData.newPlainText("内容", text));
        Toast.makeText(context, "已复制全部内容", Toast.LENGTH_SHORT).show();
    }

    private static void fail(Context context, String message) {
        MAIN.post(() -> new AlertDialog.Builder(context)
                .setTitle("预览失败")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show());
    }

    private static void toast(Context context, String message) {
        MAIN.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    private static String extOf(String name) {
        if (name == null) return "";
        int idx = name.lastIndexOf('.');
        return idx < 0 ? "" : name.substring(idx + 1).toLowerCase(Locale.US);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
