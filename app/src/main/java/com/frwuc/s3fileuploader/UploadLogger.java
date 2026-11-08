package com.frwuc.s3fileuploader;

import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 上传记录与执行日志的本地记录器。
 * - uploads.log：逐条图片上传结果（时间/文件名/云端key/URL/结果）
 * - exec.log：每次执行任务的汇总（时间/成功数/失败数/跳过数）
 * 文件放在应用私有目录 files/ 下，卸载即清除，不占用公共存储。
 */
public class UploadLogger {

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    private final File uploadsFile;
    private final File execFile;

    public UploadLogger(Context context) {
        uploadsFile = new File(context.getFilesDir(), "uploads.log");
        execFile = new File(context.getFilesDir(), "exec.log");
    }

    private static String now() {
        return TS.format(new Date());
    }

    /** 追加一行文本到指定文件（存在则续写）。 */
    private void append(File file, String line) {
        try (FileWriter fw = new FileWriter(file, true)) {
            fw.write(line + "\n");
        } catch (IOException ignored) {
        }
    }

    /** 单行日志字段转义：防止原始文件名/服务端错误伪造记录或扰乱栏位解析。 */
    private static String field(String value) {
        if (value == null) return "";
        String escaped = value.replace("\\", "\\\\")
                .replace("\r", "\\r").replace("\n", "\\n")
                .replace("|", "\\|");
        return escaped.length() > 4096 ? escaped.substring(0, 4096) + "..." : escaped;
    }

    /** 记录单张图片上传成功。 */
    public void logUploadSuccess(String fileName, String key, String url) {
        append(uploadsFile, now() + " | 成功 | " + field(fileName)
                + " | " + field(key) + " | " + field(url));
    }

    /** 记录单张图片上传失败。 */
    public void logUploadFail(String fileName, int code, String error) {
        append(uploadsFile, now() + " | 失败 | " + field(fileName)
                + " | code=" + code + " | " + field(error));
    }

    /** 记录一次任务执行的汇总结果。 */
    public void logExecution(int success, int fail, int skip) {
        append(execFile, now() + " | 完成 | 成功=" + success
                + " 失败=" + fail + " 跳过=" + skip);
    }

    /** 记录一次任务执行的开始。 */
    public void logExecutionStart() {
        append(execFile, now() + " | 开始 | 触发上传任务");
    }

    /** 记录任意一行运行日志。 */
    public void logLine(String msg) {
        append(execFile, now() + " | 日志 | " + field(msg));
    }

    /** 读取某个日志文件的最近 limit 行（按时间倒序，最新的在前）。 */
    public List<String> readRecent(File file, int limit) {
        List<String> lines = new ArrayList<>();
        if (!file.exists() || limit <= 0) return lines;
        // 只保留末尾 limit 行，避免日志长期增长后一次性把全文件读进内存。
        java.util.LinkedList<String> tail = new java.util.LinkedList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                tail.add(line);
                if (tail.size() > limit) tail.removeFirst();
            }
        } catch (IOException ignored) {
        }
        lines.addAll(tail);
        java.util.Collections.reverse(lines);
        return lines;
    }

    /** 最近的上传记录。 */
    public List<String> readRecentUploads(int limit) {
        return readRecent(uploadsFile, limit);
    }

    /** 最近的执行日志。 */
    public List<String> readRecentExec(int limit) {
        return readRecent(execFile, limit);
    }

    /** 清空「日志记录」（逐文件上传明细）。 */
    public void clearUploads() {
        try {
            if (uploadsFile.exists()) uploadsFile.delete();
        } catch (Exception ignored) {
        }
    }

    /** 清空「执行记录」（每次任务汇总）。 */
    public void clearExec() {
        try {
            if (execFile.exists()) execFile.delete();
        } catch (Exception ignored) {
        }
    }

    /** 清空所有日志。 */
    public void clearAll() {
        try {
            if (uploadsFile.exists()) uploadsFile.delete();
            if (execFile.exists()) execFile.delete();
        } catch (Exception ignored) {
        }
    }
}
