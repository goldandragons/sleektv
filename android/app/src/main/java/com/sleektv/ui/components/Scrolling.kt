package com.sleektv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import kotlin.math.abs

/**
 * On Android TV, Compose lists scroll the focused item to about 30% from the top. That pushes
 * the title off the top of pages whose first section is tall (detail pages, the home banner).
 * This spec only scrolls when the focused item isn't fully on screen, and then only as far as needed.
 */
@OptIn(ExperimentalFoundationApi::class)
private val ScrollOnlyIfNeeded = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailingEdge = offset + size
        return when {
            offset >= 0f && trailingEdge <= containerSize -> 0f // already fully visible
            offset < 0f && trailingEdge > containerSize -> 0f // bigger than the screen and covering it
            abs(offset) < abs(trailingEdge - containerSize) -> offset // closer to the top: reveal the top
            else -> trailingEdge - containerSize // reveal the bottom
        }
    }
}

/** Wrap a scrolling list so focus changes only scroll as much as needed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScrollOnlyWhenNeeded(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides ScrollOnlyIfNeeded, content = content)
}
