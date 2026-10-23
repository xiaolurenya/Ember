// 根构建脚本：只声明插件版本（apply false），具体依赖写在 app/build.gradle.kts
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 起 Compose 编译器独立成插件，不再用 composeOptions.kotlinCompilerExtensionVersion
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
