package com.frwuc.s3fileuploader;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「定时任务排查助手」弹窗：不触发的常见原因在这里逐项体检、一键处理。
 *
 * <p>逐项检查并给出对应动作：
 * <ol>
 *   <li>存储权限：扫描不到文件时任务等于空跑；</li>
 *   <li>电池优化白名单：国产 ROM 杀后台的第一嫌疑；</li>
 *   <li>精确闹钟权限（Android 12+）：被收回时 setExactAndAllowWhileIdle 退化；</li>
 *   <li>自启动 / 后台运行（小米 / vivo / OPPO / 华为 / 三星 / 魅族等厂商专属开关），
 *       尽可能直接跳到厂商对应的设置页，找不到页面则给出文字指引；</li>
 *   <li>通知权限（Android 13+）：前台服务的通知被关掉不影响上传，但用户感知不到任务；</li>
 *   <li>配置完整性与下次触发时间：AK/SK/Bucket/本地目录、时间点是否有效；</li>
 *   <li>测试闹钟：1 分钟后真实触发一次上传，用「执行记录」直接验证链路。</li>
 * </ol>
 *
 * 每次弹出都重新检测（从系统设置回来后点「重新检测」即可刷新状态）。
 */
final class ScheduleHelperDialog {

    private static final String TAG = "ScheduleHelper";

    private static final int COLOR_OK = 0xFF2E7D32;
    private static final int COLOR_WARN = 0xFFE65100;
    private static final int COLOR_TITLE = 0xFF1976D2;
    private static final int COLOR_MUTED = 0xFF777777;

    private static final SimpleDateFormat SHOW_FMT =
            new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    private ScheduleHelperDialog() {
    }

    /** 打开排查弹窗。 */ 
    public static void show(androidx.fragment.app.Fragment fragment) {
        new Builder(fragment).show();
    }

    private static final class Builder {

        private final androidx.fragment.app.Fragment fragment;
        private final Context context;
        private AlertDialog dialog;

        private LinearLayout body;
        private TextView summary;

        Builder(androidx.fragment.app.Fragment fragment) {
            this.fragment = fragment;
            this.context = fragment.requireContext();
        }

