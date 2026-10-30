package com.sleektv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sleektv.data.SeerrItem
import com.sleektv.ui.Nav
import com.sleektv.ui.Screen
import com.sleektv.ui.components.BackdropScrim
import com.sleektv.ui.components.CenterMessage
import com.sleektv.ui.components.EDGE
import com.sleektv.ui.components.SText
import com.sleektv.ui.components.SleekButton
import com.sleektv.ui.components.StatusPill
import com.sleektv.ui.theme.LocalLayout
import com.sleektv.ui.theme.LocalSleekColors
import com.sleektv.ui.theme.scaledSp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A title from Seerr: details plus Request (or Play, if it's already in Jellyfin). */
@Composable
fun SeerrDetailScreen(nav: Nav, mediaType: String, tmdbId: Int) {
    val api = nav.app.api
    val layout = LocalLayout.current
    val c = LocalSleekColors.current
    val scope = rememberCoroutineScope()
    val actionFocus = remember { FocusRequester() }
    val isTv = mediaType == "tv"

    var item by remember { mutableStateOf<SeerrItem?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mediaType, tmdbId) {
        try {
            item = api.seerrDetail(mediaType, tmdbId)
            delay(150)
            runCatching { actionFocus.requestFocus() }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this title from Seerr"
        }
    }

    val shown = item
    if (shown == null) {
        if (error != null) CenterMessage(error.orEmpty()) { SleekButton("Back", onClick = { nav.back() }, primary = true) }
        else CenterMessage("Loading…")
        return
    }

    fun request() {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                api.seerrRequest(mediaType, tmdbId)
                // Reload so the status pill updates (Requested / Processing / Available).
                item = runCatching { api.seerrDetail(mediaType, tmdbId) }.getOrDefault(shown)
                message = if (item?.isRequested == true || item?.isAvailable == true) {
                    "Requested! It'll show up in your library once it's added."
                } else {
                    "Request sent."
                }
            } catch (e: Exception) {
                message = e.message ?: "Request failed"
            } finally {
                busy = false
            }
        }
    }

    val jellyfinId = shown.mediaInfo?.jellyfinMediaId

    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = shown.backdropUrl(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize(),
        )
        BackdropScrim(leftStrength = 0.95f)

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = EDGE.dp, bottom = 64.dp)
                .fillMaxWidth(0.55f),
        ) {
            StatusPill(shown)
            if (shown.statusLabel != null) Spacer(Modifier.height(12.dp))
            SText(
                shown.displayTitle,
                size = scaledSp(layout.detailTitleSize),
                weight = FontWeight.ExtraBold,
                maxLines = 2,
            )
            Spacer(Modifier.height(14.dp))
            val meta = shown.metaLine(isTv)
            if (meta.isNotBlank()) {
                SText(meta, size = scaledSp(15), weight = FontWeight.Medium, color = c.muted, maxLines = 1)
            }
            if (shown.genres.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                SText(shown.genres.take(4).joinToString("  •  ") { it.name }, size = scaledSp(14), color = c.faint, maxLines = 1)
            }
            shown.overview?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(14.dp))
                SText(it, size = scaledSp(15), color = c.muted, maxLines = 5, lineHeight = scaledSp(22))
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    (shown.isAvailable || shown.isPartlyAvailable) && jellyfinId != null -> SleekButton(
                        text = "▶  Watch in Jellyfin",
                        primary = true,
                        modifier = Modifier.focusRequester(actionFocus),
                        onClick = { nav.push(Screen.Detail(jellyfinId)) },
                    )
                    shown.isAvailable -> SleekButton(
                        text = "Already in your library",
                        modifier = Modifier.focusRequester(actionFocus),
                        onClick = { },
                    )
                    shown.isRequested -> SleekButton(
                        text = "Already requested",
                        modifier = Modifier.focusRequester(actionFocus),
                        onClick = { },
                    )
                    else -> SleekButton(
                        text = when {
                            busy -> "Requesting…"
                            isTv -> "Request all seasons"
                            else -> "Request"
                        },
                        primary = true,
                        modifier = Modifier.focusRequester(actionFocus),
                        onClick = { request() },
                    )
                }
                // Partly available shows can still request the missing seasons.
                if (shown.isPartlyAvailable && isTv) {
                    SleekButton(text = if (busy) "Requesting…" else "Request missing seasons", onClick = { request() })
                }
                SleekButton(text = "Back", onClick = { nav.back() })
            }
            message?.let {
                Spacer(Modifier.height(14.dp))
                SText(it, size = scaledSp(15), color = c.text)
            }
        }
    }
}
