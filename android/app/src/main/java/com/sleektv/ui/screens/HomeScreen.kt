package com.sleektv.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sleektv.data.ApiException
import com.sleektv.data.BaseItem
import com.sleektv.data.JellyfinApi
import com.sleektv.data.LayoutConfig
import com.sleektv.data.episodeCode
import com.sleektv.data.cardTitle
import com.sleektv.data.isEpisode
import com.sleektv.data.progress
import com.sleektv.data.runtimeText
import com.sleektv.ui.components.ProgressBar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.sleektv.data.metaLine
import com.sleektv.data.SeerrItem
import com.sleektv.ui.DiscoverData
import com.sleektv.ui.Nav
import com.sleektv.ui.Screen
import com.sleektv.ui.components.SeerrRow
import com.sleektv.ui.components.rememberHeaderVisible
import com.sleektv.ui.components.ScrollOnlyWhenNeeded
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import androidx.compose.ui.ExperimentalComposeUiApi
import com.sleektv.ui.components.BackdropScrim
import com.sleektv.ui.components.CenterMessage
import com.sleektv.ui.components.EDGE
import com.sleektv.ui.components.MediaRow
import com.sleektv.ui.components.PagerDots
import com.sleektv.ui.components.SText
import com.sleektv.ui.components.SleekButton
import com.sleektv.ui.components.SleekHeader
import com.sleektv.ui.theme.LocalLayout
import com.sleektv.ui.theme.LocalSleekColors
import com.sleektv.ui.theme.scaledSp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

data class HomeData(
    val views: List<BaseItem>,
    val resume: List<BaseItem>,
    val nextUp: List<BaseItem>,
    val latest: List<Pair<BaseItem, List<BaseItem>>>,
) {
    /**
     * What the hero banner rotates through.
     * "ContinueWatching": in-progress items, then Next Up; falls back to Latest if both are empty.
     * "Latest": newly added movies and shows, alternating between libraries.
     */
    fun heroItems(source: String): List<BaseItem> {
        if (source.equals("ContinueWatching", ignoreCase = true)) {
            val hasArt = { it: BaseItem -> it.backdropImageTags.isNotEmpty() || it.parentBackdropImageTags.isNotEmpty() }
            val watching = (resume + nextUp).distinctBy { it.id }.filter(hasArt).take(10)
            if (watching.isNotEmpty()) return watching
        }
        return latestHero
    }

    private val latestHero: List<BaseItem>
        get() {
            val pools = latest.map { (_, items) -> items.filter { it.backdropImageTags.isNotEmpty() } }
            val out = mutableListOf<BaseItem>()
            var i = 0
            while (out.size < 8 && pools.any { i < it.size }) {
                pools.forEach { pool -> if (i < pool.size && out.size < 8) out += pool[i] }
                i++
            }
            return out
        }
}

private val mediaLibraries = setOf("movies", "tvshows")

private suspend fun loadHome(api: JellyfinApi, layout: LayoutConfig): HomeData = coroutineScope {
    val views = api.views()
    val resume = async { runCatching { api.resume() }.getOrDefault(emptyList()) }
    val nextUp = async { runCatching { api.nextUp() }.getOrDefault(emptyList()) }
    val latest = views
        .filter { it.collectionType in mediaLibraries }
        .map { view ->
            async { view to runCatching { api.latest(view.id, layout.latestItemsPerLibrary) }.getOrDefault(emptyList()) }
        }
    HomeData(views, resume.await(), nextUp.await(), latest.awaitAll())
}

