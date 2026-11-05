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
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS = 1001;
    private static final int REQ_BATTERY = 1002;

    private EditText etAccessKey, etSecretKey, etBucket, etDomain;
    private LinearLayout timesContainer;
    private TextView tvStatus;
    private Button btnAddTime, btnSave, btnExecuteNow, btnBattery;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etAccessKey = findViewById(R.id.et_access_key);
        etSecretKey = findViewById(R.id.et_secret_key);
        etBucket = findViewById(R.id.et_bucket);
        etDomain = findViewById(R.id.et_domain);
        timesContainer = findViewById(R.id.times_container);
        tvStatus = findViewById(R.id.tv_status);
        btnAddTime = findViewById(R.id.btn_add_time);
        btnSave = findViewById(R.id.btn_save);
        btnExecuteNow = findViewById(R.id.btn_execute_now);
        btnBattery = findViewById(R.id.btn_battery);

        etAccessKey.setText(Prefs.getString(this, Prefs.KEY_ACCESS_KEY, QiniuConfig.ACCESS_KEY));
        etSecretKey.setText(Prefs.getString(this, Prefs.KEY_SECRET_KEY, QiniuConfig.SECRET_KEY));
        etBucket.setText(Prefs.getString(this, Prefs.KEY_BUCKET, QiniuConfig.BUCKET));
        etDomain.setText(Prefs.getString(this, Prefs.KEY_DOMAIN, QiniuConfig.DOMAIN));

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
            Prefs.putString(this, Prefs.KEY_ACCESS_KEY, etAccessKey.getText().toString().trim());
            Prefs.putString(this, Prefs.KEY_SECRET_KEY, etSecretKey.getText().toString().trim());
            Prefs.putString(this, Prefs.KEY_BUCKET, etBucket.getText().toString().trim());
            Prefs.putString(this, Prefs.KEY_DOMAIN, etDomain.getText().toString().trim());

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

            tvStatus.setText("已保存，定时任务已设置");
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

        updateStatus();
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
