# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# keep kotlinx serializable classes
-keep @kotlinx.serialization.Serializable class * {*;}

# keep jlatexmath
-keep class org.scilab.forge.jlatexmath.** {*;}

-dontwarn com.google.re2j.**
-dontobfuscate

# Ktor 在 Android 上引用了仅 JVM 可用的 java.lang.management 类（IntellijIdeaDebugDetector）
# Android 不包含这些类，需要告知 R8 忽略
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# java.beans is not available on Android; Jackson references it only on JVM
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient

# auth0/jackson: TypeReference subclasses rely on runtime generic signatures.
# R8 strips Signature/InnerClasses/EnclosingMethod by default, and its class
# merging/inlining optimizations can destroy the anonymous class hierarchy that
# TypeReference.<init> depends on via getClass().getGenericSuperclass().
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep class com.fasterxml.jackson.** { *; }
-keep class com.auth0.jwt.** { *; }

# ===== JSch (SSH) — mwiede fork =====
# JSch 内部通过反射加载 com.jcraft.jsch.jce.* 等类（随机数提供者、加密实现）。
# release 开启 minify 时 R8 会混淆/移除这些反射加载的类，导致运行时
# ClassNotFoundException: com.jcraft.jsch.jce.Random（SSH 工具全挂）。
# keep 整个 JSch 包及反射会用到的内部类。
-keep class com.jcraft.jsch.** { *; }
-keep interface com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
