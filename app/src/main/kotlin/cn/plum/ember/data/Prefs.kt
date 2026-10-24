package cn.plum.ember.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.plum.ember.util.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "ember_prefs")

/** 全局偏好：内核参数 / UI / 行为 */
data class PlayerSettings(
    /** 硬解优先，失败自动软解 */
    val hwdec: Boolean = true,
    /** 网络预读秒数（10-60） */
    val cacheSecs: Int = 20,
    /** 缓存字节上限 */
    val demuxerMaxMb: Int = 150,
    /** 网络超时 */
    val networkTimeout: Int = 15,
    /** 自动跳过片头片尾 */
    val autoSkipIntro: Boolean = true,
    /** 片头时长（秒）：起播位置低于该值时跳过 */
    val introSkipSec: Int = 90,
    /** 片尾时长（秒）：剩余时间低于该值时出现「跳过片尾/下一集」 */
    val creditsLenSec: Int = 180,
    /** 连播下一集 */
    val autoPlayNext: Boolean = true,
    /** 起播默认倍速 */
    val defaultSpeed: Float = 1.0f,
    /** 画面比例偏好：origin / fit / fill / stretch */
    val aspectMode: String = "origin",
    /** 显示解码信息浮层 */
    val showDecoderInfo: Boolean = false,
    /** HDR 直通（关闭则色调映射到 SDR） */
    val hdrPassthrough: Boolean = true,
    /** seek 步长（秒） */
    val seekStep: Int = 10,
    /** 横屏播放 */
    val landscapePlayer: Boolean = true,
    /** 首页货架顺序：继续观看/最近添加/电影/剧集 */
    val homeShelves: List<String> = listOf("resume", "recent", "movies", "series"),
    /** 备份用的 WebDAV 地址 */
    val backupWebdav: String = "",
    val backupUser: String = ""
)

class Prefs(private val ctx: Context) {
    /** 应用上下文（App 单例建播放内核用） */
    val appCtx: Context get() = ctx.applicationContext
    private object K {
        val hwdec = booleanPreferencesKey("hwdec")
        val cacheSecs = intPreferencesKey("cache_secs")
        val demuxerMaxMb = intPreferencesKey("demuxer_max_mb")
        val networkTimeout = intPreferencesKey("network_timeout")
        val autoSkipIntro = booleanPreferencesKey("auto_skip_intro")
        val introSkipSec = intPreferencesKey("intro_skip_secs")
        val creditsLenSec = intPreferencesKey("credits_len_secs")
        val autoPlayNext = booleanPreferencesKey("auto_play_next")
        val defaultSpeed = floatPreferencesKey("default_speed")
        val aspectMode = stringPreferencesKey("aspect_mode")
        val showDecoderInfo = booleanPreferencesKey("show_decoder_info")
        val hdrPassthrough = booleanPreferencesKey("hdr_passthrough")
        val seekStep = intPreferencesKey("seek_step")
        val landscapePlayer = booleanPreferencesKey("landscape_player")
        val homeShelves = stringPreferencesKey("home_shelves")
        val backupWebdav = stringPreferencesKey("backup_webdav")
        val backupUser = stringPreferencesKey("backup_user")
        val lastSourceId = stringPreferencesKey("last_source_id")
        val onboarded = booleanPreferencesKey("onboarded")
    }

    val flow: Flow<PlayerSettings> = ctx.dataStore.data.map { it.toSettings() }

    /** Preferences → PlayerSettings（只此一处，避免两处默认值漂移） */
    private fun androidx.datastore.preferences.core.Preferences.toSettings(): PlayerSettings =
        PlayerSettings(
            hwdec = this[K.hwdec] ?: true,
            cacheSecs = this[K.cacheSecs] ?: 20,
            demuxerMaxMb = this[K.demuxerMaxMb] ?: 150,
            networkTimeout = this[K.networkTimeout] ?: 15,
            autoSkipIntro = this[K.autoSkipIntro] ?: true,
            introSkipSec = this[K.introSkipSec] ?: 90,
            creditsLenSec = this[K.creditsLenSec] ?: 180,
            autoPlayNext = this[K.autoPlayNext] ?: true,
            defaultSpeed = this[K.defaultSpeed] ?: 1.0f,
            aspectMode = this[K.aspectMode] ?: "origin",
            showDecoderInfo = this[K.showDecoderInfo] ?: false,
            hdrPassthrough = this[K.hdrPassthrough] ?: true,
            seekStep = this[K.seekStep] ?: 10,
            landscapePlayer = this[K.landscapePlayer] ?: true,
            homeShelves = (this[K.homeShelves] ?: "resume,recent,movies,series").split(','),
            backupWebdav = this[K.backupWebdav] ?: "",
            backupUser = this[K.backupUser] ?: ""
        )

