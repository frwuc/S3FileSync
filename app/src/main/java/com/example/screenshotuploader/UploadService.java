package com.example.screenshotuploader;

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
import java.util.HashSet;
import java.util.Set;

public class UploadService extends Service {

    public static final String ACTION_UPLOAD = "com.example.screenshotuploader.ACTION_UPLOAD";
    private static final String TAG = "UploadService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "uploader_channel";

    private static final String[] SCREENSHOT_DIRS = {
            Environment.getExternalStorageDirectory() + "/DCIM/Screenshots",
            Environment.getExternalStorageDirectory() + "/Pictures/Screenshots",
            "/sdcard/DCIM/Screenshots",
            "/sdcard/Pictures/Screenshots"
    };

    private File uploadedRecordFile;
    private Set<String> uploadedSet = new HashSet<>();

    @Override
    public void onCreate() {
        super.onCreate();
        uploadedRecordFile = new File(getFilesDir(), "uploaded.txt");
        loadUploadedSet();
        startForeground(NOTIFICATION_ID, createNotification("正在执行定时上传任务..."));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_UPLOAD.equals(intent.getAction())) {
            new Thread(() -> {
                try {
                    performUpload();
                } finally {
                    stopForeground(true);
                    stopSelf();
                }
            }).start();
        }
        return START_NOT_STICKY;
    }

    private void performUpload() {
        Log.d(TAG, "开始执行定时上传任务");
        int success = 0, fail = 0, skip = 0;

        QiniuUploader uploader = new QiniuUploader(this);

        for (String dirPath : SCREENSHOT_DIRS) {
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
                String key = QiniuConfig.UPLOAD_PREFIX + "/" + System.currentTimeMillis() + "_" + fileName;

                Log.d(TAG, "准备上传: " + fileName);

                uploader.upload(absPath, key, new QiniuUploader.Callback() {
                    @Override
                    public void onSuccess(String remoteKey, String url) {
                        uploadedSet.add(absPath);
                        saveUploadedPath(absPath);
                        success++;
                        Log.d(TAG, "上传成功: " + fileName + " -> " + url);
                    }

                    @Override
                    public void onFail(String filePath, int code, String error) {
                        fail++;
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

        Log.d(TAG, "上传任务完成: 成功=" + success + ", 失败=" + fail + ", 跳过=" + skip);
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
                .setContentTitle("截图定时上传")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "截图上传服务", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
