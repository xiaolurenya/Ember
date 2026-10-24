package cn.plum.ember.ui.screens.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import cn.plum.ember.data.App
import cn.plum.ember.data.Chapter
import cn.plum.ember.data.LibraryRepo
import cn.plum.ember.data.MediaItem
import cn.plum.ember.data.MediaSource
import cn.plum.ember.data.Person
import cn.plum.ember.ui.theme.Ember
import cn.plum.ember.ui.theme.EmberSoft
import cn.plum.ember.ui.theme.Ink
import cn.plum.ember.ui.theme.InkRaised
import cn.plum.ember.ui.theme.Scrim
import cn.plum.ember.ui.theme.TextHi
import cn.plum.ember.ui.theme.TextLow
import cn.plum.ember.ui.theme.TextMid
import cn.plum.ember.ui.widgets.ErrorState
import cn.plum.ember.ui.widgets.ShelfTitle
import cn.plum.ember.ui.widgets.TagChip
import cn.plum.ember.ui.widgets.isTv
import cn.plum.ember.ui.widgets.tvFocus
import cn.plum.ember.util.Format
import kotlinx.coroutines.launch

/** 详情页金色主按钮（Emby 风金色 CTA，区别于播放器的炭火橙） */
private val Gold = Color(0xFFE2A63D)
private val GoldText = Color(0xFF241505)
/** 磨砂半透明暖棕面板（右侧媒体规格悬浮卡） */
private val Frosted = Color(0xB3221C15)

