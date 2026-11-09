package com.frwuc.s3fileuploader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.Toast;

public class AlarmReceiver extends BroadcastReceiver {

    /** {@link UploadScheduler} 测试闹钟的标记 extra；普通定时闹钟不带。 */
    public static final String EXTRA_TEST = "test";

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean test = intent != null && intent.getBooleanExtra(EXTRA_TEST, false);

        Intent serviceIntent = new Intent(context, UploadService.class);
        serviceIntent.setAction(UploadService.ACTION_UPLOAD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }

        if (test) {
            Toast.makeText(context, "⏱ 测试闹钟已触发，开始上传一次（结果见「执行记录」）",
                    Toast.LENGTH_LONG).show();
        } else {
            // 关键修复：旧的实现在「保存配置」和「开机」时排闹钟，但每次闹钟触发后
            // 并没有再排下一次，于是设定的每个时间点都只生效一次，第二天起就不再
            // 上传——这正是「定时上传不会触发」的主因。这里在每次触发后立刻把
            // 所有配置时间点的下一次重新排上（schedule 内部会跳过已过去的时间）。
            UploadScheduler.reSchedule(context);
        }
    }
}
