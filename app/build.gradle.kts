import java.io.FileInputStream
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

android {
    namespace = "cn.plum.ember"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.plum.ember"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // 只出 arm64 + armeabi-v7a：x86 基本用不上，且能砍掉一半体积
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        resourceConfigurations += setOf("zh", "en")
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
            isMinifyEnabled = false        // mpv 的 JNI 回调依赖类名，先不混淆
            isShrinkResources = false
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
    // 注意：这个库没有能用的 README，真实 API 是 MPV(ctx) + nativeCreate() + nativeInit()
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
