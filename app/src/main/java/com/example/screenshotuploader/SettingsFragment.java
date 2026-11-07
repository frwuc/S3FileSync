package com.example.screenshotuploader;

import android.content.Intent;
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

    private EditText etAccessKey, etSecretKey, etBucket, etDomain, etQiniuDir, etLocalDir;
    private Spinner spinnerLocalDir;
    private LinearLayout timesContainer;
    private Button btnAddTime, btnSave, btnBattery;

    /** 常用本地截图目录，供下拉快速选择 */
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

        loadConfig();
        setupLocalDirSpinner();
        setupCollapsible(view);

        // 载入定时时间
        Set<String> savedTimes = Prefs.getStringSet(requireContext(), Prefs.KEY_TIMES, new HashSet<>());
        if (savedTimes.isEmpty()) {
            savedTimes.add("10:00");
            savedTimes.add("19:00");
        }
        for (String t : savedTimes) addTimeRow(t);

        btnAddTime.setOnClickListener(v -> addTimeRow(""));
        btnSave.setOnClickListener(v -> saveConfig());
        btnBattery.setOnClickListener(v -> openBatteryOptimization());
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

        while (qiniuDir.startsWith("/")) qiniuDir = qiniuDir.substring(1);

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
        Toast.makeText(requireContext(), "配置已保存，每天将在设定时间自动上传截图到七牛云", Toast.LENGTH_LONG).show();
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
