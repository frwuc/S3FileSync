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

    /** 权限申请回来之后要继续执行的动作 */
    private boolean pendingBrowseLocal = false;
    private boolean pendingSync = false;

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
        Button btnBrowseLocal = view.findViewById(R.id.btn_browse_local);
        Button btnBrowseCloud = view.findViewById(R.id.btn_browse_cloud);

        btnSync.setOnClickListener(v -> doSync());
        btnBrowseLocal.setOnClickListener(v -> browseLocal());
        btnBrowseCloud.setOnClickListener(v -> browseCloud());
        view.findViewById(R.id.btn_schedule_helper)
                .setOnClickListener(v -> ScheduleHelperDialog.show(this));

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

        // 自动同步是后台起的，这里顺带把「正在跑」的状态反映到页面上
        if (UploadService.isRunning()) {
            tvStatus.setText("状态：正在同步，请查看通知栏...");
        } else {
            tvStatus.setText("状态：" + (configured ? "已配置，就绪" : "未完成配置"));
        }
    }

    private void doSync() {
        if (!hasPermissions()) {
            pendingSync = true;
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
            Toast.makeText(requireContext(), "请先到「设置」填写本地文件目录", Toast.LENGTH_LONG).show();
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) return;

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            pendingBrowseLocal = false;
            pendingSync = false;
            Toast.makeText(requireContext(), "需要存储权限才能读取本地文件夹",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (pendingBrowseLocal) {
            pendingBrowseLocal = false;
            browseLocal();
        } else if (pendingSync) {
            pendingSync = false;
            doSync();
        }
    }

    // ------------------------------------------------------------ 浏览两端内容

    /** 浏览本地同步文件夹内容；顺手支持换一个目录（选中即保存）。 */
    private void browseLocal() {
        if (!hasPermissions()) {
            pendingBrowseLocal = true;
            requestPermissions();
            return;
        }
        String current = Prefs.getString(requireContext(), Prefs.KEY_LOCAL_DIR, "");
        BrowserDialog.showLocal(requireContext(), current, true, path -> {
            Prefs.putString(requireContext(), Prefs.KEY_LOCAL_DIR, path);
            refreshSummary();
            Toast.makeText(requireContext(), "已切换本地同步目录：" + path,
                    Toast.LENGTH_LONG).show();
        });
    }

    /** 浏览云端目录内容；同样可以逐级进入并选中作为上传目录。 */
    private void browseCloud() {
        String ak = Prefs.getString(requireContext(), Prefs.KEY_ACCESS_KEY, QiniuConfig.ACCESS_KEY);
        String sk = Prefs.getString(requireContext(), Prefs.KEY_SECRET_KEY, QiniuConfig.SECRET_KEY);
        String bucket = Prefs.getString(requireContext(), Prefs.KEY_BUCKET, QiniuConfig.BUCKET);
        if (TextUtils.isEmpty(ak) || TextUtils.isEmpty(sk) || TextUtils.isEmpty(bucket)) {
            Toast.makeText(requireContext(), "请先到「设置」填写七牛云 AK / SK / Bucket",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String current = Prefs.getString(requireContext(), Prefs.KEY_QINIU_DIR, "");
        BrowserDialog.showCloud(requireContext(), ak, sk, bucket, current, true, prefix -> {
            Prefs.putString(requireContext(), Prefs.KEY_QINIU_DIR, prefix);
            refreshSummary();
            Toast.makeText(requireContext(),
                    "已切换云端目录：" + (prefix.isEmpty() ? "（bucket 根目录）" : prefix),
                    Toast.LENGTH_LONG).show();
        });
    }
}
