package cn.plum.ember.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import coil.compose.AsyncImage
import cn.plum.ember.data.App
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
import cn.plum.ember.ui.widgets.PosterSkeleton
import cn.plum.ember.ui.widgets.ShelfTitle
import cn.plum.ember.ui.widgets.WideCard
import cn.plum.ember.ui.widgets.isTv
import cn.plum.ember.ui.widgets.tvFocus
import cn.plum.ember.util.Format
import kotlinx.coroutines.launch

/**
 * 首页：货架式布局。
 * 手机：底部 Tab（首页/媒体库/收藏/我的）
 * TV：顶部 Tab（遥控器左右切换更顺手）
 */
@Composable
fun HomeScreen(
    repo: LibraryRepo,
    activeSource: MediaSource?,
    onSwitchSource: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
    onOpenLibrary: (libId: String, name: String) -> Unit,
    onOpenSources: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("首页", "媒体库", "收藏", "我的")
    val tv = isTv()

    Column(Modifier.fillMaxSize().background(Ink)) {
        // ---- 顶栏 ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Ember", color = Ember, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            // 源切换
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(InkRaised)
                    .tvFocus(1.04f) { onSwitchSource() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    activeSource?.name ?: "添加源",
                    color = TextHi, fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(if (tv) 160.dp else 120.dp)
                )
                Text("  ▾", color = TextLow, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Default.Search, contentDescription = "搜索", tint = TextMid)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置", tint = TextMid)
            }
        }

        // ---- Tab 切换 ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tabs.forEachIndexed { i, t ->
                val selected = tab == i
                Text(
                    text = t,
                    color = if (selected) Ember else TextMid,
                    fontSize = if (tv) 17.sp else 15.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .tvFocus(1.05f) { tab = i }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // ---- 内容区 ----
        when (tab) {
            0 -> HomeTab(repo, activeSource, onOpenDetail, onOpenLibrary)
            1 -> LibraryTab(repo, activeSource, onOpenLibrary)
            2 -> FavoritesTab(repo, onOpenDetail)
            else -> MyTab(repo, activeSource, onOpenSources, onOpenSettings)
        }
    }
}

// ==================== 首页 Tab ====================

@Composable
private fun HomeTab(
    repo: LibraryRepo,
    source: MediaSource?,
    onOpenDetail: (MediaItem) -> Unit,
    onOpenLibrary: (String, String) -> Unit
) {
    val tv = isTv()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var resume by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var recent by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var movies by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var series by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    LaunchedEffect(source?.id) {
        if (source == null) { loading = false; return@LaunchedEffect }
        loading = true
        error = null
        try {
            // 并行拉四路货架
            kotlinx.coroutines.coroutineScope {
                val j1 = launch { resume = repo.resumeAll() }
                val j2 = launch { recent = try { repo.latest(source.id, null) } catch (_: Throwable) { emptyList() } }
                val j3 = launch {
                    try {
                        val libs = repo.libraries(source.id)
                        val movieLib = libs.firstOrNull { it.kind == "movie" } ?: libs.firstOrNull()
                        movies = movieLib?.let { repo.items(source.id, it.id, 0) } ?: emptyList()
                    } catch (_: Throwable) { movies = emptyList() }
                }
                val j4 = launch {
                    try {
                        val libs = repo.libraries(source.id)
                        val seriesLib = libs.firstOrNull { it.kind == "series" }
                        series = seriesLib?.let { repo.items(source.id, it.id, 0) } ?: emptyList()
                    } catch (_: Throwable) { series = emptyList() }
                }
                listOf(j1, j2, j3, j4).forEach { it.join() }
            }
        } catch (t: Throwable) {
            error = t.message ?: "加载失败"
        }
        loading = false
    }

    if (loading) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(4) { Box(Modifier.weight(1f)) { PosterSkeleton() } }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(4) { Box(Modifier.weight(1f)) { PosterSkeleton() } }
            }
        }
        return
    }
    error?.let { ErrorState(it, onRetry = { loading = true }); return }

    if (source == null) {
        cn.plum.ember.ui.widgets.EmptyState("还没有添加媒体源\n点右上角「添加源」开始", icon = "📼")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        // ---- Banner：继续观看的第一条 ----
        resume.firstOrNull()?.let { hero ->
            item {
                HeroBanner(item = hero, onClick = { onOpenDetail(hero) })
            }
        }

        // ---- 继续观看 ----
        if (resume.isNotEmpty()) {
            item { ShelfTitle("继续观看", "从上次的位置接着看") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(resume, key = { "${it.sourceId}-${it.id}" }) { m ->
                        WideCard(item = m, onClick = { onOpenDetail(m) })
                    }
                }
            }
        }

        // ---- 最近添加 ----
        if (recent.isNotEmpty()) {
            item { ShelfTitle("最近添加") }
            item { PosterRow(recent, onOpenDetail) }
        }

        // ---- 电影 ----
        if (movies.isNotEmpty()) {
            item {
                ShelfTitle("电影") {
                    movies.firstOrNull()?.libraryId?.let { onOpenLibrary(it, "电影") }
                }
            }
            item { PosterRow(movies, onOpenDetail) }
        }

        // ---- 剧集 ----
        if (series.isNotEmpty()) {
            item {
                ShelfTitle("剧集") {
                    series.firstOrNull()?.libraryId?.let { onOpenLibrary(it, "剧集") }
                }
            }
            item { PosterRow(series, onOpenDetail) }
        }

        if (resume.isEmpty() && recent.isEmpty() && movies.isEmpty() && series.isEmpty()) {
            item { cn.plum.ember.ui.widgets.EmptyState("这个源还没有内容", icon = "🍿") }
        }
    }
}

