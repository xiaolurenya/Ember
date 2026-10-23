package cn.plum.ember.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import cn.plum.ember.R
import cn.plum.ember.data.MediaItem
import cn.plum.ember.ui.theme.Ember
import cn.plum.ember.ui.theme.EmberSoft
import cn.plum.ember.ui.theme.InkBorder
import cn.plum.ember.ui.theme.Scrim
import cn.plum.ember.ui.theme.TextHi
import cn.plum.ember.ui.theme.TextLow
import cn.plum.ember.ui.theme.TextMid
import cn.plum.ember.util.Format

/**
 * TV / 手机自适应。
 * TV 判定：UiMode == TELEVISION；此时字号、间距、焦点视觉都要放大。
 */
@Composable
fun isTv(): Boolean {
    val ctx = LocalContext.current
    val cfg = LocalConfiguration.current
    return (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            (cfg.screenWidthDp >= 720 && cfg.screenHeightDp >= 720 &&
                    !ctx.packageManager.hasSystemFeature("android.hardware.touchscreen"))
}

/** 焦点视觉（TV 必备）：聚焦时轻微放大 + 炭火橙描边 */
fun Modifier.tvFocus(
    scaleUp: Float = 1.06f,
    borderColor: Color = EmberSoft,
    cornerRadius: Dp = 12.dp,
    onClick: () -> Unit
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    this
        .scale(if (focused) scaleUp else 1f)
        .clip(RoundedCornerShape(cornerRadius))
        .border(
            width = if (focused) 3.dp else 0.dp,
            color = if (focused) borderColor else Color.Transparent,
            shape = RoundedCornerShape(cornerRadius)
        )
        .focusable(interactionSource = interaction)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/**
 * 海报卡：2:3 竖版海报 + 标题 + 评分角标 + 观看进度条。
 * 所有媒体库网格的通用原子。
 */
@Composable
fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showProgress: Boolean = true,
    tvScale: Float = 1.06f
) {
    val tv = isTv()
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(if (tv) 14.dp else 10.dp))
                .background(InkBorder)
                .tvFocus(tvScale) { onClick() }
        ) {
            if (!item.poster.isNullOrBlank()) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(item.title.take(2), color = TextLow, fontSize = 32.sp)
                }
            }

            // 底部渐变压暗，让白色文字可读
            if (item.positionSec > 0 || item.rating != null || item.tag4k || item.tagHdr) {
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Scrim), startY = 200f)
                ))
            }

            // 评分（左上角）
            if (item.rating != null && item.rating > 0) {
                Text(
                    text = String.format("%.1f", item.rating),
                    color = EmberSoft,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0x88000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
            // 4K / HDR 角标
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (item.tag4k) TagChip("4K")
                if (item.tagHdr) TagChip("HDR")
            }

            // 观看进度
            if (showProgress && item.resumePercent > 0) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp)) {
                    LinearProgressIndicator(
                        progress = { item.resumePercent / 100f },
                        color = Ember,
                        trackColor = Color(0x66000000),
                        modifier = Modifier.fillMaxWidth().height(3.dp)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "${item.resumePercent}%",
                        color = TextHi,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            color = TextHi,
            fontSize = if (tv) 15.sp else 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        item.year?.let {
            Text(
                text = "$it${if (item.kind == "series") " · 剧集" else ""}",
                color = TextLow, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TagChip(label: String) {
    Text(
        text = label,
        color = TextHi,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(Color(0x88000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

/**
 * 横版卡（继续观看用）：16:9 背景 + 标题 + 剩余时间
 */
@Composable
fun WideCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 260.dp
) {
    val tv = isTv()
    Column(modifier = modifier.width(width)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(if (tv) 12.dp else 8.dp))
                .background(InkBorder)
                .tvFocus(1.04f) { onClick() }
        ) {
            if (!item.backdrop.isNullOrBlank() || !item.poster.isNullOrBlank()) {
                AsyncImage(
                    model = item.backdrop ?: item.poster,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Scrim), startY = 80f)
            ))
            // 播放按钮居中
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("▶", color = Color(0xCCFFFFFF), fontSize = 32.sp)
            }
            if (item.resumePercent in 1..99) {
                LinearProgressIndicator(
                    progress = { item.resumePercent / 100f },
                    color = Ember,
                    trackColor = Color(0x66000000),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            color = TextHi,
            fontSize = if (tv) 15.sp else 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = buildString {
                if (item.positionSec > 0) {
                    append("剩 ${Format.duration(maxOf(0, item.durationSec - item.positionSec))}")
                } else {
                    append(Format.duration(item.durationSec))
                }
                item.subtitle?.let { append(" · $it") }
            },
            color = TextLow, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 首页横向货架标题行
 */
@Composable
fun ShelfTitle(title: String, subtitle: String? = null, onMore: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(18.dp)
                .background(Ember, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(title, color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        subtitle?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, color = TextLow, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        onMore?.let {
            Text("更多 ›", color = TextMid, fontSize = 13.sp,
                modifier = Modifier.tvFocus(1.05f) { it() })
        }
    }
}

/** 通用空态 */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, icon: String = "🎬") {
    Box(modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 40.sp)
            Spacer(Modifier.height(10.dp))
            Text(text, color = TextMid, fontSize = 14.sp)
        }
    }
}

/** 通用错误提示 */
@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚠️", fontSize = 40.sp)
            Spacer(Modifier.height(10.dp))
            Text(message, color = TextMid, fontSize = 14.sp, maxLines = 4)
            if (onRetry != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "重试", color = Ember, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(Ember.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                        .tvFocus(1.05f) { onRetry() }
                )
            }
        }
    }
}

/** 骨架屏占位 */
@Composable
fun PosterSkeleton() {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.linearGradient(listOf(
                    InkBorder, InkBorder.copy(alpha = 0.6f), InkBorder
                ))
            )
    )
}
