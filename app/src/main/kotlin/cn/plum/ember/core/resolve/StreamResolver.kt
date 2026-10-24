package cn.plum.ember.core.resolve

import cn.plum.ember.core.net.Http
import cn.plum.ember.data.MediaSource
import cn.plum.ember.data.Playable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 直链解析管线。
 *
 * 一次「用户点片」到「mpv 拿到真正能播的地址」之间发生的事：
 *
 *   1. 目标是本地路径        → file:// 直接给 mpv（mpv 原生支持）
 *   2. 目标是 .strm          → 读出里面的 URL（可能多行，取第一行非注释）
 *   3. URL 是 http(s)        → 探测（Range: 0-0）
 *       - 直接 200/206      → 网盘/Alist raw_url 直接可用
 *       - 30x               → 手动跟跳转，沿途收集 Set-Cookie（关键！）
 *   4. 组装最终头（UA/Referer/Cookie）+ 一起丢给 mpv
 *
 * token 过期的处理：mpv 播放中断流 → PlayerViewModel 捕获 → revalidate() 重跑管线
 * → 拿到新地址 → seek 到原位置继续播。本类只负责「单次解析」，重试由上层控制。
 */
object StreamResolver {

    /** STRM 文件内容 → 直链 URL（支持多行、忽略注释/空行） */
    fun parseStrm(content: String): String? =
        content.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }

    /** 本地文件 → mpv 可用的 file:// */
    fun local(path: String): String =
        if (path.startsWith("file://")) path
        else "file://" + path.trim().let { if (!it.startsWith("/")) "/$it" else it }

    /**
     * 主入口：把任意目标转成 Playable。
     * @param target 可以是 http(s) 直链、file:// 路径、.strm 文件路径、.strm 文件内容
     */
    suspend fun resolve(
        target: String,
        source: MediaSource? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        startSec: Long = 0L,
        title: String = ""
    ): Result<Playable> = withContext(Dispatchers.IO) {
        try {
            // ---------- 1) 本地文件 ----------
            if (!target.startsWith("http", true)) {
                val p = if (target.startsWith("file://")) target.substringAfter("file://") else target
                val f = File(p)
                if (!f.exists()) {
                    return@withContext Result.failure(IllegalStateException("本地文件不存在：$p"))
                }
                // .strm 是本地文件，读出 URL 再继续走网络解析
                if (f.name.endsWith(".strm", true)) {
                    val url = parseStrm(f.readText())
                        ?: return@withContext Result.failure(IllegalStateException(".strm 文件是空的"))
                    return@withContext resolveHttp(url, source, extraHeaders, startSec, title)
                }
                return@withContext Result.success(
                    Playable(local(f.absolutePath), startSec = startSec, title = title)
                )
            }

            // ---------- 2) 网络地址 ----------
            resolveHttp(target, source, extraHeaders, startSec, title)
        } catch (t: Throwable) {
            Result.failure(RuntimeException("解析失败：${t.message ?: t.javaClass.simpleName}", t))
        }
    }

    private suspend fun resolveHttp(
        url: String,
        source: MediaSource?,
        extraHeaders: Map<String, String>,
        startSec: Long,
        title: String
    ): Result<Playable> = withContext(Dispatchers.IO) {
        // m3u8/HLS 让 mpv 自己处理，不走 resolveRedirects（mpv 对 HLS 的 Cookie 语义更熟）
        if (url.substringBefore('?').endsWith(".m3u8", true) ||
            url.contains(".m3u8", true) && url.indexOf(".m3u8", 0, true) > 0
        ) {
            return@withContext Result.success(
                Playable(url, headers = headersFor(source, extraHeaders, emptyMap()), startSec = startSec, title = title)
            )
        }

        // 探测：Range: 0-0，既能确认直链可用、又能拿到真实状态码，
        // 还避免 GET 整个流（4K 蓝光一个几 GB 直接灌内存）
        val probeHeaders = headersFor(source, extraHeaders, emptyMap()) +
                mapOf("Range" to "bytes=0-0")
        val resp = try {
            Http.get(url, source, probeHeaders)
        } catch (t: Throwable) {
            return@withContext Result.failure(RuntimeException(Http.describe(t)))
        }

        val status = resp.code
        val loc = resp.header("Location")
        val probeCookies = linkedMapOf<String, String>()
        resp.headers("Set-Cookie").forEach { raw ->
            val kv = raw.substringBefore(';').trim()
            val i = kv.indexOf('=')
            if (i > 0) probeCookies[kv.substring(0, i).trim()] = kv.substring(i + 1).trim()
        }
        resp.close()

        // 30x：手动跟跳转，收集沿途所有 Cookie（mpv 自己跟跳转拿不到 Cookie 会 403）
        if (status in 300..399) {
            if (loc.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException("服务器返回 $status 但没有 Location"))
            }
            val resolved = Http.resolveRedirects(url, source, headersFor(source, extraHeaders, emptyMap()))
            val finalUrl = resolved.finalUrl
            val cookies = probeCookies + resolved.cookies
            return@withContext Result.success(
                Playable(finalUrl, headersFor(source, extraHeaders, cookies), startSec, title)
            )
        }

        // 200/206：直链可用，带上探测过程中收到的 Cookie
        if (status in 200..299) {
            return@withContext Result.success(
                Playable(url, headersFor(source, extraHeaders, probeCookies), startSec, title)
            )
        }

        // 其它错误：给可读提示
        return@withContext Result.failure(
            when (status) {
                401 -> RuntimeException("鉴权失败（401），请重新登录该源")
                403 -> RuntimeException("无权访问（403），Cookie 可能已过期")
                404 -> RuntimeException("资源不存在（404）")
                410 -> RuntimeException("链接已失效（410），需要重新换取直链")
                500, 502, 503 -> RuntimeException("服务端错误（$status），源服务器可能正在维护")
                else -> RuntimeException("HTTP $status")
            }
        )
    }

    /** 组装最终给 mpv 的请求头：源的自定义头 → 探测收集的 Cookie → 全局默认 */
    private fun headersFor(
        source: MediaSource?,
        extra: Map<String, String>,
        cookies: Map<String, String>
    ): Map<String, String> {
        val out = linkedMapOf<String, String>()
        // OkHttp 层已经注入了源自定义 UA，这里明确写给 mpv
        out["User-Agent"] = source?.extraHeaders?.entries
            ?.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: Http.DEFAULT_UA
        source?.extraHeaders?.forEach { (k, v) ->
            if (!k.equals("User-Agent", true)) out[k] = v
        }
        extra.forEach { (k, v) -> out[k] = v }
        if (cookies.isNotEmpty()) {
            out["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        return out
    }
}