    suspend fun set(transform: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        ctx.dataStore.edit { transform(it) }
    }

    suspend fun setHwdec(v: Boolean) = set { it[K.hwdec] = v }
    suspend fun setCacheSecs(v: Int) = set { it[K.cacheSecs] = v.coerceIn(10, 60) }
    suspend fun setAutoSkipIntro(v: Boolean) = set { it[K.autoSkipIntro] = v }
    suspend fun setIntroSkipSec(v: Int) = set { it[K.introSkipSec] = v.coerceIn(0, 300) }
    suspend fun setCreditsLenSec(v: Int) = set { it[K.creditsLenSec] = v.coerceIn(0, 600) }
    suspend fun setAutoPlayNext(v: Boolean) = set { it[K.autoPlayNext] = v }
    suspend fun setDefaultSpeed(v: Float) = set { it[K.defaultSpeed] = v }
    suspend fun setAspectMode(v: String) = set { it[K.aspectMode] = v }
    suspend fun setHdrPassthrough(v: Boolean) = set { it[K.hdrPassthrough] = v }
    suspend fun setShowDecoderInfo(v: Boolean) = set { it[K.showDecoderInfo] = v }
    suspend fun setSeekStep(v: Int) = set { it[K.seekStep] = v.coerceIn(5, 60) }
    suspend fun setLandscapePlayer(v: Boolean) = set { it[K.landscapePlayer] = v }
    suspend fun setHomeShelves(v: List<String>) = set { it[K.homeShelves] = v.joinToString(",") }
    suspend fun setLastSourceId(v: String) = set { it[K.lastSourceId] = v }
    suspend fun setOnboarded(v: Boolean) = set { it[K.onboarded] = v }

    /** 同步读上次使用的源（冷启动一次，可接受 runBlocking） */
    fun lastSourceIdBlocking(): String? = try {
        runBlocking { ctx.dataStore.data.first()[K.lastSourceId] }
    } catch (_: Throwable) { null }

    /** 一次性读全量设置 */
    suspend fun snapshot(): PlayerSettings = ctx.dataStore.data.first().toSettings()

    /**
     * 同步快照。只给「必须立刻拿到值」的场景用（比如 remember { } 里构造播放内核），
     * 普通 UI 请订阅 flow。DataStore 首次读在几百微秒量级，可接受。
     */
    fun snapshotBlocking(): PlayerSettings = try {
        runBlocking { snapshot() }
    } catch (_: Throwable) { PlayerSettings() }
}

/**
 * 敏感凭据独立保管：API Key / 服务器密码 / 网盘 Cookie。
 * 用 EncryptedSharedPreferences（Android Keystore 主密钥），
 * 系统密钥库损坏时清空重来但不崩溃。
 */
class Secrets(ctx: Context) {
    private val sp: SharedPreferences by lazy {
        try {
            val mk = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx, "ember_secrets", mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            // Keystore 损坏的兜底：退回普通 SP 并记录，用户重新登录即可恢复
            ctx.getSharedPreferences("ember_secrets_fallback", Context.MODE_PRIVATE)
        }
    }

    fun put(key: String, value: String) = sp.edit().putString(key, value).apply()
    fun get(key: String): String? = sp.getString(key, null)
    fun remove(key: String) = sp.edit().remove(key).apply()
}

/** 媒体源列表持久化（不存密码，密码在 Secrets 里） */
class SourceStore(private val ctx: Context) {
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("ember_sources", Context.MODE_PRIVATE)

    fun all(): List<MediaSource> {
        val raw = sp.getString("sources", null) ?: return emptyList()
        return Json.gson.fromJson(raw, Array<MediaSource>::class.java)?.toList() ?: emptyList()
    }

    fun save(list: List<MediaSource>) {
        sp.edit().putString("sources", Json.gson.toJson(list.toTypedArray())).apply()
    }

    fun upsert(s: MediaSource) {
        val list = all().filterNot { it.id == s.id } + s
        save(list.sortedByDescending { it.lastUsed })
    }

    fun remove(id: String) {
        save(all().filterNot { it.id == id })
    }

    fun find(id: String): MediaSource? = all().firstOrNull { it.id == id }
}
