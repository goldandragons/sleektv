package com.sleektv.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sleektv.data.BaseItem
import com.sleektv.ui.Nav
import com.sleektv.ui.components.CenterMessage
import com.sleektv.ui.components.EDGE
import com.sleektv.ui.components.MediaRow
import com.sleektv.ui.components.SText
import com.sleektv.ui.components.ScrollOnlyWhenNeeded
import com.sleektv.ui.theme.LocalLayout
import com.sleektv.ui.theme.LocalSleekColors
import com.sleektv.ui.theme.scaledSp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

private data class PersonData(
    val person: BaseItem?,
    val movies: List<BaseItem>,
    val shows: List<BaseItem>,
)

/** An actor/director page: photo, bio, and what they're in from your library. */
@Composable
fun PersonScreen(nav: Nav, personId: String, name: String) {
    val api = nav.app.api
    val layout = LocalLayout.current
    val c = LocalSleekColors.current
    var data by remember { mutableStateOf<PersonData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(personId) {
        try {
            data = coroutineScope {
                val person = async { runCatching { api.item(personId) }.getOrNull() }
                val movies = async { runCatching { api.itemsWithPerson(personId, "Movie") }.getOrDefault(emptyList()) }
                val shows = async { runCatching { api.itemsWithPerson(personId, "Series") }.getOrDefault(emptyList()) }
                PersonData(person.await(), movies.await(), shows.await())
            }
            delay(150)
            runCatching { firstFocus.requestFocus() }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this person"
        }
    }

    val d = data
    when {
        d == null && error != null -> CenterMessage(error.orEmpty())
        d == null -> CenterMessage("Loading…")
        else -> ScrollOnlyWhenNeeded {
            LazyColumn(
                contentPadding = PaddingValues(top = 48.dp, bottom = 160.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = EDGE.dp).fillMaxWidth(),
                    ) {
                        Box(
                            Modifier
                                .size(150.dp)
                                .clip(CircleShape),
                        ) {
                            val photo = d.person?.let { api.poster(it, 300) }
                            if (photo != null) {
                                AsyncImage(
                                    model = photo,
                                    contentDescription = name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                SText(
                                    name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
                                    modifier = Modifier.align(Alignment.Center),
                                    size = scaledSp(40),
                                    weight = FontWeight.Bold,
                                    color = c.muted,
                                )
                            }
                        }
                        Spacer(Modifier.width(32.dp))
                        Column(Modifier.weight(1f)) {
                            SText(d.person?.name ?: name, size = scaledSp(layout.detailTitleSize - 4), weight = FontWeight.ExtraBold, maxLines = 2)
                            val count = d.movies.size + d.shows.size
                            Spacer(Modifier.height(6.dp))
                            SText(
                                if (count == 0) "Nothing in your library yet" else "$count title${if (count == 1) "" else "s"} in your library",
                                size = scaledSp(15),
                                color = c.muted,
                            )
                            d.person?.overview?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(12.dp))
                                SText(it, size = scaledSp(14), color = c.muted, maxLines = 5, lineHeight = scaledSp(20))
                            }
                        }
                    }
                }

                if (d.movies.isNotEmpty()) {
                    item(key = "movies") {
                        MediaRow(
                            title = "Movies",
                            items = d.movies,
                            landscape = layout.postersLandscape,
                            imageFor = { if (layout.postersLandscape) api.landscape(it) else api.poster(it) },
                            onClick = { nav.open(it) },
                            firstItemFocus = firstFocus,
                            modifier = Modifier.padding(top = layout.rowSpacing.dp),
                        )
                    }
                }
                if (d.shows.isNotEmpty()) {
                    item(key = "shows") {
                        MediaRow(
                            title = "Shows",
                            items = d.shows,
                            landscape = layout.postersLandscape,
                            imageFor = { if (layout.postersLandscape) api.landscape(it) else api.poster(it) },
                            onClick = { nav.open(it) },
                            firstItemFocus = if (d.movies.isEmpty()) firstFocus else null,
                            modifier = Modifier.padding(top = layout.rowSpacing.dp),
                        )
                    }
                }
            }
        }
    }
}
