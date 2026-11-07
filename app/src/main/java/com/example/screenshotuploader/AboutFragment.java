package com.example.screenshotuploader;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * 关于页：App 项目信息、版本号、开源地址。
 */
public class AboutFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_about, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView tvVersion = view.findViewById(R.id.tv_about_version);
        TextView tvGithub = view.findViewById(R.id.tv_about_github);
        TextView tvGitee = view.findViewById(R.id.tv_about_gitee);
        TextView tvLicense = view.findViewById(R.id.tv_about_license);

        // 版本号：从 PackageManager 读取
        String versionName;
        try {
            versionName = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Exception e) {
            versionName = "1.0";
        }
        tvVersion.setText("当前版本：v" + versionName);

        setupLink(tvGithub, AboutConfig.GITHUB_URL);
        setupLink(tvGitee, AboutConfig.GITEE_URL);
        tvLicense.setText("开源协议：" + AboutConfig.LICENSE);
    }

    private void setupLink(TextView view, final String url) {
        view.setText(url);
        view.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } catch (Exception ignored) {
            }
        });
    }
}
