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
 * AList 适配器。
 * AList 把网盘统一挂载成目录树，/api/fs/list 浏览、/api/fs/get 换 raw_url（302 直链）。
 * 注意：AList raw_url 可能是 302 重定向结果，所以拿直链这一步用 StreamResolver 走。
 */
class AlistAdapter(override val source: MediaSource) : SourceAdapter {

    private var token: String? = null

    private fun api(path: String): String = source.baseUrl.trimEnd('/') + path

    private suspend fun ensureToken(): Result<Unit> {
        if (!token.isNullOrBlank()) return Result.success(Unit)
        // 优先用配置里直接粘贴的 token（访客/已有 token 的情况）
        if (!source.token.isNullOrBlank()) {
            token = source.token
            return Result.success(Unit)
        }
        return try {
            val body = JsonObject().apply {
                addProperty("username", source.username ?: "")
                addProperty("password", source.token ?: "")
            }
            val resp = Http.postJson(api("/api/auth/login"), Json.gson.toJson(body), source)
            val text = resp.body?.string()
            resp.close()
            val j = Json.gson.fromJson(text, JsonObject::class.java) ?: return Result.failure(RuntimeException("响应为空"))
            val code = j.get("code")?.asInt ?: -1
            if (code != 200) {
                Result.failure(RuntimeException(j.get("message")?.asString ?: "登录失败"))
            } else {
                token = j.getAsJsonObject("content")?.get("token")?.asString
                if (token.isNullOrBlank()) Result.failure(RuntimeException("Token 为空"))
                else Result.success(Unit)
            }
        } catch (t: Throwable) {
            Result.failure(RuntimeException("无法连接：${t.message ?: "网络错误"}"))
        }
    }

    private suspend fun post(body: String): JsonObject? {
        ensureToken().getOrThrow()
        val resp = Http.postJson(api("/api/fs/list"), body, source, mapOf(
            "Authorization" to (token ?: "")
        ))
        val text = resp.body?.string()
        resp.close()
        if (!resp.isSuccessful) return null
        val j = Json.gson.fromJson(text, JsonObject::class.java) ?: return null
        if (j.get("code")?.asInt != 200) return null
        return j.getAsJsonObject("data")
    }

    override suspend fun test(): Result<Unit> {
        return ensureToken()
    }

    override suspend fun libraries(): List<Library> {
        // AList 根目录作为「库」的替代：顶层每个文件夹当一个虚拟库
        val data = post(Json.gson.toJson(mapOf("path" to "/", "page" to 1, "per_page" to 100))) ?: return emptyList()
        val content = data.getAsJsonArray("content") ?: return emptyList()
        return content.mapNotNull { el ->
            val j = el as? JsonObject ?: return@mapNotNull null
            if (j.get("is_dir")?.asBoolean != true) return@mapNotNull null
            Library(
                id = "/" + (j.get("name")?.asString ?: ""),
                name = j.get("name")?.asString ?: "",
                kind = "folder"
            )
        }
    }

    override suspend fun items(libraryId: String?, page: Int, pageSize: Int): List<MediaItem> {
        val path = libraryId ?: "/"
        val data = post(Json.gson.toJson(mapOf(
            "path" to path, "page" to (page + 1), "per_page" to pageSize
        ))) ?: return emptyList()
        val content = data.getAsJsonArray("content") ?: return emptyList()
        val base = data.get("path")?.asString ?: path
        return content.mapNotNull { el ->
            val j = el as? JsonObject ?: return@mapNotNull null
            val name = j.get("name")?.asString ?: return@mapNotNull null
            val isDir = j.get("is_dir")?.asBoolean == true
            val isVideo = VIDEO_EXT.any { name.endsWith(it, true) }
            val isStrmFile = name.endsWith(".strm", true)
            if (isDir && !isVideo && !isStrmFile) {
                MediaItem(
                    id = "$base/${name}", sourceId = source.id, kind = "folder",
                    title = name, path = "$base/${name}",
                    poster = null
                )
            } else if (isVideo || isStrmFile) {
                MediaItem(
                    id = "$base/${name}", sourceId = source.id, kind = "movie",
                    title = name.substringBeforeLast('.'),
                    durationSec = 0,
                    versions = listOf(MediaVersion(
                        index = 0,
                        label = j.get("size")?.asLong?.let { "${it / 1024 / 1024} MB" } ?: "文件",
                        sizeBytes = j.get("size")?.asLong ?: 0L
                    )),
                    path = "$base/${name}",
                    container = name.substringAfterLast('.', "").lowercase()
                )
            } else null
        }
    }

