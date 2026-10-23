package cn.plum.ember.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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
import cn.plum.ember.ui.widgets.EmptyState
import cn.plum.ember.ui.widgets.PosterCard
import cn.plum.ember.util.Format
import kotlinx.coroutines.delay

/** 全库搜索：对当前活动源发起 title 查询，防抖 300ms */
@Composable
fun SearchScreen(
    repo: LibraryRepo,
    source: MediaSource?,
    onBack: () -> Unit,
    onOpenDetail: (MediaItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val cfg = LocalConfiguration.current

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // 防抖搜索
    LaunchedEffect(query) {
        if (query.isBlank() || source == null) return@LaunchedEffect
        delay(300)
        searching = true
        try {
            results = repo.search(source.id, query.trim())
        } catch (_: Throwable) {
            results = emptyList()
        }
        searched = true
        searching = false
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextHi)
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索片名 / 演员 / 关键字", color = TextLow, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextLow) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; results = emptyList(); searched = false }) {
                            Icon(Icons.Default.Close, contentDescription = "清空", tint = TextLow)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Ember,
                    cursorColor = Ember,
                    focusedTextColor = TextHi,
                    unfocusedTextColor = TextHi
                ),
                modifier = Modifier.weight(1f).focusRequester(focusRequester)
            )
        }

        when {
            source == null -> EmptyState("请先添加媒体源", icon = "📼")
            searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Ember)
            }
            query.isBlank() -> EmptyState("输入关键字开始搜索", icon = "🔍")
            searched && results.isEmpty() -> EmptyState("没有找到「$query」", icon = "🤷")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(Format.posterColumns(cfg.screenWidthDp.toFloat())),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(results, key = { "${it.sourceId}-${it.id}" }) { m ->
                    PosterCard(item = m, onClick = { onOpenDetail(m) })
                }
            }
        }
    }
}
