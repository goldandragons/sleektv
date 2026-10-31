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
import com.sleektv.ui.screens.SearchScreen
import com.sleektv.ui.screens.SeerrDetailScreen
import com.sleektv.data.SeerrItem
import com.sleektv.data.WebCss
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sleektv.ui.theme.LocalSleekColors
import com.sleektv.ui.theme.SleekTheme

sealed interface Screen {
    data object Login : Screen
    data object Home : Screen
    data object Account : Screen
    data object Search : Screen
    data class Library(val view: BaseItem) : Screen
    data class Detail(val itemId: String) : Screen
    data class Player(val itemId: String) : Screen
    data class SeerrDetail(val mediaType: String, val tmdbId: Int) : Screen
}

/** Seerr rows for the home screen. */
data class DiscoverData(
    val trending: List<SeerrItem> = emptyList(),
    val movies: List<SeerrItem> = emptyList(),
    val shows: List<SeerrItem> = emptyList(),
)

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
    var discoverCache by mutableStateOf<DiscoverData?>(null)
    var discoverFetchedAt = 0L

    /** Which Search tab was last open: library or Seerr. */
    var searchSeerrTab by mutableStateOf(false)
    val homeListState = LazyListState()

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun back() {
        // removeAt instead of removeLast: removeLast crashes on older Android when built against SDK 35.
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Swap the current screen for another (e.g. next episode) without growing the back stack. */
    fun replace(screen: Screen) {
        if (stack.isNotEmpty()) stack[stack.lastIndex] = screen else stack.add(screen)
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

    /** Opens a Seerr title; if it's already in Jellyfin, goes straight to the library page. */
    fun openSeerr(item: SeerrItem) {
        val jellyfinId = item.mediaInfo?.jellyfinMediaId
        if (item.isAvailable && jellyfinId != null) push(Screen.Detail(jellyfinId))
        else push(Screen.SeerrDetail(item.mediaType ?: "movie", item.id))
    }

    fun logout() {
        app.session.logout()
        homeCache = null
        discoverCache = null
        views = emptyList()
        resetTo(Screen.Login)
    }

    /**
     * Downloads the themes named in `@import` lines. If a download fails, keeps the last
     * copy for the same imports, so a theme doesn't flicker off when the internet blips.
     */
    private suspend fun importedCss(raw: String): String {
        val session = app.session
        val styles = LayoutConfig.customStylesOf(raw)
        val key = WebCss.importUrls(styles).joinToString("\n")
        if (key.isEmpty()) {
            session.importsKey = ""
            session.importsCss = ""
            return ""
        }
        // Same themes as last time and downloaded recently: reuse the saved copy.
        val ageMs = System.currentTimeMillis() - session.importsFetchedAt
        if (session.importsKey == key && session.importsCss.isNotBlank() && ageMs in 0 until 6 * 60 * 60 * 1000L) {
            return session.importsCss
        }
        val fresh = WebCss.resolveImports(styles) { url -> app.api.fetchText(url) }
        return when {
            fresh.isNotBlank() -> {
                session.importsKey = key
                session.importsCss = fresh
                session.importsFetchedAt = System.currentTimeMillis()
                fresh
            }
            session.importsKey == key -> session.importsCss
            else -> ""
        }
    }

    private var lastRefreshAt = 0L

    /** What happened the last time settings were fetched; shown on the Account screen. */
    var layoutStatus by mutableStateOf("Settings not loaded yet")
        private set

    val layout: LayoutConfig get() = layoutState.value

    /**
     * Pull the latest layout from the plugin. Keeps the cached one if the server can't be reached.
     * Skips the request if settings were fetched in the last 30 seconds, unless [force]d.
     */
    suspend fun refreshLayout(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshAt < 30_000) return
        lastRefreshAt = now
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        try {
            val raw = app.api.layoutJson()
            // Parsing themes can be heavy (hundreds of CSS rules): keep it off the UI thread.
            val (problem, parsed) = withContext(Dispatchers.Default) {
                val problem = LayoutConfig.decodeProblem(raw)
                problem to if (problem == null) LayoutConfig.parse(raw, importedCss(raw)) else null
            }
            if (problem != null || parsed == null) {
                layoutStatus = "Couldn't read the plugin's settings ($time): $problem"
                return
            }
            app.session.cachedLayoutJson = raw
            if (parsed != layoutState.value) layoutState.value = parsed // only redraw when something changed
            app.session.finalLayoutJson = LayoutConfig.encodeFinal(parsed)
            layoutStatus = "Updated from your server at $time"
        } catch (e: ApiException) {
            when (e.code) {
                401 -> logout()
                404 -> layoutStatus = "The SleekTV plugin isn't installed on this server ($time). Using built-in defaults."
                else -> layoutStatus = "Server error ${e.code} while loading settings ($time). Using the last saved copy."
            }
        } catch (e: Exception) {
            layoutStatus = "Couldn't reach your server ($time). Using the last saved copy."
        }
    }
}

@Composable
fun SleekTvApp(app: SleekApp) {
    val stack = remember {
        mutableStateListOf<Screen>(if (app.session.isLoggedIn) Screen.Home else Screen.Login)
    }
    val layout = remember {
        mutableStateOf(
            LayoutConfig.decodeFinal(app.session.finalLayoutJson)
                ?: LayoutConfig.parse(app.session.cachedLayoutJson, app.session.importsCss),
        )
    }
    val nav = remember { Nav(app, stack, layout) }

    BackHandler(enabled = stack.size > 1) { nav.back() }

    // Pick up settings changed on the server whenever the app comes back to the screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && app.session.isLoggedIn) {
                app.appScope.launch(Dispatchers.Main) { nav.refreshLayout() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                    Screen.Search -> SearchScreen(nav)
                    is Screen.Library -> LibraryScreen(nav, screen.view)
                    is Screen.Detail -> DetailScreen(nav, screen.itemId)
                    is Screen.Player -> PlayerScreen(nav, screen.itemId)
                    is Screen.SeerrDetail -> SeerrDetailScreen(nav, screen.mediaType, screen.tmdbId)
                }
            }
        }
    }
}
