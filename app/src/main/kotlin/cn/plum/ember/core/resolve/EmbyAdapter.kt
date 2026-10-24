package cn.plum.ember.core.resolve

import cn.plum.ember.core.net.Http
import cn.plum.ember.data.Chapter
import cn.plum.ember.data.Library
import cn.plum.ember.data.MediaItem
import cn.plum.ember.data.MediaSource
import cn.plum.ember.data.MediaVersion
import cn.plum.ember.data.Person
import cn.plum.ember.data.Playable
import cn.plum.ember.util.Json
import com.google.gson.JsonObject

/**
 * Emby / Jellyfin 统一适配器（两者的 REST API 高度兼容，仅鉴权头名字差一点）。
 *
 * 这里集中了 3 个必须知道的「坑」，都是踩过一次就回不来的：
 *   1. Emby 的 Fields 默认不含 Path！不显式要就拿不到 .strm 的原始路径 → 漏判 → 走直链流 → 500。
 *   2. 对 .strm 条目用 /Videos/{id}/stream?static=true 会直接 500 —— Emby 自己解析不了 strm。
 *      正确做法：GET /Items/{id}/Download 拿到 .strm 里的直链，自己跟 302。
 *   3. AuthenticateByName 必须带 X-Emby-Authorization 头，否则 401。
 */
open class EmbyAdapter(override val source: MediaSource) : SourceAdapter {

    protected open val authHeader: String = "X-Emby-Authorization"
    protected open val tokenHeader: String = "X-Emby-Token"

    protected var userId: String? = null
    protected var accessToken: String? = null
    /** 探活拿到的服务器名+版本，报错时带上它，用户才知道到底连上了哪台机器 */
    protected var serverName: String? = null

    private val deviceId: String = "ember-${source.id.hashCode().toUInt()}"

    /** 组装通用头 */
    private fun headers(auth: Boolean = false, withUserId: Boolean = false): Map<String, String> {
        val h = mutableMapOf<String, String>()
        if (auth) {
            h[authHeader] = buildString {
                append("MediaBrowser Client=\"Ember\", Device=\"Ember\", DeviceId=\"$deviceId\", Version=\"1.0.0\"")
            }
        }
        accessToken?.let {
            h[tokenHeader] = it
            h["X-MediaBrowser-Token"] = it
        }
        return h
    }

    /** baseUrl 末尾可能带 /（用户手输的），统一剥掉，否则拼出 //Users/... 双斜杠 */
    protected val base: String get() = source.baseUrl.trimEnd('/')

