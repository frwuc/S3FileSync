package com.frwuc.s3fileuploader;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.util.List;

/**
 * 日志记录页：逐个文件的上传明细（对应 uploads.log），含云端 key 与访问地址。
 * 与「执行记录」页职责区分：本页看「一个文件」，执行记录页看「一次任务」。
 */
public class LogsFragment extends Fragment {

    private static final int MAX_ROWS = 200;

    private UploadLogger logger;
    private LinearLayout listContainer;
    private TextView tvEmpty, tvHint;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_logs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        logger = new UploadLogger(requireContext());
        listContainer = view.findViewById(R.id.container_log_list);
        tvEmpty = view.findViewById(R.id.tv_logs_empty);
        tvHint = view.findViewById(R.id.tv_logs_hint);

        Button btnClear = view.findViewById(R.id.btn_clear_logs);
        btnClear.setOnClickListener(v -> confirmClear());

        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 同步完成后回到本页时刷新
        refresh();
    }

    private void refresh() {
        if (listContainer == null) return;

        listContainer.removeAllViews();
        List<String> lines = logger.readRecentUploads(MAX_ROWS);
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        int rows = 0, success = 0, fail = 0;

        for (String line : lines) {
            // 格式：时间 | 成功/失败 | 文件名 | 云端key 或 code=N | URL 或 错误信息
            String[] parts = line.split(" \\| ", 5);
            if (parts.length < 4) continue;

            String time = parts[0];
            String result = parts[1];
            String fileName = parts[2];
            String fourth = parts[3];
            String fifth = parts.length > 4 ? parts[4] : "";

            boolean ok = "成功".equals(result);
            if (ok) success++; else fail++;

            View row = inflater.inflate(R.layout.item_log_record, listContainer, false);
            ((TextView) row.findViewById(R.id.tv_log_name)).setText(fileName);

            TextView badge = row.findViewById(R.id.tv_log_badge);
            badge.setText(result);
            badge.setBackgroundResource(ok ? R.drawable.bg_badge_ok : R.drawable.bg_badge_fail);

            String meta = ok
                    ? time + " · 云端：" + fourth
                    : time + " · " + fourth + (fifth.isEmpty() ? "" : " · " + fifth);
            ((TextView) row.findViewById(R.id.tv_log_meta)).setText(meta);

            TextView tvUrl = row.findViewById(R.id.tv_log_url);
            if (ok && fifth.startsWith("http")) {
                tvUrl.setText(fifth);
                tvUrl.setVisibility(View.VISIBLE);
            } else {
                tvUrl.setVisibility(View.GONE);
            }

            listContainer.addView(row);
            rows++;
        }

        tvEmpty.setVisibility(rows == 0 ? View.VISIBLE : View.GONE);
        tvHint.setText(rows == 0
                ? "最新记录在前，最多显示 " + MAX_ROWS + " 条"
                : "共 " + rows + " 条 · 成功 " + success + " · 失败 " + fail + " · 最新在前");
    }

    private void confirmClear() {
        new AlertDialog.Builder(requireContext())
                .setTitle("清空日志记录")
                .setMessage("将删除全部逐文件上传明细，此操作不可恢复。执行记录不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    logger.clearUploads();
                    refresh();
                    Toast.makeText(requireContext(), "日志记录已清空", Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}
