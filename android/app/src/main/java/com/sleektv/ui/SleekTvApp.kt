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
import com.sleektv.ui.screens.PersonScreen
import com.sleektv.data.SeerrItem
import com.sleektv.data.WebCss
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleektv.data.cardSubtitle
import com.sleektv.data.cardTitle
import com.sleektv.data.isWatched
import com.sleektv.ui.components.LocalItemHold
import com.sleektv.ui.components.LocalWatchedOverrides
import com.sleektv.ui.components.LocalCardFlash
import com.sleektv.ui.components.SText
import com.sleektv.ui.components.SleekClickable
import com.sleektv.ui.theme.Inter
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
    data class Player(val itemId: String, val fromStart: Boolean = false) : Screen
    data class SeerrDetail(val mediaType: String, val tmdbId: Int) : Screen
    data class Person(val personId: String, val name: String) : Screen
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

    // ---------- Watched status and the hold-OK quick menu ----------

    /** Watched changes made in this session; cards read these so checkmarks update instantly. */
    val watchedOverrides = mutableStateMapOf<String, Boolean>()

    /** Goes up whenever watched status changes, so open pages can refresh (e.g. which episode Resume plays). */
    var dataVersion by mutableIntStateOf(0)
        private set

    var menuFor by mutableStateOf<BaseItem?>(null)
    var toast by mutableStateOf<String?>(null)

    /** The card that just changed and what to say on it, e.g. "Marked as watched". */
    var cardFlash by mutableStateOf<Pair<String, String>?>(null)

    fun isWatched(item: BaseItem): Boolean = watchedOverrides[item.id] ?: item.isWatched

    /** What holding OK on a card does: quick menu, or toggle watched straight away (plugin setting). */
    fun hold(item: BaseItem) {
        if (layout.holdAction.equals("ToggleWatched", ignoreCase = true)) setWatched(item, !isWatched(item))
        else menuFor = item
    }

    fun setWatched(item: BaseItem, watched: Boolean) {
        watchedOverrides[item.id] = watched
        cardFlash = item.id to (if (watched) "Marked as watched" else "Marked as unwatched")
        app.appScope.launch(Dispatchers.Main) {
            try {
                app.api.setWatched(item.id, watched)
                dataVersion++
            } catch (e: Exception) {
                watchedOverrides.remove(item.id)
                toast = "Couldn't update: ${e.message ?: "server error"}"
            }
        }
    }

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
      CompositionLocalProvider(
        LocalItemHold provides nav::hold,
        LocalWatchedOverrides provides nav.watchedOverrides,
        LocalCardFlash provides nav.cardFlash,
      ) {
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
                    is Screen.Player -> PlayerScreen(nav, screen.itemId, screen.fromStart)
                    is Screen.SeerrDetail -> SeerrDetailScreen(nav, screen.mediaType, screen.tmdbId)
                    is Screen.Person -> PersonScreen(nav, screen.personId, screen.name)
                }
            }
            nav.menuFor?.let { ItemQuickMenu(nav, it) }
            ToastPill(nav)
            // Clear the on-card message after a moment.
            LaunchedEffect(nav.cardFlash) {
                if (nav.cardFlash != null) {
                    kotlinx.coroutines.delay(1_600)
                    nav.cardFlash = null
                }
            }
        }
      }
    }
}


/** Hold-OK menu: mark watched/unwatched (pre-selected), play from the start, go to the show. */
@Composable
private fun ItemQuickMenu(nav: Nav, item: BaseItem) {
    val c = LocalSleekColors.current
    val first = remember { FocusRequester() }
    val watched = nav.isWatched(item)
    val resumable = (item.userData?.playbackPositionTicks ?: 0L) > 0L && (item.isEpisode || item.type == "Movie")
    fun close() { nav.menuFor = null }
    BackHandler { close() }
    LaunchedEffect(item.id) { runCatching { first.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(c.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SText(item.cardTitle, size = 22.sp, weight = FontWeight.Bold, maxLines = 2)
            item.cardSubtitle?.takeIf { it.isNotBlank() }?.let { SText(it, size = 14.sp, color = c.muted, maxLines = 1) }
            Spacer(Modifier.height(6.dp))
            MenuButton(
                if (watched) "✓  Mark as unwatched" else "✓  Mark as watched",
                Modifier.focusRequester(first),
            ) { close(); nav.setWatched(item, !watched) }
            if (resumable) MenuButton("▶  Play from the beginning") { close(); nav.push(Screen.Player(item.id, fromStart = true)) }
            if (item.isEpisode && item.seriesId != null && (nav.current as? Screen.Detail)?.itemId != item.seriesId) {
                MenuButton("Go to show") { close(); nav.push(Screen.Detail(item.seriesId)) }
            }
            MenuButton("Cancel") { close() }
        }
    }
}

@Composable
private fun MenuButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalSleekColors.current
    SleekClickable(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        focusScale = 1.03f,
        containerColor = Color.White.copy(alpha = 0.06f),
        focusedContainerColor = Color.White,
        contentColor = c.text,
        focusedContentColor = Color.Black,
        modifier = modifier.fillMaxWidth(),
    ) {
        androidx.tv.material3.Text(
            text = text,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
    }
}

/** Small confirmation at the bottom of the screen, e.g. "Marked as watched". */
@Composable
private fun BoxScope.ToastPill(nav: Nav) {
    val message = nav.toast
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(2_200)
            nav.toast = null
        }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            SText(message ?: "", size = 15.sp, weight = FontWeight.SemiBold, color = Color.White)
        }
    }
}
