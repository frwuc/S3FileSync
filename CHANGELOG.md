# 更新日志 / CHANGELOG

本文件记录 [s3-fileuploader](https://github.com/frwuc/s3-fileuploader)（原 ScreenshotUploader）的版本变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [v1.6.2] - 2026-11-08

### 修复：云端目录浏览 401 时的错误提示不透明
- 401 / 403 等错误现在会把**七牛服务器返回的原始错误体**一并显示出来
  （此前统一显示「AK / SK 不正确或已失效」，无法区分「密钥无效」与「签名不匹配」等真实原因），
  并附上排查指引：到七牛控制台「密钥管理」确认 AK / SK 有效性
- 新增 `QiniuTokenTest`：管理凭证（QBox token）生成结果与官方算法的 Python 参考实现**逐字节一致**，
  排除签名实现问题；`QiniuToken` 抽出 Base64 编码钩子便于 JVM 单测（android.util.Base64 在单测不可用）

### 变更：APK 产物名带版本号
- 构建产物由固定的 `s3-fileuploader.apk` 改为 `s3-fileuploader-<版本号>.apk`
  （如 `s3-fileuploader-1.6.2.apk`），本地同时保留多个版本的包时不会互相覆盖，也便于与发行版附件名对应；
  CI 整理产物改用通配符拷贝，不再依赖固定文件名

## [v1.6.1] - 2026-11-08

### 优化：安装包 9.82MB → 3.37MB
| 组成 | 优化前 | 优化后 | 手段 |
|------|--------|--------|------|
| dex | 4.85MB | 1.00MB | release 开启 R8 + `shrinkResources` |
| `lib/*.so` | 3.44MB | 1.39MB | `abiFilters` 只留 arm64-v8a / armeabi-v7a |
| `resources.arsc` | 0.97MB | 0.55MB | `resConfigs 'zh','en'` |

- 体积大头是七牛 SDK 传递依赖的 `conscrypt-android`：它按 4 个 ABI 各带一份
  `libconscrypt_jni.so`，而七牛自身代码并未引用它的任何类；x86 / x86_64 只对模拟器有意义，
  默认不打进包，需要时 `gradle assembleDebug -PwithX86=true`
- `packaging.resources.excludes` 去掉 META-INF 的 LICENSE / NOTICE / `*.kotlin_module`
- proguard 补 keep 规则：`com.qiniu.android.**`（反射 + JNI）、`org.conscrypt.**`（JNI 按类名绑定，
  且 Android 9 及以下 okhttp 会走到 `ConscryptSocketAdapter`，不能删也不能改名）、
  `okhttp3.internal.platform.**`（平台探测用反射）

### 变更：分发改为签名 release 包
- 新增 `app/keystore/debug.keystore`（固定调试密钥：密码 `android`、别名 `androiddebugkey`），
  debug 与 release 共用同一签名 → 本地与 CI 产出的包可互相覆盖安装，不用卸载重装
- CI 与 README 的构建命令由 `assembleDebug` 改为 `assembleRelease`，Artifacts / Release 附件体积同步变小

### 修复
- `item_browser_entry.xml`：`ellipsize="middle"` 与 `maxLines="1"` 同时使用在部分机型会崩溃，
  改为 `singleLine="true"`（lint `EllipsizeMaxLines`）
- lint 关闭 `ExpiredTargetSdkVersion`：targetSdk 29 是刻意保留的后台存活策略，本项目不上架应用商店

### 测试
- 单测 15 项全绿；`gradle lintRelease` 0 error / 115 warning

## [v1.6.0] - 2026-11-08

### 新功能
- **两端目录都能「浏览文件夹」选择**，不再靠手打路径 / key 前缀：
  - **本地端**：内置文件夹浏览器，从任意目录起逐级进入子文件夹、`⬆ 上级` 返回，
    底部「选择此文件夹」一键写回本地目录（仍需点「保存配置」才生效）。
    无存储权限时先申请，授权后自动继续打开浏览器。
  - **云端端**：新增 `QiniuBucketApi`，调用七牛 `rsf.qbox.me/list` 接口
    （`delimiter=/` 区分目录与文件，支持 marker/cursor 翻页，最多 20 页）实时列举 bucket 内的文件夹，
    逐级点进去即可选中；根目录选择返回空串，保存时自动去掉首尾斜杠，避免出现 `a//b` 形式的 key。
    浏览用界面上**当前填写**的 AK/SK/Bucket 现场请求，不必先保存配置。
  - 管理凭证（QBox token）按官方算法本地签发，新增 `QiniuToken.managementToken`：
    `signingStr = path[?query]\n`，query 参数按名称字典序、值做 URL 编码，签名与真实请求共用同一串。
    只读接口，不上传、不产生存储费用。
- **可浏览同步文件夹里的内容**：本地文件夹与云端目录内的文件都能直接看，
  显示大小 + 修改时间（云端取 `fsize` / `putTime`，`putTime` 按 100 纳秒换算为毫秒）；
  点文件弹出完整路径与信息，方便核对是不是要同步的内容。
- **列表支持名称 / 时间排序**：顶部 `名称` `时间` 两个按钮，点一次切换升 / 降序
  （`名称 ↑` `名称 ↓` `时间 ↑` `时间 ↓`），文件夹恒排在文件前、文件夹之间按名称升序，
  避免切换排序时文件夹混在文件中间上下跳（规则见 `BrowseSort`）。
- 「同步任务」页新增 `📁 本地文件夹` / `☁️ 云端目录` 两个浏览入口，选中后立即写入配置并刷新摘要。

### 新增文件
| 文件 | 作用 |
|------|------|
| `QiniuBucketApi.java` | 七牛列举接口封装（QBox 凭证、翻页、错误码翻译） |
| `BrowserDialog.java` | 本地 / 云端共用的文件夹浏览对话框 |
| `BrowseEntry.java` | 浏览条目模型（名称 / 路径 / 是否目录 / 大小 / 时间） |
| `BrowseSort.java` | 排序规则（名称 / 时间 × 升降序） |
| `dialog_browser.xml`、`item_browser_entry.xml`、`bg_sort_chip.xml` | 浏览对话框与条目样式 |

### 测试
- `BrowseSortTest`：目录优先、名称 / 时间升降序、切换方向
- `QiniuBucketApiTest`：query 参数排序与 URL 编码、前缀规范化、leafName、签名原文格式

### 变更
- 保存云端目录时统一去掉首尾 `/`（此前只去掉开头的 `/`）
- 版本号 1.5.0 → 1.6.0（versionCode 7 → 8）

## [v1.5.0] - 2026-11-08

### 新功能
- **新增设置项「打开 App 时自动同步」**（设置页第 ④ 个分区，开关形式）
  - **默认关闭**：未手动开启过时，打开界面不会发起任何上传请求
  - 开启后，应用启动首次进入主界面时自动触发一次上传，省去手动点「立即同步」
  - 只在**进程启动后的首次进入**触发：旋转屏幕、切后台再回来、切换页签均不重复上传
  - 触发前做三道校验，任一不满足则跳过并在界面上说明原因：
    配置不完整（AK/SK/Bucket/本地目录）、缺少存储权限、已有上传任务在执行
  - 开关状态写入 `SharedPreferences`（`auto_sync_on_open`），切换后立即生效，不依赖「保存配置」
- 同步任务页的状态文案会跟随运行状态显示「正在同步，请查看通知栏...」

### 修复
- `UploadService` 增加进程级运行标记：同一时刻只允许一份上传任务，
  避免自动同步与手动点击、定时任务并发执行导致重复上传
- 非上传指令（如系统重建服务）启动时立即结束服务，不再留下空的前台通知

## [v1.4.0] - 2026-11-08

### 新功能
- **日志拆分为两个独立页面**，底部导航补足为 5 个页签，入口 / 标题 / 内容一一对应：
  | 页签 | 数据来源 | 粒度 |
  |------|----------|------|
  | 同步任务 | — | 配置摘要 + 立即同步 |
  | 执行记录（新） | `exec.log` | 一次任务：触发时间、开始/完成、成功/失败/跳过，顶部累计统计卡片 |
  | 日志记录 | `uploads.log` | 一个文件：文件名、成功/失败、云端 key、访问地址 |
  | 设置 | — | 七牛云 / 本地目录 / 定时时间 |
  | 关于 | — | 版本、仓库、邮箱 |
- 执行记录与日志记录改为卡片列表渲染（原先是一大段等宽文本），最新在前、最多 200 条
- 两页的清空互不影响：`clearExec()` 只删执行记录，`clearUploads()` 只删日志记录，清空前二次确认
- 标题栏文字随页签切换，与页面内头部标题保持一致

### 文案统一
- 全应用「截图」表述统一改为「文件 / 新增文件」：同步页标题与副标题、设置页「本地文件目录」、
  保存提示、关于页简介与隐私说明、通知渠道名、README 与 CHANGELOG
- 默认云端目录 `daka_screenshots` → `daka_files`（新装应用默认值；已配置过的用户不受影响）
- 真实文件系统路径（`DCIM/Screenshots`、`Pictures/Screenshots`）保持不变，避免扫不到文件

### 变更（项目更名）
- **项目名 / 应用名 / 安装包名统一为 `s3-fileuploader`**（原 `ScreenshotUploader` / `S3-fileuploader`）
- **包名与 applicationId 改为 `com.frwuc.s3fileuploader`**（原 `com.example.screenshotuploader`），
  去掉占位 `com.example` 与遗留的 `screenshot` 字样
  - ⚠️ 属于新的应用身份：与旧版不互通，升级需先卸载旧版，本地配置与上传记录不会迁移
- 根项目名设为 `s3-fileuploader`（`settings.gradle` 的 `rootProject.name`）
- APK 产物名统一为 `s3-fileuploader.apk`（Android 输出、CI 产物、Actions Artifacts 三处一致）
- 关于页与 README 的仓库地址改为 `github.com/frwuc/s3-fileuploader` / `gitee.com/frwuc/s3-fileuploader`
- 本地配置存储名改为 `s3_fileuploader_prefs`（新包名对应新数据目录，无迁移包袱）
- 应用名改从 `@string/app_name` 读取，移除 Manifest 中硬编码 label 与 `tools:replace`

## [v1.3.1] - 2026-11-08

### 优化
- **UI 全面美化**：四个页面（同步/设置/日志/关于）统一视觉风格
  - 同步/日志页新增渐变头部
  - 各页卡片统一为圆角 + 阴影
  - 设置页分区标题加蓝色强调色
  - 立即同步主按钮改渐变圆角
  - 关于页优化图标与标题排版

## [v1.3.0] - 2026-11-08

### 变更
- **应用改名**：应用名 / 安装包文件名统一改为 `S3-fileuploader`
- **默认定时时间**：默认定时上传时间改为 09:00 和 18:20

### 新功能
- **关于页加联系邮箱**：显示 `frwucc@163.com`，点击可唤起邮件客户端发送反馈

### 优化
- **UI 美化**：同步页新增渐变头部、圆角卡片、渐变主按钮，整体观感更现代

## [v1.2.0] - 2026-11-07

### 新功能
- **底部导航**：全新底部导航栏，4 个标签页（同步 / 设置 / 日志 / 关于），单手切换更方便
- **默认同步页**：打开 App 默认进入「同步」页，展示当前配置摘要（Bucket / 本地目录 / 云端目录 / 定时时间）+ 立即同步大按钮 + 配置完整性提示

### 优化
- **设置页可折叠**：三个配置分区（七牛云账号 / 本地目录 / 定时时间）改为可折叠卡片，点击标题展开 / 收起，界面更清爽
- **日志页整合**：上传记录与执行日志整合进底部导航「日志」标签，双标签切换
- **关于页整合**：版本号、项目地址、MIT 协议整合进「关于」标签
- 架构重构为 Fragment + BottomNavigationView，结构更清晰

## [v1.1.0] - 2026-11-06

### 新功能
- **上传记录查看**：新增「上传记录」界面，逐条展示每张图片的上传结果（时间、文件名、云端 key、访问 URL、成功/失败）
- **执行日志查看**：新增「执行日志」标签，展示每次定时/手动任务的执行汇总（成功数、失败数、跳过数）
- **关于界面**：新增「关于」页面，集中展示 App 名称、版本号、项目简介、GitHub/Gitee 开源地址（可点击跳转浏览器）、MIT 协议、隐私说明
- **双平台发版**：CI 支持推送 `v*` 标签自动发布 GitHub Release 并附上 APK；APK 输出文件名统一为 `ScreenshotUploader.apk`

### 优化
- 七牛云「云端目录」可在界面选择/编辑，替代原硬编码 `daka_screenshots`
- 本地截图目录支持下拉选择常用目录 + 手动输入自定义绝对路径，替代原写死的多个目录
- 每个输入项下方新增详细中文填写说明（来源、格式、示例），降低配置门槛
- GUI 改为卡片式分区布局（①七牛云配置 ②本地目录 ③定时任务），SK 输入框密码遮蔽，按钮配色统一
- 记录界面支持一键清空全部日志

### 修复
- 修复布局 XML 中转义引号导致的资源编译失败

## [v1.0.0] - 2026-11-05

### 新功能
- 首个可用版本：定时扫描手机截图并自动上传到七牛云对象存储
- 支持设置多个每日定时时间点
- 上传凭证本地签发，无需自建服务器
- 开机自启恢复定时任务
- 电池优化白名单一键引导（vivo/OPPO 等国产 ROM 必做）
- 增量上传，自动跳过已上传文件
- GitHub Actions 自动构建 APK
