@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.sleektv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sleektv.data.BaseItem
import com.sleektv.ui.theme.LocalLayout
import com.sleektv.ui.theme.LocalSleekColors
import com.sleektv.ui.theme.scaledSp
import com.sleektv.ui.theme.toColorOr
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * SleekFin's compact navigation bar. "Floating" draws a translucent pill over the page,
 * "Solid" a full-width bar, "Hidden" nothing.
 *
 * [visible] lets the page slide it away while scrolling down. Pressing Down anywhere in the
 * header moves focus into the page ([contentFocus]) instead of to whatever happens to sit below.
 */
@Composable
fun SleekHeader(
    views: List<BaseItem>,
    selectedId: String?, // null = Home
    onHome: () -> Unit,
    onView: (BaseItem) -> Unit,
    onSearch: () -> Unit,
    onAccount: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    contentFocus: FocusRequester? = null,
    entryFocus: FocusRequester? = null, // lets the page move focus onto the current tab
) {
    val layout = LocalLayout.current
    if (layout.headerHidden) return
    val c = LocalSleekColors.current

    var clock by remember { mutableStateOf(now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            clock = now()
        }
    }

    val s = layout.headerScale.toFloat()
    val barModifier = if (layout.headerSolid) {
        Modifier
            .fillMaxWidth()
            .background(c.background)
            .padding(horizontal = EDGE.dp, vertical = (14 * s).dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(start = EDGE.dp - 8.dp, end = EDGE.dp - 8.dp, top = 18.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = layout.headerOpacity / 100f))
            .padding(horizontal = (20 * s).dp, vertical = (8 * s).dp)
    }

    // Tabs: the libraries picked in the plugin, in that order; otherwise the first seven.
    val chosen = layout.headerLibraryIds
    val tabs = if (chosen.isEmpty()) {
        views.take(7)
    } else {
        chosen.mapNotNull { id -> views.firstOrNull { it.id.replace("-", "").lowercase() == id } }
    }

    // While leaving the header with Down, the other pills step out of the way so focus lands on
    // whatever is directly below the current pill instead of sliding along the header.
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            barModifier.onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                    if (!leaving) {
                        leaving = true
                        scope.launch {
                            withFrameNanos { } // let the other pills become unfocusable first
                            val moved = focusManager.moveFocus(FocusDirection.Down)
                            if (!moved) runCatching { contentFocus?.requestFocus() }
                            leaving = false
                        }
                    }
                    true
                } else {
                    false
                }
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Wordmark: two words, two colors (set in the plugin).
            BrandWordmark(size = (20 * s).toInt())
            Spacer(Modifier.width((28 * s).dp))
            Row(horizontalArrangement = Arrangement.spacedBy((4 * s).dp), modifier = Modifier.weight(1f)) {
                NavPill("Home", selected = selectedId == null, leaving = leaving, onClick = onHome,
                    entry = entryFocus.takeIf { selectedId == null })
                tabs.forEach { view ->
                    NavPill(view.name.orEmpty(), selected = selectedId == view.id, leaving = leaving, onClick = { onView(view) },
                        entry = entryFocus.takeIf { selectedId == view.id })
                }
            }
            if (layout.headerShowSearch) {
                NavPill("Search", selected = selectedId == com.sleektv.ui.screens.SEARCH_TAB, leaving = leaving, onClick = onSearch,
                    entry = entryFocus.takeIf { selectedId == com.sleektv.ui.screens.SEARCH_TAB })
            }
            if (layout.headerShowClock) {
                Spacer(Modifier.width((12 * s).dp))
                SText(clock, size = scaledSp((14 * s).toInt()), color = c.muted, weight = FontWeight.Medium)
            }
            Spacer(Modifier.width((12 * s).dp))
            NavPill("Account", selected = false, leaving = leaving, onClick = onAccount)
        }
    }
}

/** The app name, e.g. "Sleek" in white + "TV" in the accent color. Used in the header and on sign-in. */
@Composable
fun BrandWordmark(size: Int, scaleWithFont: Boolean = true) {
    val layout = LocalLayout.current
    val c = LocalSleekColors.current
    val textSize = if (scaleWithFont) scaledSp(size) else size.sp
    val first = layout.brandPrimaryColor.takeIf { it.isNotBlank() }?.toColorOr(c.text) ?: c.text
    val second = layout.brandSecondaryColor.takeIf { it.isNotBlank() }?.toColorOr(c.accent) ?: c.accent
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (layout.brandPrimary.isNotEmpty()) SText(layout.brandPrimary, size = textSize, weight = FontWeight.ExtraBold, color = first, maxLines = 1)
        if (layout.brandSecondary.isNotEmpty()) SText(layout.brandSecondary, size = textSize, weight = FontWeight.ExtraBold, color = second, maxLines = 1)
    }
}

/**
 * True while the header should show: at the top of the page, or right after scrolling up.
 * [position] should grow as the page scrolls down (e.g. index * 100_000 + offset).
 */
@Composable
fun rememberHeaderVisible(atTop: () -> Boolean, position: () -> Long): Boolean {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        var last = position()
        snapshotFlow { position() to atTop() }.collect { (pos, top) ->
            visible = when {
                top -> true
                pos > last + 4 -> false // scrolling down
                pos < last - 4 -> true // scrolling up
                else -> visible
            }
            last = pos
        }
    }
    return visible
}

@Composable
private fun NavPill(
    label: String,
    selected: Boolean,
    leaving: Boolean,
    onClick: () -> Unit,
    entry: FocusRequester? = null,
) {
    val c = LocalSleekColors.current
    var focused by remember { mutableStateOf(false) }
    val focusable = !leaving || focused
    SleekClickable(
        modifier = Modifier
            .then(if (entry != null) Modifier.focusRequester(entry) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusProperties { canFocus = focusable },
        onClick = onClick,
        shape = RoundedCornerShape(50),
        focusScale = 1.04f,
        containerColor = if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent,
        focusedContainerColor = Color.White,
        contentColor = if (selected) c.text else c.muted,
        focusedContentColor = Color.Black,
    ) {
        val sc = LocalLayout.current.headerScale.toFloat()
        Box(Modifier.padding(horizontal = (16 * sc).dp, vertical = (7 * sc).dp)) {
            androidx.tv.material3.Text(
                text = label,
                fontFamily = com.sleektv.ui.theme.Inter,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = scaledSp((14 * sc).toInt()),
                maxLines = 1,
            )
        }
    }
}

private fun now(): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
