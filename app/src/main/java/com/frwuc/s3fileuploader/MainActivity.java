package com.frwuc.s3fileuploader;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 主界面：底部 5 个一级页面 + Fragment 容器，默认打开「同步任务」。
 *
 * 入口 / 标题 / 内容三者固定对应，顺序与菜单 {@code res/menu/bottom_nav.xml} 一致：
 * 同步任务、执行记录、日志记录、设置、关于。
 */
public class MainActivity extends AppCompatActivity {

    /** 当前展示的页签，首次进入前为 0。 */
    private int currentTabId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });

        // 默认打开「同步任务」页
        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_sync);
        }
    }

    /** 切换到指定页签：标题栏文字与页面内的头部标题保持一致。 */
    private void showTab(int itemId) {
        setTitle(titleOf(itemId));
        if (itemId == currentTabId) return;
        currentTabId = itemId;

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, createFragment(itemId))
                .commit();
    }

    @NonNull
    private Fragment createFragment(int itemId) {
        if (itemId == R.id.nav_exec) {
            return new ExecutionFragment();
        }
        if (itemId == R.id.nav_log) {
            return new LogsFragment();
        }
        if (itemId == R.id.nav_settings) {
            return new SettingsFragment();
        }
        if (itemId == R.id.nav_about) {
            return new AboutFragment();
        }
        return new SyncFragment();
    }

    @StringRes
    private int titleOf(int itemId) {
        if (itemId == R.id.nav_exec) {
            return R.string.title_execution;
        }
        if (itemId == R.id.nav_log) {
            return R.string.title_logs;
        }
        if (itemId == R.id.nav_settings) {
            return R.string.title_settings;
        }
        if (itemId == R.id.nav_about) {
            return R.string.title_about;
        }
        return R.string.title_sync;
    }
}