    override suspend fun search(query: String, limit: Int): List<MediaItem> {
        // AList 的搜索 API：POST /api/fs/search
        ensureToken().getOrThrow()
        val resp = Http.postJson(api("/api/fs/search"), Json.gson.toJson(mapOf(
            "parent" to "/", "keywords" to query, "page" to 1, "per_page" to limit
        )), source, mapOf("Authorization" to (token ?: "")))
        val text = resp.body?.string()
        resp.close()
        val j = Json.gson.fromJson(text, JsonObject::class.java) ?: return emptyList()
        val content = j.getAsJsonObject("data")?.getAsJsonArray("content") ?: return emptyList()
        return content.mapNotNull { el ->
            val it = el as? JsonObject ?: return@mapNotNull null
            val name = it.get("name")?.asString ?: return@mapNotNull null
            val parent = it.get("parent")?.asString ?: "/"
            if (VIDEO_EXT.any { name.endsWith(it, true) } || name.endsWith(".strm", true)) {
                MediaItem(
                    id = "$parent/$name", sourceId = source.id, kind = "movie",
                    title = name.substringBeforeLast('.'),
                    path = "$parent/$name"
                )
            } else null
        }
    }

    override suspend fun detail(id: String): MediaItem? =
        items(id.takeBeforeLast('/'), 0, 1)
            .firstOrNull { it.id == id }
            ?.copy(id = id, path = id)

    private fun String?.takeBeforeLast(sep: String): String? {
        if (this == null) return null
        val i = lastIndexOf(sep)
        return if (i <= 0) "/" else substring(0, i)
    }

    override suspend fun resume(page: Int, pageSize: Int): List<MediaItem> = emptyList()

    override suspend fun latest(libraryId: String?, limit: Int): List<MediaItem> =
        items(libraryId, 0, limit)

    override suspend fun playable(item: MediaItem, versionIndex: Int): Result<Playable> {
        ensureToken().getOrThrow()
        // POST /api/fs/get 拿 raw_url（可能带 sign）
        val resp = Http.postJson(api("/api/fs/get"), Json.gson.toJson(mapOf("path" to item.path)), source,
            mapOf("Authorization" to (token ?: "")))
        val text = resp.body?.string()
        resp.close()
        val j = Json.gson.fromJson(text, JsonObject::class.java) ?: return Result.failure(RuntimeException("响应异常"))
        val code = j.get("code")?.asInt ?: -1
        if (code != 200) return Result.failure(RuntimeException(j.get("message")?.asString ?: "获取直链失败"))
        val raw = j.getAsJsonObject("data")?.get("raw_url")?.asString
            ?: return Result.failure(RuntimeException("服务器未返回直链"))

        return StreamResolver.resolve(
            target = raw,
            source = source,
            startSec = item.positionSec,
            title = item.title
        )
    }

    override suspend fun reportProgress(item: MediaItem, positionSec: Long, durationSec: Long): Result<Unit>? = null
    override suspend fun markWatched(item: MediaItem): Result<Unit>? = null

    companion object {
        private val VIDEO_EXT = listOf(
            ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v",
            ".ts", ".m2ts", ".mts", ".mpg", ".mpeg", ".m3u8", ".iso"
        )
    }
}

/** WebDAV 适配器：PROPFIND 列目录，GET 直链（mpv 自己带 Range 播） */
class WebDavAdapter(override val source: MediaSource) : SourceAdapter {

    private fun url(path: String): String =
        source.baseUrl.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")

    private fun auth(): Map<String, String> {
        if (source.username.isNullOrBlank() && source.token.isNullOrBlank()) return emptyMap()
        val raw = "${source.username ?: ""}:${source.token ?: ""}"
        val b64 = android.util.Base64.encodeToString(raw.toByteArray(), android.util.Base64.NO_WRAP)
        return mapOf("Authorization" to "Basic $b64")
    }

    override suspend fun test(): Result<Unit> = try {
        val req = okhttp3.Request.Builder().url(url("/"))
            .method("PROPFIND", null)
            .header("Depth", "0")
            .header("Authorization", auth()["Authorization"] ?: "")
            .build()
        val resp = Http.client(source).newCall(req).execute()
        val code = resp.code
        resp.close()
        when {
            code in 200..299 -> Result.success(Unit)
            code == 401 -> Result.failure(RuntimeException("认证失败（401），检查用户名/密码"))
            else -> Result.failure(RuntimeException("HTTP $code"))
        }
    } catch (t: Throwable) {
        Result.failure(RuntimeException("无法连接：${t.message ?: "网络错误"}"))
    }

    override suspend fun libraries(): List<Library> = listOf(Library("/", "根目录", kind = "folder"))

