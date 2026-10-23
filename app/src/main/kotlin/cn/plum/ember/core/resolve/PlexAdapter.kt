package cn.plum.ember.core.resolve

import cn.plum.ember.core.net.Http
import cn.plum.ember.data.Library
import cn.plum.ember.data.MediaItem
import cn.plum.ember.data.MediaSource
import cn.plum.ember.data.MediaVersion
import cn.plum.ember.data.Playable
import cn.plum.ember.util.Json
import com.google.gson.JsonObject
import java.net.URLEncoder

/**
 * Plex 适配器。Plex 用 X-Plex-Token 鉴权，结构都是 MediaContainer 包 Metadata。
 * 直链 = media part 的 key + token，直接 GET 就能拿到原始文件流（不走转码）。
 */
class PlexAdapter(override val source: MediaSource) : SourceAdapter {

    private val token: String get() = source.token ?: ""

    private fun url(path: String, params: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder(source.baseUrl).append(path)
        val all = params.toMutableMap().apply {
            put("X-Plex-Token", token)
            put("X-Plex-Client-Identifier", "ember-client")
            put("X-Plex-Product", "Ember")
            put("X-Plex-Version", "1.0.0")
        }
        if (all.isNotEmpty()) {
            sb.append(if (path.contains('?')) '&' else '?')
            all.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) sb.append('&')
                sb.append(k).append('=').append(URLEncoder.encode(v, "UTF-8"))
            }
        }
        return sb.toString()
    }

    private fun container(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        val j = Json.gson.fromJson(text, JsonObject::class.java) ?: return null
        return j.getAsJsonObject("MediaContainer")
    }

    private fun metadataList(j: JsonObject?): List<JsonObject> {
        val arr = j?.getAsJsonArray("Metadata") ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }
    }

    private fun dirList(j: JsonObject?): List<JsonObject> {
        val arr = j?.getAsJsonArray("Directory") ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }
    }

    override suspend fun test(): Result<Unit> = try {
        val resp = Http.get(url("/library/sections"), source)
        val text = resp.body?.string()
        resp.close()
        if (resp.code == 401) Result.failure(RuntimeException("Token 无效（401）"))
        else if (container(text) != null) Result.success(Unit)
        else Result.failure(RuntimeException("响应异常，请确认地址和 Token"))
    } catch (t: Throwable) {
        Result.failure(RuntimeException("无法连接：${t.message ?: "网络错误"}"))
    }

    override suspend fun libraries(): List<Library> {
        val resp = Http.get(url("/library/sections"), source)
        val text = resp.body?.string()
        resp.close()
        return dirList(container(text)).map {
            Library(
                id = it.get("key")?.asString ?: "",
                name = it.get("title")?.asString ?: "",
                kind = when (it.get("type")?.asString) {
                    "movie" -> "movie"
                    "show" -> "series"
                    else -> "mixed"
                }
            )
        }
    }

    override suspend fun items(libraryId: String?, page: Int, pageSize: Int): List<MediaItem> {
        if (libraryId.isNullOrBlank()) return emptyList()
        val resp = Http.get(url("/library/sections/$libraryId/all", mapOf(
            "X-Plex-Container-Start" to (page * pageSize).toString(),
            "X-Plex-Container-Size" to pageSize.toString()
        )), source)
        val text = resp.body?.string()
        resp.close()
        return metadataList(container(text)).mapNotNull { toMediaItem(it) }
    }

    override suspend fun search(query: String, limit: Int): List<MediaItem> {
        val resp = Http.get(url("/search", mapOf("query" to query)), source)
        val text = resp.body?.string()
        resp.close()
        return metadataList(container(text)).mapNotNull { toMediaItem(it) }
    }

    override suspend fun detail(id: String): MediaItem? {
        val resp = Http.get(url("/library/metadata/$id", mapOf(
            "includeChildren" to "1", "includeFields" to "rating,contentRating,summary"
        )), source)
        val text = resp.body?.string()
        resp.close()
        val md = metadataList(container(text)).firstOrNull() ?: return null
        val item = toMediaItem(md) ?: return null
        // 剧集：拉取季 / 集
        val children = md.getAsJsonArray("Children") ?: return item
        val eps = mutableListOf<MediaItem>()
        children.forEach { seasonEl ->
            val season = seasonEl as? JsonObject ?: return@forEach
            val seasonId = season.get("ratingKey")?.asString ?: return@forEach
            val r = Http.get(url("/library/metadata/$seasonId/children"), source)
            val t = r.body?.string()
            r.close()
            eps += metadataList(container(t)).mapNotNull { toMediaItem(it) }
        }
        return item.copy(episodes = eps)
    }

    override suspend fun resume(page: Int, pageSize: Int): List<MediaItem> {
        val resp = Http.get(url("/library/onDeck"), source)
        val text = resp.body?.string()
        resp.close()
        return metadataList(container(text)).mapNotNull { toMediaItem(it) }
    }

    override suspend fun latest(libraryId: String?, limit: Int): List<MediaItem> {
        val resp = Http.get(url("/library/recentlyAdded", mapOf(
            "X-Plex-Container-Size" to limit.toString()
        )), source)
        val text = resp.body?.string()
        resp.close()
        return metadataList(container(text)).mapNotNull { toMediaItem(it) }
    }

    override suspend fun playable(item: MediaItem, versionIndex: Int): Result<Playable> {
        // Plex 的直链 = part key + token；对 strm/PlexDrive 场景 Plex 会自己 302
        val partKey = item.versions.getOrNull(versionIndex)?.url
            ?: item.versions.firstOrNull()?.url
            ?: item.path
            ?: return Result.failure(IllegalStateException("没有可播放的 Part"))
        val directUrl = if (partKey.startsWith("http")) partKey
        else url(partKey)
        return StreamResolver.resolve(
            target = directUrl,
            source = source,
            startSec = item.positionSec,
            title = item.title
        )
    }

    override suspend fun reportProgress(item: MediaItem, positionSec: Long, durationSec: Long): Result<Unit>? {
        return try {
            // 先创建播放会话再更新，简化为单次 /:/timeline 更新
            val resp = Http.get(url("/:/timeline", mapOf(
                "ratingKey" to item.id,
                "key" to "/library/metadata/${item.id}",
                "time" to (positionSec * 1000).toString(),
                "duration" to (durationSec * 1000).toString(),
                "state" to "playing",
                "identifier" to "com.plexapp.plugins.library"
            )), source)
            resp.close()
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun markWatched(item: MediaItem): Result<Unit>? {
        return try {
            val resp = Http.get(url("/:/scrobble", mapOf(
                "identifier" to "com.plexapp.plugins.library",
                "key" to "/library/metadata/${item.id}"
            )), source)
            resp.close()
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun toMediaItem(j: JsonObject): MediaItem? {
        val id = j.get("ratingKey")?.asString ?: return null
        val type = j.get("type")?.asString ?: "movie"
        val kind = when (type) {
            "show" -> "series"
            "episode" -> "episode"
            else -> "movie"
        }
        val versions = mutableListOf<MediaVersion>()
        j.getAsJsonArray("Media")?.forEach { mediaEl ->
            val m = mediaEl as? JsonObject ?: return@forEach
            val parts = m.getAsJsonArray("Part") ?: return@forEach
            parts.forEach { partEl ->
                val p = partEl as? JsonObject ?: return@forEach
                versions += MediaVersion(
                    index = versions.size,
                    label = buildString {
                        val w = m.get("width")?.asInt ?: 0
                        val h = m.get("height")?.asInt ?: 0
                        append(if (h >= 2000) "4K" else if (h >= 1000) "1080P" else if (h > 0) "${h}P" else "原始")
                        m.get("videoCodec")?.asString?.let { append(" · $it") }
                    },
                    container = p.get("container")?.asString,
                    sizeBytes = p.get("size")?.asLong ?: 0L,
                    bitrate = p.get("bitrate")?.asLong ?: 0L,
                    videoCodec = m.get("videoCodec")?.asString,
                    audioCodec = m.get("audioCodec")?.asString,
                    width = m.get("width")?.asInt ?: 0,
                    height = m.get("height")?.asInt ?: 0,
                    // 存 part 的 key，播放时拼 token
                    url = p.get("key")?.asString
                )
            }
        }
        val thumb = j.get("thumb")?.asString
        val art = j.get("art")?.asString

        return MediaItem(
            id = id,
            sourceId = source.id,
            kind = kind,
            title = j.get("title")?.asString ?: "",
            subtitle = if (kind == "episode") {
                listOfNotNull(
                    j.get("grandparentTitle")?.asString,
                    j.get("seasonTitle")?.asString,
                    j.get("title")?.asString
                ).joinToString(" · ")
            } else null,
            overview = j.get("summary")?.asString,
            year = j.get("year")?.asInt,
            rating = j.get("rating")?.asDouble,
            durationSec = (j.get("duration")?.asLong ?: 0L) / 1000,
            positionSec = (j.getAsJsonObject("userRating")?.get("offset")?.asLong
                ?: j.get("viewOffset")?.asLong ?: 0L) / 1000,
            versions = versions,
            poster = thumb?.let { url(it) },
            backdrop = art?.let { url(it) },
            container = versions.firstOrNull()?.container,
            path = versions.firstOrNull()?.url,
            tag4k = versions.any { it.height >= 2000 }
        )
    }
}
