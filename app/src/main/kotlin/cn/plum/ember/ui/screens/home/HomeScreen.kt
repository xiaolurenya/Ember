package cn.plum.ember.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.plum.ember.data.LibraryRepo
import cn.plum.ember.data.MediaItem
import cn.plum.ember.data.MediaSource
import cn.plum.ember.ui.theme.Ember
import cn.plum.ember.ui.theme.EmberSoft
import cn.plum.ember.ui.theme.Ink
import cn.plum.ember.ui.theme.InkBorder
import cn.plum.ember.ui.theme.InkRaised
import cn.plum.ember.ui.theme.TextHi
import cn.plum.ember.ui.theme.TextLow
import cn.plum.ember.ui.theme.TextMid
import androidx.compose.material3.CircularProgressIndicator
import cn.plum.ember.ui.widgets.EmptyState
import cn.plum.ember.ui.widgets.PosterCard
import cn.plum.ember.ui.widgets.PosterSkeleton
import cn.plum.ember.ui.widgets.WideCard
import cn.plum.ember.ui.widgets.isTv
import cn.plum.ember.ui.widgets.tvFocus
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TV / 手机共用影视首页。
 *
 * 真实数据由 repo 拉取：继续观看（resume）→ 最近添加（latest）→ 各媒体库条目。
 * 顶栏品牌名来自当前已添加的媒体源名称（动态），不再硬编码。
 * 卡片点击：一律进详情页（选集/版本/播放都在详情页完成）。
 */
data class HomeSection(
    val key: String,
    val title: String,
    val items: List<MediaItem>,
    val wide: Boolean = false
)

@Composable
fun HomeScreen(
    repo: LibraryRepo,
    source: MediaSource?,
    onOpenSourceManage: () -> Unit,
    onOpenDetail: (MediaItem) -> Unit
) {
    val brand = source?.name?.takeIf { it.isNotBlank() } ?: "Ember"
    val sid = source?.id ?: ""

    // ⭐ 顶部导航：必须真的能切换（之前全是空 onClick={}，点了「没反应」）
    var tab by remember { mutableStateOf("精选") }
    var libs by remember { mutableStateOf<List<cn.plum.ember.data.Library>>(emptyList()) }
    var tabItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var tabLoading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    var sections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(sid) {
        loading = true
        if (sid.isBlank()) { sections = emptyList(); loading = false; return@LaunchedEffect }
        val out = mutableListOf<HomeSection>()
        try {
            libs = withContext(Dispatchers.IO) { runCatching { repo.libraries(sid) }.getOrDefault(emptyList()) }
            val resume = repo.resume(sid)
            if (resume.isNotEmpty()) out += HomeSection("resume", "继续观看", resume, wide = true)
            val latest = repo.latest(sid, null)
            if (latest.isNotEmpty()) out += HomeSection("latest", "最近添加", latest)
            // 并发拉取每个媒体库的第一页，控制并发量避免压垮内网服务器
            libs.take(8).map { lib ->
                async {
                    runCatching { repo.items(sid, lib.id, 0) }.getOrElse { emptyList() }
                        .let { if (it.isNotEmpty()) HomeSection("lib-${lib.id}", lib.name, it) else null }
                }
            }.awaitAll().filterNotNull().let { out += it }
        } catch (t: Throwable) {
            android.util.Log.w("EmberHome", "加载首页失败：${t.message}")
        }
        sections = out
        loading = false
    }

    // ---- 顶部导航切换：按 Emby 媒体库名字/类型匹配，拉该库内容（不阻塞首页精选）----
    LaunchedEffect(tab, libs.size) {
        if (tab == "精选" || tab == "搜索" || sid.isBlank()) return@LaunchedEffect
        tabLoading = true
        tabItems = emptyList()
        tabItems = withContext(Dispatchers.IO) {
            runCatching {
                val matched = libs.filter { cn.plum.ember.util.TabMatch.isLibraryFor(tab, it.name, it.kind) }
                if (matched.isNotEmpty()) {
                    matched.map { runCatching { repo.items(sid, it.id, 0) }.getOrDefault(emptyList()) }
                        .flatten().distinctBy { it.id }
                } else {
                    // 没有同名媒体库 → 用已有栏目数据按类型兜底过滤
                    val want = when (tab) {
                        "电视剧" -> "series"
                        "电影" -> "movie"
                        else -> null
                    }
                    if (want != null) sections.flatMap { it.items }.filter { it.kind == want }.distinctBy { it.id }
                    else emptyList()
                }
            }.getOrDefault(emptyList())
        }
        tabLoading = false
    }

    // ---- 搜索（以前是个空壳按钮）----
    LaunchedEffect(query) {
        if (query.isBlank() || sid.isBlank()) { tabItems = emptyList(); return@LaunchedEffect }
        tabLoading = true
        tabItems = withContext(Dispatchers.IO) {
            runCatching { repo.search(sid, query) }.getOrDefault(emptyList())
        }
        tabLoading = false
    }

    // ⚠️ 点任何影片（电影/剧集/单集/合集）一律先进详情页——
    // 大哥明确要求：直接跳播放器没有详情页，且选集/版本都要在详情页完成
    val onClick: (MediaItem) -> Unit = { it -> onOpenDetail(it) }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize()) {
            // ---- 顶部导航（唯一实例，所有栏目共用）----
            TopNavBar(
                brand = brand, current = tab,
                onTab = { t ->
                    tab = t
                    query = ""
                    if (t != "搜索") tabItems = emptyList()
                },
                onOpenSourceManage = onOpenSourceManage
            )

            if (tab != "精选") {
                // ---- 分类 / 搜索结果：整屏网格 ----
                if (tab == "搜索") SearchField(query, onQueryChange = { query = it })
                Box(Modifier.fillMaxSize()) {
                    when {
                        tabLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Ember, modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
                        }
                        tabItems.isEmpty() -> EmptyState(
                            if (tab == "搜索" && query.isBlank()) "输入片名开始搜索"
                            else "这个分类下还没有内容"
                        )
                        else -> LibraryGrid(tabItems, onClick)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // 头图：取「最近添加」第一部的背景图做主视觉（播放/详情都进详情页）
                    val hero = sections.firstOrNull { it.key == "latest" }?.items?.firstOrNull()
                        ?: sections.firstOrNull { it.key == "resume" }?.items?.firstOrNull()
                    item { hero?.let { HomeHero(item = it, onPlay = { onOpenDetail(it) }, onDetail = { onOpenDetail(it) }) } }

                    if (loading) {
                        item { SectionSkeleton() }
                    } else if (sections.isEmpty()) {
                        item { EmptyState("还没有内容，去「媒体库」添加你的 Emby / Jellyfin 源吧") }
                    } else {
                        items(sections) { sec -> SectionRow(sec, onClick) }
                    }

                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

/** 顶部导航栏：品牌名（源名称）｜精选｜电视剧｜电影｜动漫｜纪录片｜媒体库｜搜索 */
@Composable
private fun TopNavBar(
    brand: String,
    current: String,
    onTab: (String) -> Unit,
    onOpenSourceManage: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.tvFocus(1.04f) { onTab("精选") }
        ) {
            Text(text = brand, color = Ember, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(24.dp))

        val navTabs = listOf("精选", "电视剧", "电影", "动漫", "纪录片")
        navTabs.forEach { tabName ->
            val selected = current == tabName
            Column(
                Modifier
                    .padding(horizontal = 10.dp)
                    .tvFocus(scaleUp = 1.05f) { onTab(tabName) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = tabName,
                    color = if (selected) TextHi else TextMid,
                    fontSize = if (selected) 17.sp else 15.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
                // 选中指示条：TV/鼠标都一眼看出当前在哪个栏目
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .width(if (selected) 22.dp else 0.dp)
                        .height(3.dp)
                        .background(if (selected) Ember else Color.Transparent, RoundedCornerShape(2.dp))
                )
            }
        }

        Text(
            text = "媒体库",
            color = TextMid,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .tvFocus(scaleUp = 1.05f, onClick = onOpenSourceManage)
        )

        Spacer(Modifier.weight(1f))
        Text(
            text = "🔍 搜索", color = if (current == "搜索") EmberSoft else TextMid, fontSize = 15.sp,
            modifier = Modifier.tvFocus(scaleUp = 1.05f) { onTab("搜索") }
        )
    }
}

/** 搜索输入框 */
@Composable
private fun SearchField(value: String, onQueryChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("搜索片名 / 剧集…", color = TextLow, fontSize = 14.sp) },
            shape = RoundedCornerShape(12.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = TextHi, fontSize = 15.sp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Ember,
                unfocusedBorderColor = InkBorder,
                cursorColor = Ember,
                focusedContainerColor = InkRaised,
                unfocusedContainerColor = InkRaised
            ),
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (value.isNotBlank()) {
                    Box(Modifier.tvFocus(1.05f) { onQueryChange("") }.padding(8.dp)) {
                        Text("✕", color = TextMid, fontSize = 16.sp)
                    }
                }
            }
        )
    }
}

