package com.frwuc.s3filesync;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

public class Prefs {
    private static final String NAME = "s3filesync_prefs";

    public static final String KEY_ACCESS_KEY = "access_key";
    public static final String KEY_SECRET_KEY = "secret_key";
    public static final String KEY_BUCKET = "bucket";
    public static final String KEY_DOMAIN = "domain";
    public static final String KEY_TIMES = "upload_times";
    /** 云端目录：同步到七牛 bucket 下的哪个目录（key 前缀） */
    public static final String KEY_QINIU_DIR = "qiniu_dir";
    /** 本地文件目录：上传时扫描哪个目录 / 下载时落到哪个目录 */
    public static final String KEY_LOCAL_DIR = "local_dir";

    /** 打开 App 界面时是否立即自动同步一次 */
    public static final String KEY_AUTO_SYNC_ON_OPEN = "auto_sync_on_open";

    /** 同步方向：存 {@link SyncMode#name()}，默认「仅上传」 */
    public static final String KEY_SYNC_MODE = "sync_mode";

    /** 浏览列表排序方式：存 {@link BrowseSort#name()}，默认「时间 ↓」（新到旧） */
    public static final String KEY_BROWSE_SORT = "browse_sort";

    /** 读取同步方向；未设置 / 值异常时回落到 SyncMode.DEFAULT。 */
    public static SyncMode getSyncMode(Context context) {
        return SyncMode.fromName(getString(context, KEY_SYNC_MODE, null));
    }

    public static void putSyncMode(Context context, SyncMode mode) {
        putString(context, KEY_SYNC_MODE, (mode == null ? SyncMode.DEFAULT : mode).name());
    }

    /** 读取浏览排序方式；未设置 / 值异常时回落到 BrowseSort.DEFAULT。 */
    public static BrowseSort getBrowseSort(Context context) {
        return BrowseSort.fromName(getString(context, KEY_BROWSE_SORT, null));
    }

    public static void putBrowseSort(Context context, BrowseSort sort) {
        putString(context, KEY_BROWSE_SORT, (sort == null ? BrowseSort.DEFAULT : sort).name());
    }

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
