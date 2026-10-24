package cn.plum.ember.ui.screens.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cn.plum.ember.core.mpv.MpvEngine
import cn.plum.ember.data.App
import cn.plum.ember.data.LibraryRepo
import cn.plum.ember.data.MediaItem
import cn.plum.ember.data.MediaSource
import cn.plum.ember.data.TrackInfo
import cn.plum.ember.ui.theme.Ember
import cn.plum.ember.ui.theme.EmberSoft
import cn.plum.ember.ui.theme.InkRaised
import cn.plum.ember.ui.theme.Scrim
import cn.plum.ember.ui.theme.TextHi
import cn.plum.ember.ui.theme.TextLow
import cn.plum.ember.ui.theme.TextMid
import cn.plum.ember.ui.widgets.isTv
import cn.plum.ember.ui.widgets.tvFocus
import cn.plum.ember.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全屏播放器（libmpv 内核 + 美化 UI）。
 *
 * 手势（页内实现，不依赖第三方手势库）：
 *   单击 = 呼出/隐藏控制条（4 秒无操作自动隐藏）
 *   双击 = 播放/暂停
 *   水平拖动 = seek（带中心时间气泡）
 *   左半屏上下滑 = 亮度 ±（带百分比 OSD）
 *   右半屏上下滑 = 音量 ±（带百分比 OSD）
 *   返回 = 退出播放（自动保存进度）
 *
 * 与旧版的关键区别：loadfile 由 MpvEngine 保证在 surface 绑定后下发，杜绝首播黑屏。
 */
