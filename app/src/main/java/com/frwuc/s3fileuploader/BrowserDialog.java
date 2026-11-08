package com.frwuc.s3fileuploader;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文件夹浏览 / 选择对话框，本地文件夹与七牛云端目录共用一套界面。
 *
 * <p>能力：
 * <ul>
 *   <li>逐级进入子文件夹、返回上级；</li>
 *   <li>列出文件夹内的文件（大小 + 修改时间），云端为 key 的 fsize / putTime；</li>
 *   <li>按名称或时间排序，点同一按钮切换升 / 降序；</li>
 *   <li>{@code pickMode} 为 true 时底部提供「选择此文件夹」，作为配置项取值入口；
 *       为 false 时仅浏览内容。</li>
 * </ul>
 *
 * <p>本地读取走 {@link File}（与上传逻辑一致，依赖已授予的存储权限），云端读取走
 * {@link QiniuBucketApi}，都在单线程池里执行，结果回主线程刷新。
 */
public class BrowserDialog {

    /** 选中文件夹的回调：本地为绝对路径，云端为目录前缀（根目录返回空串）。 */
    public interface Listener {
        void onPicked(String path);
    }

    private enum Mode { LOCAL, CLOUD }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    private static final int COLOR_PRIMARY = 0xFF1976D2;
    private static final int COLOR_NORMAL = 0xFF333333;
    private static final int COLOR_MUTED = 0xFF666666;

    private final Context context;
    private final Mode mode;
    private final Listener listener;
    private final boolean pickMode;

    // 云端参数（用界面上当前填写的值，不必先保存配置）
    private final String accessKey;
    private final String secretKey;
    private final String bucket;

    /** 当前目录：本地为绝对路径；云端为以 "/" 结尾的前缀，根目录为空串 */
    private String currentPath;
    private BrowseSort sort = BrowseSort.NAME_ASC;

    private AlertDialog dialog;
    private ListView listView;
    private ProgressBar loading;
    private TextView tvPath, tvEmpty, tvCount, btnUp, btnSortName, btnSortTime;
    private EntryAdapter adapter;

    /** 加载序号：快速连点目录时丢弃过期结果 */
    private int loadSeq = 0;

    private BrowserDialog(Context context, Mode mode, Listener listener, boolean pickMode,
                          String accessKey, String secretKey, String bucket, String startPath) {
        this.context = context;
        this.mode = mode;
        this.listener = listener;
        this.pickMode = pickMode;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.bucket = bucket;
        this.currentPath = startPath;
    }

    /**
     * 打开本地文件夹浏览器。
     *
     * @param startPath 起始目录；不存在时回退到外部存储根目录
     * @param pickMode  true = 底部显示「选择此文件夹」
     */
    public static void showLocal(Context context, String startPath, boolean pickMode,
                                 Listener listener) {
        new BrowserDialog(context, Mode.LOCAL, listener, pickMode,
                null, null, null, resolveStartDir(startPath)).show();
    }

    /**
     * 打开七牛云端目录浏览器。
     *
     * @param startPrefix 起始目录前缀，如 {@code daka_files} 或 {@code daka_files/2024}
     */
    public static void showCloud(Context context, String accessKey, String secretKey,
                                 String bucket, String startPrefix, boolean pickMode,
                                 Listener listener) {
        new BrowserDialog(context, Mode.CLOUD, listener, pickMode,
                accessKey, secretKey, bucket, normalizeCloudPrefix(startPrefix)).show();
    }

    // ---------------------------------------------------------------- 生命周期

    private void show() {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_browser, null);
        listView = view.findViewById(R.id.lv_browser);
        loading = view.findViewById(R.id.pb_browser_loading);
        tvPath = view.findViewById(R.id.tv_browser_path);
        tvEmpty = view.findViewById(R.id.tv_browser_empty);
        tvCount = view.findViewById(R.id.tv_browser_count);
        btnUp = view.findViewById(R.id.btn_browser_up);
        btnSortName = view.findViewById(R.id.btn_sort_name);
        btnSortTime = view.findViewById(R.id.btn_sort_time);

        adapter = new EntryAdapter();
        listView.setAdapter(adapter);

