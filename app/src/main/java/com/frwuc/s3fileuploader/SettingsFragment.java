package com.frwuc.s3fileuploader;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 设置页：可折叠的配置表单（七牛云账号 / 本地目录 / 定时时间）+ 保存 + 电池白名单。
 */
public class SettingsFragment extends Fragment {

    private static final int REQ_BATTERY = 1002;
    private static final int REQ_STORAGE_FOR_BROWSE = 1003;

    private EditText etAccessKey, etSecretKey, etBucket, etDomain, etQiniuDir, etLocalDir;
    private Spinner spinnerLocalDir;
    private LinearLayout timesContainer;
    private Button btnAddTime, btnSave, btnBattery, btnScheduleHelper, btnBrowseLocal, btnBrowseCloud;
    private SwitchCompat switchAutoSync;

    /** 常用本地文件目录，供下拉快速选择 */
    private static final String[] COMMON_LOCAL_DIRS = {
            "/sdcard/DCIM/Screenshots",
            "/sdcard/Pictures/Screenshots",
            "/sdcard/DCIM/Camera",
            "/sdcard/Pictures"
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etAccessKey = view.findViewById(R.id.et_access_key);
        etSecretKey = view.findViewById(R.id.et_secret_key);
        etBucket = view.findViewById(R.id.et_bucket);
        etDomain = view.findViewById(R.id.et_domain);
        etQiniuDir = view.findViewById(R.id.et_qiniu_dir);
        etLocalDir = view.findViewById(R.id.et_local_dir);
        spinnerLocalDir = view.findViewById(R.id.spinner_local_dir);
        timesContainer = view.findViewById(R.id.times_container);
        btnAddTime = view.findViewById(R.id.btn_add_time);
        btnSave = view.findViewById(R.id.btn_save);
        btnBattery = view.findViewById(R.id.btn_battery);
        btnScheduleHelper = view.findViewById(R.id.btn_schedule_helper);
        btnBrowseLocal = view.findViewById(R.id.btn_browse_local);
        btnBrowseCloud = view.findViewById(R.id.btn_browse_cloud);
        switchAutoSync = view.findViewById(R.id.switch_auto_sync);

        loadConfig();
        setupLocalDirSpinner();
        setupCollapsible(view);
        setupAutoSyncSwitch();

        // 载入定时时间
        Set<String> savedTimes = Prefs.getStringSet(requireContext(), Prefs.KEY_TIMES, new HashSet<>());
        if (savedTimes.isEmpty()) {
            savedTimes.add("09:00");
            savedTimes.add("18:20");
        }
        for (String t : savedTimes) addTimeRow(t);

        btnAddTime.setOnClickListener(v -> addTimeRow(""));
        btnSave.setOnClickListener(v -> saveConfig());
        btnBattery.setOnClickListener(v -> openBatteryOptimization());
        btnScheduleHelper.setOnClickListener(v -> ScheduleHelperDialog.show(this));
        btnBrowseLocal.setOnClickListener(v -> browseLocalDir());
        btnBrowseCloud.setOnClickListener(v -> browseCloudDir());
    }

    // ------------------------------------------------------------ 目录浏览

