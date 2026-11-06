package com.example.screenshotuploader;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * 记录界面：分「上传记录」「执行日志」两个标签查看本地记录，并可一键清空。
 */
public class RecordsActivity extends AppCompatActivity {

    private UploadLogger logger;
    private TextView tvContent;
    private Button btnTabUpload, btnTabExec, btnClear;
    private boolean showingUploads = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_records);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("上传记录");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        logger = new UploadLogger(this);
        tvContent = findViewById(R.id.tv_records_content);
        btnTabUpload = findViewById(R.id.btn_tab_upload);
        btnTabExec = findViewById(R.id.btn_tab_exec);
        btnClear = findViewById(R.id.btn_clear_records);

        btnTabUpload.setOnClickListener(v -> showTab(true));
        btnTabExec.setOnClickListener(v -> showTab(false));
        btnClear.setOnClickListener(v -> {
            logger.clearAll();
            Toast.makeText(this, "记录已清空", Toast.LENGTH_SHORT).show();
            refresh();
        });

        showTab(true);
    }

    private void showTab(boolean uploads) {
        showingUploads = uploads;
        // 高亮当前标签
        btnTabUpload.setAlpha(uploads ? 1f : 0.55f);
        btnTabExec.setAlpha(uploads ? 0.55f : 1f);
        refresh();
    }

    private void refresh() {
        List<String> lines = showingUploads
                ? logger.readRecentUploads(200)
                : logger.readRecentExec(200);

        if (lines.isEmpty()) {
            tvContent.setText(showingUploads
                    ? "暂无上传记录。\n执行一次上传后，这里会显示每张图片的上传结果。"
                    : "暂无执行日志。\n定时或手动触发上传后，这里会显示每次任务的执行汇总。");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append("\n\n");
        }
        tvContent.setText(sb.toString().trim());
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
