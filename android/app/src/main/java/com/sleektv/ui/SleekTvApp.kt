package com.sleektv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import com.sleektv.SleekApp
import com.sleektv.data.ApiException
import com.sleektv.data.BaseItem
import com.sleektv.data.LayoutConfig
import com.sleektv.data.isBrowsableFolder
import com.sleektv.data.isEpisode
import com.sleektv.ui.screens.AccountScreen
import com.sleektv.ui.screens.DetailScreen
import com.sleektv.ui.screens.HomeData
import com.sleektv.ui.screens.HomeScreen
import com.sleektv.ui.screens.LibraryScreen
import com.sleektv.ui.screens.LoginScreen
import com.sleektv.ui.screens.PlayerScreen
import com.sleektv.ui.theme.LocalSleekColors
import com.sleektv.ui.theme.SleekTheme

sealed interface Screen {
    data object Login : Screen
    data object Home : Screen
    data object Account : Screen
    data class Library(val view: BaseItem) : Screen
    data class Detail(val itemId: String) : Screen
    data class Player(val itemId: String) : Screen
}

/** Back stack, shared state and the remote layout. */
class Nav(
    val app: SleekApp,
    private val stack: SnapshotStateList<Screen>,
    private val layoutState: MutableState<LayoutConfig>,
) {
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** Library tabs for the header, filled in by the home screen. */
    var views by mutableStateOf<List<BaseItem>>(emptyList())

    /** Home content + scroll position survive trips into detail pages and the player. */
    var homeCache by mutableStateOf<HomeData?>(null)
    val homeListState = LazyListState()

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun back() {
        // removeAt instead of removeLast: removeLast crashes on older Android when built against SDK 35.
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun resetTo(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }

    fun open(item: BaseItem) {
        when {
            item.isEpisode && item.seriesId != null -> push(Screen.Detail(item.seriesId))
            item.isBrowsableFolder -> push(Screen.Library(item))
            else -> push(Screen.Detail(item.id))
        }
    }

    fun play(item: BaseItem) = push(Screen.Player(item.id))

    fun logout() {
        app.session.logout()
        homeCache = null
        views = emptyList()
        resetTo(Screen.Login)
    }

    /** Pull the latest layout from the plugin. Keeps the cached one if the server can't be reached. */
    suspend fun refreshLayout() {
        try {
            val raw = app.api.layoutJson()
            app.session.cachedLayoutJson = raw
            layoutState.value = LayoutConfig.parse(raw)
        } catch (e: ApiException) {
            if (e.code == 401) logout()
            // 404 = plugin not installed: just keep defaults.
        } catch (e: Exception) {
            // Offline or server down: keep the cached layout.
        }
    }
}

@Composable
fun SleekTvApp(app: SleekApp) {
    val stack = remember {
        mutableStateListOf<Screen>(if (app.session.isLoggedIn) Screen.Home else Screen.Login)
    }
    val layout = remember { mutableStateOf(LayoutConfig.parse(app.session.cachedLayoutJson)) }
    val nav = remember { Nav(app, stack, layout) }

    BackHandler(enabled = stack.size > 1) { nav.back() }

    SleekTheme(layout.value) {
        Box(
            Modifier
                .fillMaxSize()
                .background(LocalSleekColors.current.background),
        ) {
            val screen = stack.last()
            key(screen, stack.size) {
                when (screen) {
                    Screen.Login -> LoginScreen(nav)
                    Screen.Home -> HomeScreen(nav)
                    Screen.Account -> AccountScreen(nav)
                    is Screen.Library -> LibraryScreen(nav, screen.view)
                    is Screen.Detail -> DetailScreen(nav, screen.itemId)
                    is Screen.Player -> PlayerScreen(nav, screen.itemId)
                }
            }
        }
    }
}
