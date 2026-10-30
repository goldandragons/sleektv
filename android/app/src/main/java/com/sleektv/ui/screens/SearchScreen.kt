package com.sleektv.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleektv.data.BaseItem
import com.sleektv.data.episodeCode
import com.sleektv.ui.Nav
import com.sleektv.ui.Screen
import com.sleektv.ui.components.EDGE
import com.sleektv.ui.components.MediaRow
import com.sleektv.ui.components.SText
import com.sleektv.ui.components.SleekHeader
import com.sleektv.ui.theme.Inter
import com.sleektv.ui.theme.LocalLayout
import com.sleektv.ui.theme.LocalSleekColors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

private data class SearchResults(
    val movies: List<BaseItem> = emptyList(),
    val shows: List<BaseItem> = emptyList(),
    val episodes: List<BaseItem> = emptyList(),
) {
    val isEmpty: Boolean get() = movies.isEmpty() && shows.isEmpty() && episodes.isEmpty()
}

/** Last search, so pressing Back from a result returns to the same results. */
private var lastQuery = ""

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(nav: Nav) {
    val api = nav.app.api
    val layout = LocalLayout.current
    val c = LocalSleekColors.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }

    var query by remember { mutableStateOf(lastQuery) }
    var results by remember { mutableStateOf(SearchResults()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    // Search as you type, after a short pause.
    LaunchedEffect(query) {
        lastQuery = query
        val term = query.trim()
        if (term.length < 2) {
            results = SearchResults()
            error = null
            return@LaunchedEffect
        }
        delay(400)
        searching = true
        try {
            results = coroutineScope {
                val movies = async { runCatching { api.search(term, "Movie") }.getOrDefault(emptyList()) }
                val shows = async { runCatching { api.search(term, "Series") }.getOrDefault(emptyList()) }
                val episodes = async { runCatching { api.search(term, "Episode", 24) }.getOrDefault(emptyList()) }
                SearchResults(movies.await(), shows.await(), episodes.await())
            }
            error = null
        } catch (e: Exception) {
            error = e.message ?: "Search failed"
        } finally {
            searching = false
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.text.copy(alpha = 0.3f),
        cursorColor = c.accent,
        focusedTextColor = c.text,
        unfocusedTextColor = c.text,
        focusedLabelColor = c.text,
        unfocusedLabelColor = c.muted,
    )

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(top = if (layout.headerHidden) 32.dp else 100.dp, bottom = 56.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "field") {
                androidx.compose.material3.MaterialTheme(colorScheme = darkColorScheme(primary = c.accent)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { SText("Search movies, shows and episodes", size = 14.sp) },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = Inter, fontSize = 22.sp),
                        colors = fieldColors,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            focus.moveFocus(FocusDirection.Down)
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = EDGE.dp)
                            .focusRequester(fieldFocus)
                            // Let the D-pad leave the text field to reach the results.
                            .onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                                    keyboard?.hide()
                                    focus.moveFocus(FocusDirection.Down)
                                } else {
                                    false
                                }
                            },
                    )
                }
            }

            val term = query.trim()
            val status = when {
                term.length < 2 -> "Type at least two letters."
                searching && results.isEmpty -> "Searching…"
                error != null -> error
                results.isEmpty -> "Nothing found for \"$term\"."
                else -> null
            }
            if (status != null) {
                item(key = "status") {
                    SText(status, size = 16.sp, color = c.muted, modifier = Modifier.padding(start = EDGE.dp, top = 24.dp))
                }
            }

            if (results.movies.isNotEmpty()) {
                item(key = "movies") {
                    MediaRow(
                        title = "Movies",
                        items = results.movies,
                        landscape = layout.postersLandscape,
                        imageFor = { if (layout.postersLandscape) api.landscape(it) else api.poster(it) },
                        onClick = { nav.open(it) },
                        modifier = Modifier.padding(top = layout.rowSpacing.dp),
                    )
                }
            }
            if (results.shows.isNotEmpty()) {
                item(key = "shows") {
                    MediaRow(
                        title = "Shows",
                        items = results.shows,
                        landscape = layout.postersLandscape,
                        imageFor = { if (layout.postersLandscape) api.landscape(it) else api.poster(it) },
                        onClick = { nav.open(it) },
                        modifier = Modifier.padding(top = layout.rowSpacing.dp),
                    )
                }
            }
            if (results.episodes.isNotEmpty()) {
                item(key = "episodes") {
                    MediaRow(
                        title = "Episodes",
                        items = results.episodes,
                        landscape = true,
                        imageFor = { api.landscape(it) },
                        onClick = { nav.play(it) },
                        subtitleFor = { listOfNotNull(it.episodeCode, it.name).joinToString(" · ") },
                        modifier = Modifier.padding(top = layout.rowSpacing.dp),
                    )
                }
            }
        }

        SleekHeader(
            views = nav.views,
            selectedId = SEARCH_TAB,
            onHome = { nav.resetTo(Screen.Home) },
            onView = { nav.push(Screen.Library(it)) },
            onSearch = { runCatching { fieldFocus.requestFocus() } },
            onAccount = { nav.push(Screen.Account) },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/** Passed as selectedId so the header highlights the Search pill. */
const val SEARCH_TAB = "__search__"
