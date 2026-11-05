# Android 项目 GitHub Actions 构建排错记录

> 项目：`frwuc/screenshot-uploader`（本地 `D:\09agentworkspace\dshworkspace01\ScreenshotUploader`）
> 目标：用 GitHub Actions 构建 Android Debug APK
> 记录时间：2026-11-05

本文档记录了一次完整的 GitHub Actions 构建排错过程，按「报错 → 根因 → 解决办法」整理。
下次如果再报错，把本文档 + 最新报错日志一起交给新的模型，即可快速定位。

---

## 项目环境背景

- 构建系统：Gradle 8.0（`gradle/wrapper/gradle-wrapper.properties` 声明 `gradle-8.0-bin.zip`）
- Android Gradle Plugin (AGP)：`8.1.1`（根 `build.gradle` 的 `classpath`）
- SDK 版本：`compileSdk 34`、`minSdk 21`、`targetSdk 29`
- JDK：需要 **JDK 17**（AGP 8.1.x 要求）
- 依赖：`androidx.*` + `com.qiniu:qiniu-android-sdk:7.16.0`
- 关键文件：
  - `.github/workflows/android-build.yml`（CI 工作流）
  - `gradlew` / `gradlew.bat`（Gradle 启动脚本）
  - `gradle/wrapper/gradle-wrapper.jar`（wrapper 引导 jar）
  - `gradle/wrapper/gradle-wrapper.properties`（声明 Gradle 版本）

---

## 报错 1：Failed to find package 'tools'

**报错原文（关键行）：**
```
Warning: Failed to find package 'tools'
The process '.../cmdline-tools/16.0/bin/sdkmanager' failed with exit code 1
```

**根因：**
`android-actions/setup-android@v3` 这个第三方 Action 已经过时（GitHub 上停更）。
它内部会去安装一个叫 `tools` 的 SDK 包，但新版 Android SDK 早已把旧的 `tools` 包
拆分/改名为 `cmdline-tools`，导致 `sdkmanager` 找不到 `tools` 包而报错退出。

**解决办法：**
不再使用 `android-actions/setup-android@v3`，改为自己调用 `sdkmanager` 安装 SDK 组件。

---

## 报错 2：sdkmanager: command not found

**报错原文：**
```
yes: standard output: Broken pipe
line 2: sdkmanager: command not found
Error: Process completed with exit code 127.
```

**根因：**
新版 `ubuntu-latest`（Ubuntu 24.04）runner 的预装 Android SDK 布局变了，
`cmdline-tools` 里的 `sdkmanager` **没有放进 PATH**，直接 `sdkmanager` 会找不到命令。

**解决办法：**
自己在工作流里下载并安装 cmdline-tools，固定版本，不受 runner 镜像变化影响：

```yaml
- name: 安装 Android SDK cmdline-tools 并设置环境
  run: |
    set -e
    export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
    if [ -z "$ANDROID_HOME" ]; then
      export ANDROID_HOME="$HOME/android-sdk"
    fi
    mkdir -p "$ANDROID_HOME"
    CMDTOOLS_DIR="$ANDROID_HOME/cmdline-tools"
    if [ ! -x "$CMDTOOLS_DIR/latest/bin/sdkmanager" ]; then
      mkdir -p "$CMDTOOLS_DIR"
      curl -sSL -o /tmp/cmdline-tools.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
      unzip -q -o /tmp/cmdline-tools.zip -d "$CMDTOOLS_DIR"
      # 官方 zip 解压出来是 cmdline-tools/ 目录，规范布局要求放到 latest/
      if [ -d "$CMDTOOLS_DIR/cmdline-tools" ]; then
        mv "$CMDTOOLS_DIR/cmdline-tools" "$CMDTOOLS_DIR/latest"
      fi
    fi
    export PATH="$CMDTOOLS_DIR/latest/bin:$PATH"
    yes | sdkmanager --licenses > /dev/null 2>&1 || true
    sdkmanager "platforms;android-34" "build-tools;34.0.0"
    echo "ANDROID_HOME=$ANDROID_HOME" >> "$GITHUB_ENV"
    echo "ANDROID_SDK_ROOT=$ANDROID_HOME" >> "$GITHUB_ENV"
```

**要点：**
- `platform-tools` 一般无需手动装（runner 预装，且构建非必需）。
- `sdkmanager --licenses` 用 `yes |` 自动接受许可，`|| true` 防止已接受过时报错中断。

---

## 报错 3：Could not find or load main class org.gradle.wrapper.GradleWrapperMain

**报错原文：**
```
Error: Could not find or load main class org.gradle.wrapper.GradleWrapperMain
Caused by: java.lang.ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain
```

