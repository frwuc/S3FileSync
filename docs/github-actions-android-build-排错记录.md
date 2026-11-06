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
- 依赖：`androidx.*` + `com.qiniu:qiniu-android-sdk:8.11.0`（最初误写为不存在的 `7.16.0`，中间曾尝试 `7.6.7`）
- 七牛上传凭证：Android SDK 无 Auth 类，由 `QiniuToken.java` 按官方算法本地签发
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

## 报错 8：Could not find com.qiniu:qiniu-android-sdk:7.16.0

**报错原文（关键行）：**
```
> Could not find com.qiniu:qiniu-android-sdk:7.16.0.
  Searched in the following locations:
    https://dl.google.com/dl/android/maven2/com/qiniu/qiniu-android-sdk/7.16.0/...
    https://repo.maven.apache.org/maven2/com/qiniu/qiniu-android-sdk/7.16.0/...
```

**根因：**
`app/build.gradle` 中声明的七牛 Android SDK 版本 `7.16.0` 不存在。
这不是 Gradle 缓存或 CI 网络问题，单纯重跑工作流无法解决。

**处理过程：**
1. 先将版本改成可下载的 `7.6.7`，依赖解析错误消失。
2. 随后 Java 编译报 `Auth`、`Zone.autoZone()` 不存在，说明项目源码使用的是七牛 8.x API，不能停在 `7.6.7`。
3. 最终改为源码所需的版本：

```gradle
dependencies {
    implementation 'com.qiniu:qiniu-android-sdk:8.11.0'
}
```

**要点：**
“版本能下载”只代表依赖解析成功，不代表与源码 API 兼容。
遇到这种连续报错，要同时核对「Maven 上版本是否存在」和「源码针对哪个大版本编写」。

---

## 报错 9：processDebugResources 资源链接失败，launcher 图标缺失

**报错原文（关键行）：**
```
> Task :app:processDebugResources FAILED
error: resource mipmap/ic_launcher
  (aka com.example.screenshotuploader:mipmap/ic_launcher) not found.
error: resource mipmap/ic_launcher_round
  (aka com.example.screenshotuploader:mipmap/ic_launcher_round) not found.
error: failed linking references.
```

**根因：**
`AndroidManifest.xml` 引用了两个资源：
```xml
android:icon="@mipmap/ic_launcher"
android:roundIcon="@mipmap/ic_launcher_round"
```
但仓库的 `mipmap-*` 目录里没有对应文件。AAPT2 在链接资源时找不到引用目标，
因此尚未进入 Java 编译阶段就失败。

**解决办法：**
为五种密度分别补齐 `ic_launcher.png` 和 `ic_launcher_round.png`，共 10 个文件：

```text
app/src/main/res/
├── mipmap-mdpi/       # 48 × 48
├── mipmap-hdpi/       # 72 × 72
├── mipmap-xhdpi/      # 96 × 96
├── mipmap-xxhdpi/     # 144 × 144
└── mipmap-xxxhdpi/    # 192 × 192
```

本次已生成并提交蓝紫底、白色上传箭头的普通及圆形图标。
如果项目已有图标，也可以删除 Manifest 中的 `android:roundIcon`，
或把两个引用都改到真实存在的资源，但不能保留悬空引用。

**排查要点：**
- `@mipmap/...` 必须在 `res/mipmap-*` 下有同名资源。
- `@drawable/...`、`@string/...` 等同理；资源扩展名不参与引用名。
- 资源缺失看 `processDebugResources` / AAPT2，不要误当成 Java 源码问题。

---

## 报错 10：compileDebugJavaWithJavac 源码编译失败

**报错原文（关键行）：**
```
> Task :app:compileDebugJavaWithJavac FAILED
error: cannot find symbol
  symbol: class Auth
error: cannot find symbol
  symbol:   method autoZone()
  location: class Zone
error: isValidTime() has private access in UploadScheduler
```

**根因 1：七牛 SDK 与源码 API 不匹配（两条）。**
项目源码调用了 `Auth.create(...)`、`Zone.autoZone()`。
当时升级到 8.11.0 只解决了依赖下载和 `Zone` 类的 import 问题
（`com.qiniu.android.common.Zone` 确实存在），但后经核对官方源码仓库
（qiniu/android-sdk，v7.6.7 与 v8.11.0 的完整源码树）发现：

- `com.qiniu.android.auth.Auth` 在 Android SDK 的**任何版本都不存在**
  （官方口径：上传凭证应由服务端签发，Android SDK 从不内置 Auth）。
- `Zone.autoZone()` 静态方法也不存在；`Zone` 是抽象类，
  `Configuration.Builder` 的 zone 缺省值就是 `AutoZone`（自动区域）。

这两个写法疑似参照了 Java SDK（`com.qiniu.util.Auth`）的用法，
属于「把 Java SDK 的 API 当成 Android SDK 用」。完整修复见报错 12。

**当时采取的措施：**
将依赖升级为 `com.qiniu:qiniu-android-sdk:8.11.0`（与存在的 API 对齐）。

