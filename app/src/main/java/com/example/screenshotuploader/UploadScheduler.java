package com.example.screenshotuploader;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Set;

public class UploadScheduler {

    public static void schedule(Context context, List<String> times) {
        cancelAll(context);
        if (times == null || times.isEmpty()) return;

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        for (int i = 0; i < times.size(); i++) {
            String time = times.get(i);
            if (!isValidTime(time)) continue;

            long triggerAt = getNextTriggerTime(time);
            Intent intent = new Intent(context, AlarmReceiver.class);
            intent.putExtra("time", time);
            PendingIntent pi = PendingIntent.getBroadcast(
                    context, 1000 + i, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }

            android.util.Log.d("UploadScheduler", "已设置定时: " + time);
        }
    }

    public static void cancelAll(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int i = 0; i < 20; i++) {
            Intent intent = new Intent(context, AlarmReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(
                    context, 1000 + i, intent,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        }
    }

    public static void reSchedule(Context context) {
        Set<String> timesSet = Prefs.getStringSet(context, Prefs.KEY_TIMES, new java.util.HashSet<>());
        if (timesSet.isEmpty()) return;
        List<String> times = new ArrayList<>(timesSet);
        schedule(context, times);
    }

    private static boolean isValidTime(String time) {
        if (time == null || time.length() != 5) return false;
        if (time.charAt(2) != ':') return false;
        try {
            int h = Integer.parseInt(time.substring(0, 2));
            int m = Integer.parseInt(time.substring(3, 5));
            return h >= 0 && h <= 23 && m >= 0 && m <= 59;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static long getNextTriggerTime(String hhmm) {
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        int h = Integer.parseInt(hhmm.substring(0, 2));
        int m = Integer.parseInt(hhmm.substring(3, 5));
        target.set(Calendar.HOUR_OF_DAY, h);
        target.set(Calendar.MINUTE, m);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        if (target.getTimeInMillis() <= now.getTimeInMillis()) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        }
        return target.getTimeInMillis();
    }
}
