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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 执行记录页：每次同步任务的时间、触发类型与结果汇总（对应 exec.log）。
 * 与「日志记录」页职责区分：本页只看「一次任务」，日志记录页看「一个文件」。
 */
public class ExecutionFragment extends Fragment {

    /** 最多渲染多少条，避免记录变多后一次性 inflate 大量视图。 */
    private static final int MAX_ROWS = 200;

    private static final Pattern COUNT = Pattern.compile("(成功|失败|跳过)=(\\d+)");

    private UploadLogger logger;
    private LinearLayout listContainer;
    private TextView tvEmpty, tvHint, tvStatRuns, tvStatSuccess, tvStatFail, tvStatSkip;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_execution, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        logger = new UploadLogger(requireContext());
        listContainer = view.findViewById(R.id.container_exec_list);
        tvEmpty = view.findViewById(R.id.tv_exec_empty);
        tvHint = view.findViewById(R.id.tv_exec_hint);
        tvStatRuns = view.findViewById(R.id.tv_stat_runs);
        tvStatSuccess = view.findViewById(R.id.tv_stat_success);
        tvStatFail = view.findViewById(R.id.tv_stat_fail);
        tvStatSkip = view.findViewById(R.id.tv_stat_skip);

        Button btnClear = view.findViewById(R.id.btn_clear_exec);
        btnClear.setOnClickListener(v -> confirmClear());

        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 同步任务执行完回到本页时刷新
        refresh();
    }

    private void refresh() {
        if (listContainer == null) return;

        listContainer.removeAllViews();
        List<String> lines = logger.readRecentExec(MAX_ROWS);
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        int rows = 0, runs = 0, success = 0, fail = 0, skip = 0;

        for (String line : lines) {
            String[] parts = line.split(" \\| ", 3);
            if (parts.length < 3) continue;

            String time = parts[0];
            String type = parts[1];
            String detail = parts[2];

            if ("完成".equals(type)) {
                runs++;
                success += readCount(detail, "成功");
                fail += readCount(detail, "失败");
                skip += readCount(detail, "跳过");
                detail = formatCounts(detail);
            }

            View row = inflater.inflate(R.layout.item_exec_record, listContainer, false);
            ((TextView) row.findViewById(R.id.tv_exec_time)).setText(time);

            TextView badge = row.findViewById(R.id.tv_exec_badge);
            badge.setText(type);
            badge.setBackgroundResource(badgeBackground(type));

            ((TextView) row.findViewById(R.id.tv_exec_detail)).setText(detail);
            listContainer.addView(row);
            rows++;
        }

        tvEmpty.setVisibility(rows == 0 ? View.VISIBLE : View.GONE);
        tvHint.setText(rows == 0
                ? "最新记录在前，最多显示 " + MAX_ROWS + " 条"
                : "共 " + rows + " 条记录 · 最新在前");

        tvStatRuns.setText(String.valueOf(runs));
        tvStatSuccess.setText(String.valueOf(success));
        tvStatFail.setText(String.valueOf(fail));
        tvStatSkip.setText(String.valueOf(skip));
    }

    private void confirmClear() {
        new AlertDialog.Builder(requireContext())
                .setTitle("清空执行记录")
                .setMessage("将删除全部执行记录，此操作不可恢复。日志记录不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    logger.clearExec();
                    refresh();
                    Toast.makeText(requireContext(), "执行记录已清空", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private static int readCount(String detail, String key) {
        Matcher m = Pattern.compile(key + "=(\\d+)").matcher(detail);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 把 "成功=3 失败=0 跳过=1" 美化成 "成功 3 · 失败 0 · 跳过 1"。 */
    private static String formatCounts(String detail) {
        Matcher m = COUNT.matcher(detail);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(m.group(1)).append(' ').append(m.group(2));
        }
        return sb.length() == 0 ? detail : sb.toString();
    }

    private static int badgeBackground(String type) {
        switch (type) {
            case "完成":
                return R.drawable.bg_badge_ok;
            case "开始":
                return R.drawable.bg_badge_info;
            default:
                return R.drawable.bg_badge_info;
        }
    }
}
