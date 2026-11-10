# S3FileSync

**S3 兼容对象存储同步工具**——把**任意文件**在「本地目录」与「云端空间」之间同步，支持
**仅上传 / 仅下载 / 双向**三种方向。当前后端为**七牛云 Kodo（S3 兼容）**，其他 S3 兼容
服务商尚未验证。

本仓库包含三个客户端，共用同一套数据目录（`%APPDATA%\S3FileSync\`）与同步语义：

| 客户端 | 目录 | 平台 | 技术栈 | 产物 |
| --- | --- | --- | --- | --- |
| 桌面端（Python） | [`windows/`](windows/) | Windows（托盘常驻） | Python + Tkinter | `S3FileSync.exe`（PyInstaller） |
| 桌面端（Rust） | [`windows-rust/`](windows-rust/) | Windows（托盘常驻） | Rust + egui 原生 GUI | `S3FileSync.exe`（单文件约 6MB） |
| 安卓端 | [`android/`](android/) | Android | Kotlin + Java | `S3FileSync-<version>.apk` |

三个客户端都对接同一个七牛云 Bucket，文件名/目录结构在多端保持一致（上传不加时间戳、
下载不加前缀），可互相补齐新增文件。两个桌面端共用同一份
`config.json / sync_state.json / app.log`，可互换使用、无缝切换。

## 功能特性

- 三种同步方向：仅下载（云端 → 本地）、仅上传（本地 → 云端）、双向同步
- 任意文件类型：图片、文档、压缩包、视频……子文件夹递归扫描
- 本地目录 / 云端目录均可浏览选择（不限于固定前缀）
- 文件内容可预览、可删除（云端走管理接口真删）
- 定时自动同步（安卓端多时间点、Windows 端手动/常驻触发）
- 本地签发上传凭证，无需自建服务器

> 注：当前仅七牛云 Kodo 经过实测；其它 S3 兼容服务商（如 MinIO、AWS S3）的兼容性暂未验证。

## 目录结构

```
S3FileSync/
├── windows/        # Windows 桌面端（Python + Tkinter 托盘程序）
│   ├── app/        # 应用源码
│   ├── run.pyw     # 无控制台入口
│   ├── S3FileSync.spec   # PyInstaller 打包配置
│   └── README.md   # 桌面端使用说明
├── windows-rust/   # Windows 桌面端（Rust + egui 原生 GUI，功能与 Python 端对等）
│   ├── src/        # 应用源码（含单测）
│   ├── Cargo.toml
│   └── README.md   # 构建与差异说明
├── android/        # 安卓端（Gradle / Kotlin + Java）
│   ├── app/        # 应用源码
│   ├── build.gradle
│   └── README.md   # 安卓端使用说明
└── README.md       # 本文件
```

## 下载与安装

前往 **Releases** 页面获取对应平台的安装包：

- **Windows**：任选其一——
  - `S3FileSync-Setup-<version>.exe` / `S3FileSync-<version>.exe`（Python 端，PyInstaller 打包）
  - `S3FileSync-rust-<version>.zip`（Rust 端，解压即用，单 exe 无运行时依赖）
- **Android**：下载 `S3FileSync-<version>.apk`，允许「未知来源」后安装

## 配置

两个客户端首次使用都需填写：

- **Access Key / Secret Key**（七牛云控制台获取）
- **Bucket 名称** 与 **存储区域**
- **本地目录** 与 **云端目录（Key 前缀）**
- 同步方向（仅上传 / 仅下载 / 双向）

## 构建

- 桌面端（Python）：`cd windows && pyinstaller S3FileSync.spec`（需 Python 3.12 + 依赖见 `windows/requirements.txt`）
- 桌面端（Rust）：`cd windows-rust && cargo build --release`（需 Rust stable-msvc 工具链，
  产物 `target/release/S3FileSync.exe`，详见 `windows-rust/README.md`）
- 安卓端：`cd android && gradle assembleRelease`（需 Android SDK，见 `android/local.properties`；
  `gradle` 可用本地缓存版本直接调用，无需 wrapper）

## 许可证

见 [`android/LICENSE`](android/LICENSE)（MIT）。