@Composable
fun PlayerScreen(
    repo: LibraryRepo,
    source: MediaSource?,
    itemId: String,
    versionIndex: Int,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val tv = isTv()

    // 完整的 MediaItem（详情拉回后补齐集数/时长/海报）
    var item by remember { mutableStateOf(MediaItem(id = itemId, sourceId = source?.id ?: "", title = "")) }
    LaunchedEffect(itemId) {
        try {
            repo.detail(source?.id ?: "", itemId)?.let { item = it }
        } catch (_: Throwable) {}
    }

    // ---- 初始设置 ----
    val settings = remember { App.prefs.snapshotBlocking() }

    val engine = remember { MpvEngine(context.applicationContext, settings) }
    val state by engine.state.collectAsState()
    val brightness = remember { mutableFloatStateOf(getBrightness(view)) }
    val volume = remember { mutableFloatStateOf(getVolume(context)) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var gestureMode by remember { mutableStateOf<Int?>(null) }   // null / 0=seek / 1=brightness / 2=volume
    var gestureStartX by remember { mutableFloatStateOf(0f) }
    var gestureStartY by remember { mutableFloatStateOf(0f) }
    var gestureStartPos by remember { mutableFloatStateOf(0f) }
    var gesturePreview by remember { mutableStateOf("") }

    var controlsVisible by remember { mutableStateOf(true) }
    var panelMode by remember { mutableStateOf<String?>(null) }  // audio / sub / speed / episode / aspect
    // 解析失败（区别于 mpv 播放错误的 state.error）：必须显示出来，之前只打 logcat 用户什么都看不到
    var loadError by remember { mutableStateOf<String?>(null) }

    // ---- 内核初始化 + 加载（load 内部会等 surface 就绪）----
    LaunchedEffect(itemId) {
        engine.start()
        val r = repo.playable(source?.id ?: "", item, versionIndex)
        if (r.isSuccess) {
            loadError = null
            engine.load(r.getOrThrow())
        } else {
            val msg = r.exceptionOrNull()?.message ?: "未知错误"
            android.util.Log.e("Ember", "起播失败：$msg")
            loadError = msg
        }
    }

    // ---- 记忆播放：自动跳过片头（起播位置在片头范围内时一次性 seek）----
    var introSkipped by remember { mutableStateOf(false) }
    LaunchedEffect(state.loaded) {
        if (state.loaded && !introSkipped && settings.autoSkipIntro &&
            settings.introSkipSec > 0 && state.durationSec > 600 &&
            state.positionSec < settings.introSkipSec
        ) {
            introSkipped = true
            engine.seekTo(settings.introSkipSec.toDouble())
        }
    }

    // ---- 控制条自动隐藏（4 秒）----
    LaunchedEffect(controlsVisible, panelMode) {
        if (controlsVisible && panelMode == null) {
            delay(4000)
            if (panelMode == null) controlsVisible = false
        }
    }

    // ---- 进度定时上报（每 10 秒）----
    LaunchedEffect(itemId) {
        while (true) {
            delay(10_000)
            if (state.loaded && state.durationSec > 0) {
                repo.reportProgress(source?.id ?: "", item, state.positionSec.toLong(), state.durationSec.toLong())
            }
        }
    }

    // ---- 自动下一集 ----
    val episodes = if (item.kind == "episode" || item.kind == "series") item.episodes else emptyList()
    val currentEpIdx = episodes.indexOfFirst { it.id == item.id }
    LaunchedEffect(state.finished) {
        if (state.finished && settings.autoPlayNext) {
            val next = episodes.getOrNull(currentEpIdx + 1)
            if (next != null) {
                repo.markWatched(source?.id ?: "", item)
                val r = repo.playable(source?.id ?: "", next, versionIndex)
                if (r.isSuccess) engine.load(r.getOrThrow())
            }
        }
    }

    // ---- 退出时保存进度 ----
    DisposableEffect(Unit) {
        onDispose {
            val dur = state.durationSec.toLong()
            val pos = state.positionSec.toLong()
            if (dur > 0) {
                runCatching {
                    val sid = source?.id ?: ""
                    cn.plum.ember.data.App.db.saveProgress(
                        cn.plum.ember.data.PlayProgress(
                            sid, item.id, pos, dur, watched = pos >= dur * 0.95
                        )
                    )
                    cn.plum.ember.data.App.db.recordHistory(
                        sid, item.id, item.title, item.poster, pos, dur
                    )
                }
            }
            engine.destroy()
        }
    }

    // ---- 屏幕常亮 + 沉浸式 ----
    DisposableEffect(Unit) {
        val win = (view.context as? Activity)?.window
        win?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            win?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- 视频渲染层 ----
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { engine.attachSurface(h.surface) }
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, h2: Int) {
                            engine.attachSurface(h.surface)
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) { engine.attachSurface(null) }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ---- 加载 / 缓冲指示 ----
        if (state.buffering && !state.loaded && state.error == null && loadError == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Ember, modifier = Modifier.size(48.dp), strokeWidth = 3.dp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("正在缓冲…", color = TextMid, fontSize = 13.sp)
                }
            }
        }

        // ---- 中央播放/暂停反馈 ----
        AnimatedVisibility(
            visible = !state.buffering && state.loaded && !controlsVisible && !state.playing && state.error == null,
            enter = fadeIn(), exit = fadeOut()
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Pause, contentDescription = "已暂停",
                    tint = Color(0xAAFFFFFF), modifier = Modifier.size(80.dp))
            }
        }

        // ---- 手势预览 OSD（seek / 亮度 / 音量）----
        if (gesturePreview.isNotBlank()) {
            Box(
                Modifier.align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(gesturePreview, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }

        // ---- 解码信息浮层（设置开启时）----
        if (settings.showDecoderInfo && state.loaded) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 12.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text(
                    "${state.width}x${state.height}\n" +
                            "v: ${state.videoCodec ?: "-"}${if (state.hwdecActive) " (hw)" else " (sw)"}\n" +
                            "a: ${state.audioCodec ?: "-"}\n" +
                            "cache: ${state.cacheAheadSec.toInt()}s\n" +
                            "speed: ${state.speed}x",
                    color = Color(0xAAFFFFFF), fontSize = 10.sp, lineHeight = 14.sp
                )
            }
        }

        // ---- 跳过片头 / 跳过片尾 快捷卡（右下角，常显不随控制条隐藏）----
        val showSkipIntro = state.loaded && settings.introSkipSec > 0 && state.durationSec > 600 &&
                state.positionSec < settings.introSkipSec && state.error == null && loadError == null
        val remainSec = state.durationSec - state.positionSec
        val showSkipCredits = state.loaded && settings.creditsLenSec > 0 && state.durationSec > 600 &&
                remainSec in 1.0..settings.creditsLenSec.toDouble() &&
                state.error == null && loadError == null
        if (showSkipIntro || showSkipCredits) {
            Row(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = if (tv) 40.dp else 24.dp, bottom = if (tv) 120.dp else 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (showSkipIntro) {
                    SkipCard("跳过片头") {
                        introSkipped = true
                        engine.seekTo(settings.introSkipSec.toDouble())
                    }
                }
                if (showSkipCredits) {
                    val hasNext = episodes.isNotEmpty() && currentEpIdx in 0 until episodes.size - 1
                    SkipCard(if (hasNext) "跳过片尾 · 下一集" else "跳过片尾") {
                        val next = if (hasNext) episodes.getOrNull(currentEpIdx + 1) else null
                        if (next != null) {
                            scope.launch {
                                repo.markWatched(source?.id ?: "", item)
                                val r = repo.playable(source?.id ?: "", next, versionIndex)
                                if (r.isSuccess) engine.load(r.getOrThrow())
                            }
                        } else {
                            engine.seekTo((state.durationSec - 3.0).coerceAtLeast(0.0))
                        }
                    }
                }
            }
        }

        // ---- 错误页（解析失败 loadError / mpv 播放错误 state.error，保留重试 + 返回）----
        (state.error ?: loadError)?.let { err ->
            Box(Modifier.fillMaxSize().background(Color(0xEE000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text("⚠", color = Color(0xFFFF5555), fontSize = 60.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(err, color = Color.White, fontSize = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier
                                .background(Ember, RoundedCornerShape(32.dp))
                                .padding(horizontal = 30.dp, vertical = 12.dp)
                                .tvFocus(1.06f) {
                                    scope.launch {
                                        loadError = null
                                        val r = repo.playable(source?.id ?: "", item, versionIndex)
                                        if (r.isSuccess) {
                                            engine.load(r.getOrThrow(), resumeSec = state.positionSec.toLong())
                                        } else {
                                            loadError = r.exceptionOrNull()?.message ?: "未知错误"
                                        }
                                    }
                                }
                        ) { Text("重试", color = Color(0xFF1A0E04), fontWeight = FontWeight.SemiBold) }
                        Box(
                            Modifier
                                .background(Color(0x33FFFFFF), RoundedCornerShape(32.dp))
                                .padding(horizontal = 30.dp, vertical = 12.dp)
                                .tvFocus(1.06f) { onClose() }
                        ) { Text("返回", color = Color.White) }
                    }
                }
            }
        }

        // ---- 手势层（覆盖整个屏幕，在控制条之下）----
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(item.id) {
                    detectTapGestures(
                        onTap = {
                            if (panelMode != null) { panelMode = null; controlsVisible = true }
                            else controlsVisible = !controlsVisible
                        },
                        onDoubleTap = { engine.togglePlay() }
                    )
                }
                .pointerInput(item.id) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            gestureStartX = offset.x
                            gestureStartY = offset.y
                            gestureStartPos = state.positionSec.toFloat()
                            gestureMode = null
                        },
                        onDrag = { change, drag ->
                            val dx = drag.x
                            val dy = drag.y
                            if (gestureMode == null && (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12)) {
                                gestureMode = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) 0
                                else if (gestureStartX < size.width / 2f) 1 else 2
                            }
                            when (gestureMode) {
                                0 -> {
                                    val delta = dx * 0.25f
                                    val target = (gestureStartPos + delta).coerceIn(0f, state.durationSec.toFloat())
                                    gesturePreview = "${if (delta >= 0) "→" else "←"} ${Format.duration(target.toLong())}"
                                }
                                1 -> {
                                    val delta = -dy / (size.height * 0.7f)
                                    val nb = (brightness.floatValue + delta).coerceIn(0.02f, 1f)
                                    brightness.floatValue = nb
                                    setBrightness(view, nb)
                                    gesturePreview = "亮度 ${(nb * 100).toInt()}%"
                                }
                                2 -> {
                                    val delta = -dy / (size.height * 0.7f)
                                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    val nv = (cur + (delta * max).toInt()).coerceIn(0, max)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                                    volume.floatValue = if (max > 0) nv / max.toFloat() else 0f
                                    gesturePreview = "音量 ${(volume.floatValue * 100).toInt()}%"
                                }
                            }
                        },
                        onDragEnd = {
                            if (gestureMode == 0 && gesturePreview.isNotBlank()) {
                                val t = gesturePreview.removePrefix("→ ").removePrefix("← ")
                                val parts = t.split(":")
                                val sec = when (parts.size) {
                                    3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
                                    2 -> parts[0].toLong() * 60 + parts[1].toLong()
                                    else -> 0L
                                }
                                if (sec > 0) engine.seekTo(sec.toDouble())
                            }
                            gestureMode = null
                            gesturePreview = ""
                        }
                    )
                }
        )

        // ---- 控制条（上下渐变遮罩 + 控件）----
        AnimatedVisibility(
            visible = controlsVisible && state.error == null && loadError == null,
            enter = fadeIn(), exit = fadeOut()
        ) {
            Column(Modifier.fillMaxSize()) {
                // 顶部渐变 + 标题 + 工具按钮
                Box(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xE6000000), Color.Transparent)))
                        .padding(horizontal = if (tv) 24.dp else 12.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(item.title.ifBlank { "正在播放" },
                                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val meta = buildString {
                                if (state.width > 0) append("${state.width}×${state.height}  ")
                                append(if (state.hwdecActive) "硬解" else "软解")
                                if (state.hdrActive) append(" · HDR")
                                state.videoCodec?.let { append(" · ${it.uppercase()}") }
                            }
                            if (meta.isNotBlank()) {
                                Text(meta, color = Color(0xBBFFFFFF), fontSize = 11.sp)
                            }
                        }
                        IconButton(onClick = { panelMode = if (panelMode == "audio") null else "audio" }) {
                            Icon(Icons.Default.Tune, contentDescription = "音轨", tint = Color.White)
                        }
                        IconButton(onClick = { panelMode = if (panelMode == "sub") null else "sub" }) {
                            Icon(Icons.Default.Subtitles, contentDescription = "字幕", tint = Color.White)
                        }
                        IconButton(onClick = { panelMode = if (panelMode == "speed") null else "speed" }) {
                            Icon(Icons.Default.Speed, contentDescription = "倍速", tint = Color.White)
                        }
                        IconButton(onClick = { panelMode = if (panelMode == "aspect") null else "aspect" }) {
                            Icon(Icons.Default.AspectRatio, contentDescription = "画面比例", tint = Color.White)
                        }
                        if (episodes.isNotEmpty()) {
                            Box(
                                Modifier.tvFocus(1.05f) {
                                    panelMode = if (panelMode == "episode") null else "episode"
                                }
                                .padding(10.dp)
                            ) {
                                Text("选集", color = EmberSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // 底部渐变 + 进度 + 控制
                Box(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                        .padding(horizontal = if (tv) 24.dp else 16.dp, vertical = if (tv) 12.dp else 8.dp)
                ) {
                    Column {
                        var dragging by remember { mutableStateOf(false) }
                        var dragValue by remember { mutableFloatStateOf(0f) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                Format.duration(if (dragging) (dragValue * state.durationSec).toLong() else state.positionSec.toLong()),
                                color = Color.White, fontSize = 12.sp
                            )
                            Slider(
                                value = if (dragging) dragValue
                                else if (state.durationSec > 0)
                                    (state.positionSec / state.durationSec).toFloat().coerceIn(0f, 1f) else 0f,
                                onValueChange = { v ->
                                    dragging = true
                                    dragValue = v
                                },
                                onValueChangeFinished = {
                                    if (dragging) {
                                        engine.seekTo(dragValue * state.durationSec)
                                        dragging = false
                                    }
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = Ember, activeTrackColor = Ember,
                                    inactiveTrackColor = Color(0x55FFFFFF)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Text(Format.duration(state.durationSec.toLong()), color = Color.White, fontSize = 12.sp)
                        }

                        Row(
                            Modifier.fillMaxWidth().padding(vertical = if (tv) 10.dp else 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 上一集
                            if (episodes.isNotEmpty() && currentEpIdx > 0) {
                                IconButton(onClick = {
                                    episodes.getOrNull(currentEpIdx - 1)?.let { prev ->
                                        scope.launch {
                                            val r = repo.playable(source?.id ?: "", prev, versionIndex)
                                            if (r.isSuccess) engine.load(r.getOrThrow())
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.SkipNext, contentDescription = "上一集",
                                        tint = Color.White, modifier = Modifier.size(if (tv) 30.dp else 26.dp))
                                }
                            }
                            IconButton(onClick = { engine.seekBy(-settings.seekStep.toDouble()) }) {
                                Icon(Icons.Default.Replay10, contentDescription = "后退", tint = Color.White,
                                    modifier = Modifier.size(if (tv) 30.dp else 26.dp))
                            }
                            IconButton(onClick = { engine.togglePlay() }, modifier = Modifier.size(if (tv) 72.dp else 56.dp)) {
                                Icon(
                                    if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "播放/暂停", tint = Color.White,
                                    modifier = Modifier.size(if (tv) 54.dp else 42.dp)
                                )
                            }
                            IconButton(onClick = { engine.seekBy(settings.seekStep.toDouble()) }) {
                                Icon(Icons.Default.Forward10, contentDescription = "前进", tint = Color.White,
                                    modifier = Modifier.size(if (tv) 30.dp else 26.dp))
                            }
                            // 下一集
                            if (episodes.isNotEmpty() && currentEpIdx >= 0 && currentEpIdx < episodes.size - 1) {
                                IconButton(onClick = {
                                    episodes.getOrNull(currentEpIdx + 1)?.let { next ->
                                        scope.launch {
                                            repo.markWatched(source?.id ?: "", item)
                                            val r = repo.playable(source?.id ?: "", next, versionIndex)
                                            if (r.isSuccess) engine.load(r.getOrThrow())
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.SkipNext, contentDescription = "下一集",
                                        tint = Color.White, modifier = Modifier.size(if (tv) 30.dp else 26.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- 底部弹出面板：音轨 / 字幕 / 倍速 / 选集 / 画面比例 ----
        AnimatedVisibility(
            visible = panelMode != null,
            enter = fadeIn(), exit = fadeOut()
        ) {
            panelMode?.let { mode ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Column(
                        Modifier.fillMaxWidth().background(InkRaised.copy(alpha = 0.98f))
                            .padding(horizontal = if (tv) 28.dp else 16.dp, vertical = 16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (mode) {
                                    "audio" -> "音轨选择"
                                    "sub" -> "字幕选择"
                                    "speed" -> "播放倍速"
                                    "episode" -> "选集"
                                    else -> "画面比例"
                                },
                                color = TextHi, fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.weight(1f))
                            Text("关闭", color = TextMid, fontSize = 13.sp,
                                modifier = Modifier.padding(6.dp).tvFocus(1.05f) { panelMode = null })
                        }
                        Spacer(Modifier.height(10.dp))

                        when (mode) {
                            "audio" -> AudioSubList(state.audioTracks, state.currentAudio) { engine.setAudioTrack(it) }
                            "sub" -> {
                                TrackRow(TrackInfo(-1, "sub", title = "关闭字幕"), state.currentSub == -1) {
                                    engine.setSubtitleTrack(-1)
                                }
                                Spacer(Modifier.height(4.dp))
                                AudioSubList(state.subTracks, state.currentSub) { engine.setSubtitleTrack(it) }
                            }
                            "speed" -> SpeedList(state.speed) { engine.setSpeed(it) }
                            "episode" -> EpisodeGrid(episodes, currentEpIdx) { ep ->
                                scope.launch {
                                    repo.markWatched(source?.id ?: "", item)
                                    val r = repo.playable(source?.id ?: "", ep, versionIndex)
                                    if (r.isSuccess) engine.load(r.getOrThrow())
                                }
                                panelMode = null
                            }
                            "aspect" -> AspectList { engine.setAspect(it); panelMode = null }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkipCard(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xB3221C15))
            .tvFocus(1.06f, EmberSoft) { onClick() }
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AudioSubList(tracks: List<TrackInfo>, current: Int, onSelect: (Int) -> Unit) {
    if (tracks.isEmpty()) {
        Text("暂无可选轨道", color = TextLow, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    LazyColumn(Modifier.fillMaxHeight(0.5f)) {
        items(tracks, key = { it.id }) { t ->
            TrackRow(t, current == t.id) { onSelect(t.id) }
        }
    }
}

@Composable
private fun SpeedList(current: Float, onSelect: (Float) -> Unit) {
    LazyColumn(Modifier.fillMaxHeight(0.5f)) {
        items(Format.speedTable(), key = { it.toString() }) { s ->
            Row(
                Modifier.fillMaxWidth().tvFocus(1.02f) { onSelect(s) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${s}x", color = if (current == s) Ember else TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                if (current == s) Text("✓", color = Ember, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun AspectList(onSelect: (String) -> Unit) {
    val modes = listOf("origin" to "原始比例", "16:9" to "16:9", "4:3" to "4:3", "2.35:1" to "影院 2.35:1")
    LazyColumn(Modifier.fillMaxHeight(0.5f)) {
        items(modes, key = { it.first }) { (k, label) ->
            Row(
                Modifier.fillMaxWidth().tvFocus(1.02f) { onSelect(k) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = TextHi, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun EpisodeGrid(episodes: List<MediaItem>, currentIdx: Int, onSelect: (MediaItem) -> Unit) {
    LazyColumn(Modifier.fillMaxHeight(0.6f)) {
        items(episodes, key = { it.id }) { ep ->
            val idx = episodes.indexOf(ep)
            Row(
                Modifier.fillMaxWidth().tvFocus(1.02f) { onSelect(ep) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    ep.subtitle ?: ep.title,
                    color = if (idx == currentIdx) Ember else TextHi,
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                if (ep.resumePercent in 1..99) {
                    Text("${ep.resumePercent}%", color = TextMid, fontSize = 12.sp)
                } else if (idx == currentIdx) {
                    Text("▶", color = Ember, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun TrackRow(t: TrackInfo, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .tvFocus(1.02f) { onSelect() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            t.display, color = if (selected) Ember else TextHi,
            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.weight(1f))
        if (selected) Text("✓", color = Ember, fontSize = 15.sp)
    }
}

private fun getVolume(ctx: Context): Float {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    return if (max > 0) am.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat() else 0f
}

private fun getBrightness(view: android.view.View): Float {
    val win = (view.context as? Activity)?.window ?: return 0.5f
    val b = win.attributes.screenBrightness
    return if (b >= 0) b.toFloat() else 0.5f
}

private fun setBrightness(view: android.view.View, value: Float) {
    val win = (view.context as? Activity)?.window ?: return
    win.attributes = win.attributes.apply { screenBrightness = value }
}
