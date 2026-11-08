package com.frwuc.s3fileuploader;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.util.Set;

/**
 * 同步主页（默认页）：展示当前配置摘要 + 立即同步按钮 + 运行状态。
 */
public class SyncFragment extends Fragment {

    private static final int REQ_PERMISSIONS = 1001;

    private TextView tvBucket, tvDirs, tvCloud, tvTimes, tvStatus, tvHint;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_sync, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvBucket = view.findViewById(R.id.tv_sync_bucket);
        tvDirs = view.findViewById(R.id.tv_sync_dirs);
        tvCloud = view.findViewById(R.id.tv_sync_cloud);
        tvTimes = view.findViewById(R.id.tv_sync_times);
        tvStatus = view.findViewById(R.id.tv_sync_status);
        tvHint = view.findViewById(R.id.tv_sync_hint);
        Button btnSync = view.findViewById(R.id.btn_sync_now);

        btnSync.setOnClickListener(v -> doSync());

        refreshSummary();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 从设置页返回时刷新摘要
        refreshSummary();
    }

    private void refreshSummary() {
        if (getView() == null) return;
        String bucket = Prefs.getString(requireContext(), Prefs.KEY_BUCKET, "");
        String localDir = Prefs.getString(requireContext(), Prefs.KEY_LOCAL_DIR, "");
        String qiniuDir = Prefs.getString(requireContext(), Prefs.KEY_QINIU_DIR, "");
        Set<String> times = Prefs.getStringSet(requireContext(), Prefs.KEY_TIMES, null);

        tvBucket.setText("Bucket：" + (TextUtils.isEmpty(bucket) ? "未设置" : bucket));
        tvDirs.setText("本地目录：" + (TextUtils.isEmpty(localDir) ? "未设置" : localDir));
        tvCloud.setText("云端目录：" + (TextUtils.isEmpty(qiniuDir) ? "(根目录)" : qiniuDir));
        tvTimes.setText("定时时间：" + (times == null || times.isEmpty() ? "未设置" : TextUtils.join(", ", times)));

        // 判断是否已配置完整
        boolean configured = !TextUtils.isEmpty(bucket)
                && !TextUtils.isEmpty(localDir)
                && !TextUtils.isEmpty(Prefs.getString(requireContext(), Prefs.KEY_ACCESS_KEY, ""))
                && !TextUtils.isEmpty(Prefs.getString(requireContext(), Prefs.KEY_SECRET_KEY, ""));
        tvHint.setVisibility(configured ? View.GONE : View.VISIBLE);

        tvStatus.setText("状态：" + (configured ? "已配置，就绪" : "未完成配置"));
    }

    private void doSync() {
        if (!hasPermissions()) {
            requestPermissions();
            return;
        }
        String bucket = Prefs.getString(requireContext(), Prefs.KEY_BUCKET, "");
        String ak = Prefs.getString(requireContext(), Prefs.KEY_ACCESS_KEY, "");
        String sk = Prefs.getString(requireContext(), Prefs.KEY_SECRET_KEY, "");
        String localDir = Prefs.getString(requireContext(), Prefs.KEY_LOCAL_DIR, "");

        if (TextUtils.isEmpty(ak) || TextUtils.isEmpty(sk) || TextUtils.isEmpty(bucket)) {
            Toast.makeText(requireContext(), "请先到「设置」填写七牛云 AK/SK/Bucket", Toast.LENGTH_LONG).show();
            return;
        }
        if (TextUtils.isEmpty(localDir)) {
            Toast.makeText(requireContext(), "请先到「设置」填写本地截图目录", Toast.LENGTH_LONG).show();
            return;
        }

        Intent svc = new Intent(requireContext(), UploadService.class);
        svc.setAction(UploadService.ACTION_UPLOAD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(svc);
        } else {
            requireContext().startService(svc);
        }
        tvStatus.setText("状态：正在同步，请查看通知栏...");
        Toast.makeText(requireContext(), "开始同步，请查看通知栏", Toast.LENGTH_SHORT).show();
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(requireActivity(),
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERMISSIONS);
    }
}
