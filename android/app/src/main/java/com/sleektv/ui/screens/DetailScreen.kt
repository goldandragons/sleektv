package com.sleektv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import com.sleektv.ui.components.ScrollOnlyWhenNeeded
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sleektv.data.BaseItem
import com.sleektv.data.JellyfinApi
import com.sleektv.data.LayoutConfig
import com.sleektv.data.isEpisode
import com.sleektv.data.isSeries
import com.sleektv.data.isWatched
import com.sleektv.data.metaLine
import com.sleektv.data.progress
import com.sleektv.data.runtimeText
import com.sleektv.ui.Nav
import com.sleektv.ui.Screen
import com.sleektv.ui.components.BackdropScrim
import com.sleektv.ui.components.CenterMessage
import com.sleektv.ui.components.EDGE
import com.sleektv.ui.components.MediaRow
import com.sleektv.ui.components.PersonCard
import com.sleektv.ui.components.ProgressBar
import com.sleektv.ui.components.SText
import com.sleektv.ui.components.SectionTitle
import com.sleektv.ui.components.SleekButton
import com.sleektv.ui.components.SleekClickable
import com.sleektv.ui.components.WatchedBadge
import com.sleektv.ui.theme.LocalLayout
import com.sleektv.ui.theme.LocalSleekColors
import com.sleektv.ui.theme.scaledSp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class DetailData(
    val item: BaseItem,
    val seasons: List<BaseItem> = emptyList(),
    val episodes: List<BaseItem> = emptyList(),
    val nextUp: BaseItem? = null,
    val similar: List<BaseItem> = emptyList(),
)

private data class SeasonGroup(val id: String, val title: String, val episodes: List<BaseItem>)

private suspend fun loadDetail(api: JellyfinApi, id: String): DetailData = coroutineScope {
    val item = api.item(id)
    val similar = async { runCatching { api.similar(id) }.getOrDefault(emptyList()) }
    if (item.isSeries) {
        val seasons = async { runCatching { api.seasons(id) }.getOrDefault(emptyList()) }
        val episodes = async { runCatching { api.episodes(id) }.getOrDefault(emptyList()) }
        val nextUp = async { runCatching { api.nextUp(id).firstOrNull() }.getOrNull() }
        DetailData(item, seasons.await(), episodes.await(), nextUp.await(), similar.await())
    } else {
        DetailData(item, similar = similar.await())
    }
}

/** Groups episodes under their seasons, in season order, honouring the layout's sort. */
private fun groupSeasons(data: DetailData, layout: LayoutConfig): List<SeasonGroup> {
    val byId = data.episodes.groupBy { it.seasonId ?: "s${it.parentIndexNumber ?: 0}" }
    val groups = if (data.seasons.isNotEmpty()) {
        data.seasons.mapNotNull { s ->
            byId[s.id]?.let { SeasonGroup(s.id, s.name ?: "Season ${s.indexNumber ?: ""}", it) }
        }
    } else {
        byId.entries.map { (key, eps) ->
            val n = eps.firstOrNull()?.parentIndexNumber
            SeasonGroup(key, if (n == 0) "Specials" else "Season ${n ?: ""}", eps)
        }
    }
    val descending = layout.episodeSort.equals("Descending", true)
    return groups.map { g ->
        val sorted = g.episodes.sortedBy { it.indexNumber ?: 0 }
        g.copy(episodes = if (descending) sorted.reversed() else sorted)
    }
}

