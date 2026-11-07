package com.example.screenshotuploader;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 主界面：底部导航（同步/设置/日志/关于）+ Fragment 容器，默认打开同步页。
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            Fragment selected;
            int id = item.getItemId();
            if (id == R.id.nav_settings) {
                selected = new SettingsFragment();
            } else if (id == R.id.nav_logs) {
                selected = new LogsFragment();
            } else if (id == R.id.nav_about) {
                selected = new AboutFragment();
            } else {
                selected = new SyncFragment();
            }
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, selected)
                    .commit();
            return true;
        });

        // 默认打开「同步」页
        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_sync);
        }
    }
}
