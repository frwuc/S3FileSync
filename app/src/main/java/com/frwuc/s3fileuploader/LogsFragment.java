package com.frwuc.s3fileuploader;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.List;

/**
 * 日志页：分「上传记录」「执行日志」两个标签查看本地记录，并可一键清空。
 */
public class LogsFragment extends Fragment {

    private UploadLogger logger;
    private TextView tvContent;
    private Button btnTabUpload, btnTabExec;
    private boolean showingUploads = true;

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
        tvContent = view.findViewById(R.id.tv_logs_content);
        btnTabUpload = view.findViewById(R.id.btn_tab_upload);
        btnTabExec = view.findViewById(R.id.btn_tab_exec);
        Button btnClear = view.findViewById(R.id.btn_clear_logs);

        btnTabUpload.setOnClickListener(v -> showTab(true));
        btnTabExec.setOnClickListener(v -> showTab(false));
        btnClear.setOnClickListener(v -> {
            logger.clearAll();
            Toast.makeText(requireContext(), "记录已清空", Toast.LENGTH_SHORT).show();
            refresh();
        });

        showTab(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 同步完成后回到本页时刷新
        refresh();
    }

    private void showTab(boolean uploads) {
        showingUploads = uploads;
        btnTabUpload.setAlpha(uploads ? 1f : 0.55f);
        btnTabExec.setAlpha(uploads ? 0.55f : 1f);
        refresh();
    }

    private void refresh() {
        if (logger == null || tvContent == null) return;
        List<String> lines = showingUploads
                ? logger.readRecentUploads(200)
                : logger.readRecentExec(200);

        if (lines.isEmpty()) {
            tvContent.setText(showingUploads
                    ? "暂无上传记录。\n执行一次同步后，这里会显示每张图片的上传结果。"
                    : "暂无执行日志。\n定时或手动同步后，这里会显示每次任务的执行汇总。");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append("\n\n");
        }
        tvContent.setText(sb.toString().trim());
    }
}