/**
 * 详情页（TV 横屏 16:9 优先，手机自适应）：
 * 顶部全幅背景横幅（片名/原名/评分/日期/片长/分级/类型/简介/右侧磨砂规格面板/
 * 金色播放 + 已看勾选 + 收藏爱心 + 更多菜单）
 * → 章节 → 演职人员 → 相册 → 更多类似 → 工作室/外部链接。
 * 有续播时主按钮显示「继续播放 · 已看 N%」（记忆播放）。
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
    var watched by remember { mutableStateOf(false) }
    var playingEp by remember { mutableStateOf<MediaItem?>(null) }

    LaunchedEffect(itemId) {
        if (source == null) { error = "未选择媒体源"; loading = false; return@LaunchedEffect }
        try {
            val d = repo.detail(source.id, itemId)
            item = d
            if (d != null) {
                fav = repo.isFavorite(source.id, d.id)
                watched = d.watched
            } else error = "找不到该条目"
        } catch (t: Throwable) {
            error = cn.plum.ember.core.net.Http.describe(t)
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
                favorite = fav, watched = watched,
                playingEp = playingEp,
                onVersionChange = { version = it },
                onFavToggle = {
                    if (source != null && item != null) {
                        fav = repo.toggleFavorite(source.id, item!!)
                    }
                },
                onWatchedToggle = {
                    if (source != null && item != null) {
                        val cur = item!!
                        watched = !watched
                        scope.launch {
                            if (watched) repo.markWatched(source.id, cur)
                            else repo.markUnwatched(source.id, cur)
                        }
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
    watched: Boolean,
    playingEp: MediaItem?,
    onVersionChange: (Int) -> Unit,
    onFavToggle: () -> Unit,
    onWatchedToggle: () -> Unit,
    onBack: () -> Unit,
    onPlay: (MediaItem) -> Unit,
    onSelectEp: (MediaItem?) -> Unit
) {
    val tv = isTv()
    val scope = rememberCoroutineScope()
    var related by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        if (source != null) {
            try { related = repo.related(source.id, item) } catch (_: Throwable) {}
        }
    }

    val target = playingEp ?: item
    val resumeSec = if (playingEp != null) {
        source?.id?.let { sid -> localResumeSec(sid, target) } ?: 0L
    } else item.positionSec

    LazyColumn(Modifier.fillMaxSize()) {
        // ================= 顶部全幅横幅 =================
        item {
            BannerSection(
                item = item, tv = tv, resumeSec = resumeSec,
                favorite = favorite, watched = watched,
                menuOpen = menuOpen, onMenuToggle = { menuOpen = !menuOpen },
                onMenuClose = { menuOpen = false },
                onFavToggle = onFavToggle, onWatchedToggle = onWatchedToggle,
                onBack = onBack,
                onPlay = { onPlay(target.copy(positionSec = resumeSec)) },
                onPlayFromStart = { onPlay(target.copy(positionSec = 0)) }
            )
        }

        // ================= 版本选择 =================
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

        // ================= 剧集 =================
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

        // ================= 章节 =================
        if (item.chapters.isNotEmpty()) {
            item {
                ShelfTitle("章节")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(item.chapters.size) { i ->
                        val ch = item.chapters[i]
                        ChapterCard(index = i + 1, chapter = ch) {
                            onPlay(target.copy(positionSec = ch.startSec))
                        }
                    }
                }
            }
        }

        // ================= 演职人员 =================
        if (item.people.isNotEmpty()) {
            item {
                ShelfTitle("演职人员")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(item.people, key = { it.name + (it.role ?: "") }) { p ->
                        PersonCard(p, tv)
                    }
                }
            }
        }

        // ================= 相册 =================
        if (item.stills.isNotEmpty()) {
            item {
                ShelfTitle("相册")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(item.stills.size) { i ->
                        StillCard(item.stills[i], tv)
                    }
                }
            }
        }

        // ================= 更多类似 =================
        if (related.isNotEmpty()) {
            item {
                ShelfTitle("更多类似")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(related, key = { "${it.sourceId}-${it.id}" }) { m ->
                        SimilarCard(m, tv)
                    }
                }
            }
        }

        // ================= 工作室 + 外部链接 =================
        if (item.studios.isNotEmpty() || item.providerImdb != null || item.providerTmdb != null) {
            item {
                FooterSection(item)
            }
        }

        item { Spacer(Modifier.height(40.dp)) }
    }
}

// ================= 顶部横幅 =================
@Composable
private fun BannerSection(
    item: MediaItem,
    tv: Boolean,
    resumeSec: Long,
    favorite: Boolean,
    watched: Boolean,
    menuOpen: Boolean,
    onMenuToggle: () -> Unit,
    onMenuClose: () -> Unit,
    onFavToggle: () -> Unit,
    onWatchedToggle: () -> Unit,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(if (tv) 480.dp else 380.dp)) {
        // 全幅背景海报
        if (!item.backdrop.isNullOrBlank() || !item.poster.isNullOrBlank()) {
            AsyncImage(
                model = item.backdrop ?: item.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(if (tv) 10.dp else 8.dp)
            )
        }
        // 底部渐变压到暖棕背景色
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Color(0x26000000), Scrim, Ink),
                startY = 0f, endY = 1200f
            )
        ))
        // 返回按钮
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextHi)
        }

        // 宽屏（TV 横屏）才有右侧磨砂规格面板
        val wide = maxWidth >= 760.dp

        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .padding(horizontal = if (tv) 28.dp else 20.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // ---- 左列：信息 + 操作 ----
            Column(Modifier.weight(1f)) {
                Text(
                    item.title, color = TextHi,
                    fontSize = if (tv) 34.sp else 26.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                item.originalTitle?.takeIf { it.isNotBlank() && it != item.title }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = TextMid, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                // 评分 / 日期 / 片长 / 分级 / 类型
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (item.rating != null && item.rating > 0) {
                        Text("★ ${String.format("%.1f", item.rating)}",
                            color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    item.premiereDate?.let { Text(it, color = TextMid, fontSize = 13.sp) }
                        ?: item.year?.let { Text("$it", color = TextMid, fontSize = 13.sp) }
                    if (item.durationSec > 0) {
                        Text(Format.duration(item.durationSec), color = TextMid, fontSize = 13.sp)
                    }
                    item.officialRating?.takeIf { it.isNotBlank() }?.let { r ->
                        Text(r, color = TextMid, fontSize = 11.sp,
                            modifier = Modifier
                                .background(Color(0x33FFFFFF), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                if (item.genres.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.genres.take(4).forEach { g -> TagChip(g) }
                    }
                }
                item.overview?.let { o ->
                    Spacer(Modifier.height(10.dp))
                    Text(o, color = TextMid, fontSize = 13.sp, lineHeight = 19.sp,
                        maxLines = if (tv) 4 else 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(16.dp))
                // ---- 操作排：金色播放 + 勾选 + 爱心 + 更多 ----
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 主按钮：金色圆角播放（记忆播放：显示续播百分比）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(28.dp))
                            .background(Gold)
                            .tvFocus(1.04f, EmberSoft) { onPlay() }
                            .padding(horizontal = 30.dp, vertical = if (tv) 14.dp else 12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null,
                            tint = GoldText, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when {
                                resumeSec > 5 -> "继续播放 · 已看 ${Format.percent(resumeSec, maxOf(1, item.durationSec))}%"
                                else -> "播放"
                            },
                            color = GoldText,
                            fontSize = if (tv) 17.sp else 16.sp,
                            fontWeight = FontWeight.W800
                        )
                    }
                    // 已看勾选
                    RoundAction(checked = watched, onClick = onWatchedToggle) {
                        Icon(Icons.Default.Check, contentDescription = "已看",
                            tint = if (watched) Ember else TextMid, modifier = Modifier.size(20.dp))
                    }
                    // 收藏爱心
                    RoundAction(checked = favorite, onClick = onFavToggle) {
                        Icon(
                            if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "收藏",
                            tint = if (favorite) Ember else TextMid, modifier = Modifier.size(20.dp)
                        )
                    }
                    // 更多菜单（从头看 / 标记切换）
                    Box {
                        RoundAction(checked = menuOpen, onClick = onMenuToggle) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多",
                                tint = if (menuOpen) TextHi else TextMid, modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = onMenuClose,
                            containerColor = InkRaised
                        ) {
                            DropdownMenuItem(
                                text = { Text("从头看", color = TextHi, fontSize = 14.sp) },
                                onClick = { onMenuClose(); onPlayFromStart() }
                            )
                            DropdownMenuItem(
                                text = { Text(if (watched) "标记未看" else "标记已看", color = TextHi, fontSize = 14.sp) },
                                onClick = { onMenuClose(); onWatchedToggle() }
                            )
                        }
                    }
                }
                if (resumeSec > 5) {
                    Text(
                        "从头看",
                        color = TextMid, fontSize = 12.sp,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .tvFocus(1.05f) { onPlayFromStart() }
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                }
            }

            // ---- 右侧：磨砂媒体规格面板 ----
            if (wide && item.mediaSpec.isNotEmpty()) {
                Column(
                    Modifier
                        .width(if (tv) 260.dp else 230.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Frosted)
                        .padding(16.dp)
                ) {
                    Text("媒体规格", color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    item.mediaSpec.forEach { line ->
                        Text(line, color = TextMid, fontSize = 11.sp, lineHeight = 17.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

/** 圆形操作按钮（勾选/爱心/更多） */
@Composable
private fun RoundAction(
    checked: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val tv = isTv()
    Box(
        Modifier
            .size(if (tv) 46.dp else 42.dp)
            .clip(CircleShape)
            .background(if (checked) InkRaised else Color(0x33FFFFFF))
            .tvFocus(1.08f, EmberSoft) { onClick() },
        contentAlignment = Alignment.Center
    ) { content() }
}

