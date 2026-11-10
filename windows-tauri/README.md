# S3FileSync — Windows 桌面端（Rust + Tauri 2 Web UI）

功能最全的 Windows 桌面端（推荐）。Rust 后端 + Web 前端（WebView2 渲染），
界面精致现代，单 exe 约 11MB，无 Node / 无 Python 运行时依赖
（系统需带 WebView2 运行时，Windows 10/11 自带）。

## 功能

- 与 Python 端 / egui 端 / 安卓端共用 `%APPDATA%\S3FileSync\`（config / sync_state / app.log），可无缝互换
- 三种同步方向：仅下载 / 仅上传 / 双向；定时自动同步（分钟级）
- 双协议后端：**七牛云 Kodo** 与 **S3 兼容存储**（AWS S3 / MinIO 等，SigV4）
- 设置页：存储服务商切换、云端目录**逐级浏览选择**、本地目录选择、测试连接 / 测试上传、开机自启、网络代理模式
- 文件浏览页：分页列举、排序、多选上传（文件 / 整个文件夹）/ 下载 / 删除
- 状态栏常驻「下次同步」倒计时（不再被状态文本覆盖）
- 托盘常驻：立即同步（按方向）、暂停/恢复、打开本地/云端目录；关窗隐藏到托盘
- 日志页：实时跟随、导出、定位文件
- CLI：`S3FileSync.exe --once [--mode upload|download|both]`、`--watch`、`--silent`、`--version`

## 构建

```bat
cd windows-tauri/src-tauri
cargo build --release
:: 产物 target\release\S3FileSync.exe
```

- 首次构建需联网拉取 crates（约 500 个，可用 rsproxy 等镜像）
- 前端（`../ui`）在 release 构建时嵌入 exe；改前端后需重新 `cargo build`
- 注意：tauri 的资源嵌入有增量缓存，**只改 `ui/` 后请先 `cargo clean -p s3filesync` 再构建**，否则仍会打包旧资源

## 测试

```bat
cargo test
```

包含配置兼容（Python 端配置/状态互通）、路径规范化、S3 SigV4 黄金向量、
XML 解析、etag 分块等 39 个单元测试。

## 与 windows-rust（egui）端的关系

功能完全对等并在此基础上优化 UI。`windows-rust/` 保留作为原生 GUI 备选，
两者可独立构建、共用同一份数据目录。
