package com.example.screenshotuploader;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 关于界面：展示 App 项目信息、版本号、开源地址。
 */
public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("关于");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        TextView tvVersion = findViewById(R.id.tv_about_version);
        TextView tvGithub = findViewById(R.id.tv_about_github);
        TextView tvGitee = findViewById(R.id.tv_about_gitee);
        TextView tvLicense = findViewById(R.id.tv_about_license);

        // 版本号：从 BuildConfig / PackageManager 读取
        String versionName;
        try {
            versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            versionName = "1.0";
        }
        tvVersion.setText("当前版本：v" + versionName);

        // 点击开源地址跳转浏览器
        setupLink(tvGithub, AboutConfig.GITHUB_URL);
        setupLink(tvGitee, AboutConfig.GITEE_URL);
        tvLicense.setText("开源协议：" + AboutConfig.LICENSE);
    }

    private void setupLink(TextView view, final String url) {
        view.setText(url);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception ignored) {
                }
            }
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
