package com.frwuc.s3fileuploader;

import java.io.File;
import java.util.List;

/**
 * 同步扫描的纯逻辑：目录递归、相对路径、云端 key 规则。
 *
 * <p>刻意不依赖任何 Android API，方便在 JVM 单测里直接验证
 * （{@link UploadService} 里有 {@code Environment} 这类 Android 静态调用，单测无法加载）。
 */
public final class ScanRules {

    /** 递归扫描的最大层级，防止异常目录结构（软链环等）导致无限深入 */
    public static final int MAX_SCAN_DEPTH = 16;

    private ScanRules() {
    }

    /**
     * 递归收集目录下的所有文件（含子目录），跳过以 "." 开头的隐藏项
     * （.thumbnails / .nomedia / .trash 之类）。
     *
     * @param depth 当前层级，超过 {@link #MAX_SCAN_DEPTH} 不再深入
     */
    public static void collectFiles(File dir, List<File> out, int depth) {
        if (depth > MAX_SCAN_DEPTH) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = child.getName();
            if (name.startsWith(".")) continue;
            if (child.isDirectory()) {
                collectFiles(child, out, depth + 1);
            } else if (child.isFile()) {
                out.add(child);
            }
        }
    }

    /** 文件相对扫描根目录的所在子目录，以 "/" 结尾；直接在根目录下时返回空串。 */
    public static String relativeDirOf(File root, File file) {
        String rel = file.getAbsolutePath();
        String rootPath = root.getAbsolutePath();
        if (rel.startsWith(rootPath)) {
            rel = rel.substring(rootPath.length());
        }
        while (rel.startsWith("/") || rel.startsWith("\\")) {
            rel = rel.substring(1);
        }
        // 兼容 Windows 的反斜杠（JVM 单测跑在 PC 上），统一成云端 key 使用的 "/"
        int idx = Math.max(rel.lastIndexOf('/'), rel.lastIndexOf('\\'));
        return idx < 0 ? "" : rel.substring(0, idx + 1).replace('\\', '/');
    }

    /**
     * 拼云端 key：{@code 云端目录/相对子目录/时间戳_原文件名}。
     *
     * <p>时间戳前缀避免不同目录下的同名文件互相覆盖；原名保留，便于在云端辨认。
     */
    public static String buildRemoteKey(String prefix, String relDir, String fileName) {
        StringBuilder sb = new StringBuilder();
        if (prefix != null && !prefix.isEmpty()) sb.append(prefix).append('/');
        if (relDir != null && !relDir.isEmpty()) sb.append(relDir);
        sb.append(System.currentTimeMillis()).append('_').append(fileName);
        return sb.toString();
    }
}
