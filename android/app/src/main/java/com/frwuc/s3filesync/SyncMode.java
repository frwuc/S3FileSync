package com.frwuc.s3filesync;

/**
 * 同步方向：仅上传 / 仅下载 / 双向。
 *
 * <p>纯 Java，刻意不依赖 Android API，便于在 JVM 单测里直接验证，也方便
 * 以 {@code name()} 字符串存进 SharedPreferences（{@link Prefs#KEY_SYNC_MODE}）。
 *
 * <p>默认「仅上传」是兼容考虑：老版本保存过配置的用户升级后行为不变。
 */
public enum SyncMode {

    /** 只把本地新增文件传到云端 */
    UPLOAD("仅上传"),
    /** 只把云端新增文件下载到本地 */
    DOWNLOAD("仅下载"),
    /** 先上传本地新增，再下载云端新增；双向互不覆盖已存在的文件 */
    BOTH("双向同步");

    /** 未设置 / 值异常时回落的默认方向 */
    public static final SyncMode DEFAULT = UPLOAD;

    public final String label;

    SyncMode(String label) {
        this.label = label;
    }

    public boolean doUpload() {
        return this != DOWNLOAD;
    }

    public boolean doDownload() {
        return this != UPLOAD;
    }

    /** 从保存的字符串还原；未设置或值不认识时一律回落到默认方向。 */
    public static SyncMode fromName(String name) {
        if (name != null) {
            for (SyncMode mode : values()) {
                if (mode.name().equals(name)) return mode;
            }
        }
        return DEFAULT;
    }

    /** 给设置页 Spinner 用的选项文案，顺序与 {@link #values()} 一致。 */
    public static String[] labels() {
        SyncMode[] modes = values();
        String[] out = new String[modes.length];
        for (int i = 0; i < modes.length; i++) out[i] = modes[i].label;
        return out;
    }
}
