package cn.plum.ember.data

import android.content.Context
import cn.plum.ember.core.resolve.AdapterRegistry
import cn.plum.ember.core.resolve.SourceAdapter
import cn.plum.ember.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 应用级单例容器 */
object App {
    lateinit var db: Db
    lateinit var prefs: Prefs
    lateinit var secrets: Secrets
    lateinit var sources: SourceStore

    fun init(ctx: Context) {
        if (::db.isInitialized) return
        db = Db(ctx)
        prefs = Prefs(ctx)
        secrets = Secrets(ctx)
        sources = SourceStore(ctx)
    }
}

/**
 * 媒体源的统一门面：给 UI 一个「不用管具体是什么源」的入口。
 */
class LibraryRepo {
    private fun adapter(source: MediaSource): SourceAdapter = AdapterRegistry.of(source)

    /** 新建/更新源，密码落到 Secrets，列表持久化到 SP */
    suspend fun upsertSource(s: MediaSource, password: String?): Result<Unit> {
        val tested = adapter(s).test()
        if (tested.isFailure) {
            return Result.failure(tested.exceptionOrNull() ?: RuntimeException("连接失败"))
        }
        // 密码/token 存加密 SP
        val secretKey = "src-${s.id}"
        if (password != null) {
            App.secrets.put(secretKey, password)
        }
        val stored = s.copy(secretRef = if (password != null || s.token != null) secretKey else s.secretRef)
        App.sources.upsert(stored.copy(lastUsed = System.currentTimeMillis()))
        return Result.success(Unit)
    }

    /** 删除源（同时清掉它的密码） */
    fun removeSource(id: String) {
        val s = App.sources.find(id)
        s?.secretRef?.let { App.secrets.remove(it) }
        App.sources.remove(id)
        AdapterRegistry.invalidate(id)
    }

    /** 拿到一个「密码已注入」的源（源列表里的 token 字段是脱敏的） */
    fun hydrated(sourceId: String): MediaSource? {
        val s = App.sources.find(sourceId) ?: return null
        val pwd = s.secretRef?.let { App.secrets.get(it) }
        return s.copy(token = pwd ?: s.token)
    }

    fun list(): List<MediaSource> = App.sources.all()

    suspend fun libraries(sourceId: String): List<Library> = withContext(Dispatchers.IO) {
        hydrated(sourceId)?.let { adapter(it).libraries() } ?: emptyList()
    }

    suspend fun items(sourceId: String, libId: String?, page: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        hydrated(sourceId)?.let { adapter(it).items(libId, page) } ?: emptyList()
    }

    suspend fun search(sourceId: String, q: String): List<MediaItem> = withContext(Dispatchers.IO) {
        hydrated(sourceId)?.let { adapter(it).search(q) } ?: emptyList()
    }

    suspend fun detail(sourceId: String, itemId: String): MediaItem? = withContext(Dispatchers.IO) {
        hydrated(sourceId)?.let { adapter(it).detail(itemId) }
    }

    suspend fun resume(sourceId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        hydrated(sourceId)?.let { adapter(it).resume() } ?: emptyList()
    }

    suspend fun latest(sourceId: String, libId: String?): List<MediaItem> = withContext(Dispatchers.IO) {
        hydrated(sourceId)?.let { adapter(it).latest(libId) } ?: emptyList()
    }

    suspend fun playable(sourceId: String, item: MediaItem, version: Int = 0): Result<Playable> =
        withContext(Dispatchers.IO) {
            val src = hydrated(sourceId) ?: return@withContext Result.failure(RuntimeException("源不存在"))
            val a = adapter(src)
            // 服务器端进度优先，其次本地
            val merged = if (item.positionSec <= 0) {
                item.copy(positionSec = App.db.progressOf(sourceId, item.id)?.positionSec ?: 0L)
            } else item
            a.playable(merged, version)
        }

    suspend fun reportProgress(sourceId: String, item: MediaItem, pos: Long, dur: Long) {
        // ① 本地（永远写，不依赖网络）
        val progress = PlayProgress(sourceId, item.id, pos, dur, watched = dur > 0 && pos >= dur * 0.95)
        App.db.saveProgress(progress)
        App.db.recordHistory(sourceId, item.id, item.title, item.poster, pos, dur)
        // ② 服务器（可选）
        withContext(Dispatchers.IO) {
            try {
                hydrated(sourceId)?.let { adapter(it).reportProgress(item, pos, dur) }
            } catch (_: Throwable) {}
        }
    }

    suspend fun markWatched(sourceId: String, item: MediaItem) = withContext(Dispatchers.IO) {
        App.db.saveProgress(PlayProgress(sourceId, item.id,
            item.durationSec, item.durationSec, watched = true))
        try {
            hydrated(sourceId)?.let { adapter(it).markWatched(item) }
        } catch (_: Throwable) {}
    }

    suspend fun related(sourceId: String, item: MediaItem): List<MediaItem> = withContext(Dispatchers.IO) {
        try { hydrated(sourceId)?.let { adapter(it).related(item) } } catch (_: Throwable) { null }
    } ?: emptyList()

    fun isFavorite(sourceId: String, itemId: String): Boolean =
        App.db.isFavorite(sourceId, itemId)

    fun toggleFavorite(sourceId: String, item: MediaItem): Boolean =
        App.db.toggleFavorite(sourceId, item.id, item.title, item.poster, item.year)

    fun favorites(): List<HistoryRow> = App.db.favorites()

    fun history(): List<HistoryRow> = App.db.history()

    /** 所有源的「继续观看」合并 */
    suspend fun resumeAll(): List<MediaItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<MediaItem>()
        list().forEach { s ->
            try {
                out += hydrated(s.id)?.let { adapter(it).resume() } ?: emptyList()
            } catch (_: Throwable) {}
        }
        // 本地进度作为兜底：Emby/Plex 服务器端没有的也要展示
        val localOnly = App.db.history()
            .filter { h -> out.none { it.id == h.itemId && it.sourceId == h.sourceId } && h.positionSec > 0 }
            .mapNotNull { h ->
                val item = detail(h.sourceId, h.itemId)
                item?.copy(positionSec = h.positionSec)
            }
        (out + localOnly).sortedByDescending { it.positionSec / maxOf(1, it.durationSec) }
    }
}
