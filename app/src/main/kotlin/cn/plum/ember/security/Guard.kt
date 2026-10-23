package cn.plum.ember.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Debug
import android.os.Build
import cn.plum.ember.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * 运行环境自检（加固 / 防二次打包 / 防注入）。
 *
 * 设计原则（很重要，别改）：
 *   **只提示、不杀进程**。误判的代价远大于被破解的代价——
 *   大哥自己就是 Root 设备 + 自签名证书的重度用户，一旦误杀 App 直接不可用。
 *   所以除了「签名被改」这种铁证，其它一律只告警。
 *
 * 覆盖四类：
 *   1. 签名校验  —— 防二次打包 / 植入广告 / 盗号（比对 APK 证书 SHA-256 与构建时烧进去的值）
 *   2. 调试器    —— 防动态调试拖走内存里的 Cookie / 密码
 *   3. Hook 框架 —— Xposed / Frida / 太极 / Substrate，防接口被劫持改包
 *   4. Root      —— 只告警，不阻断
 */
object Guard {

    enum class Level { OK, WARN, ALARM }

    data class Finding(val level: Level, val title: String, val detail: String)

    // ===================== 1. 签名 =====================

    /** 构建时从 keystore 算出来的证书 SHA-256；debug 构建为空串 */
    private val expectedFingerprint: String get() = BuildConfig.SIGN_FINGERPRINT

    /** 当前 APK 实际签名证书的 SHA-256（小写 hex，无冒号） */
    fun currentFingerprint(ctx: Context): String? = try {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()
        }
        val raw = info?.toByteArray() ?: return null
        MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) { null }

    /**
     * 签名是否被改过。
     * 返回 null 表示「没烧指纹，不做校验」（debug 包），调用方按通过处理。
     */
    fun isTampered(ctx: Context): Boolean? {
        val expect = expectedFingerprint
        if (expect.isBlank()) return null          // debug 构建不校验
        val actual = currentFingerprint(ctx) ?: return true   // 读不到 = 可疑
        return !actual.equals(expect, ignoreCase = true)
    }

    // ===================== 2. 调试器 =====================

    fun debuggerAttached(): Boolean =
        Debug.isDebuggerConnected() || Debug.waitingForDebugger()

    // ===================== 3. Hook 框架 =====================

    private val HOOK_FILES = arrayOf(
        "/system/lib/libxposed_art.so",
        "/system/lib64/libxposed_art.so",
        "/system/lib/libxposed_art_native.so",
        "/system/framework/xposed.prop",
        "/system/framework/de.robv.android.xposed.jar",
        "/data/local/tmp/frida-server",
        "/data/local/tmp/re.frida.server",
        "/system/lib/libc_malloc_debug.so",     // 太极
        "/system/lib/libsubstrate.so",          // Substrate
        "/system/lib/libsubstrate_dvm.so"
    )

    private val HOOK_PACKAGES = arrayOf(
        "de.robv.android.xposed.installer",
        "io.va.exposed",
        "com.topjohnwu.magisk",
        "me.weishu.exp",
        "com.taichi.android",
        "org.meowcat.edxposed.manager"
    )

    /** 关键字命中的 Hook 框架（Xposed / Frida / 太极 / Substrate…） */
    fun hookFrameworks(ctx: Context): List<String> {
        val hits = mutableListOf<String>()
        HOOK_FILES.filter { File(it).exists() }.mapTo(hits) { "文件:$it" }

        val pm = ctx.packageManager
        HOOK_PACKAGES.forEach { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) }
                .onSuccess { hits += "已安装:$pkg" }
        }

        // Frida 默认监听 27042；读 /proc/net/tcp 比扫端口更省事也不会被 SELinux 拦
        runCatching {
            val tcp = File("/proc/net/tcp").readText()
            if (tcp.contains(":698A") || tcp.contains(":6992")) hits += "Frida 默认端口(27042)在监听"
        }
        return hits
    }

    // ===================== 4. Root =====================

    private val ROOT_BINARIES = arrayOf(
        "/system/app/Superuser.apk",
        "/sbin/su", "/system/bin/su", "/system/xbin/su",
        "/data/local/xbin/su", "/data/local/bin/su",
        "/system/sd/xbin/su", "/system/bin/failsafe/su",
        "/data/local/su", "/su/bin/su",
        "/magisk/.core/bin/su", "/system/xbin/daemonsu"
    )

    fun isRooted(): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) return true
        if (ROOT_BINARIES.any { File(it).exists() }) return true
        return runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
            p.inputStream.bufferedReader().readText().isNotBlank().also { p.destroy() }
        }.getOrDefault(false)
    }

    /** 最常见的云手机 / 模拟器特征（只告警） */
    fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.contains("vbox", true) ||
                Build.FINGERPRINT.contains("emulator", true) ||
                Build.MODEL.contains("Emulator", true) ||
                Build.MODEL.contains("Android SDK", true) ||
                Build.HARDWARE.contains("goldfish", true) ||
                Build.HARDWARE.contains("ranchu", true) ||
                Build.MANUFACTURER.contains("Genymotion", true)

    // ===================== 汇总 =====================

    /** 一次性跑完所有检查，给 UI 直接展示 */
    fun scan(ctx: Context): List<Finding> {
        val out = mutableListOf<Finding>()

        when (isTampered(ctx)) {
            true -> out += Finding(
                Level.ALARM, "安装包签名不一致",
                "当前 APK 的签名证书与官方不一致，可能是被二次打包过的版本。" +
                        "建议卸载后从 GitHub Release 重新下载安装。"
            )
            false -> out += Finding(Level.OK, "签名校验通过", "安装包签名与官方一致")
            null -> out += Finding(Level.OK, "签名校验未启用", "调试版不做签名校验")
        }

        if (debuggerAttached()) {
            out += Finding(Level.ALARM, "检测到调试器", "有调试器正在附加到本进程，凭据可能被读取。")
        }

        val hooks = hookFrameworks(ctx)
        if (hooks.isNotEmpty()) {
            out += Finding(Level.WARN, "检测到注入框架", hooks.joinToString("、"))
        }

        if (isRooted()) {
            out += Finding(Level.WARN, "设备已 Root", "Root 环境下凭据仍有加密保护，但风险高于普通设备。")
        }

        if (isEmulator()) {
            out += Finding(Level.WARN, "运行在模拟器", "模拟器环境仅供调试，不建议用于日常观看。")
        }

        return out
    }

    /** 是否存在必须打断用户的严重问题 */
    fun hasAlarm(ctx: Context): Boolean =
        isTampered(ctx) == true || debuggerAttached()
}
