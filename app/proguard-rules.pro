# ============================================================
# Ember 混淆 / 加固规则
#
# 开混淆的前提是把「靠名字被找到的东西」全部钉住：
#   1. JNI 从 C++ 反过来调 Java（mpv 的事件回调）
#   2. Gson 按字段名反序列化（数据模型、源配置）
#   3. 反射入口（Compose 的部分 runtime）
# 这三类漏一个，表现就是「编译过、装得上、一运行就崩」，且堆栈很难定位。
# ============================================================

# ---------- 1. libmpv：JNI 反射回调，名字一个都不能动 ----------
-keep class is.xyz.mpv.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep @interface kotlin.Metadata { *; }

# ---------- 2. 本项目自己的模型 / 内核 / 安全模块 ----------
# 源配置是 Gson 序列化进 SharedPreferences 的，字段名改了老用户的数据就废了
-keep class cn.plum.ember.data.** { *; }
-keepclassmembers class cn.plum.ember.data.** { <fields>; }
-keep class cn.plum.ember.core.mpv.** { *; }
-keep class cn.plum.ember.core.resolve.** { *; }
-keep class cn.plum.ember.core.net.** { *; }
-keep class cn.plum.ember.security.** { *; }

# ---------- 3. Gson / OkHttp / Coil ----------
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn coil.**

# ---------- 4. Compose / AndroidX ----------
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# ---------- 5. 安全模块要用到的反射信息 ----------
# Guard 扫描时靠 BuildConfig 常量比对，别让 R8 把常量折叠掉
-keep class cn.plum.ember.BuildConfig { *; }

# ---------- 6. 去掉日志里的噪音（保留异常堆栈） ----------
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
