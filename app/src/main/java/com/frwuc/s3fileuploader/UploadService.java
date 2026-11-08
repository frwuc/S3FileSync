package com.frwuc.s3fileuploader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class UploadService extends Service {

    public static final String ACTION_UPLOAD = "com.frwuc.s3fileuploader.ACTION_UPLOAD";
    private static final String TAG = "UploadService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "uploader_channel";

    private static final String[] DEFAULT_SCAN_DIRS = {
            Environment.getExternalStorageDirectory() + "/DCIM/Screenshots",
            Environment.getExternalStorageDirectory() + "/Pictures/Screenshots",
            "/sdcard/DCIM/Screenshots",
            "/sdcard/Pictures/Screenshots"
    };

    private File uploadedRecordFile;
    private Set<String> uploadedSet = new HashSet<>();
    private UploadLogger logger;

    /**
     * 当前是否有一次上传正在执行。
     *
     * <p>进程级标记，供「打开 App 自动同步」判断是否重复发起，也避免手动点击与
     * 自动触发、定时任务之间并发跑两份上传。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static boolean isRunning() {
        return RUNNING.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        uploadedRecordFile = new File(getFilesDir(), "uploaded.txt");
        loadUploadedSet();
        logger = new UploadLogger(this);
        startForeground(NOTIFICATION_ID, createNotification("正在执行定时上传任务..."));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_UPLOAD.equals(intent.getAction())) {
            // 非上传指令（如系统重建服务）：不常驻，直接结束，避免留下空的前台通知
            stopSelf();
            return START_NOT_STICKY;
        }
        // 已有任务在跑：忽略本次请求，保证同一时刻只有一份上传
        if (!RUNNING.compareAndSet(false, true)) {
            Log.d(TAG, "已有上传任务在执行，忽略本次启动请求");
            return START_NOT_STICKY;
        }
        new Thread(() -> {
            try {
                performUpload();
            } finally {
                RUNNING.set(false);
                stopForeground(true);
                stopSelf();
            }
        }).start();
        return START_NOT_STICKY;
    }

    private void performUpload() {
        Log.d(TAG, "开始执行定时上传任务");
        int skip = 0;
        // 匿名内部类(Callback)里要修改计数器：局部变量须为 final/有效 final，
        // 用 final int[] 做可变计数器（数组引用 final，元素可累加）
        final int[] success = {0};
        final int[] fail = {0};

        QiniuUploader uploader = new QiniuUploader(this);

        logger.logExecutionStart();

        // 云端目录：从配置读取（用户可在 App 设置），空则放根目录
        String qiniuDir = Prefs.getString(this, Prefs.KEY_QINIU_DIR, QiniuConfig.UPLOAD_PREFIX);
        if (qiniuDir == null) qiniuDir = "";
        while (qiniuDir.startsWith("/")) qiniuDir = qiniuDir.substring(1);
        while (qiniuDir.endsWith("/")) qiniuDir = qiniuDir.substring(0, qiniuDir.length() - 1);
        final String prefix = qiniuDir; // 供内部类使用

        // 本地目录：优先用用户设置的单一目录；未设置则回退到默认几个目录
        List<String> dirsToScan = new ArrayList<>();
        String customDir = Prefs.getString(this, Prefs.KEY_LOCAL_DIR, "");
        if (customDir != null && !customDir.trim().isEmpty()) {
            dirsToScan.add(customDir.trim());
        } else {
            dirsToScan.addAll(Arrays.asList(DEFAULT_SCAN_DIRS));
        }

        for (String dirPath : dirsToScan) {
            File dir = new File(dirPath);
            if (!dir.exists() || !dir.isDirectory()) continue;

            File[] files = dir.listFiles((d, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                        || lower.endsWith(".png") || lower.endsWith(".webp");
            });

            if (files == null) continue;

            for (File file : files) {
                String absPath = file.getAbsolutePath();
                if (uploadedSet.contains(absPath)) {
                    skip++;
                    continue;
                }
                if (file.length() < 1024) continue;

                String fileName = file.getName();
                // 云端目录为空时直接放 bucket 根目录，否则拼 前缀/时间戳_文件名
                String key = prefix.isEmpty()
                        ? System.currentTimeMillis() + "_" + fileName
                        : prefix + "/" + System.currentTimeMillis() + "_" + fileName;

                Log.d(TAG, "准备上传: " + fileName);

                uploader.upload(absPath, key, new QiniuUploader.Callback() {
                    @Override
                    public void onSuccess(String remoteKey, String url) {
                        uploadedSet.add(absPath);
                        saveUploadedPath(absPath);
                        success[0]++;
                        logger.logUploadSuccess(fileName, remoteKey, url);
                        Log.d(TAG, "上传成功: " + fileName + " -> " + url);
                    }

                    @Override
                    public void onFail(String filePath, int code, String error) {
                        fail[0]++;
                        logger.logUploadFail(fileName, code, error);
                        Log.e(TAG, "上传失败: " + fileName + " code=" + code + " err=" + error);
                    }
                });

                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        Log.d(TAG, "上传任务完成: 成功=" + success[0] + ", 失败=" + fail[0] + ", 跳过=" + skip);
        logger.logExecution(success[0], fail[0], skip);
    }

    private void loadUploadedSet() {
        uploadedSet.clear();
        try (BufferedReader br = new BufferedReader(new FileReader(uploadedRecordFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isEmpty()) uploadedSet.add(line);
            }
        } catch (IOException ignored) {
        }
    }

    private void saveUploadedPath(String path) {
        try (FileWriter fw = new FileWriter(uploadedRecordFile, true)) {
            fw.write(path + "\n");
        } catch (IOException e) {
            Log.e(TAG, "保存上传记录失败", e);
        }
    }

    private Notification createNotification(String content) {
        createChannel();
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "文件上传服务", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
