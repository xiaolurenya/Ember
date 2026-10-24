package cn.plum.ember.core.mpv

import android.content.Context
import android.util.Log
import android.view.Surface
import cn.plum.ember.data.PlayerSettings
import cn.plum.ember.data.Playable
import cn.plum.ember.data.TrackInfo
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 播放器对外暴露的完整状态，UI 直接订阅渲染 */
data class PlayerUiState(
    val loaded: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val cacheAheadSec: Double = 0.0,
    val speed: Float = 1.0f,
    val audioTracks: List<TrackInfo> = emptyList(),
    val subTracks: List<TrackInfo> = emptyList(),
    val currentAudio: Int = -1,
    val currentSub: Int = -1,
    val currentSubDelay: Double = 0.0,
    val chapters: List<Pair<String, Double>> = emptyList(),  // (标题, 开始秒)
    val currentChapter: Int = -1,
    val width: Int = 0,
    val height: Int = 0,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val hwdecActive: Boolean = false,
    val hdrActive: Boolean = false,
    val bitrate: Long = 0L,
    val mediaTitle: String = "",
    /** 最近一次出错信息，UI 显示后自动清除 */
    val error: String? = null,
    /** 播放自然结束（用于触发下一集） */
    val finished: Boolean = false
)

/**
 * libmpv 封装层。
 *
 * 关键约定：
 *  1. 每个播放会话一个 MPV 实例，会话结束彻底销毁（避免长会话内存膨胀）。
 *  2. 所有网络相关 option 在 nativeCreate() 之后 nativeInit() 之前下发才生效。
 *  3. 视频 Surface 由 Compose 的 AndroidView 挂上来，**loadfile 必须在 surface 绑定之后**下发，
 *     否则 mpv 在空 surface 上起播、surface 后到时部分机型/模拟器不会自动补渲染 → 黑屏。
 *     本类用 pendingLoad 机制：load() 在 surface 就绪前只暂存，attachSurface 后补 loadfile。
 *  4. 所有请求头（UA/Referer/Cookie/自定义头）统一经 http-header-fields 下发，
 *     保证 Emby 的 api_key 之外若有 Cookie/Referer 也不漏（libmpv 自己跟跳转拿不到上游 Cookie）。
 *  5. token 失效的自动续播由上层（PlayerScreen）监听 error / finished 事件重新走 resolver。
 */
