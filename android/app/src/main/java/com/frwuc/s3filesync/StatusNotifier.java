package com.frwuc.s3filesync;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 手机顶部（状态栏）常驻状态通知：随时可见当前工作状态。
 *
 * <p>三种状态文案：
 * <ul>
 *   <li><b>正常监听 · 正常后台运行</b>——定时任务已排好（含下次触发时间）；</li>
 *   <li><b>正在同步</b>——由 {@link UploadService} 的前台通知展示（本类不重复弹）；</li>
 *   <li><b>同步完成</b>——一轮同步结束后带上「成功/失败/跳过」摘要。</li>
 * </ul>
 *
 * <p>通知为低优先级（无声、不弹横幅）、不可滑动清除，点按回到主界面。
 * 独立于同步服务的前台通知（另一个 ID），服务结束后依然保留，
 * 下一次打开 App / 闹钟触发 / 重排定时时会刷新回「正常监听」。
 */
public final class StatusNotifier {

    /** 常驻状态通知的 ID（与 UploadService 前台通知 1001 区分开）。 */
    private static final int STATUS_NOTIFICATION_ID = 1002;
    private static final String CHANNEL_ID = "status_channel";

    private StatusNotifier() {
    }

    /** 刷新为「正常监听 · 正常后台运行」。同步进行中则不动（避免两条通知并存）。 */
    public static void monitoring(Context context) {
        if (UploadService.isRunning()) return;
        post(context, monitoringText(context));
    }

    /** 同步结束后的状态：带上本轮摘要。 */
    public static void done(Context context, String summary) {
        String text = (summary == null || summary.isEmpty())
                ? "同步完成 · 正常监听"
                : "同步完成（" + summary + "）· 正常监听";
        post(context, text);
    }

    /** 主动撤掉常驻状态通知（目前无入口调用，保留以便扩展）。 */
    public static void clear(Context context) {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(STATUS_NOTIFICATION_ID);
    }

    // ---------------------------------------------------------------- 内部

    private static String monitoringText(Context context) {
        Set<String> timesSet =
                Prefs.getStringSet(context, Prefs.KEY_TIMES, new java.util.HashSet<>());
        long nextAt = UploadScheduler.nextTriggerAt(new ArrayList<>(timesSet));
        if (nextAt <= 0) {
            return "正常后台运行 · 未设置定时同步（可在设置页添加）";
        }
        String hhmm = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(nextAt));
        return "正常监听 · 正常后台运行（下次同步 " + hhmm + "）";
    }

    private static void post(Context context, String text) {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.notify(STATUS_NOTIFICATION_ID, builder(context, text).build());
    }

    private static NotificationCompat.Builder builder(Context context, String text) {
        createChannel(context);
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setOngoing(true)          // 常驻：不可滑动清除
                .setOnlyAlertOnce(true)    // 更新文案时不重复响铃/振动
                .setPriority(NotificationCompat.PRIORITY_LOW) // < O 的设备同样低优先级
                .setContentIntent(pi);
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "运行状态", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
