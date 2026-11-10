package com.frwuc.s3filesync;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        try {
            // 以用户所选目录的 canonical path 为边界；目录本身的符号链接是用户明确选择，允许。
            File canonicalRoot = dir.getCanonicalFile();
            collectFiles(canonicalRoot, dir, out, depth, new HashSet<String>());
        } catch (IOException ignored) {
            // 无法规范化根目录时宁可不扫描，也不能越过用户选择的目录上传。
        }
    }

    private static void collectFiles(File canonicalRoot, File dir, List<File> out, int depth,
                                     Set<String> visitedDirs) {
        if (depth > MAX_SCAN_DEPTH) return;
        try {
            File canonicalDir = dir.getCanonicalFile();
            if (!isWithinRoot(canonicalRoot, canonicalDir)) return;
            // 同一目录可能被多个符号链接指到；去重同时阻断链接环。
            if (!visitedDirs.add(canonicalDir.getPath())) return;
        } catch (IOException e) {
            return;
        }

        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = child.getName();
            if (name.startsWith(".")) continue;
            try {
                // isDirectory()/isFile() 会跟随符号链接，因此必须在判断类型前校验真实目标。
                if (!isWithinRoot(canonicalRoot, child.getCanonicalFile())) continue;
                if (child.isDirectory()) {
                    collectFiles(canonicalRoot, child, out, depth + 1, visitedDirs);
                } else if (child.isFile()) {
                    // 保留原始路径，避免改变既有 uploaded.txt 记录和云端相对目录。
                    out.add(child);
                }
            } catch (IOException ignored) {
                // 单个无法解析的条目不阻断其他正常文件。
            }
        }
    }

    /** canonical 路径边界判断，避免 root2 被误认为 root 的子目录。 */
    static boolean isWithinRoot(File canonicalRoot, File canonicalCandidate) {
        String rootPath = canonicalRoot.getPath();
        String candidatePath = canonicalCandidate.getPath();
        if (candidatePath.equals(rootPath)) return true;
        String rootPrefix = rootPath.endsWith(File.separator)
                ? rootPath : rootPath + File.separator;
        return candidatePath.startsWith(rootPrefix);
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
     * 拼云端 key：{@code 云端目录/相对子目录/原文件名}。
     *
     * <p><b>文件名与本地完全一致，不加时间戳、不加任何前缀</b>；不同目录下的同名文件
     * 由相对子目录区分。上传与下载共用这一套命名，双向同步时才不会来回产生副本，
     * 用户在云端看到的文件名也和手机里一模一样。
     */
    public static String buildRemoteKey(String prefix, String relDir, String fileName) {
        StringBuilder sb = new StringBuilder(normalizeCloudDir(prefix));
        if (relDir != null && !relDir.isEmpty()) {
            String r = relDir.replace('\\', '/');
            if (!r.endsWith("/")) r = r + "/";
            sb.append(r);
        }
        sb.append(fileName);
        return sb.toString();
    }

    /** 云端目录规范化：去首尾斜杠，非空则补一个结尾 "/"，bucket 根目录返回空串。 */
    public static String normalizeCloudDir(String cloudDir) {
        if (cloudDir == null) return "";
        String p = cloudDir.trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "" : p + "/";
    }

    /**
     * 云端 key 去掉所配置云端目录前缀后的相对路径（含子目录），用于下载时还原目录结构。
     * 返回 null 表示 key 不属于该目录或本身不安全，调用方应跳过。
     */
    public static String relativePathOfKey(String cloudDir, String key) {
        if (key == null || key.isEmpty()) return null;
        String dir = normalizeCloudDir(cloudDir);
        String rel = key;
        if (!dir.isEmpty()) {
            if (!rel.startsWith(dir)) return null;
            rel = rel.substring(dir.length());
        }
        if (rel.isEmpty() || rel.endsWith("/")) return null;
        String[] segments = rel.split("/", -1);
        for (String seg : segments) {
            // 空段 / 上跳 / 隐藏项 / Windows 分隔符与盘符一律拒绝，
            // 防止云端恶意 key 通过 ../ 写到本地目录之外。
            if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) return null;
            if (seg.startsWith(".") || seg.indexOf('\\') >= 0
                    || seg.indexOf(':') >= 0 || seg.indexOf('\0') >= 0) return null;
        }
        return rel;
    }

    /**
     * 云端 key -> 本地落盘文件：{@code 本地目录/相对子目录/云端原文件名}，
     * <b>文件名与云端完全一致，不加任何前缀</b>。
     *
     * <p>返回 null 表示该 key 不安全或已越出本地根目录，调用方必须跳过，
     * 不得把它写入磁盘。
     */
    public static File localTargetFile(String localRoot, String cloudDir, String key) {
        if (localRoot == null || localRoot.trim().isEmpty()) return null;
        String rel = relativePathOfKey(cloudDir, key);
        if (rel == null) return null;
        try {
            File root = new File(localRoot.trim());
            File target = new File(root, rel.replace('/', File.separatorChar));
            File canonicalRoot = root.getCanonicalFile();
            File canonicalTarget = target.getCanonicalFile();
            if (canonicalTarget.equals(canonicalRoot)) return null;
            if (!isWithinRoot(canonicalRoot, canonicalTarget)) return null;
            return target;
        } catch (IOException e) {
            return null;
        }
    }
}