class MpvEngine(
    private val appCtx: Context,
    private val settings: PlayerSettings
) {
    private var mpv: MPV? = null
    private var surface: Surface? = null
    /** surface 是否已成功 bind 到 mpv（attachSurface 成功才置 true） */
    @Volatile private var surfaceReady = false
    /** mpv 实例是否已 init 完成 */
    @Volatile private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentPlayable: Playable? = null
    /** surface 未就绪时暂存的待播资源，surface 一到立刻 loadfile */
    private var pending: Playable? = null
    /** seek 期间不再回写 UI 位置，避免进度条抖动 */
    @Volatile private var seeking = false

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** 用户主动 seek / play 等操作发生的时间戳，用于自动隐藏控制条 */
    val lastInteraction = MutableStateFlow(0L)

    // ===================== 生命周期 =====================

    suspend fun start(): Boolean = withContext(Dispatchers.Main) {
        if (mpv != null) return@withContext true
        try {
            // mpv-android-lib 0.1.12 的真实签名：无参构造 + create(ctx) + init()
            val m = MPV()

            // ---- ① create：建出 mpv handle，此后才有得可设 ----
            m.create(appCtx.applicationContext)

            // ---- ② init 之前下发 option（这里有且只有这一次机会，init 后改不了）----
            m.setOptionString("config", "no")
            m.setOptionString("vo", "gpu-next")
            m.setOptionString("gpu-context", "android")
            // 软解兜底：auto-safe 在硬解不可用时自动退回软件解码，
            // 模拟器 / 老旧芯片不会因为拿不到硬件解码器而直接黑屏。
            m.setOptionString("hwdec", if (settings.hwdec) "auto-safe" else "no")
            m.setOptionString("cache", "auto")
            m.setOptionString("cache-secs", settings.cacheSecs.toString())
            m.setOptionString("demuxer-max-bytes", "${settings.demuxerMaxMb}MiB")
            m.setOptionString("cache-pause", "yes")
            m.setOptionString("cache-pause-wait", "2")
            m.setOptionString("cache-pause-initial", "yes")
            m.setOptionString("force-seekable", "yes")
            m.setOptionString("demuxer-hysteresis-secs", "10")
            m.setOptionString("network-timeout", settings.networkTimeout.toString())
            m.setOptionString("stream-buffer-size", "256KiB")
            m.setOptionString("audio-output", "audiotrack")
            m.setOptionString("sub-auto", "fuzzy")      // 外挂字幕自动同目录匹配
            m.setOptionString("sub-fonts-dir", appCtx.filesDir.resolve("fonts").absolutePath)
            m.setOptionString("sub-bold", "yes")
            // 字幕用 libass 渲染进视频帧（不走 Overlay），支持 ASS 特效
            m.setOptionString("sub-use-ffi", "no")
            m.setOptionString("blend-subtitles", "video")

            // ---- ③ init（真正初始化）----
            m.init()

            // ---- ④ init 之后：观察属性，接事件 ----
            m.observeProperty("time-pos", MPV_FORMAT_DOUBLE)
            m.observeProperty("duration", MPV_FORMAT_DOUBLE)
            m.observeProperty("demuxer-cache-time", MPV_FORMAT_DOUBLE)
            m.observeProperty("paused", MPV_FORMAT_FLAG)
            m.observeProperty("paused-for-cache", MPV_FORMAT_FLAG)
            m.observeProperty("speed", MPV_FORMAT_DOUBLE)
            m.observeProperty("track-list", MPV_FORMAT_NODE)
            m.observeProperty("chapter-list", MPV_FORMAT_NODE)
            m.observeProperty("chapter", MPV_FORMAT_INT64)
            m.observeProperty("video-params", MPV_FORMAT_NODE)
            m.observeProperty("video-format", MPV_FORMAT_STRING)
            m.observeProperty("video-codec", MPV_FORMAT_STRING)
            m.observeProperty("audio-codec-name", MPV_FORMAT_STRING)
            m.observeProperty("hwdec-current", MPV_FORMAT_STRING)
            m.observeProperty("media-title", MPV_FORMAT_STRING)
            m.observeProperty("eof-reached", MPV_FORMAT_FLAG)
            m.observeProperty("seeking", MPV_FORMAT_FLAG)
            m.observeProperty("sub-delay", MPV_FORMAT_DOUBLE)
            m.observeProperty("cache-buffering-state", MPV_FORMAT_INT64)

            mpv = m
            started = true
            subscribeFlows(m)

            // start 完成时若 surface 早已创建（先到），这里补绑定
            surface?.let { s ->
                if (s.isValid) {
                    try { m.attachSurface(s); surfaceReady = true } catch (t: Throwable) {
                        Log.w(TAG, "attachSurface on start failed: ${t.message}")
                    }
                }
            }
            // 若 start 之前就有暂存的待播，此刻 surface 已绑，直接补 loadfile
            flushPending()

            Log.i(TAG, "mpv started (hwdec=${settings.hwdec})")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "mpv init failed: ${t.message}", t)
            _state.value = _state.value.copy(error = "内核初始化失败：${t.message ?: "未知错误"}")
            false
        }
    }

    fun attachSurface(s: Surface?) {
        surface = s
        val m = mpv ?: return   // 内核还没起，先存着，start() 结束会补绑
        try {
            if (s != null && s.isValid) {
                m.attachSurface(s)
                surfaceReady = true
                Log.i(TAG, "surface attached")
                // surface 一到，把暂存的待播补上
                flushPending()
            } else {
                m.detachSurface()
                surfaceReady = false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "attachSurface failed: ${t.message}")
        }
    }

    /** surface 就绪后补 loadfile（pending 或普通 load 暂存的资源） */
    private fun flushPending() {
        val p = pending ?: return
        pending = null
        if (surfaceReady && started) {
            Log.i(TAG, "flush pending load: ${p.url.take(80)}")
            doLoad(p, p.startSec)
        }
    }

    fun stop() {
        val m = mpv ?: return
        try { m.command("stop") } catch (_: Throwable) {}
        currentPlayable = null
        pending = null
    }

    fun destroy() {
        val m = mpv ?: run { scope.cancel(); return }
        try { m.detachSurface() } catch (_: Throwable) {}
        try { m.destroy() } catch (_: Throwable) {}   // destroySession() + nativeDestroy()
        mpv = null
        surface = null
        surfaceReady = false
        started = false
        pending = null
        scope.cancel()
    }

    // ===================== 加载 =====================

    /**
     * 加载一个 Playable。
     * ⚠️ 若 surface 尚未绑定，先暂存，等 attachSurface 后再真正 loadfile —— 这是修复黑屏的关键。
     * @param resumeSec 起播位置（覆盖 Playable.startSec）
     */
    suspend fun load(p: Playable, resumeSec: Long = -1L): Boolean = withContext(Dispatchers.Main) {
        currentPlayable = p
        _state.value = _state.value.copy(
            loaded = false, playing = false, buffering = true,
            positionSec = 0.0, durationSec = 0.0, finished = false, error = null
        )
        if (!started) {
            // 内核都还没起，等 start() 完成后再说
            pending = p
            Log.i(TAG, "load deferred: engine not started yet")
            return@withContext true
        }
        if (!surfaceReady) {
            // surface 未到：暂存，attachSurface 后 flushPending() 补 loadfile
            pending = p
            Log.i(TAG, "load deferred: surface not ready yet")
            return@withContext true
        }
        doLoad(p, if (resumeSec >= 0) resumeSec else p.startSec)
    }

    /** 真正下发 loadfile（调用方需保证 surface 已就绪） */
    private fun doLoad(p: Playable, startSec: Long): Boolean {
        val m = mpv ?: return false
        val headers = p.headers

        // ---- 头部：UA 单独走 user-agent 属性；其余（Referer/Cookie/自定义头）走 http-header-fields ----
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val headerFields = mutableListOf<String>()
        headers.forEach { (k, v) ->
            if (!k.equals("User-Agent", true)) headerFields += "$k: $v"
        }
        try {
            if (!ua.isNullOrBlank()) m.setPropertyString("user-agent", ua)
            m.setPropertyNode(
                "http-header-fields",
                MPVNode.ArrayNode(headerFields.map { MPVNode.StringNode(it) }.toTypedArray())
            )
        } catch (t: Throwable) {
            Log.w(TAG, "set headers failed: ${t.message}")
        }

        // ---- loadfile：start 选项传起始位置 ----
        return try {
            if (startSec > 5) {
                m.command("loadfile", p.url, "replace", "start=+$startSec")
            } else {
                m.command("loadfile", p.url, "replace")
            }
            Log.i(TAG, "loadfile issued: ${p.url.take(120)} (start=$startSec)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "loadfile failed: ${t.message}", t)
            _state.value = _state.value.copy(error = "无法加载：${t.message ?: "格式不支持"}")
            false
        }
    }

    /** 播放中断流后的自动重试：换新直链 + 回到原位置继续 */
    suspend fun revalidate(resolver: suspend (Long) -> Playable?): Boolean {
        val pos = _state.value.positionSec.toLong()
        val np = resolver(pos) ?: return false
        return load(np, resumeSec = pos)
    }

    // ===================== 控制 =====================

    fun play() { mpv?.setPropertyBoolean("pause", false) }
    fun pause() { mpv?.setPropertyBoolean("pause", true) }
    fun togglePlay() {
        val m = mpv ?: return
        val paused = m.getPropertyBoolean("pause") ?: false
        m.setPropertyBoolean("pause", !paused)
        touch()
    }
    fun seekTo(sec: Double) {
        val m = mpv ?: return
        val d = _state.value.durationSec
        val target = sec.coerceIn(0.0, if (d > 0) d - 0.5 else sec)
        try {
            m.command("seek", target.toString(), "absolute")
            _state.value = _state.value.copy(positionSec = target)
            touch()
        } catch (_: Throwable) {}
    }
    fun seekBy(deltaSec: Double) {
        val m = mpv ?: return
        val pos = _state.value.positionSec + deltaSec
        seekTo(pos)
    }
    fun setSpeed(f: Float) {
        mpv?.setPropertyDouble("speed", f.toDouble())
        touch()
    }
    fun setAudioTrack(id: Int) { mpv?.setPropertyInt("aid", id) }
    fun setSubtitleTrack(id: Int) { mpv?.setPropertyInt("sid", id) }   // -1 = 关闭
    fun setSubDelay(sec: Double) { mpv?.setPropertyDouble("sub-delay", sec) }
    fun setHwdec(on: Boolean) { mpv?.setPropertyString("hwdec", if (on) "auto-safe" else "no") }

    /** 画面比例：origin / fit / fill / stretch / 16:9 / 4:3 */
    fun setAspect(mode: String) {
        val m = mpv ?: return
        val v = when (mode) {
            "origin" -> "-1"        // 跟随源
            "fit" -> "-1"           // Compose 层用 ContentScale 处理
            "fill" -> "-1"
            "stretch" -> "-1"
            "16:9" -> "16:9"
            "4:3" -> "4:3"
            "2.35:1" -> "2.35"
            else -> "-1"
        }
        m.setPropertyString("video-aspect-override", v)
        touch()
    }

    /** 音频直通开关（HDR/杜比设备） */
    fun setPassthrough(on: Boolean) {
        mpv?.setPropertyString("audio-spdif", if (on) "truehd,dts-hd,eac3,ac3,dts" else "")
    }

    fun chapterJump(delta: Int) {
        val m = mpv ?: return
        val idx = _state.value.currentChapter + delta
        if (idx >= 0 && idx < _state.value.chapters.size) {
            m.setPropertyInt("chapter", idx)
            touch()
        }
    }

    fun touch() { lastInteraction.value = System.currentTimeMillis() }

    // ===================== 属性订阅 =====================

    private fun subscribeFlows(m: MPV) {
        scope.launch {
            m.propFlow<Double>("time-pos").collect { v ->
                if (!seeking) _state.value = _state.value.copy(positionSec = v ?: 0.0)
            }
        }
        scope.launch {
            m.propFlow<Double>("duration").collect { v ->
                _state.value = _state.value.copy(durationSec = v ?: 0.0)
            }
        }
        scope.launch {
            m.propFlow<Double>("demuxer-cache-time").collect { v ->
                _state.value = _state.value.copy(cacheAheadSec = v ?: 0.0)
            }
        }
        scope.launch {
            m.propFlow<Boolean>("paused").collect { v ->
                _state.value = _state.value.copy(playing = (v != true) && _state.value.loaded)
            }
        }
        scope.launch {
            m.propFlow<Boolean>("paused-for-cache").collect { v ->
                _state.value = _state.value.copy(buffering = v == true)
            }
        }
        scope.launch {
            m.propFlow<Double>("speed").collect { v ->
                _state.value = _state.value.copy(speed = (v ?: 1.0).toFloat())
            }
        }
        scope.launch {
            m.propFlow<MPVNode>("track-list").collect { v -> parseTracks(v) }
        }
        scope.launch {
            m.propFlow<MPVNode>("chapter-list").collect { v -> parseChapters(v) }
        }
        scope.launch {
            m.propFlow<Long>("chapter").collect { v ->
                _state.value = _state.value.copy(currentChapter = (v ?: -1L).toInt())
            }
        }
        scope.launch {
            m.propFlow<MPVNode>("video-params").collect { v ->
                // MPVNode.asInt() 返回的是 Long（IntNode 存 Long），必须显式转 Int
                val w = v?.get("w")?.asInt()?.toInt() ?: 0
                val h = v?.get("h")?.asInt()?.toInt() ?: 0
                val prim = v?.get("color-primaries")?.asString() ?: ""
                val trc = v?.get("color-trc")?.asString() ?: ""
                _state.value = _state.value.copy(
                    width = w, height = h,
                    hdrActive = trc.contains("pq", true) || trc.contains("hlg", true) ||
                            prim.contains("bt2020", true)
                )
            }
        }
        scope.launch {
            m.propFlow<String>("video-format").collect { v ->
                _state.value = _state.value.copy(videoCodec = v)
            }
        }
        scope.launch {
            m.propFlow<String>("audio-codec-name").collect { v ->
                _state.value = _state.value.copy(audioCodec = v)
            }
        }
        scope.launch {
            m.propFlow<String>("hwdec-current").collect { v ->
                _state.value = _state.value.copy(
                    hwdecActive = !v.isNullOrBlank() && !v.equals("no", true)
                )
            }
        }
        scope.launch {
            m.propFlow<String>("media-title").collect { v ->
                _state.value = _state.value.copy(mediaTitle = v ?: "")
            }
        }
        scope.launch {
            m.propFlow<Boolean>("eof-reached").collect { v ->
                if (v == true) {
                    _state.value = _state.value.copy(finished = true, playing = false)
                }
            }
        }
        scope.launch {
            m.propFlow<Double>("sub-delay").collect { v ->
                _state.value = _state.value.copy(currentSubDelay = v ?: 0.0)
            }
        }
        scope.launch {
            m.propFlow<Long>("cache-buffering-state").collect { v ->
                if ((v ?: 100L) < 100) {
                    _state.value = _state.value.copy(buffering = true)
                }
            }
        }
        // 起播成功：file-loaded 事件（事件监听比属性订阅更直接）
        val observer = object : MPV.EventObserver {
            override fun eventProperty(property: String) {
                if (property == "file-loaded") onFileLoaded()
            }
            override fun eventProperty(property: String, value: Long) {
                if (property == "file-loaded") onFileLoaded()
            }
            override fun eventProperty(property: String, value: Boolean) {}
            override fun eventProperty(property: String, value: String) {}
            override fun eventProperty(property: String, value: Double) {}
            override fun eventProperty(property: String, value: MPVNode) {}
            override fun event(eventId: Int, data: MPVNode) {
                // MPV_EVENT_END_FILE = 7；data 里 reason: 0=EOF 2=STOP 3=QUIT 4=REDIRECT 5=ERROR
                if (eventId == 7) {
                    val reason = data?.get("reason")?.asInt() ?: -1L
                    if (reason == 5L) {
                        val err = data?.get("file_error")?.asString() ?: "播放中断"
                        Log.e(TAG, "playback error (end-file reason=5): $err")
                        _state.value = _state.value.copy(error = err, buffering = false)
                    }
                }
            }
        }
        m.addObserver(observer)
    }

    private fun onFileLoaded() {
        seeking = false
        _state.value = _state.value.copy(loaded = true, buffering = false, finished = false)
        Log.i(TAG, "file-loaded: loaded=true")
        // mpv 的 loadfile 带 start=+N 时部分版本不触发 seek 事件，这里主动校正一次 UI 位置
        val m = mpv ?: return
        val pos = m.getPropertyDouble("time-pos") ?: 0.0
        if (pos > 0) _state.value = _state.value.copy(positionSec = pos)
    }

    private fun parseTracks(node: MPVNode?) {
        val arr = node?.asArray() ?: return
        val audio = mutableListOf<TrackInfo>()
        val subs = mutableListOf<TrackInfo>()
        var curA = -1
        var curS = -1
        arr.forEach { t ->
            val m = t.asMap() ?: return@forEach
            val id = m["id"]?.asInt()?.toInt() ?: return@forEach
            val type = m["type"]?.asString() ?: return@forEach
            val info = TrackInfo(
                id = id,
                type = type,
                title = m["title"]?.asString(),
                lang = m["lang"]?.asString(),
                codec = m["codec"]?.asString(),
                isDefault = m["default"]?.asBoolean() == true,
                isForced = m["forced"]?.asBoolean() == true,
                isExternal = m["external"]?.asBoolean() == true
            )
            when (type) {
                "audio" -> {
                    audio += info
                    if (m["selected"]?.asBoolean() == true) curA = id
                }
                "sub" -> {
                    subs += info
                    if (m["selected"]?.asBoolean() == true) curS = id
                }
            }
        }
        _state.value = _state.value.copy(
            audioTracks = audio, subTracks = subs,
            currentAudio = curA, currentSub = curS
        )
    }

    private fun parseChapters(node: MPVNode?) {
        val arr = node?.asArray() ?: return
        val list = arr.mapNotNull { c ->
            val m = c.asMap() ?: return@mapNotNull null
            val title = m["title"]?.asString() ?: "章节"
            val time = m["time"]?.asDouble() ?: 0.0
            title to time
        }
        _state.value = _state.value.copy(chapters = list)
    }

    companion object {
        private const val TAG = "EmberMpv"

        // mpv_format 常量（从 is.xyz.mpv.MPV 拷出来，避免依赖内部对象）
        const val MPV_FORMAT_NONE: Int = 0
        const val MPV_FORMAT_STRING: Int = 1
        const val MPV_FORMAT_OSD_STRING: Int = 2
        const val MPV_FORMAT_FLAG: Int = 3
        const val MPV_FORMAT_INT64: Int = 4
        const val MPV_FORMAT_DOUBLE: Int = 5
        const val MPV_FORMAT_NODE: Int = 6
        const val MPV_FORMAT_NODE_ARRAY: Int = 7
        const val MPV_FORMAT_NODE_MAP: Int = 8

        /** 主动预读下一集直链，减少切集黑屏 */
        suspend fun preloadNext(p: Playable): Playable? = withContext(Dispatchers.IO) {
            // 预加载只做直链解析，不做真正的缓冲（mpv 内部有 cache 层管这件事）
            return@withContext p.nextPlayable
        }
    }
}
