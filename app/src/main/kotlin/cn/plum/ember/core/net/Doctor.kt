package cn.plum.ember.core.net

import cn.plum.ember.core.resolve.AdapterRegistry
import cn.plum.ember.data.MediaSource
import cn.plum.ember.data.SourceType
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/** 诊断单步结果 */
data class DiagStep(
    val title: String,
    val ok: Boolean,
    val detail: String,
    val ms: Long
)

/**
 * 连接诊断器。
 *
 * 为什么要有这个东西：
 *   之前「登录失败」永远只有一句「网络错误」，而「网络错误」可能是六种完全不同的故障——
 *   DNS 没解析、端口没开、反代没转发、明文 HTTP 被系统拦、账号密码错、服务器没建库。
 *   把它们压成一句话等于没有信息，只能一轮轮猜，大哥的时间全耗在往返上。
 *
 *   所以这里把一次连接拆成 6 步，每步单独出结果，失败在哪一步一目了然：
 *     ① 地址 → ② 域名解析 → ③ TCP 连通 → ④ 服务探活（不带账号）→ ⑤ 账号登录 → ⑥ 读取媒体库
 *
 * 关键设计：**④⑤ 分开**。
 *   ④ 用各自服务的「匿名端点」（Emby 的 /System/Info/Public、Plex 的 /identity、
 *   AList 的 /api/public/settings），这一层不需要任何账号。
 *   ④ 挂了 = 网络/地址/端口的问题；④ 通了 ⑤ 挂了 = 纯粹是账号密码的问题。
 *   这两类故障的排查方向完全不同，绝不能混在一起报。
 */
object Doctor {

    suspend fun run(source: MediaSource, password: String?): List<DiagStep> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<DiagStep>()
            // 密码是单独传进来的（表单里没落库），先注入一份副本再测
            val s = source.copy(token = password?.takeIf { it.isNotBlank() } ?: source.token)

            if (s.type == SourceType.LOCAL) {
                measure("① 本地目录", out) {
                    val p = s.scanRoot ?: s.baseUrl
                    val f = java.io.File(p)
                    when {
                        !f.exists() -> false to "路径不存在：$p"
                        !f.isDirectory -> false to "不是目录：$p"
                        !f.canRead() -> false to "没有读取权限：$p（需要授予存储权限）"
                        else -> true to "$p（可读，${f.listFiles()?.size ?: 0} 个条目）"
                    }
                }
                return@withContext out
            }

            var host = ""
            var port = 0

            measure("① 解析地址", out) {
                val u = URI(s.baseUrl)
                host = u.host ?: ""
                val scheme = u.scheme ?: "http"
                port = if (u.port > 0) u.port else if (scheme == "https") 443 else 80
                if (host.isBlank()) false to "地址里解析不出主机名：${s.baseUrl}"
                else true to "$scheme://$host:$port"
            }
            if (host.isBlank()) return@withContext out

            measure("② 域名解析", out) {
                val addr = InetAddress.getByName(host)
                val ip = addr.hostAddress ?: ""
                if (ip.isBlank()) false to "$host 解析失败"
                else true to "$host → $ip"
            }

            measure("③ TCP 连通", out) {
                Socket().use { sk ->
                    sk.connect(InetSocketAddress(host, port), 4000)
                    true to "$host:$port 连接成功"
                }
            }

            measure("④ 服务探活（不带账号）", out) { probe(s) }

            measure("⑤ 账号登录", out) {
                val a = AdapterRegistry.fresh(s)
                val r = a.test()
                if (r.isSuccess) true to "登录成功：${s.username?.takeIf { it.isNotBlank() } ?: "(Token 方式)"}"
                else false to (r.exceptionOrNull()?.message ?: "未知失败")
            }

            measure("⑥ 读取媒体库", out) {
                val libs = AdapterRegistry.fresh(s).libraries()
                if (libs.isEmpty()) false to "登录成功，但没读到媒体库（服务器里可能还没建库，或该账号没有可见库）"
                else true to "共 ${libs.size} 个：${libs.take(4).joinToString("、") { it.name }}"
            }

            out
        }

    // ------------------------------------------------------------------

    private inline fun measure(
        title: String,
        out: MutableList<DiagStep>,
        block: () -> Pair<Boolean, String>
    ) {
        val t0 = System.currentTimeMillis()
        val r = try {
            block()
        } catch (t: Throwable) {
            false to Http.describe(t)
        }
        out.add(DiagStep(title, r.first, r.second, System.currentTimeMillis() - t0))
    }

    /** 各类型服务的匿名探活端点 */
    private fun probe(s: MediaSource): Pair<Boolean, String> {
        val base = s.baseUrl.trimEnd('/')
        val req = when (s.type) {
            SourceType.EMBY, SourceType.JELLYFIN -> Request.Builder()
                .url("$base/System/Info/Public")
                .get()
                .header(
                    "X-Emby-Authorization",
                    "MediaBrowser Client=\"Ember\", Device=\"Ember\", DeviceId=\"diag\", Version=\"1.0.0\""
                )
                .build()

            SourceType.PLEX -> Request.Builder()
                .url("$base/identity")
                .get()
                .header("Accept", "application/json")
                .header("X-Plex-Client-Identifier", "ember-client")
                .build()

            SourceType.ALIST -> Request.Builder()
                .url("$base/api/public/settings")
                .get()
                .build()

            SourceType.WEBDAV -> Request.Builder()
                .url("$base/")
                .method("PROPFIND", null)
                .header("Depth", "0")
                .build()

            SourceType.LOCAL -> return true to "本地目录"
        }

        Http.client(s).newCall(req).execute().use { resp ->
            val code = resp.code
            val body = try {
                resp.body?.string().orEmpty()
            } catch (_: Throwable) {
                ""
            }
            return when {
                code in 200..299 -> true to "HTTP $code · ${summarize(s.type, body)}"
                code in 300..399 -> true to "HTTP $code 重定向（多半走反代，正常）"
                code == 401 -> true to "HTTP 401（服务在跑，只是要账号 —— 正常）"
                else -> false to "HTTP $code · ${body.take(120).ifBlank { "（空响应体）" }}"
            }
        }
    }

    /** 从探活响应里摘出「这是什么服务、什么版本」，确认没连错机器 */
    private fun summarize(t: SourceType, body: String): String {
        if (body.isBlank()) return "响应体为空"
        val j: JsonObject? = try {
            com.google.gson.JsonParser.parseString(body).asJsonObject
        } catch (_: Throwable) {
            null
        }
        if (j == null) {
            // WebDAV 之类返回 XML，能拿到响应就说明服务是活的
            return when (t) {
                SourceType.WEBDAV -> "WebDAV 已响应"
                else -> body.take(60)
            }
        }
        fun str(vararg names: String): String? =
            names.firstNotNullOfOrNull { n ->
                j.get(n)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            }
        return when (t) {
            SourceType.EMBY, SourceType.JELLYFIN ->
                listOfNotNull(str("ServerName"), str("Version")).joinToString(" ").ifBlank { "Emby/Jellyfin" }
            SourceType.PLEX ->
                (j.getAsJsonObject("MediaContainer")?.let { mc ->
                    mc.get("version")?.takeIf { it.isJsonPrimitive }?.asString
                })?.let { "Plex $it" } ?: "Plex"
            SourceType.ALIST ->
                (j.getAsJsonObject("data")?.let { d ->
                    d.get("version")?.takeIf { it.isJsonPrimitive }?.asString
                })?.let { "AList $it" } ?: "AList"
            else -> "已响应"
        }
    }
}