        void show() {
            View content = LayoutInflater.from(context)
                    .inflate(R.layout.dialog_schedule_helper, null);
            summary = content.findViewById(R.id.tv_helper_summary);
            body = content.findViewById(R.id.ll_helper_body);

            AlertDialog.Builder builder = new AlertDialog.Builder(context)
                    .setTitle("⏰ 定时任务排查助手")
                    .setView(content)
                    .setPositiveButton("重新检测", (d, w) -> rebuild())
                    .setNegativeButton("关闭", null);
            dialog = builder.create();
            dialog.setOnShowListener(d -> {
                Window window = dialog.getWindow();
                if (window != null) {
                    window.setLayout(
                            (int) (context.getResources().getDisplayMetrics().widthPixels * 0.95f),
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                }
            });
            dialog.show();
            rebuild();
        }

        // ------------------------------------------------------------ 检测与渲染

        private void rebuild() {
            List<CheckItem> items = collectChecks();
            int problemCount = 0;
            for (CheckItem it : items) {
                // neutral 行是人工确认提示（无开关可探测），不算阻断问题
                if (!it.ok && !it.neutral) problemCount++;
            }
            if (problemCount == 0) {
                summary.setText("✅ 未发现阻断问题。若仍不触发，建议：保持手机不重启验证 1-2 天；"
                        + "若手机被「省电清理」强杀过进程，用下面的测试闹钟再复现一次。");
                summary.setTextColor(COLOR_OK);
            } else {
                summary.setText("⚠️ 有 " + problemCount + " 项待处理，按行依次点「去处理」：");
                summary.setTextColor(COLOR_WARN);
            }
            body.removeAllViews();
            for (CheckItem it : items) {
                body.addView(buildRow(it));
            }
            body.addView(buildTestRow());
        }

        private List<CheckItem> collectChecks() {
            List<CheckItem> items = new ArrayList<>();
            items.add(checkStorage());
            items.add(checkBattery());
            items.add(checkExactAlarm());
            items.add(checkVendorAutostart());
            items.add(checkNotification());
            items.add(checkConfig());
            return items;
        }

        private CheckItem checkStorage() {
            boolean granted = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
            return new CheckItem(granted, "存储权限",
                    granted ? "已授权，可读取本地目录" : "未授权：定时到点后也扫不到文件，等于空跑",
                    granted ? null : "去授予权限",
                    () -> fragment.requestPermissions(
                            new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 3001));
        }

        private CheckItem checkBattery() {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            boolean ok = pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
            return new CheckItem(ok, "电池优化白名单",
                    ok ? "已在白名单内，后台不容易被系统清理" : "未加入白名单：国产 ROM 最常见的定时失败原因",
                    ok ? null : "加入白名单",
                    ok ? null : () -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + context.getPackageName()));
                            fragment.startActivityForResult(intent, 3002);
                        } catch (Exception e) {
                            fallbackAppSettings(context, "无法跳转电池设置，请到系统设置手动处理");
                        }
                    });
        }

        private CheckItem checkExactAlarm() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return new CheckItem(true, "精确闹钟权限",
                        "本系统版本（Android 12 以下）无需单独授权", null, null);
            }
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            boolean ok = am != null && am.canScheduleExactAlarms();
            return new CheckItem(ok, "精确闹钟权限",
                    ok ? "已允许，闹钟可精确到设定分钟触发"
                            : "被限制：闹钟会被推迟到系统允许的窗口期（如夜间）",
                    ok ? null : "去授权",
                    ok ? null : () -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                            intent.setData(Uri.parse("package:" + context.getPackageName()));
                            fragment.startActivity(intent);
                        } catch (Exception e) {
                            fallbackAppSettings(context, "无法打开闹钟权限页，可到系统设置 → 应用搜索本应用");
                        }
                    });
        }

        private CheckItem checkNotification() {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            boolean ok = nm == null || nm.areNotificationsEnabled();
            return new CheckItem(ok, "通知权限",
                    ok ? "已开启：到点上传时能看到前台通知"
                            : "已关闭：上传在后台照常执行，但看不到进度提示（不影响触发）",
                    ok ? null : "去开启",
                    ok ? null : () -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
                            fragment.startActivity(intent);
                        } catch (Exception e) {
                            fallbackAppSettings(context, "无法打开通知设置，请到系统设置手动开启");
                        }
                    });
        }

        private CheckItem checkVendorAutostart() {
            String vendor = vendorName();
            String guide = vendorGuide(vendor);
            Runnable jump = buildVendorJump(vendor);
            return new CheckItem(true, "自启动 / 后台运行（" + vendor + "）",
                    guide + "\n各厂商没有开放检测接口，需人工确认：若已设置过，可忽略本行",
                    jump == null ? null : "打开设置页",
                    jump, true);
        }

        private CheckItem checkConfig() {
            Set<String> times = Prefs.getStringSet(context, Prefs.KEY_TIMES, null);
            List<String> msg = new ArrayList<>();
            if (TextUtils.isEmpty(Prefs.getString(context, Prefs.KEY_ACCESS_KEY, "")))
                msg.add("AK 未填写");
            if (TextUtils.isEmpty(Prefs.getString(context, Prefs.KEY_SECRET_KEY, "")))
                msg.add("SK 未填写");
            if (TextUtils.isEmpty(Prefs.getString(context, Prefs.KEY_BUCKET, "")))
                msg.add("Bucket 未填写");
            if (TextUtils.isEmpty(Prefs.getString(context, Prefs.KEY_LOCAL_DIR, "")))
                msg.add("本地目录未填写");

            String timesText;
            long delay = -1;
            if (times == null || times.isEmpty()) {
                timesText = "定时时间未设置";
            } else {
                List<String> valid = new ArrayList<>();
                for (String t : times) {
                    if (UploadScheduler.isValidTime(t)) valid.add(t);
                }
                if (valid.isEmpty()) {
                    timesText = "设置的定时时点均无效（需 HH:mm 格式，如 10:00）";
                } else {
                    delay = UploadScheduler.nextTriggerDelayMs(valid);
                    timesText = "已设置 " + valid.size() + " 个时间点，下次触发：";
                    long at = System.currentTimeMillis() + delay;
                    timesText += SHOW_FMT.format(new Date(at)) + "（" + humanDelay(delay) + "）";
                }
            }
            if (!msg.isEmpty()) {
                timesText = TextUtils.join("；", msg) + "。" + timesText;
            } else if (delay < 0) {
                timesText = "配置完整。" + timesText + "，请到「设置」添加时间点";
            } else {
                timesText = "配置完整。" + timesText;
            }

            boolean ok = msg.isEmpty() && times != null && !times.isEmpty() && delay >= 0;
            if (!ok) timesText += "\n→ 请到「设置」补齐，然后点「保存配置并启用定时」";
            return new CheckItem(ok, "配置完整性与下次触发", timesText, null, null);
        }

        /** 「还有多久」的口语化时间（分钟 / 小时 / 天）。 */
        private static String humanDelay(long ms) {
            long minutes = ms / 60000L;
            if (minutes < 90) return "约 " + Math.max(minutes, 0) + " 分钟后";
            long hours = minutes / 60;
            if (hours < 48) return "约 " + hours + " 小时 " + (minutes % 60) + " 分后";
            return "约 " + (hours / 24) + " 天后";
        }

        // ------------------------------------------------------------ 厂商自启动

        /** 已知厂商的「应用自启动 / 后台设置」页面候选（按命中优先级尝试）。 */
        private static final String[][][] VENDOR_PAGES = {
                // 小米 / 红米 / POCO（MIUI）
                {{"com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"}},
                // vivo / iQOO
                {{"com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                        {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
                        {"com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity"}},
                // OPPO / 一加 / realme（ColorOS）
                {{"com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                        {"com.coloros.safecenter",
                                "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                        {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                        {"com.oplus.safecenter",
                                "com.oplus.safecenter.permission.startup.StartupAppListActivity"},
                        {"com.oneplus.security",
                                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"}},
                // 华为（EMUI）
                {{"com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                        {"com.huawei.systemmanager",
                                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
                        {"com.huawei.systemmanager",
                                "com.huawei.systemmanager.optimize.process.ProtectActivity"}},
                // 荣耀（HONOR）
                {{"com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"}},
                // 魅族
                {{"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"}},
                // 三星
                {{"com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"},
                        {"com.samsung.android.sm", "com.samsung.android.sm.ui.ramsetui.RamSettingsActivity"}},
        };

        private String vendorName() {
            String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim().toLowerCase(Locale.US);
            if (m.contains("huawei")) return "华为";
            if (m.contains("honor")) return "荣耀";
            if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco"))
                return "小米/红米";
            if (m.contains("oneplus")) return "一加";
            if (m.contains("oppo") || m.contains("realme")) return "OPPO";
            if (m.contains("vivo") || m.contains("iqoo")) return "vivo";
            if (m.contains("samsung")) return "三星";
            if (m.contains("meizu")) return "魅族";
            return TextUtils.isEmpty(m) ? "当前手机" : Build.MANUFACTURER.trim();
        }

        private String vendorGuide(String vendor) {
            switch (vendor) {
                case "华为":
                    return "系统设置 → 应用 → 应用启动管理 → 本应用，改为「手动管理」，"
                            + "同时勾选：允许自启动 / 关联启动 / 后台活动";
                case "荣耀":
                    return "系统管家 → 启动管理 → 本应用，勾选允许自启动与后台活动";
                case "小米/红米":
                    return "① 自启动：安全中心 → 应用管理 → 本应用 → 自启动：打开"
                            + "\n② 省电策略：应用管理 → 本应用 → 省电策略 → 无限制"
                            + "\n③ 锁定后台：最近任务界面下拉/长按本应用卡片 → 加锁（出现锁图标）"
                            + "\n⚠️ 没加锁的卡片被上滑清理后，后台闹钟可能被拒收";
                case "vivo":
                    return "🔒 ① 多任务加锁（最基础必做）：屏幕底部上滑停顿调出后台，"
                            + "找到本应用卡片按住往下滑一下，右上角出现锁图标即成功"
                            + "\n🔋 ② 后台高耗电（核心开关）：设置 → 电池 → 后台耗电管理 → 本应用 → 允许后台高耗电，"
                            + "不开这个息屏后容易被冻结"
                            + "\n🚀 ③ 自启动：手机管家（原 i管家）→ 应用管理 → 权限管理 → 自启动，打开本应用；"
                            + "再点进应用详情把「关联启动」也打开"
                            + "\n🛡️ ④ 加速白名单：手机管家 → 省电管理 → 后台高耗电/加速白名单 → 右上角 + 加入本应用"
                            + "\n🌙 ⑤ 睡眠模式：设置 → 电池 → 更多设置/睡眠模式 → 智能后台冻结/睡眠待机优化，"
                            + "把本应用加入不受限制名单（凌晨时间点必做）"
                            + "\n⚠️ 别开省电模式/超级省电（会强制限后台）；卡片被上滑清理后闹钟会被拒收——"
                            + "回到 App 开一下会自动重新排上";
                case "OPPO":
                case "一加":
                    return "设置 → 应用 → 本应用 → 耗电管理：允许自启动 / 完全后台行为；"
                            + "关闭「智能后台杀应用」对本应用的限制";
                case "三星":
                    return "设置 → 电池 → 后台使用限制，把本应用从不限制列表排除（或选择「关闭限制」）";
                case "魅族":
                    return "手机管家 → 应用管理 → 本应用：允许后台运行与自启动";
                default:
                    return "① 系统设置 → 应用 → 本应用：允许自启动 / 后台活动"
                            + "\n② 电池设置：允许后台耗电（加入电池优化白名单）"
                            + "\n③ 多任务界面锁定本应用卡片，避免被一键清理";
            }
        }

        /** 逐个尝试厂商页面候选，能解析（已安装且存在）就返回跳转动作。 */
        private Runnable buildVendorJump(String vendor) {
            int idx = vendorIndex(vendor);
            if (idx < 0) return null;
            for (String[] comp : VENDOR_PAGES[idx]) {
                Intent intent = new Intent().setComponent(
                        new android.content.ComponentName(comp[0], comp[1]));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    // 包名 + Activity 形式的显式 Intent 必须先确认目标存在（安全中心可能被 ROM 裁剪），
                    // 存在才返回跳转动作；Android 11+ 的可见性由 Manifest 的 <queries> 声明保证
                    boolean exists = context.getPackageManager()
                            .resolveActivity(intent, 0) != null;
                    if (exists) {
                        return () -> {
                            try {
                                context.startActivity(intent);
                            } catch (Exception e) {
                                Log.w(TAG, "跳转厂商设置页失败", e);
                                fallbackAppSettings(context, "跳转失败，请按文字指引手动打开");
                            }
                        };
                    }
                } catch (Exception ignored) {
                    // 安全中心包被裁剪等：试下一个候选
                }
            }
            return null;
        }

        private int vendorIndex(String vendor) {
            switch (vendor) {
                case "华为": return 3;
                case "荣耀": return 4;
                case "小米/红米": return 0;
                case "OPPO": case "一加": return 2;
                case "vivo": return 1;
                case "魅族": return 5;
                case "三星": return 6;
                default: return -1;
            }
        }

        // ------------------------------------------------------------ 测试闹钟行

        private View buildTestRow() {
            View row = LayoutInflater.from(context)
                    .inflate(R.layout.item_helper_check, body, false);
            TextView tvState = row.findViewById(R.id.tv_check_state);
            TextView tvTitle = row.findViewById(R.id.tv_check_title);
            TextView tvDesc = row.findViewById(R.id.tv_check_desc);
            Button btn = row.findViewById(R.id.btn_check_action);
            btn.setVisibility(View.VISIBLE);

            tvState.setText("▶");
            tvState.setTextColor(COLOR_TITLE);
            tvTitle.setText("是否仍不触发？做一次实测");
            tvDesc.setText("排好 1 分钟后的真实闹钟：到点会像正式任务一样唤醒前台服务并上传一次"
                    + "（不影响每日定时的安排）。触发后手机会弹提示；上传结果去「执行记录」页看。");
            btn.setText("1 分钟后测试");
            btn.setOnClickListener(v -> {
                UploadScheduler.scheduleTest(context, 60_000L);
                Toast.makeText(context,
                        "已排好：约 1 分钟后触发。保持屏幕亮着别锁屏，结果看「执行记录」",
                        Toast.LENGTH_LONG).show();
            });
            return row;
        }

        // ------------------------------------------------------------ 行 UI

        private View buildRow(CheckItem item) {
            View row = LayoutInflater.from(context)
                    .inflate(R.layout.item_helper_check, body, false);
            TextView tvState = row.findViewById(R.id.tv_check_state);
            TextView tvTitle = row.findViewById(R.id.tv_check_title);
            TextView tvDesc = row.findViewById(R.id.tv_check_desc);
            Button btn = row.findViewById(R.id.btn_check_action);

            tvState.setText(item.neutral ? "🔧" : (item.ok ? "✅" : "⚠️"));
            tvState.setTextColor(item.ok ? COLOR_OK : COLOR_WARN);
            tvTitle.setText(item.title);
            tvTitle.setTextColor(item.ok ? COLOR_MUTED : 0xFF333333);
            tvDesc.setText(item.desc);
            if (item.actionLabel == null || item.action == null) {
                btn.setVisibility(View.GONE);
            } else {
                btn.setVisibility(View.VISIBLE);
                btn.setText(item.actionLabel);
                btn.setOnClickListener(v -> item.action.run());
            }
            return row;
        }

        private void fallbackAppSettings(Context ctx, String hint) {
            Toast.makeText(ctx, hint + "：可先到应用详情页处理", Toast.LENGTH_LONG).show();
            try {
                Intent app = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                app.setData(Uri.parse("package:" + ctx.getPackageName()));
                fragment.startActivity(app);
            } catch (Exception e) {
                Toast.makeText(ctx, "无法跳转应用详情页，请到系统设置手动处理", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 一行检查结果。neutral = 人工确认提示（厂商页面无检测接口），不算阻断问题。 */
    private static final class CheckItem {
        final boolean ok;
        final String title;
        final String desc;
        final String actionLabel;
        final Runnable action;
        final boolean neutral;

        CheckItem(boolean ok, String title, String desc, String actionLabel, Runnable action) {
            this(ok, title, desc, actionLabel, action, false);
        }

        CheckItem(boolean ok, String title, String desc, String actionLabel, Runnable action,
                  boolean neutral) {
            this.ok = ok;
            this.title = title;
            this.desc = desc;
            this.actionLabel = actionLabel;
            this.action = action;
            this.neutral = neutral;
        }
    }
}
