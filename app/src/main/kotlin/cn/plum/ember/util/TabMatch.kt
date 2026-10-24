package cn.plum.ember.util

/**
 * 顶部导航 tab → Emby 媒体库匹配。
 *
 * 大哥的 Emby 已经按内容类型建好了媒体库（电影 / 电视剧 / 动漫 / 纪录片...），
 * App 不该自己猜一套假分类 —— 直接按库名 + CollectionType 匹配真实媒体库，
 * 匹配不到再按条目类型兜底。
 */
object TabMatch {

    /** 关键词（中文）+ Emby CollectionType 兼容一套 */
    private val rules = mapOf(
        "电视剧" to listOf("剧", "综艺", "tv", "show", "series"),
        "电影" to listOf("电影", "movie", "film"),
        "动漫" to listOf("动漫", "动画", "anime", "cartoon"),
        "纪录片" to listOf("纪录", "documentary", "doc")
    )

    /** 该媒体库是否属于这个 tab */
    fun isLibraryFor(tab: String, libName: String, collectionType: String): Boolean {
        val keys = rules[tab] ?: return false
        val name = libName.lowercase()
        val kind = collectionType.lowercase()
        return keys.any { k -> name.contains(k.lowercase()) || kind.contains(k.lowercase()) }
    }
}
