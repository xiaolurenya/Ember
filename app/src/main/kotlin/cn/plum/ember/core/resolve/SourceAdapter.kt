package cn.plum.ember.core.resolve

import cn.plum.ember.data.Library
import cn.plum.ember.data.MediaItem
import cn.plum.ember.data.MediaSource
import cn.plum.ember.data.Playable

/**
 * 源适配器：把一个具体媒体服务（Emby/Plex/AList/WebDAV/本地目录）的
 * 私有 API 归一化为统一的「浏览 / 搜索 / 取直链 / 回写进度」四个动作。
 *
 * 生命周期：由 AdapterRegistry 按 sourceId 创建并复用，切源时销毁。
 */
interface SourceAdapter {
    val source: MediaSource

    /** 连接测试：登录 / 探活，失败返回带可读错误 */
    suspend fun test(): Result<Unit>

    /** 媒体库列表（Emby 的 Views / Plex 的 Sections / 目录的顶级文件夹） */
    suspend fun libraries(): List<Library>

    /** 拉取某库下的条目，page 从 0 开始 */
    suspend fun items(libraryId: String?, page: Int, pageSize: Int = 40): List<MediaItem>

    /** 全库搜索（title 关键字） */
    suspend fun search(query: String, limit: Int = 30): List<MediaItem>

    /** 单条详情（含多版本、剧集列表） */
    suspend fun detail(id: String): MediaItem?

    /** 继续观看 */
    suspend fun resume(page: Int = 0, pageSize: Int = 20): List<MediaItem>

    /** 某库最新 */
    suspend fun latest(libraryId: String?, limit: Int = 20): List<MediaItem>

    /**
     * 解析为可直接交给 mpv 的 Playable。
     * 内部完成：.strm 读取 → 302 手动跟随 → Cookie/UA/Referer 组装 → token 刷新。
     */
    suspend fun playable(item: MediaItem, versionIndex: Int = 0): Result<Playable>

    /** 回写播放进度（Emby/Plex 支持，本地源返回 null 表示不支持） */
    suspend fun reportProgress(item: MediaItem, positionSec: Long, durationSec: Long): Result<Unit>?

    /** 标记已看 */
    suspend fun markWatched(item: MediaItem): Result<Unit>?

    /** 相关推荐（可选能力，默认空） */
    suspend fun related(item: MediaItem): List<MediaItem> = emptyList()
}

/** 适配器构造工厂 */
object AdapterRegistry {
    private val cache = HashMap<String, SourceAdapter>()

    fun of(source: MediaSource): SourceAdapter = synchronized(cache) {
        cache.getOrPut(source.id) { create(source) }
    }

    fun invalidate(sourceId: String) = synchronized(cache) { cache.remove(sourceId) }

    fun clear() = synchronized(cache) { cache.clear() }

    private fun create(s: MediaSource): SourceAdapter = when (s.type) {
        cn.plum.ember.data.SourceType.EMBY -> EmbyAdapter(s)
        cn.plum.ember.data.SourceType.JELLYFIN -> JellyfinAdapter(s)
        cn.plum.ember.data.SourceType.PLEX -> PlexAdapter(s)
        cn.plum.ember.data.SourceType.ALIST -> AlistAdapter(s)
        cn.plum.ember.data.SourceType.WEBDAV -> WebDavAdapter(s)
        cn.plum.ember.data.SourceType.LOCAL -> LocalAdapter(s)
    }
}
