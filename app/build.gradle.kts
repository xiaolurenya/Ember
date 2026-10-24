import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 本地签名：key.properties 不存在时（如 CI 未配 secrets）自动退回 debug 签名，保证能装
val keystorePropsFile = rootProject.file("key.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        load(FileInputStream(keystorePropsFile))
    }
}

/**
 * 从签名密钥库里算出证书 SHA-256，烧进 BuildConfig。
 * 运行时 Guard 拿它跟 APK 实际签名比对 → 能识别二次打包。
 * 没配 keystore（debug 构建）时返回空串，Guard 会自动跳过校验。
 *
 * ⚠️ .kts 里必须显式 import java.security.*，不能写全限定名：
 *   「java」在 Gradle Kotlin DSL 里是 Project 的扩展属性（JavaPluginExtension），
 *   写 java.security.KeyStore 会被解析成 java.security → Unresolved reference。
 */
fun signingFingerprint(): String {
    val store = keystoreProps["storeFile"]?.toString()
    if (store.isNullOrBlank() || !rootProject.file(store).exists()) return ""
    val alias = keystoreProps["keyAlias"]?.toString() ?: return ""
    val pw = keystoreProps["storePassword"]?.toString()?.toCharArray() ?: return ""
    return try {
        // .jks 里存的可能是 PKCS12 也可能是 JKS，两种都试
        val ks = runCatching {
            KeyStore.getInstance("PKCS12").apply {
                FileInputStream(store).use { load(it, pw) }
            }
        }.getOrElse {
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                FileInputStream(store).use { load(it, pw) }
            }
        }
        val cert = ks.getCertificate(alias) ?: return ""
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        println("读取签名指纹失败（不影响构建）：${t.message}")
        ""
    }
}

val signFingerprint = signingFingerprint()

android {
    namespace = "cn.plum.ember"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.plum.ember"
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "1.1.6"

        // 只出 arm64 + armeabi-v7a：x86 基本用不上，且能砍掉一半体积
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        resourceConfigurations += setOf("zh", "en")

        // 加固用：APK 证书指纹，运行时防二次打包
        buildConfigField("String", "SIGN_FINGERPRINT", "\"$signFingerprint\"")
        // 构建时间戳，UI 上显示「构建于 xxxx」
        buildConfigField("long", "BUILD_TIME", System.currentTimeMillis().toString() + "L")
    }

    signingConfigs {
        create("release") {
            val store = keystoreProps["storeFile"]?.toString()
            if (!store.isNullOrBlank() && file(store).exists()) {
                storeFile = file(store)
                storePassword = keystoreProps["storePassword"]?.toString()
                keyAlias = keystoreProps["keyAlias"]?.toString()
                keyPassword = keystoreProps["keyPassword"]?.toString()
            }
        }
    }

    buildTypes {
        release {
            // 加固一环：混淆 + 压缩。mpv 的 JNI 回调类已在 proguard-rules.pro 里 keep 住，
            // 我们自己的 Gson 模型同样保留字段名，详见该文件注释。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (keystoreProps["storeFile"] != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true   // 同时出一个通用包，方便直接分享
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE"
            )
        }
        jniLibs {
            pickFirsts += setOf("**/libc++_shared.so")
        }
    }
}

dependencies {
    // ---------- AndroidX 基础 ----------
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.window:window:1.3.0")

    // ---------- Compose ----------
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---------- 播放内核：libmpv ----------
    // 0.1.12 真实 API（README 是准的，别照抄旧版博客）：
    //   val mpv = MPV(); mpv.create(ctx); mpv.setOptionString(...); mpv.init()
    //   mpv.propFlow<T>("pause") / mpv.prop["pause"] / mpv.destroy()
    //   MPVNode.asInt() 返回 Long（IntNode 存 Long），取宽高要 .toInt()
    implementation("io.github.abdallahmehiz:mpv-android-lib:0.1.12")

    // ---------- 网络 / 图片 / JSON ----------
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // ---------- 存储 / 安全 ----------
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.1.0")

    // ---------- 其它 ----------
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
}
