# S3FileSync（Rust 桌面端）

S3 兼容对象存储同步工具的 **Windows 桌面客户端（Rust 原生 GUI）**，与
[`windows/`](../windows/)（Python 端）**功能对等、配置互通**：

- 同一份 `%APPDATA%\S3FileSync\config.json` / `sync_state.json` / `app.log`——
  两个客户端可互相替换使用，切换不丢配置、不重复传输
- 同一套同步语义：仅上传 / 仅下载 / 双向；etag 比对跳过；同名冲突不覆盖（改名 `.conflictN`）

## 支持的后端

| 后端 | 说明 |
| --- | --- |
| **七牛云 Kodo** | 与 Python 端完全一致（凭证 / etag / 上传区域自纠） |
| **S3 协议兼容存储** | AWS S3、MinIO、阿里云 OSS（S3 兼容模式）等；AWS SigV4 签名， ListObjectsV2 / PUT / GET / DELETE |

设置页顶部的「存储服务商」切换；S3 模式需填写服务端点（Endpoint）与区域（Region）。
AWS 官方端点默认虚拟主机式寻址，自定义端点（MinIO 等）默认路径式，可强制切换。

## 功能

- **自动同步**：按间隔（默认 30 分钟）按设定方向跑一轮，托盘常驻、可开机自启
- **临时单跑**：状态栏「单跑 ▾」或托盘菜单，只上传 / 只下载 / 双向一轮，不改默认设置
- **云端目录浏览选择**：设置页「云端目录（Key 前缀）」旁的「浏览…」可逐级浏览云端
  子目录直接选中，无需手敲前缀（两种后端都支持）
- **文件浏览**：任意云端目录（Key 前缀）、分页（100/页）、点表头排序、多选下载/删除、
  上传文件/文件夹（保留子目录结构、同名覆盖）、复制 Key、打开云端
- **自检**：测试连接（列举 + 对象探测）；测试上传（上传极小临时对象后立即删除）
- **网络容错**：自动（探测直连，忽略失效系统代理）/ 直连 / 系统代理三种模式
- **上传区域自纠**（七牛）：UC 查询空间真实区域；`incorrect region` 报错自动切换端点重试
- **CLI 调试**：`S3FileSync.exe --once [--mode upload|download|both]`、`--watch`

> 注：Python 端目前仅支持七牛 Kodo；`provider=s3` 的配置对 Python 端不可用，
> 在 Rust 端使用同一配置文件不受影响（新增字段向后兼容）。

## 构建

```
cargo build --release          # 产物 target/release/S3FileSync.exe（单文件，无运行时依赖）
cargo test                     # 单元测试（含与 qiniu Python SDK 交叉验证的黄金向量）
```

需要 Rust stable（MSVC 工具链）。依赖见 `Cargo.toml`（egui + ureq + tray-icon，全部纯 Rust）。

## 与 Python 端的差异

| 项 | Rust 端 | Python 端 |
| --- | --- | --- |
| 运行时 | 单 exe，无依赖 | 需 Python 3.12 + pip 依赖 |
| 启动速度 / 内存 | 快 / 小 | 慢 / 大（Tkinter） |
| 打包 | `cargo build --release` 即可 | PyInstaller（约 15MB） |
| UI 风格 | egui 即时模式 | Tkinter + ttk |
| 功能 | 对等 | 对等 |
| 配置 / 状态 / 日志 | **共用同一目录** | **共用同一目录** |

## 实现要点

- 七牛凭证本地签发：上传凭证（scope 转义）、Qiniu / QBox 双管理凭证（401 自动回退）、
  私有下载 URL——签名算法与官方 Python SDK 逐字节对齐（测试向量为黄金向量）
- S3 SigV4：HMAC-SHA256 密钥链 + 规范化请求；canonical query 严格排序与 percent-encode；
  上传用 `UNSIGNED-PAYLOAD` 流式（签名与 Python 独立计算黄金向量对齐）
- S3 ETag 比对策略：单请求上传的 ETag（MD5）与本地 MD5 精确比对；分块上传的
  ETag（`-N` 后缀）退化为大小比对，避免不可比哈希导致无谓重传
- etag（七牛）：4MB 分块 SHA-1（0x16 / 0x96 前缀），与 `qiniu.utils.etag_stream` 一致
- 上传：七牛走 multipart 手拼流式请求体；S3 走单请求 PUT；均显式 Content-Length（大文件不进内存）
- 安全：UC 返回的主机走七牛官方域名白名单；`incorrect region` 注入防护；
  S3 端点主机名合法性校验；下载先写 `.part` 再改名
- 线程模型与 Python 端相同：主线程 GUI，单个工作线程串行网络任务，channel 回传事件

## 目录

```
src/
├── main.rs        入口（GUI / CLI 分流，中文字体加载）
├── app.rs         状态机 + 工作线程 + 托盘事件 + 调度
├── config.rs      配置（与 Python 端共用 config.json）
├── state.rs       同步状态（共用 sync_state.json）
├── sync.rs        同步引擎（三方向）
├── storage/       后端抽象（mod.rs）+ S3 客户端（s3.rs：SigV4 / XML 解析）
├── autostart.rs   开机自启（HKCU Run）
├── logger.rs      内存环形 + 文件日志
├── tray.rs        托盘图标与菜单
├── qiniu/         auth（凭证） / etag / client（HTTP）
└── ui/            settings / browser / logs / about
```
