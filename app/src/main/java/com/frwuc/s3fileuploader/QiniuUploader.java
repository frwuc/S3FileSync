package com.frwuc.s3fileuploader;

import android.content.Context;
import android.util.Log;

import com.qiniu.android.http.ResponseInfo;
import com.qiniu.android.storage.Configuration;
import com.qiniu.android.storage.UpCompletionHandler;
import com.qiniu.android.storage.UploadManager;

import org.json.JSONObject;

import java.io.File;

public class QiniuUploader {
    private static final String TAG = "QiniuUploader";

    private final UploadManager uploadManager;
    private final String accessKey;
    private final String secretKey;
    private final String bucket;
    private final String domain;

    public interface Callback {
        void onSuccess(String remoteKey, String url);
        void onFail(String filePath, int code, String error);
    }

    public QiniuUploader(Context context) {
        this.accessKey = Prefs.getString(context, Prefs.KEY_ACCESS_KEY, QiniuConfig.ACCESS_KEY);
        this.secretKey = Prefs.getString(context, Prefs.KEY_SECRET_KEY, QiniuConfig.SECRET_KEY);
        this.bucket = Prefs.getString(context, Prefs.KEY_BUCKET, QiniuConfig.BUCKET);
        this.domain = Prefs.getString(context, Prefs.KEY_DOMAIN, QiniuConfig.DOMAIN);

        // zone 缺省即 AutoZone（自动匹配区域），旧写法 Zone.autoZone() 在 8.x 中不存在
        Configuration config = new Configuration.Builder()
                .build();
        uploadManager = new UploadManager(config);
    }

    public void upload(String localPath, String key, Callback callback) {
        if (accessKey == null || accessKey.isEmpty()
                || secretKey == null || secretKey.isEmpty()
                || bucket == null || bucket.isEmpty()) {
            if (callback != null) callback.onFail(localPath, -1, "七牛云配置未填写，请先在 App 界面设置 AK/SK/Bucket");
            return;
        }

        File file = new File(localPath);
        if (!file.exists()) {
            if (callback != null) callback.onFail(localPath, -1, "文件不存在");
            return;
        }

        // Android SDK 不含 Auth 类，本地按官方算法签发上传凭证（见 QiniuToken）
        String token = QiniuToken.uploadToken(accessKey, secretKey, bucket, key, 3600);

        uploadManager.put(localPath, key, token, new UpCompletionHandler() {
            @Override
            public void complete(String key, ResponseInfo info, JSONObject response) {
                if (info.isOK()) {
                    Log.d(TAG, "上传成功: " + key);
                    if (callback != null) callback.onSuccess(key, domain + "/" + key);
                } else {
                    Log.e(TAG, "上传失败: " + info.error + " code=" + info.statusCode);
                    if (callback != null) callback.onFail(localPath, info.statusCode, info.error);
                }
            }
        }, null);
    }
}
