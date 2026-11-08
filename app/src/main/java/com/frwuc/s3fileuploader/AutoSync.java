package com.frwuc.s3fileuploader;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「打开 App 时自动同步」开关的读取与触发判定。
 *
 * <p>行为约定：
 * <ul>
 *   <li>默认<b>关闭</b>，见 {@link Prefs#DEFAULT_AUTO_SYNC_ON_OPEN}；</li>
 *   <li>关闭时任何入口都不会发起同步，{@link #onMainEntered(Activity)} 直接返回；</li>
 *   <li>开启后，仅在应用进程启动后<b>首次</b>进入主界面时触发一次；旋转屏幕、
 *       切后台再回来、切换页签都不会重复触发；</li>
 *   <li>配置不完整、缺少存储权限、已有任务在执行时跳过，不会强行上传。</li>
 * </ul>
 */
public final class AutoSync {

    private static final String TAG = "AutoSync";

    /** 等首帧渲染完再发起，避免启动瞬间争抢主线程与磁盘 IO */
    private static final long START_DELAY_MS = 500L;

    /** 每个进程只尝试一次，防止界面重建导致重复上传 */
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean(false);

    private AutoSync() {
    }

    /** 触发结果 / 跳过原因。 */
    public enum SkipReason {
        /** 满足条件，已发起同步 */
        NONE,
        /** 开关关闭（默认状态） */
        DISABLED,
        /** 本次进程已经尝试过 */
        ALREADY_ATTEMPTED,
        /** AK/SK/Bucket/本地目录未配置完整 */
        INCOMPLETE_CONFIG,
        /** 缺少读取存储的权限 */
        NO_PERMISSION,
        /** 已有上传任务在执行 */
        ALREADY_RUNNING
    }

    /** 读取开关状态；未设置过时返回默认值（关闭）。 */
    public static boolean isEnabled(Context context) {
        return Prefs.getBoolean(context, Prefs.KEY_AUTO_SYNC_ON_OPEN,
                Prefs.DEFAULT_AUTO_SYNC_ON_OPEN);
    }

    /** 写入开关状态，立即生效（下一次进入主界面起作用）。 */
    public static void setEnabled(Context context, boolean enabled) {
        Prefs.putBoolean(context, Prefs.KEY_AUTO_SYNC_ON_OPEN, enabled);
    }

    /**
     * 进入主界面时调用：按开关状态决定是否发起一次自动同步。
     *
     * <p>开关关闭时不会发出任何请求；开关开启但条件不满足时，用 Toast 说明跳过原因。
     * 只能从主线程调用。
     */
    public static void onMainEntered(Activity activity) {
        if (!isEnabled(activity)) {
            Log.d(TAG, "自动同步已关闭，跳过");
            return;
        }
        if (!ATTEMPTED.compareAndSet(false, true)) {
            Log.d(TAG, "本次启动已尝试过自动同步，跳过");
            return;
        }

        SkipReason reason = evaluate(activity);
        switch (reason) {
            case NONE:
                scheduleStart(activity);
                break;
            case INCOMPLETE_CONFIG:
                toast(activity, "自动同步已跳过：请先在「设置」完成七牛云与本地目录配置");
                break;
            case NO_PERMISSION:
                toast(activity, "自动同步已跳过：缺少存储权限，可在「同步任务」页手动同步并授权");
                break;
            case ALREADY_RUNNING:
                Log.d(TAG, "已有上传任务在执行，跳过自动同步");
                break;
            case DISABLED:
            case ALREADY_ATTEMPTED:
            default:
                break;
        }
    }

    /** 纯判定：不产生任何副作用，便于复核与测试。 */
    public static SkipReason evaluate(Context context) {
        return decide(
                isEnabled(context),
                UploadService.isRunning(),
                isConfigured(context),
                hasStoragePermission(context));
    }

    /**
     * 判定核心（纯函数）：给定开关、运行中、配置、权限四个事实，得出是否该发起同步。
     *
     * <p>顺序即优先级：开关关闭最优先（任何情况下都不上传），其次避免重复执行，
     * 再检查配置是否完整，最后看权限。
     */
    public static SkipReason decide(boolean enabled, boolean running,
                                    boolean configured, boolean hasPermission) {
        if (!enabled) {
            return SkipReason.DISABLED;
        }
        if (running) {
            return SkipReason.ALREADY_RUNNING;
        }
        if (!configured) {
            return SkipReason.INCOMPLETE_CONFIG;
        }
        if (!hasPermission) {
            return SkipReason.NO_PERMISSION;
        }
        return SkipReason.NONE;
    }

    /** 与手动同步（{@link SyncFragment}）保持同一套前置条件。 */
    private static boolean isConfigured(Context context) {
        return !TextUtils.isEmpty(Prefs.getString(context, Prefs.KEY_ACCESS_KEY, ""))
                && !TextUtils.isEmpty(Prefs.getString(context, Prefs.KEY_SECRET_KEY, ""))
                && !TextUtils.isEmpty(Prefs.getString(context, Prefs.KEY_BUCKET, ""))
                && !TextUtils.isEmpty(Prefs.getString(context, Prefs.KEY_LOCAL_DIR, ""));
    }

    /**
     * 读取存储权限。
     *
     * <p>targetSdk 仍为 29，走的是旧存储模型，Android 13+ 上 READ_EXTERNAL_STORAGE
     * 对 target&lt;33 的应用依旧可授予，因此统一用这一条判定。
     */
    private static boolean hasStoragePermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void scheduleStart(Activity activity) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            // 延迟期间用户可能已把开关关掉，或在别处点了手动同步
            if (!isEnabled(activity) || UploadService.isRunning()) {
                return;
            }
            start(activity.getApplicationContext());
        }, START_DELAY_MS);
    }

    private static void start(Context context) {
        Intent service = new Intent(context, UploadService.class);
        service.setAction(UploadService.ACTION_UPLOAD);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, service);
            } else {
                context.startService(service);
            }
            Log.d(TAG, "打开 App 自动同步已触发");
        } catch (Exception e) {
            // 系统限制前台服务启动时不应让 App 崩溃，本次跳过即可
            Log.w(TAG, "自动同步启动失败", e);
        }
    }

    private static void toast(Activity activity, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
}