    override suspend fun items(libraryId: String?, page: Int, pageSize: Int): List<MediaItem> {
        val path = libraryId ?: "/"
        val req = okhttp3.Request.Builder().url(url(path))
            .method("PROPFIND", null)
            .header("Depth", "1")
            .header("Authorization", auth()["Authorization"] ?: "")
            .build()
        val resp = Http.client(source).newCall(req).execute()
        val body = resp.body?.string()
        resp.close()
        if (!resp.isSuccessful) return emptyList()
        return parsePropfind(body, path)
    }

    private fun parsePropfind(xml: String?, basePath: String): List<MediaItem> {
        if (xml.isNullOrBlank()) return emptyList()
        // 简化版 XML 解析：WebDAV 响应的 <D:response> / <D:href> / <D:getcontentlength>
        val out = mutableListOf<MediaItem>()
        val regex = Regex("<(?:[a-zA-Z0-9]+:)?response>(.*?)</(?:[a-zA-Z0-9]+:)?response>", RegexOption.DOT_MATCHES_ALL)
        val hrefRegex = Regex("<(?:[a-zA-Z0-9]+:)?href>(.*?)</(?:[a-zA-Z0-9]+:)?href>")
        val sizeRegex = Regex("<(?:[a-zA-Z0-9]+:)?getcontentlength>(\\d+)</(?:[a-zA-Z0-9]+:)?getcontentlength>")
        val collRegex = Regex("<(?:[a-zA-Z0-9]+:)?collection\\s*/?>")

        regex.findAll(xml).forEach { m ->
            val body = m.groupValues[1]
            val href = hrefRegex.find(body)?.groupValues?.get(1)
                ?.replace("&amp;", "&")?.replace("%20", " ") ?: return@forEach
            val name = java.net.URLDecoder.decode(href.trimEnd('/').substringAfterLast('/'), "UTF-8")
            if (name.isBlank()) return@forEach
            val isDir = collRegex.containsMatchIn(body)
            val size = sizeRegex.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val absPath = href.trimEnd('/').let { h ->
                val i = h.indexOf("/", h.indexOf("//") + 2)  // skip scheme://host
                if (i < 0) "/" else h.substring(i)
            }
            if (isDir) {
                out += MediaItem(id = absPath, sourceId = source.id, kind = "folder",
                    title = name, path = absPath)
            } else if (VIDEO_EXT.any { name.endsWith(it, true) } || name.endsWith(".strm", true)) {
                out += MediaItem(
                    id = absPath, sourceId = source.id, kind = "movie",
                    title = name.substringBeforeLast('.'),
                    versions = listOf(MediaVersion(index = 0, label = "文件", sizeBytes = size)),
                    path = absPath,
                    container = name.substringAfterLast('.', "").lowercase()
                )
            }
        }
        // 跳过第一个（目录自身）
        return out.drop(1).filterNot { it.kind == "folder" && it.id == basePath.trimEnd('/') }
    }

    override suspend fun search(query: String, limit: Int): List<MediaItem> {
        // WebDAV 没有搜索，扫根目录 + 第一层，关键字过滤
        return items("/", 0, 200).filter {
            it.title.contains(query, true)
        }.take(limit)
    }

    override suspend fun detail(id: String): MediaItem? =
        items(id.takeBeforeLast('/'), 0, 1).firstOrNull { it.id == id }

    private fun String?.takeBeforeLast(sep: String): String? {
        if (this == null) return null
        val i = lastIndexOf(sep)
        return if (i <= 0) "/" else substring(0, i)
    }

    override suspend fun resume(page: Int, pageSize: Int): List<MediaItem> = emptyList()
    override suspend fun latest(libraryId: String?, limit: Int): List<MediaItem> = items(libraryId, 0, limit)

    override suspend fun playable(item: MediaItem, versionIndex: Int): Result<Playable> {
        val full = url(item.path ?: item.id)
        if (item.isStrm || (item.path?.endsWith(".strm", true) == true)) {
            // WebDAV 上的 .strm：下载内容再解析
            val req = okhttp3.Request.Builder().url(full)
                .header("Authorization", auth()["Authorization"] ?: "").build()
            val resp = Http.client(source).newCall(req).execute()
            val content = resp.body?.string()
            resp.close()
            val url2 = StreamResolver.parseStrm(content ?: "")
                ?: return Result.failure(IllegalStateException(".strm 内容为空"))
            return StreamResolver.resolve(url2, source, startSec = item.positionSec, title = item.title)
        }
        // 直接给 mpv，Basic Auth 通过 http-header 传入
        val headers = auth() + mapOf("User-Agent" to Http.DEFAULT_UA)
        return Result.success(Playable(full, headers = headers, startSec = item.positionSec, title = item.title))
    }

