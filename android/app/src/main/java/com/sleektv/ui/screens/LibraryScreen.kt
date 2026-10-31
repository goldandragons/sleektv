package com.sleektv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sleektv.data.BaseItem
import com.sleektv.ui.Nav
import com.sleektv.ui.Screen
import com.sleektv.ui.components.CenterMessage
import com.sleektv.ui.components.EDGE
import com.sleektv.ui.components.MediaCard
import com.sleektv.ui.components.SText
import com.sleektv.ui.components.SleekClickable
import com.sleektv.ui.components.SleekHeader
import com.sleektv.ui.components.rememberHeaderVisible
import com.sleektv.ui.theme.LocalLayout
import com.sleektv.ui.theme.LocalSleekColors
import com.sleektv.ui.theme.scaledSp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val PAGE = 100
private val LETTERS = listOf('#') + ('A'..'Z')

/** '#' for anything that doesn't start with A–Z (numbers, symbols, other alphabets). */
private fun letterOf(sortName: String): Char {
    val ch = sortName.trimStart().firstOrNull()?.uppercaseChar() ?: return '#'
    return if (ch in 'A'..'Z') ch else '#'
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LibraryScreen(nav: Nav, view: BaseItem) {
    val api = nav.app.api
    val layout = LocalLayout.current
    val c = LocalSleekColors.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<BaseItem>() }
    var total by remember { mutableIntStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    val firstFocus = remember { FocusRequester() }
    val jumpFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var jumpTarget by remember { mutableIntStateOf(-1) }
    val loadLock = remember { Mutex() }

    // Letter -> index of the first title starting with it, from the full sorted list.
    var letterIndex by remember { mutableStateOf<Map<Char, Int>>(emptyMap()) }
    var sortedLetters by remember { mutableStateOf<List<Char>>(emptyList()) }

    val includeTypes = when (view.collectionType) {
        "movies" -> "Movie"
        "tvshows" -> "Series"
        "boxsets" -> "BoxSet"
        else -> null
    }
    val recursive = includeTypes != null
    val landscape = layout.postersLandscape

    /** Loads pages until at least [count] items are loaded (or the library runs out). */
    suspend fun loadUntil(count: Int) = loadLock.withLock {
        while ((total < 0 || items.size < total) && items.size < count) {
            try {
                val page = api.items(
                    parentId = view.id,
                    includeTypes = includeTypes,
                    recursive = recursive,
                    startIndex = items.size,
                    limit = maxOf(PAGE, count - items.size + 24),
                )
                total = page.totalRecordCount
                if (page.items.isEmpty()) break
                items.addAll(page.items)
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load this library"
                break
            }
        }
    }

    LaunchedEffect(view.id) {
        loadUntil(PAGE)
        delay(150)
        runCatching { firstFocus.requestFocus() }
    }

    LaunchedEffect(view.id) {
        runCatching { api.sortNames(view.id, includeTypes, recursive) }.getOrNull()?.let { names ->
            val map = LinkedHashMap<Char, Int>()
            names.forEachIndexed { i, name -> map.putIfAbsent(letterOf(name), i) }
            letterIndex = map
            sortedLetters = names.map(::letterOf)
        }
    }

    // Load the next page when the user scrolls near the end.
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            items.isNotEmpty() && last >= items.size - 24
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) loadUntil(items.size + PAGE) }

    // Letter of the first title on screen, so the rail can highlight where you are.
    val currentLetter by remember {
        derivedStateOf {
            val first = (gridState.firstVisibleItemIndex - 1).coerceAtLeast(0) // item 0 is the title
            sortedLetters.getOrNull(first) ?: items.getOrNull(first)?.let { letterOf(it.sortName ?: it.name.orEmpty()) }
        }
    }

    fun jumpTo(letter: Char) {
        val target = letterIndex[letter] ?: return
        scope.launch {
            loadUntil(target + 1)
            if (target >= items.size) return@launch
            jumpTarget = target
            gridState.scrollToItem(target + 1)
            delay(120)
            runCatching { jumpFocus.requestFocus() }
        }
    }

    val headerVisible = rememberHeaderVisible(
        atTop = { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset < 40 },
        position = { gridState.firstVisibleItemIndex * 100_000L + gridState.firstVisibleItemScrollOffset },
    )
    val topPad = layout.headerSpaceDp.dp
    val showRail = letterIndex.size > 1

    Box(Modifier.fillMaxSize()) {
        when {
            error != null && items.isEmpty() -> CenterMessage(error.orEmpty())
            total == 0 -> CenterMessage("This library is empty.")
            items.isEmpty() -> CenterMessage("Loading…")
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = if (landscape) (layout.posterWidth * 1.85f).dp else layout.posterWidth.dp),
                contentPadding = PaddingValues(
                    start = EDGE.dp,
                    end = if (showRail) (EDGE + 44).dp else EDGE.dp,
                    top = topPad,
                    bottom = 48.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(layout.cardSpacing.dp),
                verticalArrangement = Arrangement.spacedBy((layout.rowSpacing * 0.8f).dp),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(contentFocus),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "title") {
                    SText(
                        text = view.name.orEmpty() + if (total > 0) "  ·  $total" else "",
                        size = scaledSp(layout.sectionTitleSize + 8),
                        weight = FontWeight.Bold,
                        color = c.text,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                }
                itemsIndexed(items, key = { index, it -> "$index-${it.id}" }) { index, item ->
                    MediaCard(
                        item = item,
                        imageUrl = if (landscape) api.landscape(item) else api.poster(item),
                        landscape = landscape,
                        onClick = { nav.open(item) },
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = when (index) {
                            jumpTarget -> jumpFocus
                            0 -> firstFocus
                            else -> null
                        },
                    )
                }
            }
        }

        if (showRail && items.isNotEmpty()) {
            AlphabetRail(
                available = letterIndex.keys,
                current = currentLetter,
                onPick = ::jumpTo,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = topPad, end = 12.dp, bottom = 24.dp)
                    .fillMaxHeight(),
            )
        }

        SleekHeader(
            views = nav.views,
            selectedId = view.id,
            onHome = { nav.resetTo(Screen.Home) },
            onView = { if (it.id != view.id) nav.push(Screen.Library(it)) },
            onSearch = { nav.push(Screen.Search) },
            onAccount = { nav.push(Screen.Account) },
            modifier = Modifier.align(Alignment.TopCenter),
            visible = headerVisible,
            contentFocus = contentFocus,
        )
    }
}

/** Vertical # A–Z list. Scrolls with the D-pad; OK jumps the grid to that letter. */
@Composable
private fun AlphabetRail(
    available: Set<Char>,
    current: Char?,
    onPick: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSleekColors.current
    LazyColumn(
        modifier = modifier.width(36.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(LETTERS, key = { it }) { letter ->
            val enabled = letter in available
            val selected = letter == current
            SleekClickable(
                onClick = { if (enabled) onPick(letter) },
                shape = CircleShape,
                focusScale = 1.25f,
                containerColor = if (selected) c.accent else Color.Transparent,
                focusedContainerColor = Color.White,
                contentColor = when {
                    selected -> Color.White
                    enabled -> c.text
                    else -> c.faint.copy(alpha = 0.3f)
                },
                focusedContentColor = Color.Black,
                modifier = Modifier.size(28.dp),
            ) {
                androidx.tv.material3.Text(
                    text = letter.toString(),
                    modifier = Modifier.align(Alignment.Center),
                    fontFamily = com.sleektv.ui.theme.Inter,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = scaledSp(12),
                    maxLines = 1,
                )
            }
        }
    }
}