**根因 2：Java 方法可见性不足。**
`MainActivity` 调用了 `UploadScheduler.isValidTime(time)`，但该方法原来声明为：
```java
private static boolean isValidTime(String time)
```
`private` 只允许在 `UploadScheduler` 类内部调用，其他类无法访问。

**解决办法：**
把需要跨类调用的静态工具方法改为 `public`：
```java
public static boolean isValidTime(String time) {
    // 校验 HH:mm
}
```

**要点：**
- `cannot find symbol` 优先检查 import、依赖版本、类名/方法名及源码是否使用对应大版本 API。
- `has private access` 是访问修饰符问题，与依赖下载无关。
- 不要为了消除依赖解析错误而随意降级 SDK，应以源码实际 API 为准。

---

## 报错 11：Manifest merger failed，application android:label 冲突

**报错原文（关键行）：**
```
> Task :app:processDebugMainManifest FAILED
Manifest merger failed : Attribute application@label value=(截图定时上传)
  from AndroidManifest.xml:17:9-31
is also present at [com.qiniu:qiniu-android-sdk:8.11.0]
  AndroidManifest.xml:17:9-41 value=(@string/app_name).
Suggestion: add 'tools:replace="android:label"' to <application> element
  to override.
```

同时出现的警告：
```
Setting the namespace via the package attribute in the source AndroidManifest.xml
is no longer supported, and the value is ignored.
Recommendation: remove package="com.example.screenshotuploader" ...
```

**根因：**
主应用和七牛 SDK 的 AAR 都包含 `<application android:label="...">`，但值不同：
主应用是「截图定时上传」，SDK 使用它自己的 `@string/app_name`。
Manifest merger 无法自行判断最终应用名称，因此要求主应用明确指定覆盖规则。

`package` 警告则是另一件事：项目已在 `app/build.gradle` 中配置
`namespace 'com.example.screenshotuploader'`，AGP 8.x 不再从 Manifest 的 `package` 设置 namespace。

**解决办法：**
1. 在根 `<manifest>` 增加 `xmlns:tools`，并删除旧式 `package` 属性。
2. 在主应用的 `<application>` 上增加 `tools:replace="android:label"`。

修复后的关键结构：
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:label="截图定时上传"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        tools:replace="android:label">
        <!-- activity、receiver、service -->
    </application>
</manifest>
```

`app/build.gradle` 保留：
```gradle
android {
    namespace 'com.example.screenshotuploader'
    defaultConfig {
        applicationId "com.example.screenshotuploader"
    }
}
```

**要点：**
- `tools:replace` 是构建期合并指令，表示冲突时以主应用的 `android:label` 为准。
- `tools` 命名空间只影响构建，不会作为应用功能打包进 APK。
- 不要随意把一长串属性加入 `tools:replace`；只覆盖日志明确指出的冲突项。
- 删除 Manifest 的 `package` 不会改变应用 ID，`namespace` 和 `applicationId` 仍在 Gradle 中配置。

---

## 报错 12：cannot find symbol: Auth / Zone.autoZone()（Android SDK 无此类和此方法）

**报错原文（关键行）：**
```
> Task :app:compileDebugJavaWithJavac FAILED
error: cannot find symbol
  symbol:   class Auth
  location: package com.qiniu.android
error: cannot find symbol
  symbol:   method autoZone()
  location: class com.qiniu.android.common.Zone
```

**根因（已核对官方源码确认）：**
`com.qiniu:qiniu-android-sdk` 的 7.x、8.x **所有版本都没有 `Auth` 类**，
也没有 `Zone.autoZone()` 静态方法。核对方法：
下载 AAR（`https://repo.maven.apache.org/maven2/com/qiniu/qiniu-android-sdk/8.11.0/qiniu-android-sdk-8.11.0.aar`）
解包 `classes.jar` 列出全部类；或查 GitHub 仓库
`https://github.com/qiniu/android-sdk` 对应 tag 的源码树
（v8.11.0 只有 bigdata/collect/common/http/storage/transaction/utils 包，无 auth 包）。

源码里的 `Auth.create(ak, sk).uploadToken(bucket, key, 3600)` 写法来自
**Java SDK**（`com.qiniu:qiniu-java-sdk` 的 `com.qiniu.util.Auth`），
不能直接用在 Android SDK 上。官方建议上传凭证由服务端签发；
本项目是个人工具、AK/SK 存在客户端，因此选择本地自签。

**解决办法：**
1. **删除两处不存在的 API 调用**：
   - `import com.qiniu.android.auth.Auth;` → 删除
   - `.zone(Zone.autoZone())` → 删除（`Configuration.Builder` 的 zone 缺省值
     就是 `AutoZone`，等价于"自动区域"）
2. **新增 `QiniuToken.java` 本地签发上传凭证**，按官方算法实现
   （https://developer.qiniu.com/kodo/1208/upload-token ）：

```java
policy         = {"scope":"bucket:key","deadline":秒级时间戳}
encodedPolicy  = urlSafeBase64(policy JSON 的 UTF-8 字节)
sign           = HmacSHA1(secretKey, encodedPolicy 字符串字节)   // 注意：签的是编码后的字符串
token          = AK + ":" + urlSafeBase64(sign) + ":" + encodedPolicy
```

