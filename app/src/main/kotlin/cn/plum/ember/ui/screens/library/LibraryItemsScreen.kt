package cn.plum.ember.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.plum.ember.data.LibraryRepo
import cn.plum.ember.data.MediaItem
import cn.plum.ember.data.MediaSource
import cn.plum.ember.ui.theme.Ember
import cn.plum.ember.ui.theme.Ink
import cn.plum.ember.ui.theme.TextHi
import cn.plum.ember.ui.theme.TextLow
import cn.plum.ember.ui.widgets.EmptyState
import cn.plum.ember.ui.widgets.ErrorState
import cn.plum.ember.ui.widgets.PosterCard
import cn.plum.ember.util.Format

/**
 * 媒体库条目列表：分页懒加载，滚动到底自动翻页。
 * （「滚动到底自动翻页」和「显示更多按钮」不能共存，二选一：这里选前者）
 */
@Composable
fun LibraryItemsScreen(
    repo: LibraryRepo,
    source: MediaSource?,
    libraryId: String,
    libraryName: String,
    onBack: () -> Unit,
    onOpenDetail: (MediaItem) -> Unit
) {
    val cfg = LocalConfiguration.current
    val gridState = rememberLazyGridState()
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var page by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var exhausted by remember { mutableStateOf(false) }

    suspend fun loadPage(p: Int) {
        if (source == null) return
        val batch = repo.items(source.id, libraryId, p)
        if (batch.isEmpty()) exhausted = true
        items = if (p == 0) batch else items + batch
    }

    LaunchedEffect(libraryId) {
        try {
            loadPage(0)
        } catch (t: Throwable) {
            error = t.message ?: "加载失败"
        }
        loading = false
    }

    // 滚动到底自动加载下一页
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(shouldLoadMore, items.size) {
        if (shouldLoadMore && !loading && !loadingMore && !exhausted && items.isNotEmpty()) {
            loadingMore = true
            try {
                page += 1
                loadPage(page)
            } catch (_: Throwable) {
                page -= 1
            }
            loadingMore = false
        }
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextHi)
            }
            Text(libraryName, color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            if (items.isNotEmpty()) {
                Text("· ${items.size}+", color = TextLow, fontSize = 13.sp)
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Ember)
            }
            error != null -> ErrorState(error ?: "加载失败", onRetry = { loading = true })
            items.isEmpty() -> EmptyState("这个库里还没有内容", icon = "📂")
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(Format.posterColumns(cfg.screenWidthDp.toFloat())),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(items, key = { "${it.sourceId}-${it.id}" }) { m ->
                    PosterCard(item = m, onClick = { onOpenDetail(m) })
                }
                if (loadingMore) {
                    item { Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Ember, modifier = Modifier.width(24.dp))
                    } }
                }
            }
        }
    }
}