@Composable
fun DetailScreen(nav: Nav, itemId: String) {
    val api = nav.app.api
    var data by remember { mutableStateOf<DetailData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(itemId) {
        launch { nav.refreshLayout() } // so layout changes made on the server show up here too
        try {
            data = loadDetail(api, itemId)
            delay(150)
            runCatching { playFocus.requestFocus() }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this title"
        }
    }

    val d = data
    when {
        d == null && error != null -> CenterMessage(error.orEmpty())
        d == null -> CenterMessage("Loading…")
        else -> ScrollOnlyWhenNeeded { DetailContent(nav, d, playFocus) }
    }
}

@Composable
private fun DetailContent(nav: Nav, data: DetailData, playFocus: FocusRequester) {
    val api = nav.app.api
    val layout = LocalLayout.current
    val c = LocalSleekColors.current
    val item = data.item
    val groups = remember(data, layout) { groupSeasons(data, layout) }

    // Default season tab: the one holding the next episode to watch.
    var selectedSeason by remember(groups) {
        mutableStateOf(
            groups.firstOrNull { g -> g.episodes.any { it.id == data.nextUp?.id } }?.id ?: groups.firstOrNull()?.id,
        )
    }

    // What the main button plays, and its label.
    val playTarget: BaseItem = when {
        item.isSeries -> data.nextUp
            ?: data.episodes.firstOrNull { !it.isWatched }
            ?: data.episodes.firstOrNull()
            ?: item
        else -> item
    }
    val resumable = (playTarget.userData?.playbackPositionTicks ?: 0L) > 0L
    val playLabel = when {
        item.isSeries && playTarget.isEpisode -> {
            val code = listOfNotNull(
                playTarget.parentIndexNumber?.let { "S$it" },
                playTarget.indexNumber?.let { "E$it" },
            ).joinToString(":")
            (if (resumable) "▶  Resume " else "▶  Play ") + code
        }
        resumable -> "▶  Resume"
        else -> "▶  Play"
    }

    // Once you scroll past the title section (episodes, cast…), the backdrop blurs and dims.
    val listState = rememberLazyListState()
    val pastHeader by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 120 }
    }
    val blurAmount by animateFloatAsState(if (pastHeader) 1f else 0f, tween(450), label = "backdropBlur")
    val bg = LocalSleekColors.current.background

    Box(Modifier.fillMaxSize()) {
        // Full-page backdrop, like SleekFin's detail pages.
        AsyncImage(
            model = api.backdrop(item),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - blurAmount * 0.999f },
        )
        // Blurred copy: a tiny version stretched to full screen looks soft on every Android version,
        // and Android 12+ adds a real blur on top.
        if (blurAmount > 0f) {
            AsyncImage(
                model = api.backdrop(item, width = 96),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = blurAmount }
                    .blur(24.dp),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = blurAmount }
                    .background(bg.copy(alpha = 0.45f)),
            )
        }
        BackdropScrim(leftStrength = 0.95f)

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 64.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Box(Modifier.fillParentMaxHeight(0.82f).fillMaxWidth()) {
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = EDGE.dp, bottom = 24.dp)
                            .fillMaxWidth(0.55f),
                    ) {
                        val logo = api.logo(item)
                        if (logo != null) {
                            AsyncImage(
                                model = logo,
                                contentDescription = item.name,
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterStart,
                                modifier = Modifier
                                    .heightIn(max = (layout.detailTitleSize * 2.8f).dp)
                                    .widthIn(max = 520.dp),
                            )
                        } else {
                            SText(
                                item.name.orEmpty(),
                                size = scaledSp(layout.detailTitleSize),
                                weight = FontWeight.ExtraBold,
                                maxLines = 2,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        val meta = item.metaLine()
                        if (meta.isNotBlank()) {
                            SText(meta, size = scaledSp(15), weight = FontWeight.Medium, color = c.muted, maxLines = 1)
                        }
                        if (item.genres.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            SText(item.genres.take(4).joinToString("  •  "), size = scaledSp(14), color = c.faint, maxLines = 1)
                        }
                        item.overview?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(14.dp))
                            SText(it, size = scaledSp(15), color = c.muted, maxLines = 4, lineHeight = scaledSp(22))
                        }
                        Spacer(Modifier.height(22.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SleekButton(
                                text = playLabel,
                                onClick = { nav.play(playTarget) },
                                primary = true,
                                modifier = Modifier.focusRequester(playFocus),
                            )
                            if (item.isEpisode && item.seriesId != null) {
                                SleekButton("Go to show", onClick = { nav.push(Screen.Detail(item.seriesId)) })
                            }
                        }
                    }
                }
            }

            if (item.isSeries && groups.isNotEmpty()) {
                episodeSection(nav, groups, selectedSeason, onSelectSeason = { selectedSeason = it }, layout = layout)
            }

            if (layout.showCast && item.people.isNotEmpty()) {
                item(key = "cast") {
                    Column(Modifier.padding(top = layout.rowSpacing.dp)) {
                        SectionTitle("Cast & Crew")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = EDGE.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(layout.cardSpacing.dp),
                        ) {
                            itemsIndexed(item.people.take(30), key = { i, p -> "$i-${p.id}" }) { _, p ->
                                PersonCard(p, api.personImage(p))
                            }
                        }
                    }
                }
            }

            if (layout.showSimilar && data.similar.isNotEmpty()) {
                item(key = "similar") {
                    MediaRow(
                        title = "You may like",
                        items = data.similar,
                        landscape = true,
                        imageFor = { api.landscape(it) },
                        onClick = { nav.push(Screen.Detail(it.id)) },
                        subtitleFor = { s ->
                            listOfNotNull(
                                s.communityRating?.takeIf { it > 0 }?.let { "★ " + String.format("%.1f", it) },
                                s.productionYear?.toString(),
                                when (s.type) { "Series" -> "Series"; "Movie" -> "Movie"; else -> s.type },
                            ).joinToString("  ·  ")
                        },
                        modifier = Modifier.padding(top = layout.rowSpacing.dp),
                    )
                }
            }
        }
    }
}

