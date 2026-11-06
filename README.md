# 截图定时上传 (ScreenshotUploader)

[![Build APK](https://github.com/frwuc/screenshot-uploader/actions/workflows/android-build.yml/badge.svg)](https://github.com/frwuc/screenshot-uploader/actions/workflows/android-build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

一款开源的 **Android 截图自动备份工具**。定时扫描手机里的截图，自动上传到你的**七牛云**对象存储，实现截图的自动云端备份。

无需人工干预——设好时间点，App 每天到点自动把新增截图传到云端，再也不怕截图丢失或占满手机内存。

---

## ✨ 功能特性

- ⏰ **定时自动上传**：可设置多个时间点（如 10:00、19:00），到点自动扫描并上传新增截图
- ☁️ **七牛云备份**：图片上传到自己的七牛云 bucket，安全可控、容量充足
- 📁 **本地目录可选**：支持下拉选择常用截图目录，也可手动输入任意自定义路径
- 🗂️ **云端目录可选**：自由指定上传到七牛 bucket 下的哪个目录（key 前缀）
- 🔁 **增量上传**：已上传过的截图自动跳过，不重复上传、不浪费流量
- 🔐 **本地签发凭证**：内置上传凭证本地签发算法，无需自建服务器
- 🌐 **开机自启**：支持开机自动恢复定时任务，重启不丢配置
- 🔋 **电池白名单引导**：一键引导加入电池优化白名单，避免后台被系统杀死（vivo/OPPO 等国产 ROM 必做）
- 🎨 **卡片式界面**：分组清晰、操作简单的中文界面

---

## 📸 工作原理

```
手机截图 → [定时唤醒 App] → 扫描本地截图目录
                                  ↓
                          过滤已上传过的（增量）
                                  ↓
                        本地签发七牛上传凭证
                                  ↓
                      上传到七牛云 bucket/云端目录
                                  ↓
                          记录已上传，下次跳过
```

App 通过 **AlarmManager** 在设定时间点唤醒前台服务，扫描配置的本地目录，把尚未上传过的图片（`.jpg` `.jpeg` `.png` `.webp`）上传到七牛云。上传记录保存在本地，实现增量备份。

---

## 🚀 快速开始

### 方式一：下载现成 APK

1. 进入本仓库的 **[Actions](https://github.com/frwuc/screenshot-uploader/actions)** 页面
2. 点开最新一次成功的构建（绿色 ✅）
3. 在页面底部 **Artifacts** 区域下载 **`app-debug-apk`**
4. 解压 zip，得到 `app-debug.apk`，传到手机安装

> ⚠️ Debug 包用于个人测试；如需正式发布请自行配置签名出 Release 包。

### 方式二：自己构建

```bash
git clone https://github.com/frwuc/screenshot-uploader.git
cd screenshot-uploader
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

也可直接用 GitHub Actions 在线构建（推送代码即自动触发）。

---

## ⚙️ 配置说明

安装后打开 App，按下面三步配置：

### ① 七牛云配置

| 字段 | 说明 |
|------|------|
| **AccessKey (AK)** | 七牛云账号的 AccessKey |
| **SecretKey (SK)** | 七牛云账号的 SecretKey |
| **Bucket 空间名称** | 七牛云对象存储的空间名，如 `my-bucket` |
| **上传域名** | 一般填 `https://z0.qbox.me`（华东），其它区域见七牛文档 |
| **云端目录** | 上传到 bucket 下的哪个目录，如 `daka_screenshots`；留空则放根目录 |

> AK/SK 获取：登录 [七牛云控制台](https://portal.qiniu.com/) → 个人中心 → 密钥管理。
> Bucket 创建：对象存储 Kodo → 新建空间。

### ② 本地截图目录

- **下拉选择**常用目录：`DCIM/Screenshots`、`Pictures/Screenshots`、`DCIM/Camera`、`Pictures`
- 或选 **"自定义路径..."** 手动输入任意绝对路径（如 `/sdcard/WeiXin/截图`）

### ③ 定时上传时间

- 点 **"＋ 添加时间点"** 添加多个时间（格式 `HH:mm`，如 `10:00`、`19:00`）
- 到点自动上传新增截图

配置完成后点 **"保存配置并启用定时任务"** 即可。

> 💡 **vivo / OPPO / 小米** 等机型请务必点 **"设置电池优化白名单"**，否则后台定时任务容易被系统杀掉。

---

## 🔧 技术栈

| 项目 | 说明 |
|------|------|
| 语言 | Java |
| 最低支持 | Android 5.0 (API 21) |
| 目标版本 | Android 10 (API 29) |
| 构建工具 | Gradle 8.x + Android Gradle Plugin 8.1.x |
| 云存储 SDK | [qiniu-android-sdk](https://github.com/qiniu/android-sdk) |
| 凭证签发 | 按七牛官方算法本地实现（`QiniuToken`） |

### 项目结构

```
app/src/main/java/com/example/screenshotuploader/
├── MainActivity.java      # 主界面，配置与操作入口
├── UploadService.java     # 前台服务，执行扫描与上传
├── UploadScheduler.java   # 定时任务调度（AlarmManager）
├── AlarmReceiver.java     # 定时唤醒接收器
├── BootReceiver.java      # 开机自启，恢复定时任务
├── QiniuConfig.java       # 默认配置常量
├── QiniuToken.java        # 七牛上传凭证本地签发
├── QiniuUploader.java     # 七牛上传封装
└── Prefs.java             # 配置持久化
```

---

## 🔒 隐私与安全

- 你的 **AK/SK 仅保存在手机本地**（SharedPreferences），不会上传到任何第三方服务器
- 截图只上传到**你自己配置的七牛云 bucket**，不经手任何中间服务器
- 开源透明，所有逻辑可审计

> ⚠️ 请妥善保管 AK/SK，不要泄露给他人。如怀疑泄露请立即在七牛控制台重置密钥。

---

## ❓ 常见问题

**Q：为什么定时任务没有触发？**
A：国产 ROM（vivo/OPPO/小米/华为）后台管控严格，请先点 **"设置电池优化白名单"**，并在系统设置里允许 App 自启动、后台运行。

**Q：上传的图片在哪里？**
A：在七牛云控制台 → 对象存储 → 你的 bucket → 你设置的**云端目录**下，文件名为 `时间戳_原文件名`。

**Q：可以只上传某个文件夹的截图吗？**
A：可以。在"本地截图目录"选"自定义路径..."，填入该文件夹的绝对路径。

**Q：已上传的图片会被重复上传吗？**
A：不会。App 记录了已上传文件路径，重复的自动跳过。

**Q：需要自建服务器吗？**
A：不需要。上传凭证由 App 本地签发，直连七牛云。

---

## 📄 开源协议

本项目基于 **[MIT License](LICENSE)** 开源。

你可以自由使用、修改、分发本项目，但请保留原始版权声明。详见 [LICENSE](LICENSE) 文件。

---

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！无论是 Bug 报告、功能建议还是文档改进，都非常感谢。

---

## ⭐ 支持

如果这个项目对你有帮助，欢迎点个 **Star** ⭐ 支持一下！
