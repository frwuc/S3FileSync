package com.frwuc.s3fileuploader;

import android.content.Context;
import android.net.Uri;
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
        if (!file.exists() || !file.isFile()) {
            if (callback != null) callback.onFail(localPath, -1, "文件不存在或不是普通文件");
            return;
        }

        try {
            // Android SDK 不含 Auth 类，本地按官方算法签发上传凭证（见 QiniuToken）
            String token = QiniuToken.uploadToken(accessKey, secretKey, bucket, key, 3600);

            uploadManager.put(localPath, key, token, new UpCompletionHandler() {
                @Override
                public void complete(String key, ResponseInfo info, JSONObject response) {
                    if (info.isOK()) {
                        Log.d(TAG, "上传成功: " + key);
                        if (callback != null) callback.onSuccess(key, buildFileUrl(key));
                    } else {
                        Log.e(TAG, "上传失败: " + info.error + " code=" + info.statusCode);
                        if (callback != null) callback.onFail(localPath, info.statusCode, info.error);
                    }
                }
            }, null);
        } catch (Exception e) {
            // put()/签名也可能同步抛异常；统一走失败回调，保证调用方能结束等待并记录。
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Log.e(TAG, "上传请求启动失败", e);
            if (callback != null) callback.onFail(localPath, -1, message);
        }
    }

    /** 日志中的访问 URL：保留 key 的目录分隔符，编码空格、#、?、% 等路径特殊字符。 */
    private String buildFileUrl(String key) {
        String base = domain == null ? "" : domain.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String encodedKey = Uri.encode(key, "/");
        return base.isEmpty() ? encodedKey : base + "/" + encodedKey;
    }
}
