package cn.plum.ember.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.plum.ember.data.LibraryRepo
import cn.plum.ember.ui.theme.Ember
import cn.plum.ember.ui.theme.Ink
import cn.plum.ember.ui.theme.InkRaised
import cn.plum.ember.ui.theme.TextHi
import cn.plum.ember.ui.theme.TextLow
import cn.plum.ember.ui.theme.TextMid
import cn.plum.ember.ui.widgets.tvFocus
import kotlinx.coroutines.delay

/**
 * TV端黑金霓虹影视首页
 * 对接Emby/strm‑302；卡片点击回调mediaId，上层组装302中转url播放strm资源
 *
 * ⚠️ repo 当前为预留参数：真实首页数据（Banner/栏目）后续由 repo 拉取 Emby 元数据填入，
 *    见文件底部「对接逻辑」。未接数据时栏目为空，会自动显示占位卡片。
 */
data class HomeBannerItem(
    val id: String,
    val title: String,
    val subTitle: String,
    val tags: List<String>,
    val desc: String,
    val imageUrl: String
)

data class HomeMediaCard(
    val mediaId: String,
    val title: String,
    val coverUrl: String
)

data class HomeSection(
    val sectionTitle: String,
    val items: List<HomeMediaCard>
)

@Composable
fun HomeScreen(
    repo: LibraryRepo,
    onOpenSourceManage: () -> Unit, // 跳转 SourcesScreen 媒体源管理
    onPlayMedia: (mediaId: String) -> Unit // 点击卡片/Banner，回调mediaId，上层拼接302 strm链接播放
) {
    // 模拟Banner数据，实际替换为repo拉取Emby首页焦点内容
    val bannerList = remember {
        listOf(
            HomeBannerItem(
                id = "m001",
                title = "霓虹之夜",
                subTitle = "都市犯罪系列全季上线",
                tags = listOf("悬疑", "犯罪", "剧情"),
                desc = "繁华都市下隐藏重重阴谋，多方势力交织博弈，高口碑剧集全集上线。",
                imageUrl = ""
            )
        )
    }

    // 栏目，对应你的媒体库分类：动画电影｜动漫剧集｜国产电影｜国产纪录片｜国产剧集
    val sectionList = remember {
        listOf(
            HomeSection("动画电影", emptyList()),
            HomeSection("动漫剧集", emptyList()),
            HomeSection("国产电影", emptyList()),
            HomeSection("国产纪录片", emptyList()),
            HomeSection("国产剧集", emptyList())
        )
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // ========== 顶部导航栏 ==========
            item {
                TopNavBar(onOpenSourceManage = onOpenSourceManage)
            }

            // ========== Banner轮播 ==========
            item {
                HomeBannerCarousel(
                    bannerItems = bannerList,
                    onBannerClick = { item -> onPlayMedia(item.id) }
                )
            }

            // ========== 各个横向栏目行 ==========
            items(sectionList) { section ->
                HomeContentSection(
                    section = section,
                    onCardClick = { card -> onPlayMedia(card.mediaId) }
                )
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

/** 顶部导航栏：Cinego影院｜精选｜电视剧｜电影｜动漫｜纪录片｜媒体库｜搜索 */
@Composable
private fun TopNavBar(onOpenSourceManage: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Cinego影院",
            color = Ember,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(32.dp))

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

        Spacer(modifier = Modifier.weight(1f))
        Text(text = "🔍 搜索", color = TextMid, fontSize = 15.sp, modifier = Modifier.tvFocus(scaleUp = 1.05f) {})
    }
}

/** 首页大Banner轮播（TV大横幅） */
@Composable
private fun HomeBannerCarousel(
    bannerItems: List<HomeBannerItem>,
    onBannerClick: (HomeBannerItem) -> Unit
) {
    if (bannerItems.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { bannerItems.size })

    // 自动轮播
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            val next = (pagerState.currentPage + 1) % bannerItems.size
            pagerState.animateScrollToPage(next)
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(16.dp))
    ) { page ->
        val item = bannerItems[page]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(InkRaised)
                // tvFocus 在聚焦时已自带金色发光描边，无需再 drawBehind
                .tvFocus(scaleUp = 1.02f) { onBannerClick(item) }
        ) {
            // 底部渐变遮罩
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                            startY = 100f
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(24.dp)
            ) {
                Text(text = item.title, color = TextHi, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(text = item.subTitle, color = TextMid, fontSize = 16.sp)
                Row {
                    item.tags.forEach { tag ->
                        Text(
                            text = " $tag ",
                            color = Ember,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Ember.copy(alpha = 0.12f))
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = item.desc, color = TextLow, fontSize = 13.sp, maxLines = 2)
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = { onBannerClick(item) },
                    modifier = Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                ) {
                    Text("播放影片", fontSize = 14.sp)
                }
            }
        }
    }
}

/** 内容栏目：标题 + 横向卡片行 */
@Composable
private fun HomeContentSection(
    section: HomeSection,
    onCardClick: (HomeMediaCard) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Text(
            text = section.sectionTitle,
            color = TextHi,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(section.items) { cardItem ->
                MediaTvCard(
                    item = cardItem,
                    onClick = { onCardClick(cardItem) }
                )
            }
            // 空列表占位卡片（没拉到媒体时展示）
            if (section.items.isEmpty()) {
                items(6) {
                    MediaTvCardPlaceholder()
                }
            }
        }
    }
}

/** TV媒体卡片，焦点金色发光描边，复用tvFocus扩展 */
@Composable
private fun MediaTvCard(
    item: HomeMediaCard,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(160.dp)
            .height(240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised)
            .tvFocus(scaleUp = 1.08f, onClick = onClick)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF222222))
            )
            Text(
                text = item.title,
                color = TextHi,
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

/** 占位卡片，还没加载封面 */
@Composable
private fun MediaTvCardPlaceholder() {
    Box(
        modifier = Modifier
            .width(160.dp)
            .height(240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised)
            .tvFocus(scaleUp = 1.08f) {}
    )
}