**根因：**
仓库里只有 `gradle/wrapper/gradle-wrapper.properties`，**缺了 `gradle-wrapper.jar`**。
`gradlew` 脚本运行时要靠这个 jar 加载 `GradleWrapperMain` 主类，jar 不在就报错。
（常见原因是初始化仓库时 `.gitignore` 误把 `*.jar` 忽略了，或生成 wrapper 时漏提交。）

**解决办法：**
补上 `gradle/wrapper/gradle-wrapper.jar`（见报错 4、5 的详细过程，最终得到自包含 jar）。

---

## 报错 4：NoClassDefFoundError: org/gradle/wrapper/IDownload

**报错原文：**
```
Error: Unable to initialize main class org.gradle.wrapper.GradleWrapperMain
Caused by: java.lang.NoClassDefFoundError: org/gradle/wrapper/IDownload
```

**根因：**
Gradle 8.x 把 wrapper 引导所需的类拆到了多个 jar 里。如果 `gradle-wrapper.jar`
只含主类、不含 `IDownload` 等运行时类，就会报这个错。
（本例是之前从发行包 `lib/plugins/gradle-wrapper-8.0.jar` 单独提取，缺了共享类。）

**解决办法：**
合并 wrapper 引导所需的类到一个自包含 jar。

---

## 报错 5：NoClassDefFoundError: org/gradle/cli/CommandLineParser

**报错原文：**
```
Exception in thread "main" java.lang.NoClassDefFoundError: org/gradle/cli/CommandLineParser
    at org.gradle.wrapper.GradleWrapperMain.main(GradleWrapperMain.java:43)
```

**根因：**
wrapper 引导链还依赖 `org.gradle.cli.CommandLineParser` 等 Gradle 核心类，
这些类分布在 `gradle-cli-*.jar`、`gradle-base-services-*.jar` 里，单纯合并 wrapper
相关的两个 jar 不够。

**解决办法（合并完整 wrapper jar）：**
wrapper 引导需要以下 4 个 jar 的类，全部解压合并成一个 `gradle-wrapper.jar`：

| 发行包内路径 | 提供的关键类 |
|---|---|
| `lib/plugins/gradle-wrapper-8.0.jar` | `GradleWrapperMain`（主类） |
| `lib/gradle-wrapper-shared-8.0.jar` | `IDownload`、`Download`、`BootstrapMainStarter` |
| `lib/gradle-cli-8.0.jar` | `CommandLineParser` 等 CLI 解析类 |
| `lib/gradle-base-services-8.0.jar` | 基础服务类 |

合并后应包含 **约 449 个 class**，关键类校验：
`GradleWrapperMain`、`IDownload`、`Download`、`BootstrapMainStarter`、`CommandLineParser`
全部存在即视为完整。

> 说明：更标准的做法是「本机装 JDK 17 → 跑 `gradle wrapper` 一键生成官方成品 jar」，
> 或 CI 里改用官方 `gradle/actions/setup-gradle@v4` action 来装 Gradle，彻底绕开 wrapper jar。
> 手动合并是「本机无 JDK 17 + 无法访问 GitHub/services.gradle.org」下的兜底方案。

---

## 报错 6：Unknown command-line option '-X'

**报错原文（关键行）：**
```
Downloading https://services.gradle.org/distributions/gradle-8.0-bin.zip
...100%
Unknown command-line option '-X'.
Welcome to Gradle 8.0!
USAGE: gradle [option...] [task...]
```

**根因：**
`gradlew` 脚本是 **2015 年旧版**（文件头版权 2015）。它的参数解析写法
`eval "set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \"\$@\""` 在 Gradle 8 的
wrapper 下不能正确剥离 `DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'` 里的引号，
导致 `-Xmx64m` 被拆成一个孤立的 `-X` 参数传给 Gradle，报未知选项。

**解决办法：**
把 `gradlew` 替换为 **Gradle 8.x 官方标准脚本**。核心区别是参数解析机制：
新版用 `xargs -n1` + `sed` + `eval` 正确剥离引号：

```sh
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

set -- \
        "-Dorg.gradle.appname=$APP_BASE_NAME" \
        -classpath "$CLASSPATH" \
        org.gradle.wrapper.GradleWrapperMain \
        "$@"

if ! command -v xargs >/dev/null 2>&1; then
    die "xargs is not available"
fi

eval "set -- $(
        printf '%s\n' "$DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS" |
        xargs -n1 |
        sed ' s~[^-[:alnum:]+,./:=@_]~\\&~g; ' |
        tr '\n' ' '
    )" '"$@"'

exec "$JAVACMD" "$@"
```

注意新版脚本里 `DEFAULT_JVM_OPTS` **仍然带引号**（这是官方标准写法），
靠 `xargs -n1` 正确解析，与旧版直接 `eval` 的差别就在这一处。

---

## 报错 7：checkDebugAarMetadata - android.useAndroidX not enabled

