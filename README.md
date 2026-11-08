# s3-fileuploader（文件定时上传）

[![Build APK](https://github.com/frwuc/s3-fileuploader/actions/workflows/android-build.yml/badge.svg)](https://github.com/frwuc/s3-fileuploader/actions/workflows/android-build.yml)
[![Gitee](https://img.shields.io/badge/Gitee-frwuc%2Fs3--fileuploader-C71D23.svg)](https://gitee.com/frwuc/s3-fileuploader)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

一款开源的 **Android 文件自动备份工具**。定时扫描手机里的指定目录，自动把新增文件上传到你的**七牛云**对象存储，实现文件自动云端备份。

无需人工干预——设好时间点，App 每天到点自动把新增文件传到云端，再也不怕文件丢失或占满手机空间。

---

## ✨ 功能特性

- ⏰ **定时自动上传**：可设置多个时间点（如 10:00、19:00），到点自动扫描并上传新增文件
- ☁️ **七牛云备份**：文件上传到自己的七牛云 bucket，安全可控、容量充足
- 📁 **本地目录可选**：支持下拉选择常用文件目录，也可手动输入任意自定义路径
- 🗂️ **云端目录可选**：自由指定上传到七牛 bucket 下的哪个目录（key 前缀）
- 🔁 **增量上传**：已上传过的文件自动跳过，不重复上传、不浪费流量
- 🔐 **本地签发凭证**：内置上传凭证本地签发算法，无需自建服务器
- 🌐 **开机自启**：支持开机自动恢复定时任务，重启不丢配置
- 🔋 **电池白名单引导**：一键引导加入电池优化白名单，避免后台被系统杀死（vivo/OPPO 等国产 ROM 必做）
- 🎨 **五页签界面**：同步任务 / 执行记录 / 日志记录 / 设置 / 关于，入口、标题与内容一一对应
- 🚀 **打开即同步（可选，默认关闭）**：开启后启动 App 首次进入界面即自动上传一次新增文件，旋转屏幕 / 切后台回来不会重复触发

---

## 🔄 工作原理

```
新增文件 → [定时唤醒 App] → 扫描本地文件目录
                                  ↓
                          过滤已上传过的（增量）
                                  ↓
                        本地签发七牛上传凭证
                                  ↓
                      上传到七牛云 bucket/云端目录
                                  ↓
                          记录已上传，下次跳过
```

App 通过 **AlarmManager** 在设定时间点唤醒前台服务，扫描配置的本地目录，把尚未上传过的文件（`.jpg` `.jpeg` `.png` `.webp`）上传到七牛云。上传记录保存在本地，实现增量备份。

---

## 🧭 界面结构（底部 5 个页签）

| 页签 | 标题 | 内容 |
|------|------|------|
| 同步任务 | ⚡ 同步任务 | 当前配置摘要（Bucket / 本地目录 / 云端目录 / 定时时间）+ 立即同步 + 运行状态 |
| 执行记录 | 🗂 执行记录 | **每次任务**的触发时间与结果汇总（成功 / 失败 / 跳过），顶部带累计统计卡片 |
| 日志记录 | 📄 日志记录 | **每个文件**的上传明细，含云端 key 与访问地址，成功 / 失败用颜色区分 |
| 设置 | 设置 | 七牛云账号、本地文件目录、定时时间三个可折叠分区 + 打开 App 自动同步开关 + 电池白名单 |
| 关于 | 关于 | 版本号、开源仓库、联系邮箱、隐私说明 |

「执行记录」与「日志记录」分别对应 `exec.log` 与 `uploads.log`，两页各自独立清空，互不影响。

---

## 🚀 快速开始

### 方式一：下载现成 APK

1. 进入本仓库的 **[Actions](https://github.com/frwuc/s3-fileuploader/actions)** 页面
2. 点开最新一次成功的构建（绿色 ✅）
3. 在页面底部 **Artifacts** 区域下载 **`s3-fileuploader`**
4. 解压 zip，得到 `s3-fileuploader.apk`，传到手机安装

> 🇨🇳 国内网络不便访问 GitHub 时，可直接在 **[Gitee Releases](https://gitee.com/frwuc/s3-fileuploader/releases)** 下载 `s3-fileuploader-1.5.0.apk`，无需登录。
>
> ⚠️ 附件为 debug 签名包，用于个人测试；如需正式发布请自行配置签名出 Release 包。

### 方式二：自己构建

```bash
# Gitee（国内推荐）
git clone https://gitee.com/frwuc/s3-fileuploader.git
# 或 GitHub
git clone https://github.com/frwuc/s3-fileuploader.git
cd s3-fileuploader
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/s3-fileuploader.apk
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
| **云端目录** | 上传到 bucket 下的哪个目录，如 `daka_files`；留空则放根目录 |

> AK/SK 获取：登录 [七牛云控制台](https://portal.qiniu.com/) → 个人中心 → 密钥管理。
> Bucket 创建：对象存储 Kodo → 新建空间。

### ② 本地文件目录

- **下拉选择**常用目录：`DCIM/Screenshots`、`Pictures/Screenshots`、`DCIM/Camera`、`Pictures`
- 或选 **"自定义路径..."** 手动输入任意绝对路径（如 `/sdcard/DCIM/Camera`）

### ③ 定时上传时间

- 点 **"＋ 添加时间点"** 添加多个时间（格式 `HH:mm`，如 `10:00`、`19:00`）
- 到点自动上传新增文件

配置完成后点 **"保存配置并启用定时任务"** 即可。

### ④ 打开 App 时自动同步

- **默认关闭**。关闭时打开 App 不会发起任何上传，只有定时任务和手动点击「立即同步」会触发。
- 打开后，每次启动 App 首次进入主界面会自动上传一次新增文件；**只在进程启动后的首次进入触发**，
  旋转屏幕、切后台再回来、切换页签都不会重复上传。
- 触发前会检查：七牛云 AK/SK/Bucket 与本地目录是否配置完整、是否已授予存储权限、是否已有任务在跑；
  任一项不满足则跳过，并用提示说明原因（不会强行上传）。
- 开关切换后立即保存（不需要点「保存配置」），下次打开 App 生效。

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
app/src/main/java/com/frwuc/s3fileuploader/
├── MainActivity.java      # 主界面，底部 5 页签路由
├── SyncFragment.java      # 页签 1：同步任务
├── ExecutionFragment.java # 页签 2：执行记录（exec.log）
├── LogsFragment.java      # 页签 3：日志记录（uploads.log）
├── SettingsFragment.java  # 页签 4：设置（含自动同步开关）
├── AboutFragment.java     # 页签 5：关于
├── UploadLogger.java      # 执行记录 / 日志记录落盘与读取
├── UploadService.java     # 前台服务，执行扫描与上传
├── UploadScheduler.java   # 定时任务调度（AlarmManager）
├── AlarmReceiver.java     # 定时唤醒接收器
├── BootReceiver.java      # 开机自启，恢复定时任务
├── QiniuConfig.java       # 默认配置常量
├── QiniuToken.java        # 七牛上传凭证本地签发
├── QiniuUploader.java     # 七牛上传封装
├── AutoSync.java          # 「打开 App 时自动同步」开关读取与触发判定
└── Prefs.java             # 配置持久化
```

---

## 🔒 隐私与安全

- 你的 **AK/SK 仅保存在手机本地**（SharedPreferences），不会上传到任何第三方服务器
- 文件只上传到**你自己配置的七牛云 bucket**，不经手任何中间服务器
- 开源透明，所有逻辑可审计

> ⚠️ 请妥善保管 AK/SK，不要泄露给他人。如怀疑泄露请立即在七牛控制台重置密钥。

---

## ❓ 常见问题

**Q：为什么定时任务没有触发？**
A：国产 ROM（vivo/OPPO/小米/华为）后台管控严格，请先点 **"设置电池优化白名单"**，并在系统设置里允许 App 自启动、后台运行。

**Q：上传的图片在哪里？**
A：在七牛云控制台 → 对象存储 → 你的 bucket → 你设置的**云端目录**下，文件名为 `时间戳_原文件名`。

**Q：可以只上传某个文件夹的文件吗？**
A：可以。在"本地文件目录"选"自定义路径..."，填入该文件夹的绝对路径。

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
