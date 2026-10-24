package cn.plum.ember.data

/**
 * 全项目的核心数据模型。全部字段可空 + 默认值，反序列化来源多样
 * （Emby/Jellyfin/Plex/Alist/WebDAV/本地扫描/TMDB），宁可宽进严出。
 */

/** 源类型：决定适配器的路由 */
enum class SourceType(val label: String) {
    EMBY("Emby"),
    JELLYFIN("Jellyfin"),
    PLEX("Plex"),
    ALIST("AList"),
    WEBDAV("WebDAV"),
    LOCAL("本地目录"),
    ;

    companion object {
        fun from(name: String?): SourceType? = entries.firstOrNull { it.name.equals(name, true) }
    }
}

/** 一个已配置的媒体源（多服务器/多网盘/本地目录并存） */
data class MediaSource(
    val id: String,
    val type: SourceType,
    var name: String,
    /** 标准化后的 base，末尾不带 /。例：http://192.168.2.225:8096 */
    var baseUrl: String,
    var username: String? = null,
    /** EncryptedSharedPreferences 中的 key，真实密码不落普通表 */
    var secretRef: String? = null,
    /** 115/Alist 的 token，同样走 secretRef */
    var token: String? = null,
    /** 自定义请求头：UA / Referer / Cookie，key 不区分大小写 */
    var extraHeaders: Map<String, String> = emptyMap(),
    var useProxy: Boolean = false,
    var proxyUrl: String? = null,          // http://host:port 或 socks5://host:port
    var skipTlsVerify: Boolean = false,
    var scanRoot: String? = null,          // 本地目录源用
    var lastUsed: Long = 0L,
    var locked: Boolean = false            // 生物识别锁
)

/** 媒体库（电影/剧集/动漫等分类） */
data class Library(
    val id: String,
    val name: String,
    val itemCount: Int = 0,
    /** "movie" / "series" / "mixed" / "folder" */
    val kind: String = "mixed",
    val image: String? = null
)

/** 章节（mpv/Emby 的 chapter-list，跳片头片尾也用它） */
data class Chapter(
    val name: String,
    val startSec: Long
)

/** 演职人员 */
data class Person(
    val name: String,
    val role: String? = null,
    val image: String? = null
)

/** 单条媒体（电影/剧集/剧集中的一集/本地文件统一用这个） */
data class MediaItem(
    val id: String,
    val sourceId: String,
    val libraryId: String? = null,
    /** "movie" / "episode" / "series" / "video" */
    val kind: String = "movie",
    val title: String = "",
    val subtitle: String? = null,          // 剧集的 "S01E02 · 集名"
    /** 原名/外文名（详情页副标题） */
    val originalTitle: String? = null,
    /** 宣传语（详情页可显示在原名下） */
    val tagline: String? = null,
    val overview: String? = null,
    val year: Int? = null,
    /** 首映日期（yyyy-MM-dd 或 ISO） */
    val premiereDate: String? = null,
    val rating: Double? = null,
    /** 分级（PG-13 / R / _PG 13 等，原样显示） */
    val officialRating: String? = null,
    val durationSec: Long = 0L,
    /** 已看进度（秒），本地记录；Emby 源会优先用服务器端的 */
    val positionSec: Long = 0L,
    /** 服务器端标记已看 */
    val watched: Boolean = false,
    /** "movie" 场景下：4K/1080P 等多版本 */
    val versions: List<MediaVersion> = emptyList(),
    /** 剧集：这一季的集 */
    val episodes: List<MediaItem> = emptyList(),
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val poster: String? = null,
    val backdrop: String? = null,
    /** 剧照（Backdrops 除第一张外的横图，详情页相册用） */
    val stills: List<String> = emptyList(),
    /** 章节 */
    val chapters: List<Chapter> = emptyList(),
    /** 演职人员（含角色名） */
    val people: List<Person> = emptyList(),
    /** 制作公司 */
    val studios: List<String> = emptyList(),
    /** 外部 ID：Imdb/Tmdb（拼外部链接用） */
    val providerImdb: String? = null,
    val providerTmdb: String? = null,
    /** 媒体规格面板行（如 "HEVC · 3840×2160 · DOVI"，详情页右侧悬浮面板） */
    val mediaSpec: List<String> = emptyList(),
    val container: String? = null,         // mkv/mp4/iso...
    val path: String? = null,              // 原始路径（识别 .strm / .iso 用）
    val strmUrl: String? = null,           // .strm 文件解析出的直链
    /** 分辨率标签（用于卡片角标） */
    val tag4k: Boolean = false,
    val tagHdr: Boolean = false,
    val tagDolby: Boolean = false
) {
    val isStrm: Boolean
        get() = path?.endsWith(".strm", true) == true || strmUrl != null
    val isIso: Boolean
        get() = path?.endsWith(".iso", true) == true
    val resumePercent: Int
        get() = if (durationSec > 0) ((positionSec * 100) / durationSec).toInt().coerceIn(0, 100) else 0
}

/** 播放版本（同一部片的 4K / 1080P 多版本；Emby 的 MediaSources） */
data class MediaVersion(
    val index: Int = 0,
    val label: String = "默认",
    val container: String? = null,
    val sizeBytes: Long = 0L,
    val bitrate: Long = 0L,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val path: String? = null,
    /** 直接可播地址（Emby 的 stream URL / 网盘 raw_url） */
    val url: String? = null
)

/** 源适配器最终产出的可播放对象 */
data class Playable(
    /** 交给 mpv loadfile 的最终地址 */
    val url: String,
    /** 请求头：UA / Referer / Cookie（mpv 需要） */
    val headers: Map<String, String> = emptyMap(),
    /** 起始播放位置（秒） */
    val startSec: Long = 0L,
    val title: String = "",
    /** 关联的 MediaItem，用于进度回写 */
    val item: MediaItem? = null,
    val episode: MediaItem? = null,
    /** 下一集预加载 */
    val nextPlayable: Playable? = null
)

/** 播放过程中的轨道信息（来自 mpv track-list） */
data class TrackInfo(
    val id: Int,
    /** "audio" / "sub" */
    val type: String,
    val title: String? = null,
    val lang: String? = null,
    val codec: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isExternal: Boolean = false
) {
    val display: String
        get() = buildString {
            append(title ?: lang ?: "轨道 $id")
            if (!lang.isNullOrBlank() && title != null) append(" · $lang")
            if (!codec.isNullOrBlank()) append(" ($codec)")
            if (isExternal) append(" · 外挂")
            if (isDefault) append(" · 默认")
            if (isForced) append(" · 强制")
        }
}

/** 观看进度（本地持久化；Emby 源也会同时上报服务器） */
data class PlayProgress(
    val sourceId: String,
    val itemId: String,
    val positionSec: Long,
    val durationSec: Long,
    val watched: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)
