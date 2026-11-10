# MacroDroid 手机端配置步骤（打卡截图自动上传七牛）

## 一、前置准备

### 1. 安装 MacroDroid
- Google Play 或国内应用市场搜 **MacroDroid**（免费版限 5 个宏，本项目只用 2 个）。

### 2. 授予"所有文件访问"权限（Android 11+ 必须）
- MacroDroid → 左侧菜单 **设置** → **权限** → 打开 **所有文件访问权限**。

### 3. 确认你手机的截图目录
- 用任意文件管理器截一张图，看新文件出现在哪里。常见位置：
  - `/storage/emulated/0/Pictures/Screenshots`（原生/部分 ROM）
  - `/storage/emulated/0/DCIM/Screenshots`（小米/三星等）
- **下面所有步骤中的截图目录都请替换成你自己的实际目录。**

### 4. 从电脑端获取上传凭证
在电脑上（项目根目录）执行：
```
D:\02soft\python312\python.exe token_gen.py --ak <AK> --sk <SK> --bucket <桶名> --region <区域> --verify
```
`--verify` 全部通过后，记下两个值：
- `token`（一长串 `AK:xxx:xxx`）
- 上传端点，如 `https://up-z2.qiniup.com/`

---

## 二、宏 1：上班打卡上传（00:00–11:59）

MacroDroid → **添加宏** → 命名 `上班打卡上传`。

### 触发器
1. 点 **添加触发器**，搜索 **文件**，选 **文件已更改 / File Changed**。
2. 选择文件夹：你的截图目录（如 `/storage/emulated/0/Pictures/Screenshots`）。
3. 文件名过滤：`*`（可收紧为 `Screenshot*`）。
4. 只勾选 **已创建 / Created** 事件（去掉 Deleted/Modified 等勾选）。

### 约束（限制只在上午生效）
1. 返回宏编辑页，点 **添加约束** → **时间** → **时间段 / Time Period**。
2. 从 `00:00` 到 `11:59`，每天。

### 动作 1：Shell 脚本拼接上传数据
点 **添加动作** → 搜索 **Shell** → 选 **Shell Script 命令**，粘贴以下脚本
（**把 `<TOKEN>` 和 `<REGION_HOST>` 两处替换成第一节拿到的东西**）：

```sh
TOKEN="<TOKEN>"
HOST="<REGION_HOST>"
WORK="上班打卡-"
B="qiniuboundary4clockin"
FN="/sdcard/Download/qiniu_tmp/payload.bin"
SRC="{file_path}"
D=$(date +%Y-%m-%d | sed 's/-0/-/g')
KEY="打卡/$(echo $D | cut -d- -f1)年$(echo $D | cut -d- -f2)月$(echo $D | cut -d- -f3)日/$WORK$(basename "$SRC")"
mkdir -p /sdcard/Download/qiniu_tmp
printf -- '--%s\r\nContent-Disposition: form-data; name="token"\r\n\r\n%s\r\n' "$B" "$TOKEN" > "$FN"
printf -- '--%s\r\nContent-Disposition: form-data; name="key"\r\n\r\n%s\r\n' "$B" "$KEY" >> "$FN"
printf -- '--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\nContent-Type: image/jpeg\r\n\r\n' "$B" "$(basename "$SRC")" >> "$FN"
cat "$SRC" >> "$FN"
printf -- '\r\n--%s--\r\n' "$B" >> "$FN"
```

> 说明：`{file_path}` 是 MacroDroid 的 magic text，运行时自动替换成触发文件的完整路径；
> 日期用 `sed` 去掉前导零，所以生成的是"2026年11月5日"而不是"2026年11月05日"。

### 动作 2：HTTP 上传
点 **添加动作** → 搜索 **HTTP** → 选 **HTTP 请求 / HTTP Request**：
- **URL**: `https://<REGION_HOST>/`（区域上传端点，注意结尾有 `/`）
- **方法**: POST
- **Header 参数（自定义请求头）**: 名称 `Content-Type`，
  值 `multipart/form-data; boundary=qiniuboundary4clockin`