    /**
     * 打开本地文件夹浏览器选择同步目录；无存储权限时先申请，授权后自动继续打开。
     */
    private void browseLocalDir() {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQ_STORAGE_FOR_BROWSE);
            return;
        }
        String start = etLocalDir.getText().toString().trim();
        BrowserDialog.showLocal(requireContext(), start, true, path -> {
            etLocalDir.setText(path);
            Toast.makeText(requireContext(),
                    "已选择本地文件夹：" + path + "\n记得点下方「保存配置」",
                    Toast.LENGTH_LONG).show();
        });
    }

    /**
     * 打开云端目录浏览器：用输入框里当前的 AK/SK/Bucket 现场列举，不要求先保存配置。
     */
    private void browseCloudDir() {
        String ak = etAccessKey.getText().toString().trim();
        String sk = etSecretKey.getText().toString().trim();
        String bucket = etBucket.getText().toString().trim();
        if (TextUtils.isEmpty(ak) || TextUtils.isEmpty(sk) || TextUtils.isEmpty(bucket)) {
            Toast.makeText(requireContext(),
                    "请先填写 AK / SK / Bucket，再浏览云端目录", Toast.LENGTH_LONG).show();
            return;
        }
        BrowserDialog.showCloud(requireContext(), ak, sk, bucket,
                etQiniuDir.getText().toString().trim(), true, prefix -> {
                    etQiniuDir.setText(prefix);
                    Toast.makeText(requireContext(),
                            "已选择云端目录：" + (prefix.isEmpty() ? "（bucket 根目录）" : prefix)
                                    + "\n记得点下方「保存配置」",
                            Toast.LENGTH_LONG).show();
                });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE_FOR_BROWSE) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            browseLocalDir();
        } else {
            Toast.makeText(requireContext(),
                    "未授予存储权限，无法浏览本地文件夹（仍可手动输入路径）",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void loadConfig() {
        etAccessKey.setText(Prefs.getString(requireContext(), Prefs.KEY_ACCESS_KEY, QiniuConfig.ACCESS_KEY));
        etSecretKey.setText(Prefs.getString(requireContext(), Prefs.KEY_SECRET_KEY, QiniuConfig.SECRET_KEY));
        etBucket.setText(Prefs.getString(requireContext(), Prefs.KEY_BUCKET, QiniuConfig.BUCKET));
        etDomain.setText(Prefs.getString(requireContext(), Prefs.KEY_DOMAIN, QiniuConfig.DOMAIN));
        etQiniuDir.setText(Prefs.getString(requireContext(), Prefs.KEY_QINIU_DIR, QiniuConfig.UPLOAD_PREFIX));
        etLocalDir.setText(Prefs.getString(requireContext(), Prefs.KEY_LOCAL_DIR, COMMON_LOCAL_DIRS[0]));
    }

    /** 三个分区标题点击 -> 展开/收起对应内容区，并切换箭头方向 */
    private void setupCollapsible(View view) {
        bindCollapse(view, R.id.header_qiniu, R.id.body_qiniu, R.id.arrow_qiniu);
        bindCollapse(view, R.id.header_local, R.id.body_local, R.id.arrow_local);
        bindCollapse(view, R.id.header_times, R.id.body_times, R.id.arrow_times);
    }

    private void bindCollapse(View root, int headerId, int bodyId, int arrowId) {
        View header = root.findViewById(headerId);
        View body = root.findViewById(bodyId);
        TextView arrow = root.findViewById(arrowId);
        header.setOnClickListener(v -> {
            boolean expanded = body.getVisibility() == View.VISIBLE;
            body.setVisibility(expanded ? View.GONE : View.VISIBLE);
            arrow.setText(expanded ? "▸" : "▾");
        });
    }

    private void setupLocalDirSpinner() {
        List<String> options = new ArrayList<>();
        for (String d : COMMON_LOCAL_DIRS) options.add(d);
        options.add("自定义路径...（在下方输入框填写）");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLocalDir.setAdapter(adapter);

        spinnerLocalDir.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < COMMON_LOCAL_DIRS.length) {
                    String dir = COMMON_LOCAL_DIRS[position];
                    if (!dir.equals(etLocalDir.getText().toString().trim())) {
                        etLocalDir.setText(dir);
                    }
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        String saved = etLocalDir.getText().toString().trim();
        int idx = -1;
        for (int i = 0; i < COMMON_LOCAL_DIRS.length; i++) {
            if (COMMON_LOCAL_DIRS[i].equals(saved)) { idx = i; break; }
        }
        spinnerLocalDir.setSelection(idx >= 0 ? idx : COMMON_LOCAL_DIRS.length);
    }

    private void addTimeRow(String defaultTime) {
        EditText et = new EditText(requireContext());
        et.setHint("HH:mm 例如 10:00");
        if (!defaultTime.isEmpty()) et.setText(defaultTime);
        et.setSingleLine();
        et.setPadding(16, 16, 16, 16);
        timesContainer.addView(et);
    }

    private void saveConfig() {
        String qiniuDir = etQiniuDir.getText().toString().trim();
        String localDir = etLocalDir.getText().toString().trim();

        // 云端目录统一存「无首尾斜杠」的形式，上传时再拼 "/"，避免出现 a//b 这样的 key
        while (qiniuDir.startsWith("/")) qiniuDir = qiniuDir.substring(1);
        while (qiniuDir.endsWith("/")) qiniuDir = qiniuDir.substring(0, qiniuDir.length() - 1);

        Prefs.putString(requireContext(), Prefs.KEY_ACCESS_KEY, etAccessKey.getText().toString().trim());
        Prefs.putString(requireContext(), Prefs.KEY_SECRET_KEY, etSecretKey.getText().toString().trim());
        Prefs.putString(requireContext(), Prefs.KEY_BUCKET, etBucket.getText().toString().trim());
        Prefs.putString(requireContext(), Prefs.KEY_DOMAIN, etDomain.getText().toString().trim());
        Prefs.putString(requireContext(), Prefs.KEY_QINIU_DIR, qiniuDir);
        Prefs.putString(requireContext(), Prefs.KEY_LOCAL_DIR, localDir);

        if (!localDir.isEmpty() && !new File(localDir).isDirectory()) {
            Toast.makeText(requireContext(), "提示：本地目录当前不存在，将按填写路径尝试扫描", Toast.LENGTH_LONG).show();
        }

        List<String> newTimes = new ArrayList<>();
        for (int i = 0; i < timesContainer.getChildCount(); i++) {
            View child = timesContainer.getChildAt(i);
            if (child instanceof EditText) {
                String t = ((EditText) child).getText().toString().trim();
                if (UploadScheduler.isValidTime(t)) newTimes.add(t);
            }
        }
        if (newTimes.isEmpty()) {
            Toast.makeText(requireContext(), "请至少设置一个有效的时间点（如 10:00、19:00）", Toast.LENGTH_LONG).show();
            return;
        }
        Prefs.putStringSet(requireContext(), Prefs.KEY_TIMES, new HashSet<>(newTimes));

        UploadScheduler.schedule(requireContext(), newTimes);
        Toast.makeText(requireContext(), "配置已保存，每天将在设定时间自动上传新增文件到七牛云", Toast.LENGTH_LONG).show();
    }

    /**
     * 「打开 App 时自动同步」开关：回填已保存状态，切换后立即写入，不依赖「保存配置」。
     */
    private void setupAutoSyncSwitch() {
        // 先回填再挂监听，避免初始化时触发一次无意义的写入
        switchAutoSync.setChecked(AutoSync.isEnabled(requireContext()));
        switchAutoSync.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AutoSync.setEnabled(requireContext(), isChecked);
            Toast.makeText(requireContext(),
                    isChecked ? R.string.auto_sync_state_on : R.string.auto_sync_state_off,
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void openBatteryOptimization() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
            startActivityForResult(intent, REQ_BATTERY);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "无法打开电池优化设置，请手动设置", Toast.LENGTH_LONG).show();
        }
    }
}
