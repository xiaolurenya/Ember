package cn.plum.ember.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import cn.plum.ember.data.LibraryRepo
import cn.plum.ember.data.MediaItem
import cn.plum.ember.data.MediaSource
import cn.plum.ember.ui.theme.Ember
import cn.plum.ember.ui.theme.Ink
import cn.plum.ember.ui.theme.InkRaised
import cn.plum.ember.ui.theme.Scrim
import cn.plum.ember.ui.theme.TextHi
import cn.plum.ember.ui.theme.TextLow
import cn.plum.ember.ui.theme.TextMid
import cn.plum.ember.ui.widgets.ErrorState
import cn.plum.ember.ui.widgets.PosterCard
import cn.plum.ember.ui.widgets.ShelfTitle
import cn.plum.ember.ui.widgets.TagChip
import cn.plum.ember.ui.widgets.isTv
import cn.plum.ember.ui.widgets.tvFocus
import cn.plum.ember.util.Format
import kotlinx.coroutines.launch

/**
 * 详情页：顶部背景海报（模糊放大）+ 元数据 + 主操作 + 版本/剧集切换 + 相关推荐。
 * 有续播时主按钮显示「继续播放 · 已看 N%」。
 */
@Composable
fun DetailScreen(
    repo: LibraryRepo,
    source: MediaSource?,
    itemId: String,
    onBack: () -> Unit,
    onPlay: (MediaItem, versionIndex: Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf<MediaItem?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf(0) }
    var fav by remember { mutableStateOf(false) }
    var playingEp by remember { mutableStateOf<MediaItem?>(null) }

    LaunchedEffect(itemId) {
        if (source == null) { error = "未选择媒体源"; loading = false; return@LaunchedEffect }
        try {
            val d = repo.detail(source.id, itemId)
            item = d
            fav = d != null && repo.isFavorite(source.id, d.id)
            if (d == null) error = "找不到该条目"
        } catch (t: Throwable) {
            error = t.message ?: "加载失败"
        }
        loading = false
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Ember)
            }
            error != null -> ErrorState(error ?: "加载失败", onRetry = { loading = true })
            item != null -> DetailContent(
                repo = repo, source = source, item = item!!,
                versionIndex = version,
                favorite = fav,
                playingEp = playingEp,
                onVersionChange = { version = it },
                onFavToggle = {
                    if (source != null && item != null) {
                        fav = repo.toggleFavorite(source.id, item!!)
                    }
                },
                onBack = onBack,
                onPlay = { target -> onPlay(target, version) },
                onSelectEp = { playingEp = it }
            )
        }
    }
}

