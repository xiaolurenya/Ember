# mpv 是靠 ClassName.method 反射回调 Java 的，JNI 入口类绝对不能被改名/删
-keep class is.xyz.mpv.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep @interface kotlin.Metadata { *; }

# OkHttp 用到 JDK 的 SSL / ALPN，不要被裁
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**

# Gson：本项目的数据模型字段必须保留
-keepclassmembers class cn.plum.ember.data.model.** {
    <fields>;
}
-keep class com.google.gson.** { *; }

# Coil
-dontwarn coil.**

# 保留 Compose 的稳定反射入口
-dontwarn androidx.compose.**