/** 分类/搜索结果网格（TV 用自适应列，列宽随屏幕走） */
@Composable
private fun LibraryGrid(items: List<MediaItem>, onClick: (MediaItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (isTv()) 170.dp else 130.dp),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items, key = { it.id }) { mitem ->
            PosterCard(item = mitem, onClick = { onClick(mitem) })
        }
    }
}

/** 首页大横幅头图（用媒体项背景图） */
@Composable
private fun HomeHero(item: MediaItem, onPlay: () -> Unit, onDetail: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(InkRaised)
            .tvFocus(scaleUp = 1.02f, onClick = onDetail)
    ) {
        if (!item.backdrop.isNullOrBlank() || !item.poster.isNullOrBlank()) {
            AsyncImage(
                model = item.backdrop ?: item.poster,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)),
                        startY = 80f
                    )
                )
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(24.dp)
        ) {
            Text(item.title, color = TextHi, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.overview?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = TextLow, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .background(Ember, RoundedCornerShape(10.dp))
                        .padding(horizontal = 24.dp, vertical = 11.dp)
                        .tvFocus(1.04f, onClick = onPlay)
                ) { Text("▶ 播放", color = Color(0xFF1A0E04), fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Box(
                    Modifier
                        .background(Color(0x33FFFFFF), RoundedCornerShape(10.dp))
                        .padding(horizontal = 24.dp, vertical = 11.dp)
                        .tvFocus(1.04f, onClick = onDetail)
                ) { Text("详情", color = Color.White, fontSize = 14.sp) }
            }
        }
    }
}

/** 内容栏目：标题 + 横向卡片行 */
@Composable
private fun SectionRow(section: HomeSection, onClick: (MediaItem) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Text(section.title, color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(section.items, key = { it.id }) { item ->
                if (section.wide) WideCard(item = item, onClick = { onClick(item) })
                else PosterCard(item = item, onClick = { onClick(item) })
            }
        }
    }
}

/** 加载骨架 */
@Composable
private fun SectionSkeleton() {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Box(Modifier.width(140.dp).height(20.dp).background(InkRaised, RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(8) { PosterSkeleton() }
        }
    }
}
