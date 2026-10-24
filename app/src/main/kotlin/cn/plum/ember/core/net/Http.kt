package cn.plum.ember.core.net

import cn.plum.ember.data.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 统一网络层。
 *
 * 关键设计：**默认关闭 OkHttp 自动跟随 302**。
 * 原因（踩过的坑）：网盘直链在 302 过程中会下发鉴权 Cookie（比如 115 的
 * UID/CID/SEID + 一个新的 session），如果让底层 libmpv 自己跟跳转，它拿不到
 * 这些 Cookie，第二次请求 CDN 就会 403。所以：
 *
 *   App 层 → OkHttp（手动跟跳转，沿途收集 Set-Cookie）→ 把最终 URL + Cookie 一起交给 mpv
 */
object Http {
    /** 每个源一份 OkHttpClient（不同源代理/UA/TLS 配置不同） */
    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    /** 全局默认 UA，可在源里覆盖 */
    const val DEFAULT_UA: String =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val trustAllSsl: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
    }

    /** 内存 CookieJar：跟 302 时收集沿途 Cookie */
    private val cookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val key = "${url.scheme}://${url.host}:${url.port}"
            store[key] = (store[key] ?: emptyList()).let { old ->
                // 同名 Cookie 用新的覆盖旧的（Set-Cookie 会话刷新）
                val names = cookies.map { it.name }.toSet()
                old.filterNot { it.name in names } + cookies
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val key = "${url.scheme}://${url.host}:${url.port}"
            return store[key] ?: emptyList()
        }

        fun clear() = store.clear()
    }

    /**
     * 缓存 key 必须带上「影响建连的参数」。
     * ⚠️ 只按 source.id 缓存是个坑：用户在表单里改了 baseUrl / 勾了跳过证书 / 配了代理，
     *    id 没变 → 拿到还是旧 client → 改了半天测的仍是旧配置。
     */
    private fun key(source: MediaSource?): String = buildString {
        append(source?.id ?: "default")
        append('|').append(source?.baseUrl)
        append('|').append(source?.skipTlsVerify)
        append('|').append(source?.useProxy)
        append('|').append(source?.proxyUrl)
        append('|').append(
            source?.extraHeaders?.entries?.sortedBy { it.key }
                ?.joinToString(";") { "${it.key}=${it.value}" }
        )
    }

    /** 取该源对应的 OkHttpClient */
    fun client(source: MediaSource?): OkHttpClient {
        val k = key(source)
        return clients.getOrPut(k) { build(source) }
    }

    /**
     * 丢掉某个源的所有 client（AdapterRegistry.invalidate 会连带调用）。
     * 用显式迭代器而不是 removeIf：ConcurrentHashMap.KeySetView 的 removeIf 走的是
     * Collection 默认实现，在低版本 desugar 链上出过幺蛾子，手写更稳。
     */
    fun invalidate(sourceId: String?) {
        val id = sourceId ?: "default"
        val prefix = "$id|"
        val it = clients.keys.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (k == id || k.startsWith(prefix)) it.remove()
        }
    }

    private fun build(source: MediaSource?): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(source?.let { 70 } ?: 30, TimeUnit.SECONDS)   // 大码率网盘直链给长超时
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .cookieJar(cookieJar)
            .followRedirects(false)      // 核心：手动跟 302
            .followSslRedirects(false)

        // 自定义 UA / Referer / Cookie / 其它头
        val ua = source?.extraHeaders?.entries
            ?.firstOrNull { it.key.equals("User-Agent", true) }?.value
        b.addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", ua ?: source?.extraHeaders?.entries
                    ?.firstOrNull { false }?.value ?: DEFAULT_UA)
                .build()
            chain.proceed(req)
        }

        if (source?.skipTlsVerify == true) {
            b.sslSocketFactory(trustAllSsl.socketFactory, trustAll)
            b.hostnameVerifier(HostnameVerifier { _, _ -> true })
        }
        if (source?.useProxy == true && !source.proxyUrl.isNullOrBlank()) {
            val u = source.proxyUrl!!.toHttpUrlOrNull()
            if (u != null) {
                b.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(u.host, u.port)))
            }
        }
        return b.build()
    }

    fun clearCookies() = cookieJar.clear()

    /**
     * 把网络异常翻译成可诊断的一句话。
     * ⚠️ 千万别再写「网络错误」这种没信息的文案——排查半天全靠这里。
     * NetworkOnMainThreadException 的 message 是 null，只打 message 会丢掉关键线索。
     */
    fun describe(t: Throwable): String {
        val cls = t.javaClass.simpleName.ifBlank { t.javaClass.name.substringAfterLast('.') }
        val msg = t.message?.takeIf { it.isNotBlank() } ?: "（无详细信息）"
        return "$cls: $msg"
    }

    /** 简单 GET，返回 Response（调用方负责 close） */
    fun get(url: String, source: MediaSource? = null, headers: Map<String, String> = emptyMap()): Response {
        val b = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> b.header(k, v) }
        source?.extraHeaders?.forEach { (k, v) ->
            if (k !in headers.keys && !k.equals("User-Agent", true)) b.header(k, v)
        }
        return client(source).newCall(b.build()).execute()
    }

    /** POST JSON */
    fun postJson(
        url: String,
        body: String,
        source: MediaSource? = null,
        headers: Map<String, String> = emptyMap()
    ): Response {
        val json = "application/json; charset=utf-8".toMediaTypeOrNull()
        val b = Request.Builder().url(url)
            .post(body.toRequestBody(json))
        headers.forEach { (k, v) -> b.header(k, v) }
        source?.extraHeaders?.forEach { (k, v) ->
            if (k !in headers.keys && !k.equals("User-Agent", true)) b.header(k, v)
        }
        return client(source).newCall(b.build()).execute()
    }

    /** 手动跟随 302，收集沿途所有 Set-Cookie 和最终 URL */
    data class Resolved(
        val finalUrl: String,
        val cookies: Map<String, String>,
        val status: Int,
        /** 终点响应的 Content-Type（判断终点是媒体流还是文本/网页） */
        val contentType: String? = null
    )

    suspend fun resolveRedirects(
        url: String,
        source: MediaSource? = null,
        headers: Map<String, String> = emptyMap(),
        maxHops: Int = 10
    ): Resolved = withContext(Dispatchers.IO) {
        var current = url
        var status = 0
        var contentType: String? = null
        val cookies = linkedMapOf<String, String>()
        repeat(maxHops) {
            val resp = get(current, source, headers)
            status = resp.code
            contentType = resp.header("Content-Type")
            // 收集本次响应下发的所有 Set-Cookie
            resp.headers("Set-Cookie").forEach { raw ->
                val kv = raw.substringBefore(';').trim()
                val i = kv.indexOf('=')
                if (i > 0) cookies[kv.substring(0, i).trim()] = kv.substring(i + 1).trim()
            }
            // 3xx 且有 Location 就继续跳
            val loc = resp.header("Location")
            resp.close()
            if (status in 300..399 && !loc.isNullOrBlank()) {
                current = java.net.URI(current).resolve(java.net.URI(loc)).toString()
                return@repeat
            }
            return@withContext Resolved(current, cookies, status, contentType)
        }
        Resolved(current, cookies, status, contentType)
    }

    /** 判断 URL 是否需要走 resolveRedirects（网盘 302 场景） */
    fun isDirectPlayable(url: String): Boolean {
        // m3u8/HLS 让 mpv 自己处理即可；mp4/mkv 等一般也直接可播
        return !url.contains("alist") && !url.contains("115.com") && !url.contains("115cdn")
    }
}

/** OkHttp Call 简易协程扩展 */
suspend fun Call.await(): Response = withContext(Dispatchers.IO) { execute() }
