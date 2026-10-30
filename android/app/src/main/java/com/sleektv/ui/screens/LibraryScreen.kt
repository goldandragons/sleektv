package com.sleektv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sleektv.data.BaseItem
import com.sleektv.ui.Nav
import com.sleektv.ui.Screen
import com.sleektv.ui.components.CenterMessage
import com.sleektv.ui.components.EDGE
import com.sleektv.ui.components.MediaCard
import com.sleektv.ui.components.SText
import com.sleektv.ui.components.SleekHeader
import com.sleektv.ui.theme.LocalLayout
import com.sleektv.ui.theme.LocalSleekColors
import com.sleektv.ui.theme.scaledSp
import kotlinx.coroutines.delay

private const val PAGE = 100

@Composable
fun LibraryScreen(nav: Nav, view: BaseItem) {
    val api = nav.app.api
    val layout = LocalLayout.current
    val c = LocalSleekColors.current
    val items = remember { mutableStateListOf<BaseItem>() }
    var total by remember { mutableIntStateOf(-1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    val firstFocus = remember { FocusRequester() }

    val includeTypes = when (view.collectionType) {
        "movies" -> "Movie"
        "tvshows" -> "Series"
        "boxsets" -> "BoxSet"
        else -> null
    }
    val landscape = layout.postersLandscape

    suspend fun loadMore() {
        if (loading || (total >= 0 && items.size >= total)) return
        loading = true
        try {
            val page = api.items(
                parentId = view.id,
                includeTypes = includeTypes,
                recursive = includeTypes != null,
                startIndex = items.size,
                limit = PAGE,
            )
            total = page.totalRecordCount
            items.addAll(page.items)
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this library"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(view.id) {
        loadMore()
        delay(150)
        runCatching { firstFocus.requestFocus() }
    }

    // Load the next page when the user scrolls near the end.
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            items.isNotEmpty() && last >= items.size - 24
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) loadMore() }

    Box(Modifier.fillMaxSize()) {
        when {
            error != null && items.isEmpty() -> CenterMessage(error.orEmpty())
            total == 0 -> CenterMessage("This library is empty.")
            items.isEmpty() -> CenterMessage("Loading…")
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = if (landscape) (layout.posterWidth * 1.85f).dp else layout.posterWidth.dp),
                contentPadding = PaddingValues(
                    start = EDGE.dp, end = EDGE.dp,
                    top = if (layout.headerHidden) 32.dp else 100.dp,
                    bottom = 48.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(layout.cardSpacing.dp),
                verticalArrangement = Arrangement.spacedBy((layout.rowSpacing * 0.8f).dp),
                modifier = Modifier.fillMaxSize(),
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
                        focusRequester = if (index == 0) firstFocus else null,
                    )
                }
            }
        }

        SleekHeader(
            views = nav.views,
            selectedId = view.id,
            onHome = { nav.resetTo(Screen.Home) },
            onView = { if (it.id != view.id) nav.push(Screen.Library(it)) },
            onAccount = { nav.push(Screen.Account) },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
