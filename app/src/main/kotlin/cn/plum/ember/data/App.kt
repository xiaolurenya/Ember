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

    /**
     * 新建/更新源，密码落到 Secrets，列表持久化到 SP。
     *
     * ⚠️ 两个必须记住的坑（「点保存没反应」就是栽在这）：
     *   1. 表单里的密码是单独传进来的（MediaSource.token 此刻仍是 null），
     *      **测试前必须把密码注入进去**，否则 Emby/Jellyfin/WebDAV 拿空密码去登录 → 必然失败。
     *   2. AdapterRegistry 按 sourceId 缓存适配器，**编辑已有源时必须先 invalidate**，
     *      否则拿旧地址/旧凭据去做连接测试，改了半天测的还是旧配置。
     */
    suspend fun upsertSource(s: MediaSource, password: String?): Result<Unit> {
        val secretKey = "src-${s.id}"
        // ① 丢弃旧适配器缓存（编辑场景）
        AdapterRegistry.invalidate(s.id)
        // ② 测试用的副本：带上本次填的密码（没填就沿用 s.token，比如 Plex/AList）
        // ⚠️ 必须切 IO 线程：adapter.test() 内部是 OkHttp 同步 execute()，
        //    在主线程跑会抛 NetworkOnMainThreadException（message 为 null，
        //    表现就是表单里一句没头没脑的「网络错误」）
        val toTest = s.copy(token = password ?: s.token)
        val tested = withContext(Dispatchers.IO) { adapter(toTest).test() }
        if (tested.isFailure) {
            // 失败不写库，但缓存已经清了，下次会重新建适配器（会用最新配置）
            return Result.failure(tested.exceptionOrNull() ?: RuntimeException("连接失败"))
        }
        // ③ 密码/token 存加密 SP
        if (!password.isNullOrBlank()) {
            App.secrets.put(secretKey, password)
        }
        val stored = s.copy(
            secretRef = if (!password.isNullOrBlank() || !s.token.isNullOrBlank()) secretKey else s.secretRef,
            lastUsed = System.currentTimeMillis()
        )
        App.sources.upsert(stored)
        // ④ 让缓存里那个「带明文密码」的测试副本失效，下次走 hydrated() 正规注入
        AdapterRegistry.invalidate(s.id)
        return Result.success(Unit)
    }

    /** 只做连接测试，不写库（表单「测试连接」按钮用）。⚠️ 同样必须 IO 线程，理由同上 */
    suspend fun testSource(s: MediaSource, password: String?): Result<Unit> {
        AdapterRegistry.invalidate(s.id)
        return withContext(Dispatchers.IO) {
            adapter(s.copy(token = password ?: s.token)).test()
        }
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
