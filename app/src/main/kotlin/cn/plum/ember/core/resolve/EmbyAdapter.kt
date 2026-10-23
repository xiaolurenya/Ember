package cn.plum.ember.core.resolve

import cn.plum.ember.core.net.Http
import cn.plum.ember.data.Library
import cn.plum.ember.data.MediaItem
import cn.plum.ember.data.MediaSource
import cn.plum.ember.data.MediaVersion
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

    private fun url(path: String, params: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder(source.baseUrl).append(path)
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
    override suspend fun test(): Result<Unit> = try {
        val body = JsonObject().apply {
            addProperty("Username", source.username ?: "")
            addProperty("Pw", source.token ?: "")   // Emby 的密码放在 Pw 字段
        }
        val resp = Http.postJson(url("/Users/AuthenticateByName"), Json.gson.toJson(body), source, headers(auth = true))
        val text = resp.body?.string()
        resp.close()
        if (!resp.isSuccessful) {
            return Result.failure(RuntimeException("登录失败 HTTP ${resp.code}：用户名或密码错误"))
        }
        val j = Json.gson.fromJson(text, JsonObject::class.java)
        userId = j?.getAsJsonObject("User")?.get("Id")?.asString
        accessToken = j?.get("AccessToken")?.asString
        if (userId.isNullOrBlank()) {
            Result.failure(RuntimeException("服务器返回异常：缺少 User.Id"))
        } else Result.success(Unit)
    } catch (t: Throwable) {
        Result.failure(RuntimeException("无法连接服务器：${t.message ?: "网络错误"}"))
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
            Library(
                id = it.optString("Id", ""),
                name = it.optString("Name", "未命名"),
                kind = it.optString("CollectionType", "mixed"),
                image = posterUrl(it.optString("Id"))
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
        "RecursiveItemCount", "PremiereDate", "OfficialRating", "Taglines"
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
    override suspend fun playable(item: MediaItem, versionIndex: Int): Result<Playable> {
        ensureAuth().getOrThrow()
        val v = item.versions.getOrNull(versionIndex) ?: item.versions.firstOrNull()

        // ① .strm 条目：Emby 自己解析不了，直接 500 —— 必须走 Download 拿内容
        if (item.isStrm) {
            val strmUrl = item.strmUrl ?: run {
                val resp = Http.get(url("/Items/${item.id}/Download", mapOf("api_key" to (accessToken ?: ""))), source)
                val text = resp.body?.string()
                resp.close()
                StreamResolver.parseStrm(text ?: "")
            }
            if (strmUrl.isNullOrBlank()) {
                return Result.failure(IllegalStateException(".strm 内容为空，无法解析直链"))
            }
            // strm 里通常是网盘的 302 地址或 raw 直链，交给 StreamResolver 统一处理
            return StreamResolver.resolve(
                target = strmUrl,
                source = source,
                startSec = item.positionSec,
                title = item.title
            )
        }

        // ② 普通视频：用 Emby 的 stream 端点（Emby 会对本地文件直接给原流）
        val streamUrl = url("/Videos/${item.id}/stream", mapOf(
            "static" to "true",
            "api_key" to (accessToken ?: "")
        ))
        // Emby 对某些容器 / 字幕组合可能会先 30x 跳转，同样走 StreamResolver
        return StreamResolver.resolve(
            target = streamUrl,
            source = source,
            extraHeaders = mapOf("Range" to "bytes=0-"),   // 保留 Range 以便 mpv seek
            startSec = item.positionSec,
            title = item.title
        ).map { p ->
            if (v != null && v.width > 0) p else p
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
