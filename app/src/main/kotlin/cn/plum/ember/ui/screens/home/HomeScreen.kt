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
import cn.plum.ember.ui.theme.Ink
import cn.plum.ember.ui.theme.InkRaised
import cn.plum.ember.ui.theme.TextHi
import cn.plum.ember.ui.theme.TextLow
import cn.plum.ember.ui.theme.TextMid
import cn.plum.ember.ui.widgets.EmptyState
import cn.plum.ember.ui.widgets.PosterCard
import cn.plum.ember.ui.widgets.PosterSkeleton
import cn.plum.ember.ui.widgets.WideCard
import cn.plum.ember.ui.widgets.tvFocus
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

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

    var sections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(sid) {
        loading = true
        if (sid.isBlank()) { sections = emptyList(); loading = false; return@LaunchedEffect }
        val out = mutableListOf<HomeSection>()
        try {
            val resume = repo.resume(sid)
            if (resume.isNotEmpty()) out += HomeSection("resume", "继续观看", resume, wide = true)
            val latest = repo.latest(sid, null)
            if (latest.isNotEmpty()) out += HomeSection("latest", "最近添加", latest)
            val libs = repo.libraries(sid)
            // 并发拉取每个媒体库的前两页，控制并发量避免压垮内网服务器
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

    // ⚠️ 点任何影片（电影/剧集/单集/合集）一律先进详情页——
    // 大哥明确要求：直接跳播放器没有详情页，且选集/版本都要在详情页完成
    val onClick: (MediaItem) -> Unit = { it -> onOpenDetail(it) }

    Box(Modifier.fillMaxSize().background(Ink)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item { TopNavBar(brand = brand, onOpenSourceManage = onOpenSourceManage) }

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

/** 顶部导航栏：品牌名（源名称）｜精选｜电视剧｜电影｜动漫｜纪录片｜媒体库 */
@Composable
private fun TopNavBar(brand: String, onOpenSourceManage: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = brand,
            color = Ember,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(28.dp))

        val navTabs = listOf("精选", "电视剧", "电影", "动漫", "纪录片")
        navTabs.forEach { tabName ->
            Text(
                text = tabName,
                color = TextMid,
                fontSize = 15.sp,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .tvFocus(scaleUp = 1.05f) {},
                fontWeight = FontWeight.Medium
            )
        }

        Text(
            text = "媒体库",
            color = Ember,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .tvFocus(scaleUp = 1.05f, onClick = onOpenSourceManage)
        )

        Spacer(Modifier.weight(1f))
        Text(text = "🔍 搜索", color = TextMid, fontSize = 15.sp,
            modifier = Modifier.tvFocus(scaleUp = 1.05f) {})
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