    private fun url(path: String, params: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder(base).append(path)
        if (params.isNotEmpty()) {
            sb.append(if (path.contains('?')) '&' else '?')
            params.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) sb.append('&')
                sb.append(k).append('=').append(java.net.URLEncoder.encode(v, "UTF-8"))
            }
        }
        return sb.toString()
    }

    // ================= 登录 / 测试 =================

    /**
     * 连接测试 = 探活 + 登录，两段分开。
     *
     * 为什么要拆：
     *   「连不上服务器」和「账号密码不对」是两类完全不同的故障，排查方向南辕北辙。
     *   之前一股脑报「网络错误」，用户根本不知道该去查地址还是查密码。
     *
     *   /System/Info/Public 是 Emby/Jellyfin 的**匿名端点**，不需要任何账号，
     *   拿得到 ServerName/Version 就证明地址、端口、协议、网络全对。
     *
     * ⚠️ 探活失败不能直接判死：有些反代会拦未知路径。所以探活失败只是记下来，
     *    真正的判据是登录本身 —— 登录也挂了，才把探活的错作为根因报出去。
     */
    override suspend fun test(): Result<Unit> {
        var probeErr: String? = null

        // ① 匿名探活
        try {
            val p = Http.get(url("/System/Info/Public"), source, headers(auth = true))
            val pt = p.body?.string()
            val code = p.code
            p.close()
            if (code in 200..299) {
                val pj = Json.gson.fromJson(pt, JsonObject::class.java)
                fun s(key: String): String? =
                    pj?.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                serverName = listOfNotNull(s("ServerName"), s("Version"))
                    .joinToString(" ").ifBlank { null }
            } else {
                probeErr = "探活返回 HTTP $code"
            }
        } catch (t: Throwable) {
            probeErr = Http.describe(t)
        }

        // ② 登录
        return try {
            val body = JsonObject().apply {
                addProperty("Username", source.username ?: "")
                addProperty("Pw", source.token ?: "")   // Emby 的密码放在 Pw 字段
            }
            val resp = Http.postJson(
                url("/Users/AuthenticateByName"), Json.gson.toJson(body), source, headers(auth = true)
            )
            val text = resp.body?.string()
            val code = resp.code
            resp.close()

            if (code !in 200..299) {
                val detail = when (code) {
                    401 -> "用户名或密码不对"
                    403 -> "账号被禁用，或没有登录权限"
                    404 -> "接口不存在（地址是不是少了 /emby 之类的子路径？）"
                    else -> "HTTP $code"
                }
                val msg = if (probeErr != null) {
                    "连不上服务器（$probeErr）—— 先查地址/端口/网络"
                } else {
                    "服务器已连上（${serverName ?: "Emby/Jellyfin"}），但登录失败：$detail"
                }
                return Result.failure(RuntimeException(msg))
            }

            val j = Json.gson.fromJson(text, JsonObject::class.java)
            userId = j?.getAsJsonObject("User")?.get("Id")?.asString
            accessToken = j?.get("AccessToken")?.asString
            if (userId.isNullOrBlank() || accessToken.isNullOrBlank()) {
                Result.failure(RuntimeException("服务器响应异常：缺少 User.Id 或 AccessToken（可能不是 Emby/Jellyfin 服务）"))
            } else Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(
                RuntimeException(
                    if (probeErr != null) "连不上服务器：$probeErr"
                    else "登录请求失败：${Http.describe(t)}"
                )
            )
        }
    }

    protected suspend fun ensureAuth(): Result<Unit> {
        if (userId != null && accessToken != null) return Result.success(Unit)
        return test()
    }

    // ================= 媒体库 =================
    override suspend fun libraries(): List<Library> {
        ensureAuth().getOrThrow()
        val resp = Http.get(url("/Users/$userId/Views"), source, headers())
        val text = resp.body?.string()
        resp.close()
        if (!resp.isSuccessful) return emptyList()
        return parseItems(text).map {
            // Gson 的 JsonObject 没有 optString，统一走 get(...)?.asString
            Library(
                id = it.get("Id")?.asString ?: "",
                name = it.get("Name")?.asString ?: "未命名",
                kind = it.get("CollectionType")?.asString ?: "mixed",
                image = posterUrl(it.get("Id")?.asString ?: "")
            )
        }
    }

    // ================= 条目 =================
    /**
     * Emby 的 Fields 默认不含 Path —— 不显式要，.strm 判定就会漏掉，
     * 后面走 /stream?static=true 就直接 500。血泪教训。
     */
    private val fields = listOf(
        "Path", "MediaSources", "MediaStreams", "ProductionYear", "Overview",
        "CommunityRating", "Genres", "RunTimeTicks", "ChildCount",
        "RecursiveItemCount", "PremiereDate", "OfficialRating", "Taglines",
        "OriginalTitle", "ProviderIds", "Studios", "People", "Chapters"
    ).joinToString(",")

    override suspend fun items(libraryId: String?, page: Int, pageSize: Int): List<MediaItem> {
        ensureAuth().getOrThrow()
        val params = mutableMapOf(
            "Fields" to fields,
            "StartIndex" to (page * pageSize).toString(),
            "Limit" to pageSize.toString(),
            "SortBy" to "SortName",
            "SortOrder" to "Ascending",
            "Recursive" to "true",
            "IncludeItemTypes" to "Movie,Series,Episode",
            "ImageTypeLimit" to "1"
        )
        libraryId?.let { params["ParentId"] = it }
        val resp = Http.get(url("/Users/$userId/Items", params), source, headers())
        val text = resp.body?.string()
        resp.close()
        if (!resp.isSuccessful) return emptyList()
        return parseItems(text).mapNotNull { toMediaItem(it) }
    }

    override suspend fun search(query: String, limit: Int): List<MediaItem> {
        ensureAuth().getOrThrow()
        val resp = Http.get(url("/Users/$userId/Items", mapOf(
            "Fields" to fields,
            "SearchTerm" to query,
            "Limit" to limit.toString(),
            "Recursive" to "true",
            "IncludeItemTypes" to "Movie,Series,Episode"
        )), source, headers())
        val text = resp.body?.string()
        resp.close()
        if (!resp.isSuccessful) return emptyList()
        return parseItems(text).mapNotNull { toMediaItem(it) }
    }

    override suspend fun detail(id: String): MediaItem? {
        ensureAuth().getOrThrow()
        val resp = Http.get(url("/Users/$userId/Items/$id", mapOf("Fields" to fields)), source, headers())
        val text = resp.body?.string()
        resp.close()
        if (!resp.isSuccessful) return null
        val j = Json.gson.fromJson(text, JsonObject::class.java) ?: return null
        val item = toMediaItem(j) ?: return null
        // 剧集：拉取所有季的所有集
        return if (j.get("Type")?.asString == "Series") {
            val eps = Http.get(url("/Shows/$id/Episodes", mapOf(
                "UserId" to userId!!,
                "Fields" to fields
            )), source, headers())
            val epText = eps.body?.string()
            eps.close()
            val epsJson = parseItems(epText)
            item.copy(episodes = epsJson.mapNotNull { toMediaItem(it) })
        } else item
    }

    override suspend fun resume(page: Int, pageSize: Int): List<MediaItem> {
        ensureAuth().getOrThrow()
        val resp = Http.get(url("/Users/$userId/Items/Resume", mapOf(
            "Fields" to fields,
            "StartIndex" to (page * pageSize).toString(),
            "Limit" to pageSize.toString(),
            "MediaTypes" to "Video"
        )), source, headers())
        val text = resp.body?.string()
        resp.close()
        if (!resp.isSuccessful) return emptyList()
        return parseItems(text).mapNotNull { toMediaItem(it) }
    }

    override suspend fun latest(libraryId: String?, limit: Int): List<MediaItem> {
        ensureAuth().getOrThrow()
        val params = mutableMapOf(
            "Limit" to limit.toString(),
            "Fields" to fields,
            "IncludeItemTypes" to "Movie,Episode",
            "GroupItems" to "false"
        )
        libraryId?.let { params["ParentId"] = it }
        val resp = Http.get(url("/Users/$userId/Items/Latest", params), source, headers())
        val text = resp.body?.string()
        resp.close()
        if (!resp.isSuccessful) return emptyList()
        // Latest 返回数组而不是 {Items:[]}
        val arr = Json.gson.fromJson(text, com.google.gson.JsonArray::class.java)
            ?: return emptyList()
        return arr.mapNotNull { el -> (el as? JsonObject)?.let { toMediaItem(it) } }
    }

    override suspend fun related(item: MediaItem): List<MediaItem> {
        ensureAuth().getOrThrow()
        val resp = Http.get(url("/Items/$item.id/Similar", mapOf(
            "UserId" to (userId ?: ""), "Limit" to "12", "Fields" to fields
        )), source, headers())
        val text = resp.body?.string()
        resp.close()
        if (!resp.isSuccessful) return emptyList()
        return parseItems(text).mapNotNull { toMediaItem(it) }
    }

    // ================= 解析播放地址 =================
    /**
     * ⚠️ 血泪教训（2026-10-24 模拟器实测抓到的崩溃）：
     * 这个方法里的每一次网络调用都必须接住 Throwable，绝不能让异常逃出去——
     * 一旦逃出去，协程未捕获异常会直接杀死进程，用户看到的就是
     * 「点播放 → 播放页一闪 → 闪退回桌面」，且所有版本必现。
     * 罪魁是 strm 的 /Download 拉内容：Emby 中转大文件时断流会抛
     * ProtocolException: unexpected end of stream，之前裸奔没接。
     */
    override suspend fun playable(item: MediaItem, versionIndex: Int): Result<Playable> {
        return try {
        ensureAuth().fold(
            onSuccess = {},
            onFailure = { t ->
                return Result.failure(RuntimeException(
                    cn.plum.ember.core.net.Http.describe(t), t))
            }
        )
        val v = item.versions.getOrNull(versionIndex) ?: item.versions.firstOrNull()

        // ① .strm 条目：Emby 自己解析不了，直接 500 —— 必须走 Download 拿内容
        if (item.isStrm) {
            val direct = item.strmUrl
            if (!direct.isNullOrBlank() && direct.startsWith("http", true)) {
                // Path 字段直接给了 URL 型 strm 内容 → 走标准解析管线
                return StreamResolver.resolve(
                    target = direct,
                    source = source,
                    startSec = item.positionSec,
                    title = item.title
                )
            }
            // ⚠️ Emby 的 /Download 对 strm 有三种行为（模拟器实测）：
            //   a) 302 链跳到真实地址 —— 终点即直链，Cookie 已沿途收集
            //   b) 200 直接中转媒体流 —— 终点 URL 本身可播（Content-Type 是 video/* 等）
            //   c) 200 且 Content-Type 是文本 —— 终点是 strm 文本，需再解析里面的 URL
            // 绝不能把媒体流二进制当文本嗅探（v1.1.6 首测就是栽在 matroska EBML 头上）
            val resolved = Http.resolveRedirects(
                url("/Items/${item.id}/Download", mapOf("api_key" to (accessToken ?: ""))),
                source
            )
            val ct = (resolved.contentType ?: "").lowercase()
            val looksText = ct.contains("text/html") || ct.contains("text/plain")
            if (resolved.status in 200..299 && looksText) {
                // 形态 c：小读 128 字节解析出内层 URL，再走标准解析管线
                // （只读 128 字节，绝不能用 body.string()——中转大文件会读爆内存）
                val probe = Http.get(
                    resolved.finalUrl, source,
                    mapOf("Range" to "bytes=0-127", "User-Agent" to Http.DEFAULT_UA)
                )
                val head = try {
                    probe.body?.byteStream()?.use { ins ->
                        val buf = ByteArray(128)
                        val n = ins.read(buf)
                        if (n > 0) String(buf, 0, n, Charsets.UTF_8) else null
                    }
                } catch (_: Throwable) { null } finally { probe.close() }
                val inner = StreamResolver.parseStrm(head ?: "")
                    ?: return Result.failure(IllegalStateException(".strm 内容为空，无法解析直链"))
                return StreamResolver.resolve(
                    target = inner,
                    source = source,
                    startSec = item.positionSec,
                    title = item.title
                )
            }
            if (resolved.status in 200..399) {
                // 形态 a/b：终点即直链，带 Cookie 直接交给 mpv
                return Result.success(
                    Playable(
                        resolved.finalUrl,
                        StreamResolver.headersFor(source, emptyMap(), resolved.cookies),
                        item.positionSec,
                        item.title
                    )
                )
            }
            return Result.failure(RuntimeException("Emby /Download 返回 HTTP ${resolved.status}"))
        }

        // ② 普通视频：用 Emby 的 stream 端点（Emby 会对本地文件直接给原流）
        val streamUrl = url("/Videos/${item.id}/stream", mapOf(
            "static" to "true",
            "api_key" to (accessToken ?: "")
        ))
        // Emby 对某些容器 / 字幕组合可能会先 30x 跳转，同样走 StreamResolver
        StreamResolver.resolve(
            target = streamUrl,
            source = source,
            extraHeaders = mapOf("Range" to "bytes=0-"),   // 保留 Range 以便 mpv seek
            startSec = item.positionSec,
            title = item.title
        ).map { p ->
            if (v != null && v.width > 0) p else p
        }
    } catch (t: Throwable) {
        // 兜底：任何异常（网络/解析/鉴权）都不允许杀死进程
        Result.failure(RuntimeException(cn.plum.ember.core.net.Http.describe(t), t))
    }
    }

    // ================= 进度回写 =================
    override suspend fun reportProgress(item: MediaItem, positionSec: Long, durationSec: Long): Result<Unit>? {
        val uid = userId ?: return null
        return try {
            val body = JsonObject().apply {
                addProperty("ItemId", item.id)
                addProperty("Position", positionSec)
                if (durationSec > 0) addProperty("PositionTicks", positionSec * 10_000_000)
            }
            val resp = Http.postJson(
                url("/Sessions/Playing/Progress"),
                Json.gson.toJson(body), source, headers()
            )
            resp.close()
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun markWatched(item: MediaItem): Result<Unit>? {
        val uid = userId ?: return null
        return try {
            val resp = Http.get(url("/Users/$uid/PlayedItems/${item.id}"), source, headers())
            resp.close()
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** 取消已看（详情页勾选按钮的取消态） */
    override suspend fun markUnwatched(item: MediaItem): Result<Unit>? {
        val uid = userId ?: return null
        return try {
            val resp = Http.delete(url("/Users/$uid/PlayedItems/${item.id}"), source, headers())
            resp.close()
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    // ================= JSON → MediaItem =================
    private fun parseItems(text: String?): List<JsonObject> {
        if (text.isNullOrBlank()) return emptyList()
        val j = Json.gson.fromJson(text, JsonObject::class.java) ?: return emptyList()
        val arr = j.getAsJsonArray("Items") ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }
    }

    private fun posterUrl(id: String, maxWidth: Int = 400): String =
        url("/Items/$id/Images/Primary", mapOf("maxWidth" to maxWidth.toString(), "quality" to "85"))

    private fun backdropUrl(id: String, maxWidth: Int = 1280): String =
        url("/Items/$id/Images/Backdrop", mapOf("maxWidth" to maxWidth.toString(), "quality" to "80"))

    private fun toMediaItem(j: JsonObject): MediaItem? {
        val id = j.get("Id")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val type = j.get("Type")?.asString ?: "Movie"
        val kind = when (type) {
            "Series" -> "series"
            "Episode" -> "episode"
            else -> "movie"
        }

        val sources = j.getAsJsonArray("MediaSources")
        val versions = sources?.mapIndexedNotNull { idx, el ->
            val s = el as? JsonObject ?: return@mapIndexedNotNull null
            val streams = s.getAsJsonArray("MediaStreams")
            val video = streams?.firstOrNull {
                (it as? JsonObject)?.get("Type")?.asString == "Video"
            } as? JsonObject
            val audio = streams?.firstOrNull {
                (it as? JsonObject)?.get("Type")?.asString == "Audio"
            } as? JsonObject
            val path = s.get("Path")?.takeIf { it.isJsonPrimitive }?.asString
                ?: j.get("Path")?.takeIf { it.isJsonPrimitive }?.asString
            MediaVersion(
                index = idx,
                label = buildString {
                    val w = video?.get("Width")?.asInt ?: 0
                    val h = video?.get("Height")?.asInt ?: 0
                    append(if (h >= 2000) "4K" else if (h >= 1000) "1080P" else if (h > 0) "${h}P" else "原始")
                    video?.get("Codec")?.asString?.let { append(" · $it") }
                },
                container = s.get("Container")?.asString,
                sizeBytes = s.get("Size")?.asLong ?: 0L,
                bitrate = s.get("Bitrate")?.asLong ?: 0L,
                videoCodec = video?.get("Codec")?.asString,
                audioCodec = audio?.get("Codec")?.asString,
                width = video?.get("Width")?.asInt ?: 0,
                height = video?.get("Height")?.asInt ?: 0,
                path = path
            )
        } ?: emptyList()

        val path = j.get("Path")?.takeIf { it.isJsonPrimitive }?.asString

        val userData = j.getAsJsonObject("UserData")
        val positionTicks = userData?.get("PlaybackPositionTicks")?.asLong ?: 0L
        val playedPct = userData?.get("PlayedPercentage")?.asDouble ?: 0.0

        val runtimeTicks = j.get("RunTimeTicks")?.asLong ?: 0L
        val durationSec = runtimeTicks / 10_000_000

        // ---- 详情页扩展：章节 / 演职人员 / 工作室 / 剧照 / 外部 ID / 规格面板 ----
        val chapters = j.getAsJsonArray("Chapters")?.mapNotNull { c ->
            (c as? JsonObject)?.let { co ->
                Chapter(
                    name = co.get("Name")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                        ?: "章节",
                    startSec = (co.get("StartPositionTicks")?.asLong ?: 0L) / 10_000_000
                )
            }
        } ?: emptyList()

        fun personImage(pid: String): String =
            url("/Items/$pid/Images/Primary", mapOf("maxWidth" to "160", "quality" to "85"))

        val people = j.getAsJsonArray("People")?.mapNotNull { p ->
            (p as? JsonObject)?.let { po ->
                val nm = po.get("Name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                val ptype = po.get("PrimaryImageTag")
                Person(
                    name = nm,
                    role = po.get("Role")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() },
                    image = if (ptype != null) personImage(po.get("Id")?.asString ?: "") else null
                )
            }
        } ?: emptyList()

        val studios = j.getAsJsonArray("Studios")?.mapNotNull { s ->
            (s as? JsonObject)?.get("Name")?.takeIf { it.isJsonPrimitive }?.asString
        } ?: emptyList()

        val stills = j.getAsJsonArray("BackdropImageTags")?.mapIndexedNotNull { i, _ ->
            if (i == 0) null   // 第 0 张已是 backdrop
            else url("/Items/$id/Images/Backdrop/$i", mapOf("maxWidth" to "960", "quality" to "80"))
        } ?: emptyList()

        val providerIds = j.getAsJsonObject("ProviderIds")
        val imdb = providerIds?.get("Imdb")?.takeIf { it.isJsonPrimitive }?.asString
        val tmdb = providerIds?.get("Tmdb")?.takeIf { it.isJsonPrimitive }?.asString

        // 规格面板：视频 / 音轨 / 字幕 / 容器体积，取第一个 MediaSource 的流信息
        val firstSrc = sources?.firstOrNull() as? JsonObject
        val allStreams = firstSrc?.getAsJsonArray("MediaStreams")
        val vStream = allStreams?.firstOrNull { (it as? JsonObject)?.get("Type")?.asString == "Video" } as? JsonObject
        val aStreams = allStreams?.filter { (it as? JsonObject)?.get("Type")?.asString == "Audio" }
            ?.mapNotNull { it as? JsonObject } ?: emptyList()
        val sStreams = allStreams?.filter { (it as? JsonObject)?.get("Type")?.asString == "Subtitle" }
            ?.mapNotNull { it as? JsonObject } ?: emptyList()
        val specLines = buildList {
            vStream?.let { v ->
                val w = v.get("Width")?.asInt ?: 0
                val h = v.get("Height")?.asInt ?: 0
                val codec = (v.get("Codec")?.asString ?: "").uppercase()
                val range = v.get("VideoRange")?.asString ?: ""
                val dv = v.get("VideoRangeType")?.asString?.contains("DOVI", true) == true ||
                        (v.get("Codec")?.asString ?: "").contains("dovi", true)
                add(buildString {
                    append(if (h >= 2000) "4K UHD" else if (h >= 1000) "1080P" else if (h > 0) "${h}P" else "视频")
                    if (codec.isNotBlank()) append(" · $codec")
                    if (w > 0 && h > 0) append(" · $w×$h")
                    if (dv) append(" · 杜比视界")
                    else if (range.contains("HDR", true)) append(" · $range")
                })
            }
            aStreams.take(4).forEach { a ->
                val codec = (a.get("Codec")?.asString ?: "").uppercase()
                val ch = a.get("Channels")?.asInt ?: 0
                val lang = a.get("Language")?.asString ?: ""
                val title = a.get("Title")?.asString ?: ""
                add(buildString {
                    append("音轨 · $codec")
                    if (ch >= 6) append(" ${ch}.1") else if (ch > 0) append(" ${ch}.0")
                    if (lang.isNotBlank()) append(" $lang")
                    if (title.isNotBlank() && title != lang) append(" $title")
                })
            }
            if (sStreams.isNotEmpty()) {
                val langs = sStreams.mapNotNull { it.get("Language")?.asString }.distinct().take(5)
                add("字幕 · " + (if (langs.isEmpty()) "${sStreams.size} 条内封" else langs.joinToString("/")))
            }
            firstSrc?.get("Container")?.takeIf { it.isJsonPrimitive }?.asString?.let { c ->
                val size = firstSrc.get("Size")?.asLong ?: 0L
                add(buildString {
                    append("容器 · ${c.uppercase()}")
                    if (size > 0) append(" · ${"%.1f".format(size / 1073741824.0)}GB")
                })
            }
        }

        return MediaItem(
            id = id,
            sourceId = source.id,
            libraryId = j.get("ParentId")?.takeIf { it.isJsonPrimitive }?.asString
                ?: j.get("SeriesId")?.takeIf { it.isJsonPrimitive }?.asString,
            kind = kind,
            title = j.get("Name")?.asString ?: "",
            subtitle = if (kind == "episode") {
                val season = j.get("ParentIndexNumber")?.asInt
                val ep = j.get("IndexNumber")?.asInt
                listOfNotNull(
                    season?.let { "S%02d".format(it) },
                    ep?.let { "E%02d".format(it) },
                    j.get("SeriesName")?.asString
                ).joinToString(" · ")
            } else null,
            overview = j.get("Overview")?.asString,
            originalTitle = j.get("OriginalTitle")?.takeIf { it.isJsonPrimitive }?.asString,
            tagline = j.get("Taglines")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString,
            premiereDate = j.get("PremiereDate")?.takeIf { it.isJsonPrimitive }?.asString?.take(10),
            officialRating = j.get("OfficialRating")?.takeIf { it.isJsonPrimitive }?.asString,
            watched = userData?.get("Played")?.asBoolean == true,
            year = j.get("ProductionYear")?.asInt,
            rating = j.get("CommunityRating")?.asDouble,
            durationSec = if (durationSec > 0) durationSec else {
                if (kind == "movie" && playedPct > 0 && positionTicks > 0) {
                    (positionTicks / 10_000_000 / playedPct * 100).toLong()
                } else 0L
            },
            positionSec = positionTicks / 10_000_000,
            versions = versions,
            genres = j.getAsJsonArray("Genres")?.map { it.asString } ?: emptyList(),
            cast = emptyList(),
            poster = if (j.has("ImageTags") && j.getAsJsonObject("ImageTags").has("Primary")) posterUrl(id) else null,
            backdrop = if (j.has("BackdropImageTags")) backdropUrl(id) else null,
            container = versions.firstOrNull()?.container,
            path = path,
            stills = stills,
            chapters = chapters,
            people = people,
            studios = studios,
            providerImdb = imdb,
            providerTmdb = tmdb,
            mediaSpec = specLines,
            tag4k = versions.any { it.height >= 2000 },
            tagHdr = versions.any {
                it.videoCodec?.contains("hevc", true) == true &&
                        (j.getAsJsonArray("MediaStreams")?.any { ms ->
                            (ms as? JsonObject)?.get("Type")?.asString == "Video" &&
                                    ms.get("VideoRange")?.asString?.contains("HDR", true) == true
                        } == true)
            },
            tagDolby = versions.any {
                it.audioCodec?.contains("truehd", true) == true ||
                        it.audioCodec?.contains("dolby", true) == true ||
                        it.audioCodec?.contains("eac3", true) == true
            }
        ).let {
            it.copy(
                strmUrl = if (it.isStrm) null else null, // strm 的 URL 需要Download 时才能拿到
                positionSec = it.positionSec
            )
        }
    }
}

/** Jellyfin 与 Emby API 95% 兼容，主要差异在：token 头是 Authorization，Path 字段行为一致 */
class JellyfinAdapter(source: MediaSource) : EmbyAdapter(source) {
    override val authHeader: String = "Authorization"
    override val tokenHeader: String = "X-Emby-Token"
}