调用处替换：
```java
// 旧（Auth 不存在）：
String token = Auth.create(accessKey, secretKey).uploadToken(bucket, key, 3600);
// 新（本地签发）：
String token = QiniuToken.uploadToken(accessKey, secretKey, bucket, key, 3600);
```

**实现要点（都是踩过的坑）：**
- Base64 必须用 `android.util.Base64` + `Base64.URL_SAFE | Base64.NO_WRAP`：
  `java.util.Base64` 需要 API 26+，本项目 minSdk 21 会挂；
  `NO_WRAP` 防止插入换行符破坏 token。
- HMAC 的输入是 **encodedPolicy 字符串的字节**，不是原始 policy JSON 字节
  （与 Java SDK `Auth.signWithData` 的行为一致，签错会被服务端报 401/invalid token）。
- URL 安全 Base64 只替换 `+ → -`、`/ → _`，**保留 `=` 填充**（七牛 UrlSafeBase64 的约定）。
- scope 规则：key 非空 → `"bucket:key"`（指定 key 上传）；key 空 → `"bucket"`。

**排查方法论（如何在没有日志时预先验证依赖兼容性）：**
CI 日志拿不到时，可以直接从 Maven Central 下载目标版本 AAR，
解包 `classes.jar` 列出类名清单，对照源码 import 逐个核对；
再从 GitHub 仓库拉对应 tag 的源码看方法签名。这一步在本地 5 分钟就能做完，
比等 CI 跑一轮快得多。

---

## 报错 13：setup-gradle 校验手工 jar 失败（改用 setup-gradle 后的新坑）

**报错原文（关键行）：**
```
✗ Found unknown Gradle Wrapper JAR files:
  d18ee6b... gradle/wrapper/gradle-wrapper.jar
Error: At least one Gradle Wrapper Jar failed validation!
```

**根因：**
改用官方 `gradle/actions/setup-gradle@v4` 装Gradle后，该 action 默认会
**校验仓库里的 `gradle-wrapper.jar` 是否为 Gradle 官方发布版本**（比对其 SHA256
是否在官方 wrapper jar 白名单）。而报错 3/4/5 里**手工合并的 jar** 哈希对不上
官方任何版本 → 被判定为"未知/可疑 jar"，**在构建开始前就直接失败**。

**解决办法：**
既然已改用 setup-gradle 直接调 `gradle` 命令构建，wrapper jar/gradlew 脚本
已彻底不需要，直接删掉 + 关闭校验：

```
git rm -f gradle/wrapper/gradle-wrapper.jar gradlew
```

工作流里 `setup-gradle` 加 `validate-wrappers: false`（双保险），构建命令改为
`gradle assembleDebug`。三件套只剩 `gradle-wrapper.properties`（setup-gradle
读它决定装哪个 Gradle 版本，可保留；也可在 action 里用 `gradle-version` 显式指定）。

**要点：**
- 官方 action 的 wrapper 校验是安全特性，但它会把"手工拼的 jar"判为可疑。
- 若项目根本不靠 wrapper 构建，最干净的做法是删掉 wrapper jar，而非绕过校验。

---

## 报错 14：compileDebugJavaWithJavac 内部类修改局部变量（有效 final）

**报错原文（关键行）：**
```
UploadService.java:100: error: local variables referenced from an inner class
  must be final or effectively final
                        success++;
UploadService.java:106: error: ...
                        fail++;
```

**根因：**
`performUpload()` 的**局部变量** `success`、`fail` 在**匿名内部类**
`QiniuUploader.Callback` 的 `onSuccess`/`onFail` 里被 `++` 修改。
Java 要求匿名类引用的局部变量必须是 `final` 或"有效 final"（从未被重新赋值），
而 `success++` 让它不再是有效 final → 编译失败。这是纯 Java 语言限制，
与依赖、CI 环境无关。

**解决办法：**
用 `final int[]` 做**可变计数器**（数组引用是 final、元素可累加）：

```java
final int[] success = {0};
final int[] fail = {0};

// 回调里：
success[0]++;
fail[0]++;

// 末尾日志：
Log.d(TAG, "... 成功=" + success[0] + ", 失败=" + fail[0] + ...);
```

**要点：**
- 匿名类/lambda 里要"累加计数"→ 用 `final int[]`、`AtomicInteger` 或提为成员变量。
- 只读取（不重新赋值）的局部变量（如 `absPath`、`fileName`）无需改，本就有效 final。
- 这是本地 Android Studio 也会报的错，CI 报出来说明之前编译阶段一直没跑到这。

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
   - `Could not find` / `Could not resolve` → 依赖版本、仓库或网络问题（报错 8）
   - `processDebugResources` / `failed linking references` → 资源引用或资源文件缺失（报错 9）
   - `compileDebugJavaWithJavac` / `cannot find symbol` / `has private access` → 依赖 API 或 Java 源码问题（报错 10、12：特别注意七牛 Android SDK 无 Auth 类，token 需本地自签或服务端签发）
   - `processDebugMainManifest` / `Manifest merger failed` → 主应用与依赖库的 Manifest 属性冲突（报错 11）

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
