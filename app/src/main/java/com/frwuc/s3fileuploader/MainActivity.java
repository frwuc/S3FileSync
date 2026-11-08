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

    private static final String STATE_TAB = "state_current_tab";

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

        if (savedInstanceState == null) {
            // 默认打开「同步任务」页
            bottomNav.setSelectedItemId(R.id.nav_sync);
            // 打开 App：按「设置 → 打开 App 时自动同步」决定是否立即同步一次。
            // 开关默认关闭；关闭时不发起任何上传请求。
            AutoSync.onMainEntered(this);
        } else {
            // 重建（如旋转屏幕）：Fragment 由 FragmentManager 恢复，
            // 这里只补回页签记录与标题，避免重复创建页面
            currentTabId = savedInstanceState.getInt(STATE_TAB, R.id.nav_sync);
            setTitle(titleOf(currentTabId));
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTabId);
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