/** 章节卡片：书本图标 + 序号（点卡片从该章节起播） */
@Composable
private fun ChapterCard(index: Int, chapter: Chapter, onClick: () -> Unit) {
    Column(
        Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised)
            .tvFocus(1.05f, EmberSoft) { onClick() }
            .padding(12.dp)
    ) {
        Icon(Icons.Default.MenuBook, contentDescription = null, tint = Gold, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(6.dp))
        Text("%02d".format(index), color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            chapter.name, color = TextMid, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** 演职人员：圆形头像 + 名字 + 角色 */
@Composable
private fun PersonCard(p: Person, tv: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(if (tv) 96.dp else 84.dp)) {
        Box(
            Modifier
                .size(if (tv) 76.dp else 64.dp)
                .clip(CircleShape)
                .background(InkRaised)
        ) {
            if (!p.image.isNullOrBlank()) {
                AsyncImage(
                    model = p.image, contentDescription = p.name,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        p.name.take(1), color = TextMid,
                        fontSize = 22.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(p.name, color = TextHi, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        p.role?.let {
            Text(it, color = TextLow, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 剧照卡片 */
@Composable
private fun StillCard(url: String, tv: Boolean) {
    Box(
        Modifier
            .width(if (tv) 240.dp else 200.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(InkRaised)
    ) {
        AsyncImage(
            model = url, contentDescription = "剧照",
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
        )
    }
}

/** 更多类似：封面 + 片名 + 年份 */
@Composable
private fun SimilarCard(item: MediaItem, tv: Boolean) {
    Column(Modifier.width(if (tv) 150.dp else 128.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(InkRaised)
                .tvFocus(1.04f, EmberSoft) { }
        ) {
            if (!item.poster.isNullOrBlank()) {
                AsyncImage(
                    model = item.poster, contentDescription = item.title,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, color = TextHi, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.year?.let { Text("$it", color = TextLow, fontSize = 10.sp) }
    }
}

/** 页脚：工作室 + 外部链接 */
@Composable
private fun FooterSection(item: MediaItem) {
    val ctx = LocalContext.current
    fun open(url: String) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Throwable) {}
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        if (item.studios.isNotEmpty()) {
            ShelfTitle("工作室")
            Text(item.studios.joinToString("  ·  "), color = TextMid, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
        }
        if (item.providerImdb != null || item.providerTmdb != null) {
            ShelfTitle("外部链接")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (item.providerImdb != null) {
                    LinkChip("IMDb") { open("https://www.imdb.com/title/${item.providerImdb}/") }
                }
                if (item.providerTmdb != null) {
                    val kind = if (item.kind == "series") "tv" else "movie"
                    LinkChip("TheMovieDb") { open("https://www.themoviedb.org/$kind/${item.providerTmdb}") }
                    if (item.providerImdb != null) {
                        LinkChip("Trakt") { open("https://trakt.tv/search/imdb/${item.providerImdb}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkChip(label: String, onClick: () -> Unit) {
    Text(
        label, color = TextHi, fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(InkRaised)
            .tvFocus(1.06f, EmberSoft) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** 本地续播进度（服务器端没给 positionSec 时用） */
private fun localResumeSec(sourceId: String, item: MediaItem): Long =
    cn.plum.ember.data.App.db.progressOf(sourceId, item.id)?.positionSec ?: 0L

/** 剧集单集行：缩略图 + 集名 + 时长 + 进度条 */
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