/** Season tabs/stacks plus episodes laid out as List, Grid or Row. */
private fun LazyListScope.episodeSection(
    nav: Nav,
    groups: List<SeasonGroup>,
    selectedSeason: String?,
    onSelectSeason: (String) -> Unit,
    layout: LayoutConfig,
) {
    val stacked = layout.seasonDisplay.equals("Stacked", true)

    if (!stacked && groups.size > 1) {
        item(key = "season-tabs") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = EDGE.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                items(groups, key = { it.id }) { g ->
                    SeasonChip(g.title, selected = g.id == selectedSeason, onClick = { onSelectSeason(g.id) })
                }
            }
        }
    }

    val visible = if (stacked) groups else groups.filter { it.id == selectedSeason }.ifEmpty { groups.take(1) }

    visible.forEach { group ->
        if (stacked || groups.size == 1) {
            item(key = "title-${group.id}") {
                SectionTitle(group.title, Modifier.padding(top = layout.rowSpacing.dp))
            }
        }
        when (layout.episodeLayout.lowercase()) {
            "grid" -> {
                val columns = layout.episodeGridColumns
                group.episodes.chunked(columns).forEachIndexed { rowIndex, chunk ->
                    item(key = "grid-${group.id}-$rowIndex") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(layout.cardSpacing.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = EDGE.dp, vertical = (layout.cardSpacing / 2).dp),
                        ) {
                            chunk.forEach { ep ->
                                EpisodeCard(nav, ep, Modifier.weight(1f))
                            }
                            repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            "row" -> item(key = "row-${group.id}") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = EDGE.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(layout.cardSpacing.dp),
                ) {
                    items(group.episodes, key = { it.id }) { ep ->
                        EpisodeCard(nav, ep, Modifier.width(layout.episodeThumbWidth.dp))
                    }
                }
            }

            else -> group.episodes.forEach { ep ->
                item(key = "ep-${ep.id}") { EpisodeListRow(nav, ep) }
            }
        }
    }
}

@Composable
private fun SeasonChip(title: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalSleekColors.current
    SleekClickable(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        focusScale = 1.05f,
        containerColor = if (selected) c.accent else Color.White.copy(alpha = 0.12f),
        focusedContainerColor = if (selected) c.accent else Color.White,
        contentColor = Color.White,
        focusedContentColor = if (selected) Color.White else Color.Black,
    ) {
        androidx.tv.material3.Text(
            text = title,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            fontFamily = com.sleektv.ui.theme.Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = scaledSp(14),
            maxLines = 1,
        )
    }
}

@Composable
private fun EpisodeThumb(nav: Nav, ep: BaseItem, modifier: Modifier, focusScale: Float? = null) {
    val layout = LocalLayout.current
    SleekClickable(
        onClick = { nav.play(ep) },
        modifier = modifier.aspectRatio(16f / 9f),
        focusScale = focusScale ?: layout.focusScale.toFloat(),
    ) {
        AsyncImage(
            model = nav.app.api.landscape(ep),
            contentDescription = ep.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        ep.progress?.let { ProgressBar(it, Modifier.align(Alignment.BottomStart)) }
        if (layout.showWatchedBadges && ep.isWatched) {
            WatchedBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
    }
}

/** Thumbnail on top, title and optional description below. Used by Grid and Row layouts. */
@Composable
private fun EpisodeCard(nav: Nav, ep: BaseItem, modifier: Modifier) {
    val layout = LocalLayout.current
    val c = LocalSleekColors.current
    Column(modifier) {
        EpisodeThumb(nav, ep, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        SText(
            listOfNotNull(ep.indexNumber?.let { "$it." }, ep.name).joinToString(" "),
            size = scaledSp(14),
            weight = FontWeight.SemiBold,
            maxLines = 1,
        )
        ep.runtimeText?.let { SText(it, size = scaledSp(12), color = c.faint, maxLines = 1) }
        if (layout.showEpisodeOverview) {
            ep.overview?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                SText(it, size = scaledSp(12), color = c.muted, maxLines = 2)
            }
        }
    }
}

/** Thumbnail on the left, details on the right. The List layout. */
@Composable
private fun EpisodeListRow(nav: Nav, ep: BaseItem) {
    val layout = LocalLayout.current
    val c = LocalSleekColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EDGE.dp, vertical = (layout.cardSpacing / 2 + 4).dp),
    ) {
        EpisodeThumb(nav, ep, Modifier.width(layout.episodeThumbWidth.dp), focusScale = 1.04f)
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            SText(
                listOfNotNull(ep.indexNumber?.let { "$it." }, ep.name).joinToString(" "),
                size = scaledSp(18),
                weight = FontWeight.SemiBold,
                maxLines = 1,
            )
            val sub = listOfNotNull(ep.runtimeText, if (ep.isWatched) "Watched" else null).joinToString("  ·  ")
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                SText(sub, size = scaledSp(13), color = c.faint, maxLines = 1)
            }
            if (layout.showEpisodeOverview) {
                ep.overview?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    SText(it, size = scaledSp(14), color = c.muted, maxLines = 3, lineHeight = scaledSp(20))
                }
            }
        }
    }
}