- **内容主体（Content Body）**: 类型选择 **自定义/Custom**，
  内容选择 **从文件提供**，路径 `/sdcard/Download/qiniu_tmp/payload.bin`
- 勾选 **阻止下一步直到完成（Block next action until complete）**
- 在 HTTP 请求的响应选项里：
  - **响应码保存到变量**: 整型变量 `md_code`
  - **响应体保存到变量**: 字符串变量 `md_resp`

### 动作 3：结果通知（If/Else）
点 **添加动作** → **条件/If** → 条件选 **变量值**：`md_code` 等于 `200`：
- **If 分支**：动作 → 通知 → 显示通知 `已上传打卡截图`
- **Else 分支**：依次添加三个动作：
  1. 通知 `打卡上传失败(代码{v=md_code})，60秒后重试`
  2. 等待 60 秒
  3. 动作 → **跳转动作**（Jump to Action）→ 跳回 **动作 2（HTTP 请求）** 重试一次

### 动作 4：清理临时文件
点 **添加动作** → Shell Script：
```sh
rm -f /sdcard/Download/qiniu_tmp/payload.bin
```

### 保存宏并启用。

---

## 三、宏 2：下班打卡上传（12:00–23:59）

完全复制宏 1，只改两处：
1. **约束时间段**：`12:00` 到 `23:59`。
2. 动作 1 脚本里的 `WORK="下班打卡-"`。

宏名建议 `下班打卡上传`。

---

## 四、测试验收

| # | 测试 | 期望 |
|---|---|---|
| 1 | 上午（12 点前）随手截一张图 | ≤15 秒收到"已上传打卡截图"通知；七牛控制台出现 `打卡/2026年11月5日/上班打卡-截图名.jpg`（日期无前导零） |
| 2 | 下午再截一张 | 对象名前缀为 `下班打卡-` |
| 3 | 电脑端点"立即同步" | 截图下载到本地日期文件夹，**打开图片确认内容完好**（这是最关键的一项） |
| 4 | 开飞行模式截图 | 收到失败通知；恢复网络后新截图正常上传 |
| 5 | 分别用移动数据与公司 Wi-Fi 各测一次 | 都能上传（七牛公网端点） |

### 关键校验：文件是否无损
上传后从电脑端下载同一张图，与手机原图对比：
- 文件大小一致（手机图库里查看文件详情）；
- 肉眼查看图片完整无损坏。
若图片损坏（极小概率），说明 MacroDroid "body from file" 传输有问题，
联系电脑端换 **Java Code 动作** 或 **Automate app** 方案（见文末备选）。

---

## 五、常见问题

**Q：通知显示失败，代码 614？**
同一天同名文件已存在（insertOnly 保护）。Android 截图文件名自带时间戳，正常不会出现；
出现说明两次截图重名，忽略即可，之前那张是好的。

**Q：通知失败，代码 401/403？**
token 不对或过期：重新在电脑上跑 `token_gen.py` 生成并更新宏 1/宏 2 脚本里的 `TOKEN`。

**Q：宏没触发？**
1. 检查截图目录是否配对（文件管理器确认新截图的实际路径）；
2. 检查"所有文件访问"权限；
3. MacroDroid 是否被系统省电策略杀后台：设置 → 电池 → 对 MacroDroid 取消优化/允许后台。

**Q：换新手机/重装 MacroDroid？**
重新按本文档配置一遍即可；token 仍有效（10 年期），无需在七牛侧做任何改动。

---

## 六、备选方案（仅当主方案实测失败时）

1. **MacroDroid Java Code 动作**：用内置 Java 动作以 HttpURLConnection 发完整 multipart，
   由电脑端 `token_gen.py` 补充生成这段 Java 代码。
2. **Automate app（LlamaLab，免费）**：其 "Web service" 模块原生支持 multipart 文件上传，
   触发用 "文件新增" 传感器。
3. **接受带前导零**：改用七牛 saveKey 策略命名（`2026年11月05日`），
   表单只需 token+file 两字段，实现最简，但文件夹名带前导零。
