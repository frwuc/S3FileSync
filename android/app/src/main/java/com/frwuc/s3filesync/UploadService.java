package com.frwuc.s3filesync;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 前台同步服务：按「设置 → 同步方向」执行一次同步。
 *
 * <p>三种方向（{@link SyncMode}）：
 * <ul>
 *   <li><b>仅上传</b>：扫描本地目录，把新增文件传到云端目录；</li>
 *   <li><b>仅下载</b>：递归列举云端目录，把新增对象下载到本地目录；</li>
 *   <li><b>双向</b>：先上传再下载，两端新增文件互相补齐。</li>
 * </ul>
 *
 * <p>两端都做增量：{@code uploaded.txt} 记已上传的本地绝对路径，
 * {@code downloaded.txt} 记已下载的云端 key。上传成功时顺手把该 key 记进下载记录，
 * 下载成功时顺手把该本地路径记进上传记录——这样双向同步不会把刚传上去的文件
 * 又拉回来、也不会把刚下载的文件又传回去（避免 ping-pong 与互相覆盖）。
 *
 * <p>文件名规则：上传 key 为「云端目录/相对子目录/原文件名」，下载落盘为
 * 「本地目录/相对子目录/云端原文件名」，两端都不加时间戳或任何前缀
 * （见 {@link ScanRules#buildRemoteKey} / {@link ScanRules#localTargetFile}）。
 * 本地已存在的目标文件一律不覆盖。
 */
public class UploadService extends Service {

    /** 新入口：按配置方向执行一次同步 */
    public static final String ACTION_SYNC = "com.frwuc.s3filesync.ACTION_SYNC";
    /**
     * 旧版遗留 action，保留兼容：升级前残留的 Intent 仍能触发同步
     * （现在语义同样是「按方向同步」，不再是写死的上传）。
     */
    public static final String ACTION_UPLOAD = "com.frwuc.s3filesync.ACTION_UPLOAD";

    private static final String TAG = "UploadService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "uploader_channel";

    /** 单个文件等待上传回调的上限，超时按失败计，避免后台线程永久阻塞。 */
    private static final int UPLOAD_TIMEOUT_SECONDS = 30 * 60;

    private static final String[] DEFAULT_SCAN_DIRS = {
            Environment.getExternalStorageDirectory() + "/DCIM/Screenshots",
            Environment.getExternalStorageDirectory() + "/Pictures/Screenshots",
            "/sdcard/DCIM/Screenshots",
            "/sdcard/Pictures/Screenshots"
    };

    private File uploadedRecordFile;
    private File downloadedRecordFile;
    private final Set<String> uploadedSet = new HashSet<>();
    private final Set<String> downloadedSet = new HashSet<>();
    private boolean uploadedRecordsReady;
    private boolean downloadedRecordsReady;
    private UploadLogger logger;

    /**
     * 当前是否有一次同步正在执行。
     *
     * <p>进程级标记，供「打开 App 自动同步」判断是否重复发起，也避免手动点击与
     * 自动触发、定时任务之间并发跑两份同步。
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
        downloadedRecordFile = new File(getFilesDir(), "downloaded.txt");
        uploadedRecordsReady = loadRecordSet(uploadedRecordFile, uploadedSet);
        downloadedRecordsReady = loadRecordSet(downloadedRecordFile, downloadedSet);
        logger = new UploadLogger(this);
        startForeground(NOTIFICATION_ID, createNotification("正在执行同步任务..."));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (!ACTION_SYNC.equals(action) && !ACTION_UPLOAD.equals(action)) {
            // 非同步指令（如系统重建服务）：不常驻，直接结束，避免留下空的前台通知
            stopSelf();
            return START_NOT_STICKY;
        }
        // 已有任务在跑：忽略本次请求，保证同一时刻只有一份同步
        if (!RUNNING.compareAndSet(false, true)) {
            Log.d(TAG, "已有同步任务在执行，忽略本次启动请求");
            return START_NOT_STICKY;
        }
        new Thread(() -> {
            try {
                performSync();
            } finally {
                RUNNING.set(false);
                stopForeground(true);
                stopSelf();
            }
        }).start();
        return START_NOT_STICKY;
    }

    /** 按配置方向分派；双向时先上传再下载，各自写一条执行汇总。 */
    private void performSync() {
        SyncMode mode = Prefs.getSyncMode(this);
        Log.d(TAG, "开始执行同步任务，方向=" + mode.label);
        logger.logExecutionStart(mode.label);

        if (!uploadedRecordsReady || !downloadedRecordsReady) {
            // 已有记录读取失败时若按空集合继续，会把全部历史文件重新传一遍。
            logger.logLine("历史同步记录读取失败，为避免重复传输，本次任务已中止");
            logger.logExecution("同步", 0, 1, 0);
            return;
        }
        if (mode.doUpload()) performUpload();
        if (mode.doDownload()) performDownload();
    }

    // ---------------------------------------------------------------- 上传

    private void performUpload() {
        int skip = 0;
        // 匿名内部类(Callback)里要修改计数器：用 final int[] 做可变计数器
        final int[] success = {0};
        final int[] fail = {0};

        QiniuUploader uploader = new QiniuUploader(this);

        // 云端目录：从配置读取，空则放根目录
        String qiniuDir = Prefs.getString(this, Prefs.KEY_QINIU_DIR, QiniuConfig.UPLOAD_PREFIX);
        final String prefix = ScanRules.normalizeCloudDir(qiniuDir);

        // 本地目录：优先用用户设置的单一目录；未设置则回退到默认几个目录
        List<String> dirsToScan = new ArrayList<>();
        String customDir = Prefs.getString(this, Prefs.KEY_LOCAL_DIR, "");
        if (customDir != null && !customDir.trim().isEmpty()) {
            dirsToScan.add(customDir.trim());
        } else {
            dirsToScan.addAll(Arrays.asList(DEFAULT_SCAN_DIRS));
        }

        for (String dirPath : dirsToScan) {
            File root = new File(dirPath);
            if (!root.exists() || !root.isDirectory()) continue;

            // 递归收集整棵目录树下的文件（不限制类型）
            List<File> files = new ArrayList<>();
            ScanRules.collectFiles(root, files, 0);

            for (File file : files) {
                String absPath = file.getAbsolutePath();
                if (uploadedSet.contains(absPath)) {
                    skip++;
                    continue;
                }
                // 只跳过空文件（多为写入中断留下的残骸）
                if (file.length() == 0) continue;

                String fileName = file.getName();
                // 云端 key：云端目录/相对子目录/原文件名（无时间戳、无前缀）
                String key = ScanRules.buildRemoteKey(prefix, ScanRules.relativeDirOf(root, file), fileName);

                Log.d(TAG, "准备上传: " + fileName);

                final CountDownLatch completed = new CountDownLatch(1);
                uploader.upload(absPath, key, new QiniuUploader.Callback() {
                    @Override
                    public void onSuccess(String remoteKey, String url) {
                        try {
                            if (uploadedSet.add(absPath) && !appendRecord(uploadedRecordFile, absPath)) {
                                logger.logLine("上传记录保存失败，重启后可能重复上传：" + fileName);
                            }
                            // 双向同步：这个文件刚由本地传上去，别在下载阶段又拉回来
                            if (downloadedSet.add(remoteKey)
                                    && !appendRecord(downloadedRecordFile, remoteKey)) {
                                logger.logLine("下载记录保存失败，重启后可能重复下载：" + fileName);
                            }
                            success[0]++;
                            logger.logUploadSuccess(fileName, remoteKey, url);
                            Log.d(TAG, "上传成功: " + fileName + " -> " + url);
                        } finally {
                            completed.countDown();
                        }
                    }

                    @Override
                    public void onFail(String filePath, int code, String error) {
                        try {
                            fail[0]++;
                            logger.logUploadFail(fileName, code, error);
                            Log.e(TAG, "上传失败: " + fileName + " code=" + code + " err=" + error);
                        } finally {
                            completed.countDown();
                        }
                    }
                });

                try {
                    // SDK 通过异步回调报告结果；必须等回调后再累计汇总并停止服务。
                    if (!completed.await(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        fail[0]++;
                        logger.logUploadFail(fileName, -1,
                                "等待上传结果超时（" + UPLOAD_TIMEOUT_SECONDS + " 秒）");
                        Log.e(TAG, "上传等待超时: " + fileName);
                    }
                    Thread.sleep(800); // 保留请求间隔，避免瞬时打满上传接口
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.logLine("上传等待被中断，本次上传已中止");
                    logger.logExecution("上传", success[0], fail[0], skip);
                    return;
                }
            }
        }

        Log.d(TAG, "上传完成: 成功=" + success[0] + ", 失败=" + fail[0] + ", 跳过=" + skip);
        logger.logExecution("上传", success[0], fail[0], skip);
    }

    // ---------------------------------------------------------------- 下载

    private void performDownload() {
        int skip = 0;
        final int[] success = {0};
        final int[] fail = {0};

        String ak = Prefs.getString(this, Prefs.KEY_ACCESS_KEY, QiniuConfig.ACCESS_KEY);
        String sk = Prefs.getString(this, Prefs.KEY_SECRET_KEY, QiniuConfig.SECRET_KEY);
        String bucket = Prefs.getString(this, Prefs.KEY_BUCKET, QiniuConfig.BUCKET);
        String domain = Prefs.getString(this, Prefs.KEY_DOMAIN, QiniuConfig.DOMAIN);
        String localDir = Prefs.getString(this, Prefs.KEY_LOCAL_DIR, "");
        localDir = localDir == null ? "" : localDir.trim();
        String cloudDir = ScanRules.normalizeCloudDir(
                Prefs.getString(this, Prefs.KEY_QINIU_DIR, QiniuConfig.UPLOAD_PREFIX));

        if (isEmpty(ak) || isEmpty(sk) || isEmpty(bucket)) {
            logger.logLine("跳过下载：请先在「设置」填写七牛云 AK / SK / Bucket");
            logger.logExecution("下载", 0, 1, 0);
            return;
        }
        if (localDir.isEmpty()) {
            logger.logLine("跳过下载：请先在「设置」填写本地文件目录（下载目标目录）");
            logger.logExecution("下载", 0, 1, 0);
            return;
        }

        List<BrowseEntry> objects;
        try {
            // 不带 delimiter 递归列举，才能把云端目录下的子目录一起下载下来
            objects = QiniuBucketApi.listAll(ak, sk, bucket, cloudDir);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            logger.logLine("列举云端文件失败，下载已中止：" + msg);
            logger.logExecution("下载", 0, 1, 0);
            return;
        }

        for (BrowseEntry object : objects) {
            final String key = object.path;
            final String displayName = object.name;
            if (downloadedSet.contains(key)) {
                skip++;
                continue;
            }
            // key -> 本地路径：文件名与云端完全一致；不安全 key（../、隐藏项）一律跳过
            File target = ScanRules.localTargetFile(localDir, cloudDir, key);
            if (target == null) {
                skip++;
                continue;
            }
            if (target.exists()) {
                // 本地已有同名文件：不覆盖（保护本地可能更新的版本），直接记为已同步
                markDownloaded(key, target);
                skip++;
                continue;
            }

            try {
                QiniuBucketApi.downloadToFile(ak, sk, domain, key, target);
                markDownloaded(key, target);
                success[0]++;
                logger.logDownloadSuccess(displayName, key, target.getAbsolutePath());
                Log.d(TAG, "下载成功: " + key + " -> " + target.getAbsolutePath());
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                fail[0]++;
                logger.logDownloadFail(displayName, -1, msg);
                Log.e(TAG, "下载失败: " + key + " err=" + msg);
            }

            try {
                Thread.sleep(100); // 轻微限流，避免连续下载打满网络
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.logLine("下载等待被中断，本次下载已中止");
                logger.logExecution("下载", success[0], fail[0], skip);
                return;
            }
        }

        Log.d(TAG, "下载完成: 成功=" + success[0] + ", 失败=" + fail[0] + ", 跳过=" + skip);
        logger.logExecution("下载", success[0], fail[0], skip);
    }

    /**
     * 记一次「已下载」：云端 key 写入下载记录，本地路径补进上传记录。
     * 后者让双向同步不会把刚下载的文件再传回云端。
     */
    private void markDownloaded(String key, File localFile) {
        if (downloadedSet.add(key) && !appendRecord(downloadedRecordFile, key)) {
            logger.logLine("下载记录保存失败，重启后可能重复下载：" + key);
        }
        String absPath = localFile.getAbsolutePath();
        if (uploadedSet.add(absPath) && !appendRecord(uploadedRecordFile, absPath)) {
            logger.logLine("同步记录保存失败，重启后可能重复上传：" + localFile.getName());
        }
    }

    // ---------------------------------------------------------------- 增量记录

    /** 读取一行一条的记录文件；文件不存在视为空记录（成功），读取失败返回 false。 */
    private boolean loadRecordSet(File file, Set<String> out) {
        out.clear();
        if (!file.exists()) return true;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isEmpty()) out.add(line);
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "读取同步记录失败：" + file.getName(), e);
            return false;
        }
    }

    private boolean appendRecord(File file, String value) {
        try (FileWriter fw = new FileWriter(file, true)) {
            fw.write(value + "\n");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "保存同步记录失败：" + file.getName(), e);
            return false;
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ---------------------------------------------------------------- 通知

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
                    CHANNEL_ID, "文件同步服务", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
