package com.example.screenshotuploader;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS = 1001;
    private static final int REQ_BATTERY = 1002;

    private EditText etAccessKey, etSecretKey, etBucket, etDomain, etQiniuDir, etLocalDir;
    private Spinner spinnerLocalDir;
    private LinearLayout timesContainer;
    private TextView tvStatus;
    private Button btnAddTime, btnSave, btnExecuteNow, btnBattery, btnRecords, btnAbout;

    /** 常用本地截图目录，供下拉快速选择 */
    private static final String[] COMMON_LOCAL_DIRS = {
            "/sdcard/DCIM/Screenshots",
            "/sdcard/Pictures/Screenshots",
            "/sdcard/DCIM/Camera",
            "/sdcard/Pictures"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etAccessKey = findViewById(R.id.et_access_key);
        etSecretKey = findViewById(R.id.et_secret_key);
        etBucket = findViewById(R.id.et_bucket);
        etDomain = findViewById(R.id.et_domain);
        etQiniuDir = findViewById(R.id.et_qiniu_dir);
        etLocalDir = findViewById(R.id.et_local_dir);
        spinnerLocalDir = findViewById(R.id.spinner_local_dir);
        timesContainer = findViewById(R.id.times_container);
        tvStatus = findViewById(R.id.tv_status);
        btnAddTime = findViewById(R.id.btn_add_time);
        btnSave = findViewById(R.id.btn_save);
        btnExecuteNow = findViewById(R.id.btn_execute_now);
        btnBattery = findViewById(R.id.btn_battery);
        btnRecords = findViewById(R.id.btn_records);
        btnAbout = findViewById(R.id.btn_about);

        etAccessKey.setText(Prefs.getString(this, Prefs.KEY_ACCESS_KEY, QiniuConfig.ACCESS_KEY));
        etSecretKey.setText(Prefs.getString(this, Prefs.KEY_SECRET_KEY, QiniuConfig.SECRET_KEY));
        etBucket.setText(Prefs.getString(this, Prefs.KEY_BUCKET, QiniuConfig.BUCKET));
        etDomain.setText(Prefs.getString(this, Prefs.KEY_DOMAIN, QiniuConfig.DOMAIN));
        etQiniuDir.setText(Prefs.getString(this, Prefs.KEY_QINIU_DIR, QiniuConfig.UPLOAD_PREFIX));
        etLocalDir.setText(Prefs.getString(this, Prefs.KEY_LOCAL_DIR, COMMON_LOCAL_DIRS[0]));

        setupLocalDirSpinner();

        Set<String> savedTimes = Prefs.getStringSet(this, Prefs.KEY_TIMES, new HashSet<>());
        if (savedTimes.isEmpty()) {
            savedTimes.add("10:00");
            savedTimes.add("19:00");
        }
        List<String> times = new ArrayList<>(savedTimes);
        for (String t : times) {
            addTimeRow(t);
        }

        btnAddTime.setOnClickListener(v -> addTimeRow(""));

        btnSave.setOnClickListener(v -> {
            String qiniuDir = etQiniuDir.getText().toString().trim();
            String localDir = etLocalDir.getText().toString().trim();

            // 去掉云端目录可能误填的开头斜杠，避免拼出 // 开头的 key
            while (qiniuDir.startsWith("/")) qiniuDir = qiniuDir.substring(1);

            Prefs.putString(this, Prefs.KEY_ACCESS_KEY, etAccessKey.getText().toString().trim());
            Prefs.putString(this, Prefs.KEY_SECRET_KEY, etSecretKey.getText().toString().trim());
            Prefs.putString(this, Prefs.KEY_BUCKET, etBucket.getText().toString().trim());
            Prefs.putString(this, Prefs.KEY_DOMAIN, etDomain.getText().toString().trim());
            Prefs.putString(this, Prefs.KEY_QINIU_DIR, qiniuDir);
            Prefs.putString(this, Prefs.KEY_LOCAL_DIR, localDir);

            // 本地目录存在性提示（不阻断，目录可能稍后才生成）
            if (!localDir.isEmpty() && !new File(localDir).isDirectory()) {
                Toast.makeText(this, "提示：本地目录当前不存在，将按填写路径尝试扫描", Toast.LENGTH_LONG).show();
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
                Toast.makeText(this, "请至少设置一个有效的时间点（如 10:00、19:00）", Toast.LENGTH_LONG).show();
                return;
            }
            Prefs.putStringSet(this, Prefs.KEY_TIMES, new HashSet<>(newTimes));

            UploadScheduler.schedule(this, newTimes);

            tvStatus.setText("已保存 ✓  云端目录：" + (qiniuDir.isEmpty() ? "(根目录)" : qiniuDir)
                    + " ｜ 本地目录：" + localDir);
            Toast.makeText(this, "配置已保存，每天将在设定时间自动上传截图到七牛云", Toast.LENGTH_LONG).show();
        });

        btnExecuteNow.setOnClickListener(v -> {
            if (!hasPermissions()) {
                requestPermissions();
                return;
            }
            if (TextUtils.isEmpty(etAccessKey.getText()) || TextUtils.isEmpty(etSecretKey.getText())
                    || TextUtils.isEmpty(etBucket.getText())) {
                Toast.makeText(this, "请先填写 AccessKey、SecretKey、Bucket", Toast.LENGTH_LONG).show();
                return;
            }
            if (TextUtils.isEmpty(etLocalDir.getText())) {
                Toast.makeText(this, "请先填写本地截图目录", Toast.LENGTH_LONG).show();
                return;
            }
            // 先把当前输入的目录保存，确保服务读到最新值
            String qiniuDir = etQiniuDir.getText().toString().trim();
            while (qiniuDir.startsWith("/")) qiniuDir = qiniuDir.substring(1);
            Prefs.putString(this, Prefs.KEY_QINIU_DIR, qiniuDir);
            Prefs.putString(this, Prefs.KEY_LOCAL_DIR, etLocalDir.getText().toString().trim());

            Intent svc = new Intent(this, UploadService.class);
            svc.setAction(UploadService.ACTION_UPLOAD);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            tvStatus.setText("正在手动执行上传...");
            Toast.makeText(this, "开始执行上传任务，请查看通知栏", Toast.LENGTH_LONG).show();
        });

        btnBattery.setOnClickListener(v -> openBatteryOptimization());

        btnRecords.setOnClickListener(v ->
                startActivity(new Intent(this, RecordsActivity.class)));
        btnAbout.setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));

        updateStatus();
    }

    /**
     * 常用目录下拉：选中即填入下方输入框；选"自定义路径..."则清空输入框让用户手输。
     */
    private void setupLocalDirSpinner() {
        List<String> options = new ArrayList<>();
        for (String d : COMMON_LOCAL_DIRS) options.add(d);
        options.add("自定义路径...（在下方输入框填写）");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLocalDir.setAdapter(adapter);

        spinnerLocalDir.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < COMMON_LOCAL_DIRS.length) {
                    // 选中常用目录 -> 填入输入框（不覆盖正在编辑的自定义路径，若一致则跳过）
                    String dir = COMMON_LOCAL_DIRS[position];
                    if (!dir.equals(etLocalDir.getText().toString().trim())) {
                        etLocalDir.setText(dir);
                    }
                }
                // position == COMMON_LOCAL_DIRS.length 即"自定义路径..."，保留输入框当前内容供编辑
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        // 若已保存的本地目录不在常用列表里，下拉停留在"自定义路径..."
        String saved = etLocalDir.getText().toString().trim();
        int idx = -1;
        for (int i = 0; i < COMMON_LOCAL_DIRS.length; i++) {
            if (COMMON_LOCAL_DIRS[i].equals(saved)) { idx = i; break; }
        }
        spinnerLocalDir.setSelection(idx >= 0 ? idx : COMMON_LOCAL_DIRS.length);
    }

    private void addTimeRow(String defaultTime) {
        EditText et = new EditText(this);
        et.setHint("HH:mm 例如 10:00");
        if (!defaultTime.isEmpty()) et.setText(defaultTime);
        et.setSingleLine();
        et.setPadding(16, 16, 16, 16);
        timesContainer.addView(et);
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            boolean ok = true;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) updateStatus();
            else Toast.makeText(this, "需要存储权限才能读取截图", Toast.LENGTH_LONG).show();
        }
    }

    private void openBatteryOptimization() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_BATTERY);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开电池优化设置，请手动设置", Toast.LENGTH_LONG).show();
        }
    }

    private void updateStatus() {
        Set<String> times = Prefs.getStringSet(this, Prefs.KEY_TIMES, new HashSet<>());
        tvStatus.setText("定时时间：" + TextUtils.join(", ", times));
    }
}
