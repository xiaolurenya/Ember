package cn.plum.ember

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import cn.plum.ember.data.App
import cn.plum.ember.data.LibraryRepo
import cn.plum.ember.data.MediaItem
import cn.plum.ember.ui.screens.detail.DetailScreen
import cn.plum.ember.ui.screens.home.HomeScreen
import cn.plum.ember.ui.screens.library.LibraryItemsScreen
import cn.plum.ember.ui.screens.player.PlayerScreen
import cn.plum.ember.ui.screens.search.SearchScreen
import cn.plum.ember.ui.screens.settings.SettingsScreen
import cn.plum.ember.ui.screens.sources.SourcesScreen
import cn.plum.ember.ui.screens.splash.SplashScreen
import cn.plum.ember.ui.theme.EmberTheme
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

@androidx.compose.runtime.Composable
fun EmberRoot() {
    val nav = rememberNavController()
    val repo = remember { LibraryRepo() }
    val scope = rememberCoroutineScope()
    var activeSourceId by remember { mutableStateOf<String?>(null) }
    var bootReady by remember { mutableStateOf(false) }

    // 首次进入：取上次使用的源
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val list = App.sources.all()
        val last = App.prefs.lastSourceIdBlocking()
        activeSourceId = list.firstOrNull { it.id == last }?.id
            ?: list.firstOrNull()?.id
        bootReady = true
    }

    val activeSource = activeSourceId?.let { App.sources.find(it) }

    if (!bootReady) return

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
                activeSource = activeSource,
                onSwitchSource = { nav.navigate(Routes.SOURCES) },
                onOpenSearch = { nav.navigate(Routes.SEARCH) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenDetail = { item -> nav.navigate(Routes.detail(item.id)) },
                onOpenLibrary = { libId, name -> nav.navigate(Routes.library(libId, name)) },
                onOpenSources = { nav.navigate(Routes.SOURCES) }
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