@Composable
private fun HeroBanner(item: MediaItem, onClick: () -> Unit) {
    val tv = isTv()
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .aspectRatio(if (tv) 2.4f else 1.8f)
            .clip(RoundedCornerShape(if (tv) 18.dp else 14.dp))
            .background(InkRaised)
            .tvFocus(1.015f) { onClick() }
    ) {
        if (!item.backdrop.isNullOrBlank() || !item.poster.isNullOrBlank()) {
            AsyncImage(
                model = item.backdrop ?: item.poster,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // 底部渐变
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Color.Transparent, Scrim, Color(0xE60B0908)),
                startY = 120f
            )
        ))
        Column(
            Modifier.align(Alignment.BottomStart).padding(20.dp)
        ) {
            Text(
                item.title,
                color = TextHi,
                fontSize = if (tv) 26.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.year?.let { Text("$it", color = TextMid, fontSize = 13.sp) }
                if (item.rating != null && item.rating > 0) {
                    Spacer(Modifier.width(10.dp))
                    Text("★ ${String.format("%.1f", item.rating)}", color = Ember, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                if (item.positionSec > 0) {
                    Spacer(Modifier.width(10.dp))
                    Text("看到 ${Format.duration(item.positionSec)}", color = TextMid, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(Ember)
                    .tvFocus(1.06f) { onClick() }
                    .padding(horizontal = 26.dp, vertical = 10.dp)
            ) {
                Text(
                    if (item.positionSec > 5) "继续播放" else "立即播放",
                    color = Color(0xFF1A0E04),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun PosterRow(items: List<MediaItem>, onOpen: (MediaItem) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items, key = { "${it.sourceId}-${it.id}" }) { m ->
            PosterCard(item = m, onClick = { onOpen(m) }, modifier = Modifier.width(128.dp))
        }
    }
}

// ==================== 媒体库 Tab ====================

@Composable
private fun LibraryTab(
    repo: LibraryRepo,
    source: MediaSource?,
    onOpenLibrary: (String, String) -> Unit
) {
    var libs by remember { mutableStateOf<List<cn.plum.ember.data.Library>>(emptyList()) }
    var loading by remember { mutableStateOf(source != null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(source?.id) {
        if (source == null) { loading = false; return@LaunchedEffect }
        loading = true
        try {
            libs = repo.libraries(source.id)
        } catch (t: Throwable) {
            error = t.message ?: "加载失败"
        }
        loading = false
    }

    if (loading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Ember) }; return }
    error?.let { ErrorState(it); return }
    if (source == null) { cn.plum.ember.ui.widgets.EmptyState("尚未添加媒体源", icon = "📼"); return }

    LazyVerticalGrid(
        columns = GridCells.Fixed(if (isTv()) 4 else 2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(libs, key = { it.id }) { lib ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(InkRaised)
                    .tvFocus(1.04f) { onOpenLibrary(lib.id, lib.name) }
                    .padding(20.dp)
            ) {
                Text(
                    when (lib.kind) {
                        "movie" -> "🎬"
                        "series" -> "📺"
                        "music" -> "🎵"
                        else -> "📁"
                    },
                    fontSize = 30.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(lib.name, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (lib.itemCount > 0) {
                    Text("${lib.itemCount} 项", color = TextLow, fontSize = 11.sp)
                }
            }
        }
    }
}

// ==================== 收藏 Tab ====================

@Composable
private fun FavoritesTab(repo: LibraryRepo, onOpenDetail: (MediaItem) -> Unit) {
    val rows = remember { repo.favorites() }
    if (rows.isEmpty()) { cn.plum.ember.ui.widgets.EmptyState("还没有收藏", icon = "⭐"); return }
    val cfg = LocalConfiguration.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(Format.posterColumns(cfg.screenWidthDp.toFloat())),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(rows, key = { "${it.sourceId}-${it.itemId}" }) { r ->
            PosterCard(
                item = MediaItem(
                    id = r.itemId, sourceId = r.sourceId,
                    title = r.title, poster = r.poster
                ),
                onClick = { onOpenDetail(MediaItem(id = r.itemId, sourceId = r.sourceId, title = r.title, poster = r.poster)) }
            )
        }
    }
}

// ==================== 我的 Tab ====================

@Composable
private fun MyTab(
    repo: LibraryRepo,
    source: MediaSource?,
    onOpenSources: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val history = remember { repo.history() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MenuItem("📁 管理媒体源", "已保存 ${cn.plum.ember.data.App.sources.all().size} 个", onOpenSources)
        MenuItem("⚙️ 播放设置", "解码器 / 缓存 / 字幕", onOpenSettings)

        ShelfTitle("播放历史")
        if (history.isEmpty()) {
            Text("暂无记录", color = TextLow, fontSize = 13.sp)
        } else {
            history.take(30).forEach { h ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(InkRaised)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("▶", color = Ember, fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.title, color = TextHi, fontSize = 14.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(
                            "${cn.plum.ember.util.Format.ago(h.playedAt)} · 看到 ${Format.duration(h.positionSec)}",
                            color = TextLow, fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised)
            .tvFocus(1.02f) { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextLow, fontSize = 12.sp)
        }
        Text("›", color = TextLow, fontSize = 20.sp)
    }
}
