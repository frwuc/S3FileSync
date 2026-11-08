package com.example.screenshotuploader;

/**
 * 项目/应用元信息集中管理。
 */
public final class AboutConfig {
    private AboutConfig() {}

    public static final String APP_NAME = "S3-fileuploader";
    public static final String APP_NAME_EN = "S3-fileuploader";

    /** 联系邮箱 */
    public static final String EMAIL = "frwucc@163.com";

    /** 开源地址 */
    public static final String GITHUB_URL = "https://github.com/frwuc/screenshot-uploader";
    public static final String GITEE_URL = "https://gitee.com/frwuc/screenshot-uploader";

    public static final String LICENSE = "MIT License";

    /** 项目简介 */
    public static final String DESCRIPTION =
            "一款开源的 Android 截图自动备份工具。定时扫描手机里的截图，自动上传到你的七牛云对象存储，实现截图的自动云端备份。";
}
