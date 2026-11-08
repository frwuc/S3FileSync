package com.frwuc.s3fileuploader;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 「打开 App 时自动同步」判定逻辑的单元测试。
 *
 * <p>只覆盖 {@link AutoSync#decide} 这个纯函数，不依赖 Android 运行时：
 * 重点守住「开关关闭时任何情况都不触发」与各校验的优先级。
 */
public class AutoSyncDecisionTest {

    @Test
    public void 关闭时一律不触发() {
        // 即使配置完整、有权限、当前没任务在跑，关闭状态也必须跳过
        assertEquals(AutoSync.SkipReason.DISABLED,
                AutoSync.decide(false, false, true, true));
        // 其他条件再差也还是 DISABLED，不会因为别的原因去发起同步
        assertEquals(AutoSync.SkipReason.DISABLED,
                AutoSync.decide(false, true, false, false));
    }

    @Test
    public void 开启且条件齐备时触发() {
        assertEquals(AutoSync.SkipReason.NONE,
                AutoSync.decide(true, false, true, true));
    }

    @Test
    public void 已有任务在跑时跳过() {
        assertEquals(AutoSync.SkipReason.ALREADY_RUNNING,
                AutoSync.decide(true, true, true, true));
        // 运行中优先于配置/权限检查，避免重复上传
        assertEquals(AutoSync.SkipReason.ALREADY_RUNNING,
                AutoSync.decide(true, true, false, false));
    }

    @Test
    public void 配置不完整优先于权限问题提示() {
        assertEquals(AutoSync.SkipReason.INCOMPLETE_CONFIG,
                AutoSync.decide(true, false, false, false));
    }

    @Test
    public void 缺权限时跳过() {
        assertEquals(AutoSync.SkipReason.NO_PERMISSION,
                AutoSync.decide(true, false, true, false));
    }
}
