package com.frwuc.s3fileuploader;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

public class Prefs {
    private static final String NAME = "s3_fileuploader_prefs";

    public static final String KEY_ACCESS_KEY = "access_key";
    public static final String KEY_SECRET_KEY = "secret_key";
    public static final String KEY_BUCKET = "bucket";
    public static final String KEY_DOMAIN = "domain";
    public static final String KEY_TIMES = "upload_times";
    /** 云端目录：上传到七牛 bucket 下的哪个目录（key 前缀） */
    public static final String KEY_QINIU_DIR = "qiniu_dir";
    /** 本地文件目录：扫描哪个本地目录下的文件 */
    public static final String KEY_LOCAL_DIR = "local_dir";

    /** 打开 App 界面时是否立即自动同步一次 */
    public static final String KEY_AUTO_SYNC_ON_OPEN = "auto_sync_on_open";

    /**
     * 自动同步的默认值：关闭。
     *
     * <p>默认关闭是有意为之：打开界面不应在用户未预期的情况下消耗流量、上传文件，
     * 需要自动同步的用户可在「设置」里显式打开。关闭状态下任何入口都不会触发同步。
     */
    public static final boolean DEFAULT_AUTO_SYNC_ON_OPEN = false;

    private static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static void putString(Context context, String key, String value) {
        get(context).edit().putString(key, value).apply();
    }

    public static String getString(Context context, String key, String def) {
        return get(context).getString(key, def);
    }

    public static void putStringSet(Context context, String key, Set<String> values) {
        get(context).edit().putStringSet(key, values).apply();
    }

    public static Set<String> getStringSet(Context context, String key, Set<String> def) {
        return get(context).getStringSet(key, def);
    }

    public static void putBoolean(Context context, String key, boolean value) {
        get(context).edit().putBoolean(key, value).apply();
    }

    public static boolean getBoolean(Context context, String key, boolean def) {
        return get(context).getBoolean(key, def);
    }
}
