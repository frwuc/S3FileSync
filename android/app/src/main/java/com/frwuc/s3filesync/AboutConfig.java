package com.frwuc.s3filesync;

/**
 * 项目/应用元信息集中管理。
 */
public final class AboutConfig {
    private AboutConfig() {}

    public static final String APP_NAME = "S3FileSync";
    public static final String APP_NAME_EN = "S3FileSync";

    /** 联系邮箱 */
    public static final String EMAIL = "frwucc@163.com";

    /** 开源地址 */
    public static final String GITHUB_URL = "https://github.com/frwuc/S3FileSync";
    public static final String GITEE_URL = "https://gitee.com/frwuc/S3FileSync";

    public static final String LICENSE = "MIT License";

    /** 项目简介 */
    public static final String DESCRIPTION =
            "一款开源的 Android 文件同步工具。支持仅上传 / 仅下载 / 双向三种方向，按设定时间自动补齐本地目录与七牛云对象存储之间的新增文件，两端文件名始终保持一致。";
}
