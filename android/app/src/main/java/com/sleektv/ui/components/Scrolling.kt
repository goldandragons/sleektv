package com.sleektv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * On Android TV, Compose lists scroll the focused item to about 30% from the top. That pushes
 * the title off the top of pages whose first section is tall (detail pages, the home banner).
 * This spec only scrolls when the focused item isn't fully on screen, and then only as far as needed.
 */
@OptIn(ExperimentalFoundationApi::class)
private val ScrollOnlyIfNeeded = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Keep focused items clear of the edges: room for the focus zoom, the title under a card,
        // and TV overscan. More room at the bottom/right, where titles sit and the next item peeks in.
        val lead = containerSize * 0.08f
        val trail = containerSize * 0.20f
        val start = lead
        val end = containerSize - trail
        val trailingEdge = offset + size
        return when {
            size > end - start -> offset - start // taller than the comfortable area: line up its top
            offset >= start && trailingEdge <= end -> 0f // already comfortably visible
            offset < start -> offset - start // scroll up/left just enough
            else -> trailingEdge - end // scroll down/right just enough
        }
    }
}

/** Wrap a scrolling list so focus changes only scroll as much as needed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScrollOnlyWhenNeeded(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides ScrollOnlyIfNeeded, content = content)
}
