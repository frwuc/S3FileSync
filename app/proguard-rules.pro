# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ---- 七牛 SDK ----
# SDK 内部有反射加载与 JNI 调用，整体保留，避免 release 包上传时崩溃
-keep class com.qiniu.android.** { *; }
-dontwarn com.qiniu.**

# ---- conscrypt（七牛 SDK 的传递依赖）----
# JNI 按类名/方法名绑定，混淆会让 native 绑定失效；
# Android 9 及以下 okhttp 会走到 ConscryptSocketAdapter，故不能删也不能改名
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# ---- okhttp / okio ----
# 平台探测用反射（AndroidPlatform / Android10Platform），保留平台类名
-keep class okhttp3.internal.platform.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamefiles sourcefile
