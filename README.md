# s3-fileuploader（文件定时上传）

[![Build APK](https://github.com/frwuc/s3-fileuploader/actions/workflows/android-build.yml/badge.svg)](https://github.com/frwuc/s3-fileuploader/actions/workflows/android-build.yml)
[![Gitee](https://img.shields.io/badge/Gitee-frwuc%2Fs3--fileuploader-C71D23.svg)](https://gitee.com/frwuc/s3-fileuploader)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

一款开源的 **Android 文件自动备份工具**。定时扫描手机里的指定目录（含子文件夹，不限文件类型），自动把新增文件上传到你的**七牛云**对象存储，实现文件自动云端备份。

无需人工干预——设好时间点，App 每天到点自动把新增文件传到云端，再也不怕文件丢失或占满手机空间。

---

## ✨ 功能特性

- ⏰ **定时自动上传**：可设置多个时间点（如 10:00、19:00），到点自动扫描并上传新增文件
- ☁️ **七牛云备份**：文件上传到自己的七牛云 bucket，安全可控、容量充足
- 📁 **本地目录可选**：内置文件夹浏览器，逐级进入 / 返回上级挑目录，也可下拉选常用目录或手动输入路径
- 📄 **不限文件类型**：图片、文档、压缩包、视频都传；子文件夹递归扫描，云端保留目录结构
- 🗂️ **云端目录可选**：内置云端目录浏览器，实时列举 bucket 下的文件夹逐级选择，无需手打 key 前缀
- 👀 **两端内容可浏览**：本地文件夹与云端目录内的文件都能直接查看（大小 + 修改时间），支持名称 / 时间升降序排序
- 🔁 **增量上传**：已上传过的文件自动跳过，不重复上传、不浪费流量
- 🔐 **本地签发凭证**：内置上传凭证本地签发算法，无需自建服务器
- 🌐 **开机自启**：支持开机自动恢复定时任务，重启不丢配置
- 🔋 **电池白名单引导**：一键引导加入电池优化白名单，避免后台被系统杀死（vivo/OPPO 等国产 ROM 必做）
- 🎨 **五页签界面**：同步任务 / 执行记录 / 日志记录 / 设置 / 关于，入口、标题与内容一一对应
- 🚀 **打开即同步（可选，默认关闭）**：开启后启动 App 首次进入界面即自动上传一次新增文件，旋转屏幕 / 切后台回来不会重复触发

---

## 🔄 工作原理

```
新增文件 → [定时唤醒 App] → 递归扫描本地目录（不限文件类型）
                                  ↓
                          过滤已上传过的（增量）
                                  ↓
                        本地签发七牛上传凭证
                                  ↓
                      上传到七牛云 bucket/云端目录
                                  ↓
                          记录已上传，下次跳过
```

App 通过 **AlarmManager** 在设定时间点唤醒前台服务，扫描配置的本地目录（含子文件夹，不限文件类型），把尚未上传过的文件上传到七牛云，云端 key 保留相对目录结构。上传记录保存在本地，实现增量备份。

---

## 🧭 界面结构（底部 5 个页签）

| 页签 | 标题 | 内容 |
|------|------|------|
| 同步任务 | ⚡ 同步任务 | 当前配置摘要（Bucket / 本地目录 / 云端目录 / 定时时间）+ 立即同步 + 运行状态 + 本地 / 云端内容浏览 |
| 执行记录 | 🗂 执行记录 | **每次任务**的触发时间与结果汇总（成功 / 失败 / 跳过），顶部带累计统计卡片 |
| 日志记录 | 📄 日志记录 | **每个文件**的上传明细，含云端 key 与访问地址，成功 / 失败用颜色区分 |
| 设置 | 设置 | 七牛云账号、本地文件目录、定时时间三个可折叠分区 + 打开 App 自动同步开关 + 电池白名单 |
| 关于 | 关于 | 版本号、开源仓库、联系邮箱、隐私说明 |

「执行记录」与「日志记录」分别对应 `exec.log` 与 `uploads.log`，两页各自独立清空，互不影响。

---

## 🚀 快速开始

### 方式一：下载现成 APK

- **GitHub Releases**：<https://github.com/frwuc/s3-fileuploader/releases>（附件 `s3-fileuploader-1.7.2.apk`）
- **Gitee Releases（国内推荐）**：<https://gitee.com/frwuc/s3-fileuploader/releases>（附件 `s3-fileuploader-1.7.2.apk`，无需登录）
- 也可从 **[Actions](https://github.com/frwuc/s3-fileuploader/actions)** 最新一次成功构建（绿色 ✅）的 **Artifacts** 里下载 `s3-fileuploader` 压缩包，解压即得 APK

> ⚠️ 附件用仓库内的固定调试密钥签名（`app/keystore/debug.keystore`），只用于个人自测；
> 因为本地与 CI 用同一把钥匙，两个渠道下载的包可以互相覆盖安装。如需正式发布请自行配置签名出 Release 包。

> 📦 两个仓库的代码与版本保持一致：`main` 分支与 `v*` 标签同时推送到 GitHub 与 Gitee，
> 打标签后 CI 会自动构建并创建带 APK 的 Release。

### 方式二：自己构建

```bash
# Gitee（国内推荐）
git clone https://gitee.com/frwuc/s3-fileuploader.git
# 或 GitHub
git clone https://github.com/frwuc/s3-fileuploader.git
cd s3-fileuploader
gradle assembleRelease     # R8 压缩 + 资源收缩，约 3.4MB，已签名可直接安装
# 产物：app/build/outputs/apk/release/s3-fileuploader-<版本号>.apk（如 s3-fileuploader-1.7.2.apk）

# 需要断点调试时用 debug 包（不混淆，约 7.5MB）
gradle assembleDebug

# 用 x86 模拟器调试时，需要把 x86 ABI 一起打进去
gradle assembleDebug -PwithX86=true
```

> 体积主要来自七牛 SDK 传递依赖的 conscrypt native 库（arm64 + armv7 约 1.4MB）。
> x86 / x86_64 的 so 默认不打进包，需要模拟器时才用 `-PwithX86=true`。

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
| **上传域名** | 一般填 `https://z0.qbox.me`（华东），其它区域见七牛文档；上传时 SDK 也会自动探测区域 |
| **云端目录** | 上传到 bucket 下的哪个目录，如 `daka_files`；留空则放根目录。点 **「📂 浏览云端目录并选择」** 可实时列出 bucket 内的文件夹，逐级点进去后一键选中 |

> AK/SK 获取：登录 [七牛云控制台](https://portal.qiniu.com/) → 个人中心 → 密钥管理。
> Bucket 创建：对象存储 Kodo → 新建空间。
> 云端浏览走七牛 `list` 接口，用填写的 AK/SK 在本地签发管理凭证，**只读、不产生存储费用**。
> 签名优先使用七牛现行官方 SDK 的「Qiniu」方案（`QiniuMacAuth`），收到 401 会自动回退旧版「QBox」
> 签名重试一次，新老账号都能用；列举主机由 UC 接口按 bucket 所属区域自动解析，不写死华东节点。

### ② 本地文件目录

- 点 **「📂 浏览本地文件夹并选择」** 打开文件夹浏览器：逐级进入子文件夹、`⬆ 上级` 返回、底部一键「选择此文件夹」
- **下拉选择**常用目录：`DCIM/Screenshots`、`Pictures/Screenshots`、`DCIM/Camera`、`Pictures`
- 或选 **"自定义路径..."** 手动输入任意绝对路径（如 `/sdcard/DCIM/Camera`）
- 选定目录下**所有子文件夹都会被递归扫描**（深度上限 16 层），云端 key 保留相对目录结构

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

### ⑤ 浏览同步文件夹内容

配置页与「同步任务」页都提供浏览入口，打开后是同一套浏览器：

- **本地文件夹**：列出当前文件夹下的子文件夹与文件，显示文件大小与修改时间；点文件夹进入，`⬆ 上级` 返回
- **云端目录**：通过七牛 `list` 接口实时列举 bucket 内该目录下的子目录与对象（含大小与上传时间）；
  主机按 bucket 所属区域自动解析，签名用现行「Qiniu」方案（401 自动回退旧版「QBox」）
- **排序**：顶部 `名称` / `时间` 两个按钮，点一下切换升 / 降序（`名称 ↑` `名称 ↓` `时间 ↑` `时间 ↓`）；
  文件夹始终排在文件前面，避免翻找时上下跳
- **看全文件名**：文件名最多显示 3 行（不再单行中间省略）；仍超长的，点该文件弹出「文件信息」，
  含完整文件名、完整路径、大小与修改时间，正文可长按选中，也可点「复制文件名」
- 底部 **「✓ 选择此文件夹」** 会把当前目录写回配置（本地取绝对路径，云端取 key 前缀、根目录为空），
  在同步页使用时立即生效，在设置页使用时还需点一次「保存配置」

---

## 🔧 技术栈

| 项目 | 说明 |
|------|------|
| 语言 | Java |
| 最低支持 | Android 5.0 (API 21) |
| 目标版本 | Android 10 (API 29) |
| 构建工具 | Gradle 8.x + Android Gradle Plugin 8.1.x |
| 云存储 SDK | [qiniu-android-sdk](https://github.com/qiniu/android-sdk) |
| 凭证签发 | 按七牛官方算法本地实现（`QiniuToken`）：上传凭证 + 管理凭证（`QiniuMacAuth` 新签名，兼容 `QBox`） |
| 单元测试 | JUnit 4，`gradle testDebugUnitTest`（23 项，纯 JVM，无需设备） |

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
├── ScanRules.java         # 扫描规则：递归收集文件、相对路径、云端 key 拼接（纯 Java，可单测）
├── QiniuConfig.java       # 默认配置常量
├── QiniuToken.java        # 七牛上传凭证 / 管理凭证本地签发（Qiniu 新签名 + QBox 兼容）
├── QiniuUploader.java     # 七牛上传封装
├── QiniuBucketApi.java    # 七牛列举接口（区域解析 + 管理凭证，浏览 bucket 目录与文件）
├── BrowserDialog.java     # 文件夹浏览 / 选择对话框（本地与云端共用，含排序）
├── BrowseEntry.java       # 浏览列表条目模型
├── BrowseSort.java        # 浏览排序规则（名称 / 时间，升降序）
├── AutoSync.java          # 「打开 App 时自动同步」开关读取与触发判定
├── AboutConfig.java       # 关于页信息常量
└── Prefs.java             # 配置持久化

app/src/test/java/com/frwuc/s3fileuploader/
├── ScanRulesTest.java     # 递归扫描 / 隐藏项跳过 / 相对路径 / key 拼接
├── BrowseSortTest.java    # 目录恒在前、名称与时间升降序
├── QiniuTokenTest.java    # 上传凭证与管理凭证：与官方算法逐字节一致
├── QiniuBucketApiTest.java # 参数排序编码、前缀规范化、签名原文
└── AutoSyncDecisionTest.java # 自动同步触发条件
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

**Q：上传的文件在哪里？**
A：在七牛云控制台 → 对象存储 → 你的 bucket → 你设置的**云端目录**下，文件名为 `时间戳_原文件名`；
若文件来自子文件夹，会保留相对目录：`云端目录/子文件夹/时间戳_原文件名`。

**Q：可以只上传某个文件夹的文件吗？**
A：可以。在"本地文件目录"点「📂 浏览…并选择」或填该文件夹的绝对路径。

**Q：能上传图片以外的文件吗（PDF / Word / 压缩包 / 视频）？**
A：能。扫描不限文件类型，目录下有什么传什么；子文件夹也会递归扫描，云端按 `云端目录/子文件夹/时间戳_原文件名` 保存。只跳过以 `.` 开头的隐藏项和 0 字节的空文件。

**Q：已上传的文件会被重复上传吗？**
A：不会。App 记录了已上传文件路径，重复的自动跳过（同名但内容改动过也不会重传，需移除记录或换文件名）。

**Q：浏览云端目录提示 401 / `bad token`，但同步上传是正常的？**
A：上传凭证与管理凭证是两套签名体系，上传正常不代表管理接口也通。请依次检查：
① AK/SK 是否在 [七牛控制台 → 密钥管理](https://portal.qiniu.com/) 仍有效（换过密钥要重新粘贴保存）；
② Bucket 名称是否与账号下的空间完全一致；
③ 确认装的是最新版本——旧版只用「QBox」老签名，较新的七牛账号会拒绝，v1.6.3 起已改为「Qiniu」新签名并自动回退兼容。
错误提示里会带上七牛服务器的原始返回，便于进一步定位。

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