@Composable
private fun DetailContent(
    repo: LibraryRepo,
    source: MediaSource?,
    item: MediaItem,
    versionIndex: Int,
    favorite: Boolean,
    playingEp: MediaItem?,
    onVersionChange: (Int) -> Unit,
    onFavToggle: () -> Unit,
    onBack: () -> Unit,
    onPlay: (MediaItem) -> Unit,
    onSelectEp: (MediaItem?) -> Unit
) {
    val tv = isTv()
    val scope = rememberCoroutineScope()
    var related by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    LaunchedEffect(item.id) {
        if (source != null) {
            try { related = repo.related(source.id, item) } catch (_: Throwable) {}
        }
    }

    val target = playingEp ?: item
    val resumeSec = if (playingEp != null) {
        // 剧集单集的进度：查本地
        source?.id?.let { repo.let { App_db(it, target) } } ?: 0L
    } else item.positionSec

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            // ---- 背景海报 ----
            item {
                Box(Modifier.fillMaxWidth().height(if (tv) 380.dp else 300.dp)) {
                    if (!item.backdrop.isNullOrBlank() || !item.poster.isNullOrBlank()) {
                        AsyncImage(
                            model = item.backdrop ?: item.poster,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().blur(if (tv) 18.dp else 14.dp)
                        )
                    }
                    Box(Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color(0x66000000), Scrim, Ink),
                            startY = 0f, endY = 1000f
                        )
                    ))
                    // 返回按钮
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextHi)
                    }
                }
            }

            // ---- 元数据区 ----
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    // 海报 + 标题并排
                    Row {
                        Box(
                            Modifier
                                .width(if (tv) 140.dp else 110.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(InkRaised)
                        ) {
                            if (!item.poster.isNullOrBlank()) {
                                AsyncImage(
                                    model = item.poster,
                                    contentDescription = item.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, color = TextHi, fontSize = if (tv) 24.sp else 20.sp,
                                fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            item.subtitle?.let {
                                Spacer(Modifier.height(3.dp))
                                Text(it, color = TextMid, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                item.year?.let { Text("$it", color = TextMid, fontSize = 13.sp) }
                                if (item.rating != null && item.rating > 0) {
                                    Text("★ ${String.format("%.1f", item.rating)}",
                                        color = Ember, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                if (item.durationSec > 0) {
                                    Text(Format.duration(item.durationSec), color = TextMid, fontSize = 13.sp)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (item.tag4k) TagChip("4K")
                                if (item.tagHdr) TagChip("HDR")
                                if (item.tagDolby) TagChip("杜比")
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // ---- 主操作：通栏橙色大按钮 ----
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(if (tv) 62.dp else 54.dp)
                            .clip(RoundedCornerShape((if (tv) 31 else 27).dp))
                            .background(Ember)
                            .tvFocus(1.02f, EmberSoft) { onPlay(target) },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null,
                                tint = Color(0xFF1A0E04), modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when {
                                    resumeSec > 5 -> "继续播放 · 已看 ${Format.percent(resumeSec, maxOf(1, item.durationSec))}%"
                                    else -> "播放"
                                },
                                color = Color(0xFF1A0E04),
                                fontSize = if (tv) 18.sp else 17.sp,
                                fontWeight = FontWeight.W800
                            )
                        }
                    }
                    if (resumeSec > 5) {
                        Text(
                            "从头看",
                            color = TextMid, fontSize = 13.sp,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .tvFocus(1.05f) { onPlay(item.copy(positionSec = 0)) }
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(InkRaised)
                                .tvFocus(1.03f) { onFavToggle() }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "收藏",
                                tint = if (favorite) Ember else TextMid,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (favorite) "已收藏" else "收藏", color = TextMid, fontSize = 13.sp)
                        }
                    }

                    // ---- 简介 ----
                    item.overview?.let { o ->
                        Spacer(Modifier.height(16.dp))
                        Text("简介", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(o, color = TextMid, fontSize = 13.sp, lineHeight = 20.sp)
                    }

                    // ---- 类型标签 ----
                    if (item.genres.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item.genres.take(5).forEach { g ->
                                Text(
                                    g, color = TextMid, fontSize = 11.sp,
                                    modifier = Modifier
                                        .background(InkRaised, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ---- 版本选择 ----
            if (item.versions.size > 1) {
                item {
                    ShelfTitle("播放版本")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(item.versions, key = { it.index }) { v ->
                            FilterChip(
                                selected = versionIndex == v.index,
                                onClick = { onVersionChange(v.index) },
                                label = { Text(v.label, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }

            // ---- 剧集列表 ----
            if (item.episodes.isNotEmpty()) {
                item {
                    ShelfTitle("剧集", "共 ${item.episodes.size} 集")
                    Column(Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.episodes.forEach { ep ->
                            EpisodeRow(ep, onSelectEp = {
                                onSelectEp(ep)
                                onPlay(ep)
                            })
                        }
                    }
                }
            }

            // ---- 相关推荐 ----
            if (related.isNotEmpty()) {
                item {
                    ShelfTitle("相关推荐")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(related, key = { "${it.sourceId}-${it.id}" }) { m ->
                            PosterCard(item = m, onClick = { /* TODO 切换详情 */ }, modifier = Modifier.width(120.dp))
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun EpisodeRow(ep: MediaItem, onSelectEp: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised)
            .tvFocus(1.02f) { onSelectEp() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 缩略图
        Box(
            Modifier
                .width(120.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Ink)
        ) {
            if (!ep.backdrop.isNullOrBlank() || !ep.poster.isNullOrBlank()) {
                AsyncImage(
                    model = ep.backdrop ?: ep.poster,
                    contentDescription = ep.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (ep.resumePercent in 1..99) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
                    .background(Color(0x66000000))) {
                    Box(Modifier.fillMaxWidth(ep.resumePercent / 100f).fillMaxHeight().background(Ember))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ep.title, color = TextHi, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            ep.subtitle?.let { Text(it, color = TextLow, fontSize = 11.sp) }
            if (ep.durationSec > 0) {
                Text(Format.duration(ep.durationSec), color = TextLow, fontSize = 11.sp)
            }
        }
    }
}

private fun App_db(sourceId: String, item: MediaItem): Long =
    cn.plum.ember.data.App.db.progressOf(sourceId, item.id)?.positionSec ?: 0L