**报错原文（关键行）：**
```
> Task :app:checkDebugAarMetadata FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:checkDebugAarMetadata'.
> Configuration `:app:debugRuntimeClasspath` contains AndroidX dependencies,
  but the `android.useAndroidX` property is not enabled, which may cause runtime issues.
  Set `android.useAndroidX=true` in the `gradle.properties` file and retry.
  The following AndroidX dependencies are detected:
  :app:debugRuntimeClasspath -> androidx.appcompat:appcompat:1.6.1
  ...
```

**根因：**
项目用了 AndroidX 依赖（`androidx.appcompat`、`com.google.android.material`、
`androidx.constraintlayout` 等），但项目根目录**没有 `gradle.properties`**，
导致 `android.useAndroidX` 属性未开启。AGP 在 `checkDebugAarMetadata` 阶段
检测到 AndroidX 依赖却没开 AndroidX，就报错。

这类问题通常本地能跑（Android Studio 会自动生成 gradle.properties），
但仓库没提交该文件，CI 就挂。**这是项目配置缺陷，不是 CI 本身的问题。**

**解决办法：**
在项目根目录创建 `gradle.properties`：

```properties
# JVM 内存（AGP 8.x 推荐）
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8

# 启用 AndroidX（本次报错直接原因）
android.useAndroidX=true

# 自动把第三方库的 support library 引用转成 AndroidX（防老库冲突）
android.enableJetifier=true
```

提交进仓库后，本地与 CI 配置即一致。

---

## 附带：补充 .gitignore

原仓库没有 `.gitignore`，需补标准 Android 忽略项，避免本机构建缓存被误提交：

```gitignore
# Gradle
.gradle/
build/

# Local configuration file (sdk path, etc)
local.properties

# Android Studio / IDEA
.idea/
*.iml
.DS_Store
captures/
.externalNativeBuild/
.cxx/

# Keystore files
*.jks
*.keystore

# Build outputs
app/build/
```

---

## 最终可用配置速查

### 1. 工作流 `.github/workflows/android-build.yml`（核心片段）

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      # 自装 cmdline-tools + 安装 SDK 组件（见报错 2 的完整脚本）

      - run: chmod +x gradlew

      - run: ./gradlew assembleDebug --stacktrace

      - uses: actions/upload-artifact@v4
        with:
          name: app-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
          retention-days: 7
```

### 2. Gradle wrapper 三件套（缺一不可）

| 文件 | 作用 | 状态 |
|---|---|---|
| `gradlew` | Linux/macOS 启动脚本 | 需是 Gradle 8.x 标准版（勿用 2015 旧版） |
| `gradlew.bat` | Windows 启动脚本 | 需与 gradlew 版本匹配 |
| `gradle/wrapper/gradle-wrapper.jar` | 引导 jar（自包含，含主类+IDownload+CommandLineParser 等） | 必填 |
| `gradle/wrapper/gradle-wrapper.properties` | 声明 Gradle 版本 | 必填 |

---

## 排查方法论总结（下次直接照着走）

遇到 Android GitHub Actions 构建报错，按这个顺序定位：

1. **看报错是哪一层**：
   - `sdkmanager` / `tools` / `cmdline-tools` → SDK 环境问题（报错 1、2）
   - `GradleWrapperMain` / `NoClassDefFoundError` / `ClassNotFoundException` → wrapper jar 缺失或不完整（报错 3、4、5）
   - `Unknown command-line option` / 参数解析 → gradlew 脚本版本问题（报错 6）
   - `android.useAndroidX not enabled` / `checkDebugAarMetadata` → gradle.properties 配置缺失（报错 7）
   - `Could not resolve` / 依赖下载失败 → 依赖仓库/网络问题
   - `compileDebugJava` / 编译错误 → 源码问题

2. **确认版本匹配**：
   - AGP 版本 ↔ Gradle 版本 ↔ JDK 版本（三者强相关）
     - AGP 8.1.x → Gradle 8.0+，JDK 17
     - AGP 8.2+ → Gradle 8.2+，JDK 17
   - `compileSdk` / `buildToolsVersion` ↔ 安装的 SDK 组件

3. **关键原则**：
   - 优先用官方 action / 官方脚本，避免过时的第三方 action（`android-actions/setup-android` 已停更）。
   - wrapper 三件套必须齐全且版本一致。
   - 本机没 JDK 17 时，别硬跑 `gradle wrapper`；改用官方 setup-gradle action 更省事。

---

## 建议的未来优化（可选）

如果后续还要继续折腾，这几个是更省心的方向：

1. **CI 里改用官方 `gradle/actions/setup-gradle@v4`** 来装 Gradle，彻底绕开 wrapper jar 的手工维护。
2. **本机装 JDK 17**（Temurin 17），然后用 `gradle wrapper --gradle-version 8.0` 重新生成标准 wrapper 三件套，替换掉手工合并的 jar。
3. **把 Gradle 升级到 8.5+**，新版 wrapper 和工具链兼容性更好。
