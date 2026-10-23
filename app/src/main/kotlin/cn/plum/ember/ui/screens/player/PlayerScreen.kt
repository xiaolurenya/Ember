package cn.plum.ember.ui.screens.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
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
import cn.plum.ember.ui.theme.Scrim
import cn.plum.ember.ui.theme.TextHi
import cn.plum.ember.ui.theme.TextMid
import cn.plum.ember.ui.widgets.isTv
import cn.plum.ember.ui.widgets.tvFocus
import cn.plum.ember.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全屏播放器。
 *
 * 手势（全部在此页内实现，不依赖第三方手势库）：
 *   单击 = 呼出/隐藏控制条（3 秒无操作自动隐藏）
 *   双击 = 播放/暂停
 *   水平拖动 = seek（带预览时间气泡）
 *   左半屏上下滑 = 亮度 ±（带百分比 OSD）
 *   右半屏上下滑 = 音量 ±（带百分比 OSD）
 *   返回 = 退出播放（自动保存进度）
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
    val settings = remember { App.prefs.snapshotBlocking(context) }

    val engine = remember { MpvEngine(context.applicationContext, settings) }
    val state by engine.state.collectAsState()
    val brightness = remember { mutableFloatStateOf(0.5f) }
    val volume = remember { mutableFloatStateOf(getVolume(context)) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var gestureMode by remember { mutableStateOf<Int?>(null) }   // null / 0=seek / 1=brightness / 2=volume
    var gestureStartX by remember { mutableFloatStateOf(0f) }
    var gestureStartY by remember { mutableFloatStateOf(0f) }
    var gestureStartPos by remember { mutableFloatStateOf(0f) }
    var gesturePreview by remember { mutableStateOf("") }

    var controlsVisible by remember { mutableStateOf(true) }
    var panelMode by remember { mutableStateOf<String?>(null) }  // audio / sub / speed / aspect / info

    // ---- 内核初始化 + 加载 ----
    LaunchedEffect(item.id) {
        engine.start()
        val r = repo.playable(source?.id ?: "", item, versionIndex)
        if (r.isSuccess) {
            engine.load(r.getOrThrow())
        } else {
            // 起播失败也显示错误页
            android.util.Log.e("Ember", "起播失败：${r.exceptionOrNull()?.message}")
        }
    }

    // ---- 控制条自动隐藏（3 秒）----
    LaunchedEffect(controlsVisible, state.positionSec) {
        if (controlsVisible && panelMode == null) {
            delay(3000)
            controlsVisible = false
        }
    }

    // ---- 进度定时上报（每 10 秒）----
    LaunchedEffect(item.id) {
        while (true) {
            delay(10_000)
            if (state.loaded && state.durationSec > 0) {
                repo.reportProgress(source?.id ?: "", item, state.positionSec.toLong(), state.durationSec.toLong())
            }
        }
    }

    // ---- 自动下一集 ----
    val episodes = if (item.kind == "episode") item.episodes else emptyList()
    val currentEpIdx = episodes.indexOfFirst { it.id == item.id }
    LaunchedEffect(state.finished, state.durationSec) {
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
            if (state.durationSec > 0) {
                repo.reportProgress(source?.id ?: "", item, state.positionSec.toLong(), state.durationSec.toLong())
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

        // ---- 加载中指示 ----
        if (state.buffering && !state.loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = Ember, modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("正在加载…", color = TextMid, fontSize = 13.sp)
                }
            }
        }

        // ---- 错误页（保留重试 + 返回）----
        state.error?.let { err ->
            Box(Modifier.fillMaxSize().background(Color(0xEE000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)) {
                    Text("⚠", color = Color(0xFFFF4444), fontSize = 60.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(err, color = Color.White, fontSize = 16.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier
                                .background(Ember, RoundedCornerShape(32.dp))
                                .padding(horizontal = 30.dp, vertical = 12.dp)
                                .tvFocus(1.06f) {
                                    scope.launch {
                                        val r = repo.playable(source?.id ?: "", item, versionIndex)
                                        if (r.isSuccess) engine.load(r.getOrThrow(), resumeSec = state.positionSec.toLong())
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
                            // 起手方向决定模式：先假设是水平 seek
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
                                    // 水平：seek（每 px = 0.25 秒）
                                    val delta = dx * 0.25f
                                    val target = (gestureStartPos + delta)
                                        .coerceIn(0f, state.durationSec.toFloat())
                                    gesturePreview = "${if (delta >= 0) "→" else "←"} ${Format.duration(target.toLong())}"
                                }
                                1 -> {
                                    // 左半屏：亮度
                                    val delta = -dy / (size.height * 0.7f)
                                    val nb = (brightness.floatValue + delta).coerceIn(0.02f, 1f)
                                    brightness.floatValue = nb
                                    (view.context as? Activity)?.window?.attributes =
                                        (view.context as? Activity)?.window?.attributes?.apply {
                                            screenBrightness = nb
                                        }
                                    gesturePreview = "亮度 ${(nb * 100).toInt()}%"
                                }
                                2 -> {
                                    // 右半屏：音量
                                    val delta = -dy / (size.height * 0.7f)
                                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    val nv = (cur + (delta * max).toInt()).coerceIn(0, max)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                                    volume.floatValue = nv / max.toFloat()
                                    gesturePreview = "音量 ${(volume.floatValue * 100).toInt()}%"
                                }
                            }
                        },
                        onDragEnd = {
                            // 松手：把 seek 落下去
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

        // ---- 手势预览 OSD ----
        if (gesturePreview.isNotBlank()) {
            Box(
                Modifier.align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(gesturePreview, color = Color.White, fontSize = 15.sp)
            }
        }

        // ---- 中央播放/暂停反馈 ----
        if (!state.buffering && state.loaded && !controlsVisible && !state.playing && state.error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Pause, contentDescription = "已暂停",
                    tint = Color(0x88FFFFFF), modifier = Modifier.size(80.dp))
            }
        }

        // ---- 控制条 ----
        if (controlsVisible && state.error == null) {
            Column(
                Modifier.fillMaxSize().background(Scrim)
            ) {
                // 顶部
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = Color.White, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (state.width > 0) {
                            Text(
                                "${state.width}×${state.height} · " +
                                        (if (state.hwdecActive) "硬解" else "软解") +
                                        (if (state.hdrActive) " · HDR" else "") +
                                        " · ${Format.bitrate(state.bitrate)}",
                                color = Color(0x99FFFFFF), fontSize = 11.sp
                            )
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
                }

                Spacer(Modifier.weight(1f))

                // 底部：进度条 + 控制按钮
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    // 进度条：拖动中只更新本地预览，松手才真正 seek（避免拖动过程狂发 seek）
                    var dragging by remember { mutableStateOf(false) }
                    var dragValue by remember { mutableFloatStateOf(0f) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            Format.duration(if (dragging) (dragValue * state.durationSec).toLong() else state.positionSec.toLong()),
                            color = Color.White, fontSize = 11.sp
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
                        Text(Format.duration(state.durationSec.toLong()), color = Color.White, fontSize = 11.sp)
                    }
                    // 主控制按钮行
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
                                    tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                        IconButton(onClick = { engine.seekBy(-settings.seekStep.toDouble()) }) {
                            Icon(Icons.Default.Replay10, contentDescription = "后退", tint = Color.White)
                        }
                        // 播放/暂停
                        IconButton(onClick = { engine.togglePlay() }, modifier = Modifier.size(60.dp)) {
                            Icon(
                                if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "播放/暂停",
                                tint = Color.White,
                                modifier = Modifier.size(46.dp)
                            )
                        }
                        IconButton(onClick = { engine.seekBy(settings.seekStep.toDouble()) }) {
                            Icon(Icons.Default.Forward10, contentDescription = "前进", tint = Color.White)
                        }
                        // 下一集
                        if (episodes.isNotEmpty() && currentEpIdx < episodes.size - 1) {
                            IconButton(onClick = {
                                episodes.getOrNull(currentEpIdx + 1)?.let { next ->
                                    scope.launch {
                                        repo.markWatched(source?.id ?: "", item)
                                        val r = repo.playable(source?.id ?: "", next, versionIndex)
                                        if (r.isSuccess) engine.load(r.getOrThrow())
                                    }
                                }
                            }) {
                                Icon(Icons.Default.SkipNext, contentDescription = "下一集", tint = Color.White)
                            }
                        }
                    }
                }
            }

            // ---- 底部弹出面板：音轨 / 字幕 / 倍速 ----
            panelMode?.let { mode ->
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(
                        Modifier.fillMaxWidth().background(Color(0xF212100E))
                            .padding(20.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (mode) {
                                    "audio" -> "音轨选择"
                                    "sub" -> "字幕选择"
                                    "speed" -> "播放倍速"
                                    else -> "画面比例"
                                },
                                color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.weight(1f))
                            Text("关闭", color = TextMid, fontSize = 13.sp,
                                modifier = Modifier.padding(6.dp).tvFocus(1.05f) { panelMode = null })
                        }
                        Spacer(Modifier.height(10.dp))

                        LazyColumn(Modifier.height(260.dp)) {
                            when (mode) {
                                "audio" -> {
                                    items(state.audioTracks, key = { it.id }) { t ->
                                        TrackRow(t, state.currentAudio == t.id) { engine.setAudioTrack(t.id) }
                                    }
                                }
                                "sub" -> {
                                    // 「关闭字幕」放在最上面
                                    item {
                                        TrackRow(
                                            TrackInfo(-1, "sub", title = "关闭字幕"),
                                            state.currentSub == -1
                                        ) { engine.setSubtitleTrack(-1) }
                                    }
                                    items(state.subTracks, key = { it.id }) { t ->
                                        TrackRow(t, state.currentSub == t.id) { engine.setSubtitleTrack(t.id) }
                                    }
                                }
                                "speed" -> {
                                    items(cn.plum.ember.util.Format.speedTable(), key = { it.toString() }) { s ->
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .tvFocus(1.02f) { engine.setSpeed(s) }
                                                .padding(vertical = 12.dp, horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "${s}x", color = if (state.speed == s) Ember else TextHi,
                                                fontSize = 15.sp, fontWeight = FontWeight.Medium
                                            )
                                            Spacer(Modifier.weight(1f))
                                            if (state.speed == s) Text("✓", color = Ember, fontSize = 15.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- 解码信息浮层（长按/设置开启显示）----
        if (settings.showDecoderInfo && state.loaded) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(12.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text(
                    "${state.width}x${state.height}\n" +
                            "v: ${state.videoCodec ?: "-"} ${if (state.hwdecActive) "(hw)" else "(sw)"}\n" +
                            "a: ${state.audioCodec ?: "-"}\n" +
                            "cache: ${state.cacheAheadSec.toInt()}s\n" +
                            "speed: ${state.speed}x",
                    color = Color(0xAAFFFFFF), fontSize = 10.sp, lineHeight = 14.sp
                )
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
    return am.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
}

/** 收集 StateFlow 为 Compose State（收集到当前组合生命周期内） */

/** 收集 StateFlow 为 Compose State（收集到当前组合生命周期内） */

private fun cn.plum.ember.data.Prefs.snapshotBlocking(ctx: Context): cn.plum.ember.data.PlayerSettings {
    var s = cn.plum.ember.data.PlayerSettings()
    kotlinx.coroutines.runBlocking { s = snapshot() }
    return s
}