    override suspend fun reportProgress(item: MediaItem, positionSec: Long, durationSec: Long): Result<Unit>? = null
    override suspend fun markWatched(item: MediaItem): Result<Unit>? = null

    companion object {
        private val VIDEO_EXT = listOf(
            ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v",
            ".ts", ".m2ts", ".mts", ".mpg", ".mpeg", ".m3u8", ".iso"
        )
    }
}

/** 本地目录适配器：扫本地 / USB / 已挂载的 SMB/NFS 路径 */
class LocalAdapter(override val source: MediaSource) : SourceAdapter {

    private val root: String get() = source.scanRoot ?: "/storage/emulated/0"

    override suspend fun test(): Result<Unit> {
        val f = java.io.File(root)
        return if (f.exists() && f.isDirectory) Result.success(Unit)
        else Result.failure(RuntimeException("目录不可访问：$root（需要授权存储权限）"))
    }

    override suspend fun libraries(): List<Library> {
        val f = java.io.File(root)
        val dirs = f.listFiles { it -> it.isDirectory }
            ?.take(20)
            ?.filter { it.name != "Android" && it.name != "Alarms" && it.name != "Ringtones" }
            ?.map { Library(id = it.absolutePath, name = it.name, kind = "folder") }
            ?: emptyList()
        return listOf(Library(root, "全部文件", kind = "folder")) + dirs
    }

    override suspend fun items(libraryId: String?, page: Int, pageSize: Int): List<MediaItem> {
        val dir = java.io.File(libraryId ?: root)
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { f ->
            f.isFile && (VIDEO_EXT.any { ext -> f.name.endsWith(ext, true) } || f.name.endsWith(".strm", true))
        }
            .sortedBy { it.name }
            .drop(page * pageSize)
            .take(pageSize)
            .map { f ->
                MediaItem(
                    id = f.absolutePath,
                    sourceId = source.id,
                    kind = "movie",
                    title = f.nameWithoutExtension,
                    durationSec = 0,
                    versions = listOf(MediaVersion(index = 0, label = cn.plum.ember.util.Format.bytes(f.length()), sizeBytes = f.length())),
                    path = f.absolutePath,
                    container = f.extension.lowercase(),
                    tag4k = false
                )
            }
    }

    override suspend fun search(query: String, limit: Int): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        fun walk(dir: java.io.File, depth: Int) {
            if (depth > 5 || out.size >= limit) return
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) walk(child, depth + 1)
                else if (VIDEO_EXT.any { ext -> child.name.endsWith(ext, true) } && child.name.contains(query, true)) {
                    out += MediaItem(
                        id = child.absolutePath, sourceId = source.id, kind = "movie",
                        title = child.nameWithoutExtension, path = child.absolutePath
                    )
                }
            }
        }
        walk(java.io.File(root), 0)
        return out.take(limit)
    }

    override suspend fun detail(id: String): MediaItem? {
        val f = java.io.File(id)
        if (!f.exists()) return null
        return MediaItem(
            id = f.absolutePath, sourceId = source.id, kind = "movie",
            title = f.nameWithoutExtension,
            versions = listOf(MediaVersion(index = 0, label = cn.plum.ember.util.Format.bytes(f.length()), sizeBytes = f.length())),
            path = f.absolutePath,
            container = f.extension.lowercase()
        )
    }

    override suspend fun resume(page: Int, pageSize: Int): List<MediaItem> = emptyList()
    override suspend fun latest(libraryId: String?, limit: Int): List<MediaItem> = items(libraryId, 0, limit)

    override suspend fun playable(item: MediaItem, versionIndex: Int): Result<Playable> {
        val f = java.io.File(item.path ?: item.id)
        if (item.isStrm || f.name.endsWith(".strm", true)) {
            if (!f.exists()) return Result.failure(IllegalStateException("文件不存在"))
            val url = StreamResolver.parseStrm(f.readText())
                ?: return Result.failure(IllegalStateException(".strm 内容为空"))
            return StreamResolver.resolve(url, source, startSec = item.positionSec, title = item.title)
        }
        if (!f.exists()) return Result.failure(IllegalStateException("文件不存在：${f.absolutePath}"))
        return Result.success(Playable(StreamResolver.local(f.absolutePath), startSec = item.positionSec, title = item.title))
    }

    override suspend fun reportProgress(item: MediaItem, positionSec: Long, durationSec: Long): Result<Unit>? = null
    override suspend fun markWatched(item: MediaItem): Result<Unit>? = null

    companion object {
        private val VIDEO_EXT = listOf(
            ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v",
            ".ts", ".m2ts", ".mts", ".mpg", ".mpeg", ".m3u8", ".iso"
        )
    }
}