@Composable
fun HomeScreen(nav: Nav) {
    val api = nav.app.api
    val layout = LocalLayout.current
    var data by remember { mutableStateOf(nav.homeCache) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        nav.refreshLayout()
        try {
            val fresh = loadHome(api, layout)
            val firstLoad = data == null
            data = fresh
            nav.homeCache = fresh
            nav.views = fresh.views
            if (firstLoad) {
                delay(150)
                runCatching { firstFocus.requestFocus() }
            }
        } catch (e: ApiException) {
            if (e.code == 401) nav.logout() else if (data == null) error = e.message
        } catch (e: Exception) {
            if (data == null) error = e.message ?: "Couldn't reach your server"
        }
    }

    // Seerr recommendations load separately so a slow or offline Seerr never holds up the home screen.
    LaunchedEffect(layout.seerrEnabled, layout.showSeerrOnHome) {
        if (!layout.seerrEnabled || !layout.showSeerrOnHome) return@LaunchedEffect
        coroutineScope {
            // Only suggest things that aren't already in the library.
            fun List<SeerrItem>.toAdd() = filterNot { it.isAvailable }
            val trending = async { runCatching { api.seerrDiscover("trending").toAdd() }.getOrDefault(emptyList()) }
            val movies = async { runCatching { api.seerrDiscover("movies").toAdd() }.getOrDefault(emptyList()) }
            val shows = async { runCatching { api.seerrDiscover("tv").toAdd() }.getOrDefault(emptyList()) }
            nav.discoverCache = DiscoverData(trending.await(), movies.await(), shows.await())
        }
    }

    val home = data
    when {
        home == null && error != null -> CenterMessage("Couldn't load your library.\n$error") {
            SleekButton("Sign in again", onClick = { nav.logout() }, primary = true)
        }
        home == null -> CenterMessage("Loading…")
        else -> ScrollOnlyWhenNeeded { HomeContent(nav, home, nav.discoverCache, firstFocus) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HomeContent(nav: Nav, home: HomeData, discover: DiscoverData?, firstFocus: FocusRequester) {
    val api = nav.app.api
    val layout = LocalLayout.current
    val heroItems = remember(home, layout.heroSource) { home.heroItems(layout.heroSource) }
    val showHero = layout.showHero && heroItems.isNotEmpty()
    val topPad = if (showHero || layout.headerHidden) 0.dp else 96.dp
    val listState = nav.homeListState
    val contentFocus = remember { FocusRequester() }
    val headerEntry = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var heroFocused by remember { mutableStateOf(false) }
    var headerCalled by remember { mutableStateOf(false) } // Up pressed on the banner
    LaunchedEffect(heroFocused) { if (heroFocused) headerCalled = false }
    val headerVisible = rememberHeaderVisible(
        atTop = { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 },
        position = { listState.firstVisibleItemIndex * 100_000L + listState.firstVisibleItemScrollOffset },
    )

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = topPad, bottom = 56.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(contentFocus),
        ) {
            var focusUsed = false
            fun nextFocus(): FocusRequester? = if (!focusUsed) { focusUsed = true; firstFocus } else null

            if (showHero) {
                val heroFocus = nextFocus()
                item(key = "hero") {
                    Hero(
                        items = heroItems,
                        api = api,
                        playFocus = heroFocus,
                        onPlay = { nav.play(it) },
                        onInfo = { nav.open(it) },
                        onFocusChange = { heroFocused = it },
                        onUp = {
                            if (layout.headerHidden) {
                                false
                            } else {
                                // Bring the header back, then step up into it.
                                headerCalled = true
                                scope.launch {
                                    withFrameNanos { }
                                    withFrameNanos { }
                                    runCatching { headerEntry.requestFocus() }
                                }
                                true
                            }
                        },
                    )
                }
            }

            layout.homeRowList.forEach { row ->
                when (row.lowercase()) {
                    "continuewatching" -> if (home.resume.isNotEmpty()) {
                        val f = nextFocus()
                        item(key = "resume") {
                            MediaRow(
                                title = "Continue Watching",
                                items = home.resume,
                                landscape = layout.continueLandscape,
                                imageFor = { if (layout.continueLandscape) api.landscape(it) else api.poster(it) },
                                onClick = { nav.play(it) },
                                firstItemFocus = f,
                                modifier = Modifier.padding(top = layout.rowSpacing.dp),
                            )
                        }
                    }

                    "nextup" -> if (home.nextUp.isNotEmpty()) {
                        val f = nextFocus()
                        item(key = "nextup") {
                            MediaRow(
                                title = "Next Up",
                                items = home.nextUp,
                                landscape = true,
                                imageFor = { api.landscape(it) },
                                onClick = { nav.play(it) },
                                subtitleFor = { listOfNotNull(it.episodeCode, it.name).joinToString(" · ") },
                                firstItemFocus = f,
                                modifier = Modifier.padding(top = layout.rowSpacing.dp),
                            )
                        }
                    }

                    "latest" -> home.latest.forEach { (view, items) ->
                        if (items.isNotEmpty()) {
                            val f = nextFocus()
                            item(key = "latest-${view.id}") {
                                MediaRow(
                                    title = "Latest in ${view.name.orEmpty()}",
                                    items = items,
                                    landscape = layout.postersLandscape,
                                    imageFor = { if (layout.postersLandscape) api.landscape(it) else api.poster(it) },
                                    onClick = { nav.open(it) },
                                    firstItemFocus = f,
                                    modifier = Modifier.padding(top = layout.rowSpacing.dp),
                                )
                            }
                        }
                    }

                    "discover" -> if (discover != null) {
                        listOf(
                            "trending" to ("Trending — add to your library" to discover.trending),
                            "movies" to ("Popular movies to add" to discover.movies),
                            "shows" to ("Popular shows to add" to discover.shows),
                        ).forEach { (key, row) ->
                            val (title, items) = row
                            if (items.isNotEmpty()) {
                                item(key = "discover-$key") {
                                    SeerrRow(
                                        title = title,
                                        items = items,
                                        onClick = { nav.openSeerr(it) },
                                        modifier = Modifier.padding(top = layout.rowSpacing.dp),
                                    )
                                }
                            }
                        }
                    }

                    "libraries" -> if (home.views.isNotEmpty()) {
                        val f = nextFocus()
                        item(key = "libraries") {
                            MediaRow(
                                title = "Your Libraries",
                                items = home.views,
                                landscape = true,
                                imageFor = { api.landscape(it) },
                                onClick = { nav.push(Screen.Library(it)) },
                                subtitleFor = { "" },
                                firstItemFocus = f,
                                modifier = Modifier.padding(top = layout.rowSpacing.dp),
                            )
                        }
                    }

                    else -> {} // unknown row names are ignored
                }
                Unit
            }
        }

        SleekHeader(
            views = home.views,
            selectedId = null,
            onHome = { },
            onView = { nav.push(Screen.Library(it)) },
            onSearch = { nav.push(Screen.Search) },
            onAccount = { nav.push(Screen.Account) },
            modifier = Modifier.align(Alignment.TopCenter),
            // Hidden while you're on the banner so it doesn't cover the artwork.
            visible = headerVisible && (!heroFocused || headerCalled),
            contentFocus = contentFocus,
            entryFocus = headerEntry,
        )
    }
}

/** Full-width featured banner with title art, meta, overview and Play / More info. */
@Composable
private fun Hero(
    items: List<BaseItem>,
    api: JellyfinApi,
    playFocus: FocusRequester?,
    onPlay: (BaseItem) -> Unit,
    onInfo: (BaseItem) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onUp: () -> Boolean,
) {
    val layout = LocalLayout.current
    var playFocused by remember { mutableStateOf(false) }
    var infoFocused by remember { mutableStateOf(false) }
    val c = LocalSleekColors.current
    var index by remember { mutableIntStateOf(0) }
    var hasFocus by remember { mutableStateOf(false) }

    // Rotate slides, but hold still while the buttons are focused so Play stays on what you see.
    LaunchedEffect(items, hasFocus, layout.heroAutoRotateSeconds) {
        val seconds = layout.heroAutoRotateSeconds
        if (seconds <= 0 || hasFocus || items.size < 2) return@LaunchedEffect
        while (true) {
            delay(seconds * 1000L)
            index = (index + 1) % items.size
        }
    }

    val item = items[index % items.size]

    Box(
        Modifier
            .fillMaxWidth()
            .height(layout.heroHeight.dp),
    ) {
        Crossfade(targetState = item, animationSpec = tween(700), label = "hero") { shown ->
            AsyncImage(
                model = api.backdrop(shown),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
        BackdropScrim()

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = EDGE.dp, bottom = 18.dp)
                .fillMaxWidth(0.48f),
        ) {
            val logo = api.logo(item)
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier
                        .heightIn(max = (layout.heroTitleSize * 2.6f).dp)
                        .widthIn(max = 460.dp),
                )
            } else {
                SText(
                    item.cardTitle,
                    size = scaledSp(layout.heroTitleSize),
                    weight = FontWeight.ExtraBold,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(14.dp))
            // Episodes: "S2:E5 · Episode name"; everything else: year · rating · runtime.
            val meta = if (item.isEpisode) {
                listOfNotNull(item.episodeCode, item.name, item.runtimeText).joinToString("  ·  ")
            } else {
                item.metaLine()
            }
            if (meta.isNotBlank()) {
                SText(meta, size = scaledSp(14), weight = FontWeight.Medium, color = c.muted, maxLines = 1)
                Spacer(Modifier.height(8.dp))
            }
            item.progress?.let {
                ProgressBar(it, Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(10.dp))
            }
            item.overview?.takeIf { it.isNotBlank() }?.let {
                SText(it, size = scaledSp(14), color = c.muted, maxLines = 3)
                Spacer(Modifier.height(18.dp))
            }
            val resumable = (item.userData?.playbackPositionTicks ?: 0L) > 0L
            // Left from Play / Right from More info flips slides; Up hands focus to the header.
            Row(
                Modifier
                    .onFocusChanged {
                        hasFocus = it.hasFocus
                        onFocusChange(it.hasFocus)
                    }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            e.key == Key.DirectionLeft && playFocused && items.size > 1 -> {
                                index = (index - 1 + items.size) % items.size
                                true
                            }
                            e.key == Key.DirectionRight && infoFocused && items.size > 1 -> {
                                index = (index + 1) % items.size
                                true
                            }
                            e.key == Key.DirectionUp -> onUp()
                            else -> false
                        }
                    },
            ) {
                SleekButton(
                    text = if (resumable) "▶  Resume" else "▶  Play",
                    onClick = { onPlay(item) },
                    primary = true,
                    modifier = (if (playFocus != null) Modifier.focusRequester(playFocus) else Modifier)
                        .onFocusChanged { playFocused = it.isFocused },
                )
                Spacer(Modifier.width(12.dp))
                SleekButton(
                    text = "More info",
                    onClick = { onInfo(item) },
                    modifier = Modifier.onFocusChanged { infoFocused = it.isFocused },
                )
            }
            Spacer(Modifier.height(16.dp))
            if (items.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SText("‹", size = scaledSp(18), weight = FontWeight.Bold, color = if (hasFocus) c.text else c.faint)
                    Spacer(Modifier.width(10.dp))
                    PagerDots(items.size, index % items.size)
                    Spacer(Modifier.width(10.dp))
                    SText("›", size = scaledSp(18), weight = FontWeight.Bold, color = if (hasFocus) c.text else c.faint)
                    Spacer(Modifier.width(12.dp))
                    SText("${index % items.size + 1} / ${items.size}", size = scaledSp(12), color = c.faint)
                }
            }
        }
    }
}
