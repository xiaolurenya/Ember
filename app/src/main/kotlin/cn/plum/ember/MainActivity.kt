package cn.plum.ember

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import cn.plum.ember.data.App
import cn.plum.ember.data.LibraryRepo
import cn.plum.ember.data.MediaItem
import cn.plum.ember.security.Guard
import cn.plum.ember.ui.screens.detail.DetailScreen
import cn.plum.ember.ui.screens.home.HomeScreen
import cn.plum.ember.ui.screens.library.LibraryItemsScreen
import cn.plum.ember.ui.screens.player.PlayerScreen
import cn.plum.ember.ui.screens.search.SearchScreen
import cn.plum.ember.ui.screens.settings.SettingsScreen
import cn.plum.ember.ui.screens.sources.SourcesScreen
import cn.plum.ember.ui.screens.splash.SplashScreen
import cn.plum.ember.ui.theme.Ember
import cn.plum.ember.ui.theme.EmberTheme
import cn.plum.ember.ui.theme.InkRaised
import cn.plum.ember.ui.theme.TextHi
import cn.plum.ember.ui.theme.TextMid
import kotlinx.coroutines.launch

/**
 * 唯一 Activity。全部界面用 Compose Navigation 内部跳转，
 * 对外暴露 ACTION_VIEW 拉起直接进播放器（pending：v1.1）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EmberTheme { EmberRoot() }
        }
    }
}

object Routes {
    const val SPLASH = "splash"
    const val SOURCES = "sources"
    const val HOME = "home"
    const val LIBRARY = "library/{libId}/{libName}"
    const val DETAIL = "detail/{itemId}"
    const val PLAYER = "player/{itemId}/{versionIndex}"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    fun library(libId: String, name: String) = "library/${java.net.URLEncoder.encode(libId, "UTF-8")}/${java.net.URLEncoder.encode(name, "UTF-8")}"
    fun detail(itemId: String) = "detail/${java.net.URLEncoder.encode(itemId, "UTF-8")}"
    fun player(itemId: String, version: Int) = "player/${java.net.URLEncoder.encode(itemId, "UTF-8")}/$version"
}

@Composable
fun EmberRoot() {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val repo = remember { LibraryRepo() }
    val scope = rememberCoroutineScope()
    var activeSourceId by remember { mutableStateOf<String?>(null) }
    var bootReady by remember { mutableStateOf(false) }
    // 加固自检：只拦「签名被改」和「调试器附加」这两种铁证，其它只告警
    var alarms by remember { mutableStateOf<List<Guard.Finding>>(emptyList()) }

    // 首次进入：取上次使用的源 + 安全自检
    LaunchedEffect(Unit) {
        val list = App.sources.all()
        val last = App.prefs.lastSourceIdBlocking()
        activeSourceId = list.firstOrNull { it.id == last }?.id
            ?: list.firstOrNull()?.id
        alarms = try {
            Guard.scan(ctx).filter { it.level == Guard.Level.ALARM }
        } catch (_: Throwable) { emptyList() }
        bootReady = true
    }

    val activeSource = activeSourceId?.let { App.sources.find(it) }

    if (!bootReady) return

    // 安全告警：明确告知但不强行退出（误杀代价太大）
    if (alarms.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { alarms = emptyList() },
            title = { Text("安全提醒", color = TextHi) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    alarms.forEach { f ->
                        Text(
                            "· ${f.title}\n${f.detail}",
                            color = TextMid, fontSize = 13.sp, lineHeight = 19.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { alarms = emptyList() }) {
                    Text("我知道了", color = Ember)
                }
            },
            containerColor = InkRaised
        )
    }

    NavHost(navController = nav, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen(
                hasSource = App.sources.all().isNotEmpty(),
                onReady = { hasSource ->
                    nav.navigate(if (hasSource) Routes.HOME else Routes.SOURCES) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
                preInit = { /* MPV 预热：暂时空实现，内核在 PlayerScreen 里 init */ }
            )
        }

        composable(Routes.SOURCES) {
            SourcesScreen(
                repo = repo,
                onDone = {
                    val first = App.sources.all().firstOrNull()
                    if (first != null) {
                        activeSourceId = first.id
                        scope.launch { App.prefs.setLastSourceId(first.id) }
                        nav.navigate(Routes.HOME) { popUpTo(Routes.SOURCES) { inclusive = true } }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                repo = repo,
                source = activeSource,
                onOpenSourceManage = { nav.navigate(Routes.SOURCES) },
                onOpenDetail = { item -> nav.navigate(Routes.detail(item.id)) }
            )
        }

        composable(
            Routes.LIBRARY,
            arguments = listOf(
                navArgument("libId") { type = NavType.StringType },
                navArgument("libName") { type = NavType.StringType }
            )
        ) { entry ->
            val libId = java.net.URLDecoder.decode(entry.arguments?.getString("libId") ?: "", "UTF-8")
            val libName = java.net.URLDecoder.decode(entry.arguments?.getString("libName") ?: "", "UTF-8")
            LibraryItemsScreen(
                repo = repo, source = activeSource,
                libraryId = libId, libraryName = libName,
                onBack = { nav.popBackStack() },
                onOpenDetail = { item -> nav.navigate(Routes.detail(item.id)) }
            )
        }

        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType })
        ) { entry ->
            val itemId = java.net.URLDecoder.decode(entry.arguments?.getString("itemId") ?: "", "UTF-8")
            DetailScreen(
                repo = repo, source = activeSource, itemId = itemId,
                onBack = { nav.popBackStack() },
                onPlay = { item, version -> nav.navigate(Routes.player(item.id, version)) }
            )
        }

        composable(
            Routes.PLAYER,
            arguments = listOf(
                navArgument("itemId") { type = NavType.StringType },
                navArgument("versionIndex") { type = NavType.IntType }
            )
        ) { entry ->
            val itemId = java.net.URLDecoder.decode(entry.arguments?.getString("itemId") ?: "", "UTF-8")
            val version = entry.arguments?.getInt("versionIndex") ?: 0
            PlayerScreen(
                repo = repo, source = activeSource,
                itemId = itemId,
                versionIndex = version,
                onClose = { nav.popBackStack() }
            )
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                repo = repo, source = activeSource,
                onBack = { nav.popBackStack() },
                onOpenDetail = { item -> nav.navigate(Routes.detail(item.id)) }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