        btnUp.setOnClickListener(v -> goUp());
        btnSortName.setOnClickListener(v -> {
            sort = sort.byTime() ? BrowseSort.NAME_ASC : sort.toggle();
            applySort();
        });
        btnSortTime.setOnClickListener(v -> {
            sort = sort.byTime() ? sort.toggle() : BrowseSort.TIME_DESC;
            applySort();
        });
        listView.setOnItemClickListener((parent, itemView, position, id) ->
                onEntryClick(adapter.getItem(position)));

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(mode == Mode.LOCAL ? "浏览本地文件夹" : "浏览云端目录")
                .setView(view)
                .setNegativeButton("取消", null)
                .setNeutralButton("刷新", (d, which) -> refresh());
        if (pickMode) {
            builder.setPositiveButton("✓ 选择此文件夹", (d, which) -> pickCurrent());
        }

        dialog = builder.create();
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window != null) {
                int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.94f);
                window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();

        updateSortButtons();
        refresh();
    }

    private void onEntryClick(BrowseEntry entry) {
        if (entry == null) return;
        if (entry.dir) {
            currentPath = entry.path;
            refresh();
        } else {
            // 文件不可进入：弹出完整信息（名称不截断、可选中复制），方便核对是不是要同步的内容
            showFileDetail(entry);
        }
    }

    /** 文件详情：完整文件名 / 路径 / 大小 / 时间，文本可长按选中，也可一键复制文件名。 */
    private void showFileDetail(BrowseEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件名：").append(entry.name)
                .append("\n\n完整路径：").append(entry.path)
                .append("\n\n大小：").append(formatSize(entry.size));
        if (entry.mtimeMs > 0) {
            sb.append("\n修改时间：").append(TIME_FMT.format(new Date(entry.mtimeMs)));
        }

        AlertDialog detail = new AlertDialog.Builder(context)
                .setTitle("文件信息")
                .setMessage(sb.toString())
                .setPositiveButton("复制文件名", (d, w) -> copyFileName(entry.name))
                .setNegativeButton("关闭", null)
                .create();
        detail.setOnShowListener(d -> {
            // 让正文可长按选中复制（AlertDialog 的 message 默认不可选）
            TextView msg = detail.findViewById(android.R.id.message);
            if (msg != null) msg.setTextIsSelectable(true);
        });
        detail.show();
    }

    private void copyFileName(String name) {
        ClipboardManager cm =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("文件名", name));
        Toast.makeText(context, "已复制文件名", Toast.LENGTH_SHORT).show();
    }

    private void pickCurrent() {
        String picked = mode == Mode.LOCAL ? currentPath : trimTrailingSlash(currentPath);
        if (listener != null) listener.onPicked(picked);
        if (dialog != null) dialog.dismiss();
    }

    private void goUp() {
        if (!canGoUp()) return;
        if (mode == Mode.LOCAL) {
            currentPath = new File(currentPath).getParent();
        } else {
            String trimmed = trimTrailingSlash(currentPath);
            int idx = trimmed.lastIndexOf('/');
            currentPath = idx < 0 ? "" : trimmed.substring(0, idx + 1);
        }
        refresh();
    }

    private boolean canGoUp() {
        if (currentPath == null || currentPath.isEmpty()) return false;
        if (mode == Mode.LOCAL) {
            String parent = new File(currentPath).getParent();
            return parent != null && !parent.equals(currentPath);
        }
        return true;
    }

    // ---------------------------------------------------------------- 加载

    private void refresh() {
        final String path = currentPath;
        final int seq = ++loadSeq;

        tvPath.setText(displayPath());
        boolean up = canGoUp();
        btnUp.setEnabled(up);
        btnUp.setAlpha(up ? 1f : 0.35f);
        loading.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        adapter.setData(new ArrayList<>());
        tvCount.setText("加载中…");

        IO.execute(() -> {
            List<BrowseEntry> entries = null;
            String error = null;
            try {
                entries = load(path);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final List<BrowseEntry> data = entries;
            final String err = error;
            MAIN.post(() -> {
                if (seq != loadSeq || dialog == null || !dialog.isShowing()) return;
                loading.setVisibility(View.GONE);
                if (err != null) {
                    adapter.setData(new ArrayList<>());
                    tvCount.setText("共 0 项");
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText("读取失败：" + err);
                    return;
                }
                List<BrowseEntry> items = data == null ? new ArrayList<>() : data;
                sort.sort(items);
                adapter.setData(items);
                tvCount.setText("共 " + items.size() + " 项");
                tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                tvEmpty.setText(mode == Mode.LOCAL ? "此文件夹内没有内容" : "该目录下没有内容");
            });
        });
    }

    private List<BrowseEntry> load(String path) throws IOException {
        if (mode == Mode.LOCAL) return loadLocal(path);
        return QiniuBucketApi.list(accessKey, secretKey, bucket, path);
    }

    private List<BrowseEntry> loadLocal(String path) throws IOException {
        File dir = new File(path);
        if (!dir.exists()) throw new IOException("目录不存在：" + path);
        if (!dir.isDirectory()) throw new IOException("不是文件夹：" + path);

        File[] children = dir.listFiles();
        if (children == null) throw new IOException("无法读取该文件夹，请检查存储权限");

        List<BrowseEntry> list = new ArrayList<>(children.length);
        for (File f : children) {
            // 跳过隐藏项（.thumbnails 之类），否则列表噪音很大
            if (f.getName().startsWith(".")) continue;
            boolean isDir = f.isDirectory();
            list.add(new BrowseEntry(f.getName(), f.getAbsolutePath(), isDir,
                    isDir ? 0L : f.length(), f.lastModified()));
        }
        return list;
    }

    private void applySort() {
        updateSortButtons();
        adapter.sortInPlace(sort);
    }

    private void updateSortButtons() {
        boolean byName = !sort.byTime();
        String nameText = byName ? (sort.descending() ? "名称 ↓" : "名称 ↑") : "名称";
        String timeText = byName ? "时间" : (sort.descending() ? "时间 ↓" : "时间 ↑");
        btnSortName.setText(nameText);
        btnSortTime.setText(timeText);
        btnSortName.setTextColor(byName ? COLOR_PRIMARY : COLOR_MUTED);
        btnSortTime.setTextColor(byName ? COLOR_MUTED : COLOR_PRIMARY);
        btnSortName.setTypeface(null, byName ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
        btnSortTime.setTypeface(null, byName ? android.graphics.Typeface.NORMAL
                : android.graphics.Typeface.BOLD);
    }

    // ---------------------------------------------------------------- 展示辅助

    private String displayPath() {
        if (mode == Mode.LOCAL) {
            return currentPath == null || currentPath.isEmpty() ? "/" : currentPath;
        }
        // 云端：根目录显示 bucket 名，其余显示 "/目录/子目录"
        if (currentPath == null || currentPath.isEmpty()) {
            return "/（" + (bucket == null ? "" : bucket) + " 根目录）";
        }
        return "/" + trimTrailingSlash(currentPath);
    }

    private String metaOf(BrowseEntry e) {
        if (e.dir) return mode == Mode.LOCAL ? "文件夹" : "目录";
        String size = formatSize(e.size);
        String time = e.mtimeMs > 0 ? TIME_FMT.format(new Date(e.mtimeMs)) : "";
        return time.isEmpty() ? size : size + " · " + time;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb);
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0);
    }

    private static String iconOf(BrowseEntry e) {
        if (e.dir) return "📁";
        String lower = e.name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")) {
            return "🖼";
        }
        return "📄";
    }

    private static String resolveStartDir(String startPath) {
        if (startPath != null && !startPath.trim().isEmpty()) {
            File f = new File(startPath.trim());
            if (f.exists() && f.isDirectory()) return f.getAbsolutePath();
        }
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null && ext.exists()) return ext.getAbsolutePath();
        return "/";
    }

    /** 云端前缀规范化：去首尾斜杠后补一个结尾 "/"；空串表示桶根目录。 */
    private static String normalizeCloudPrefix(String prefix) {
        String p = trimTrailingSlash(prefix);
        while (p.startsWith("/")) p = p.substring(1);
        return p.isEmpty() ? "" : p + "/";
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        String p = s.trim();
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    // ---------------------------------------------------------------- 适配器

    private class EntryAdapter extends BaseAdapter {

        private List<BrowseEntry> data = new ArrayList<>();

        void setData(List<BrowseEntry> entries) {
            data = entries == null ? new ArrayList<>() : entries;
            notifyDataSetChanged();
        }

        void sortInPlace(BrowseSort by) {
            by.sort(data);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public BrowseEntry getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(context)
                        .inflate(R.layout.item_browser_entry, parent, false);
                holder = new ViewHolder();
                holder.icon = convertView.findViewById(R.id.tv_entry_icon);
                holder.name = convertView.findViewById(R.id.tv_entry_name);
                holder.meta = convertView.findViewById(R.id.tv_entry_meta);
                holder.tail = convertView.findViewById(R.id.tv_entry_tail);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            BrowseEntry entry = data.get(position);
            holder.icon.setText(iconOf(entry));
            holder.name.setText(entry.name);
            holder.name.setTextColor(entry.dir ? COLOR_PRIMARY : COLOR_NORMAL);
            holder.meta.setText(metaOf(entry));
            holder.tail.setText(entry.dir ? "进入 ›" : "");
            return convertView;
        }
    }

    private static class ViewHolder {
        TextView icon, name, meta, tail;
    }
}
