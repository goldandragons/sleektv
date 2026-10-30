@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.sleektv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.sleektv.data.BaseItem
import com.sleektv.data.Person
import com.sleektv.data.cardSubtitle
import com.sleektv.data.cardTitle
import com.sleektv.data.isWatched
import com.sleektv.data.progress
import com.sleektv.ui.theme.Inter
import com.sleektv.ui.theme.LocalLayout
import com.sleektv.ui.theme.LocalSleekColors
import com.sleektv.ui.theme.scaledSp

const val EDGE = 48 // left/right page margin, dp

/** Text that always uses Inter. */
@Composable
fun SText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    lineHeight: TextUnit = TextUnit.Unspecified,
    align: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = size,
        fontWeight = weight,
        fontFamily = Inter,
        color = if (color == Color.Unspecified) LocalSleekColors.current.text else color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        lineHeight = lineHeight,
        textAlign = align,
    )
}

/** Focusable surface with the layout's corner radius, zoom and focus ring. */
@Composable
fun SleekClickable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(LocalLayout.current.cardCornerRadius.dp),
    focusScale: Float = LocalLayout.current.focusScale.toFloat(),
    containerColor: Color = LocalSleekColors.current.surface,
    focusedContainerColor: Color = containerColor,
    contentColor: Color = LocalSleekColors.current.text,
    focusedContentColor: Color = contentColor,
    onFocused: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalSleekColors.current
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused?.invoke() },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusScale),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(border = BorderStroke(2.dp, colors.text), shape = shape),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = focusedContainerColor,
            focusedContentColor = focusedContentColor,
        ),
        content = content,
    )
}

/** Pill button: accent-filled for the main action, glassy for the rest (SleekFin style). */
@Composable
fun SleekButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val c = LocalSleekColors.current
    SleekClickable(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        focusScale = 1.06f,
        containerColor = if (primary) c.accent else Color.White.copy(alpha = 0.14f),
        focusedContainerColor = if (primary) c.accent else Color.White,
        contentColor = Color.White,
        focusedContentColor = if (primary) Color.White else Color.Black,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 11.dp),
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = scaledSp(15),
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    SText(
        text = text,
        modifier = modifier.padding(start = EDGE.dp, bottom = 12.dp),
        size = scaledSp(LocalLayout.current.sectionTitleSize),
        weight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val c = LocalSleekColors.current
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.White.copy(alpha = 0.25f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(c.accent),
        )
    }
}

@Composable
fun WatchedBadge(modifier: Modifier = Modifier) {
    val c = LocalSleekColors.current
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(c.accent),
        contentAlignment = Alignment.Center,
    ) {
        SText("✓", size = 13.sp, weight = FontWeight.Bold, color = Color.White)
    }
}

/** Dark fade used under hero and detail text so it stays readable over any backdrop. */
@Composable
fun BackdropScrim(modifier: Modifier = Modifier, leftStrength: Float = 0.92f) {
    val bg = LocalSleekColors.current.background
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0f to bg.copy(alpha = leftStrength),
                    0.45f to bg.copy(alpha = leftStrength * 0.55f),
                    0.8f to Color.Transparent,
                ),
            ),
    )
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to bg.copy(alpha = 0.35f),
                    0.25f to Color.Transparent,
                    0.62f to Color.Transparent,
                    1f to bg,
                ),
            ),
    )
}

/**
 * Poster or landscape card with concise info underneath, like SleekFin's rows.
 * Pass a width modifier (rows) or fillMaxWidth (grids).
 */
@Composable
fun MediaCard(
    item: BaseItem,
    imageUrl: String?,
    landscape: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = item.cardTitle,
    subtitle: String? = item.cardSubtitle,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
) {
    val layout = LocalLayout.current
    val c = LocalSleekColors.current
    Column(modifier) {
        SleekClickable(
            onClick = onClick,
            onFocused = onFocused,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                SText(
                    text = title,
                    modifier = Modifier.align(Alignment.Center).padding(12.dp),
                    size = scaledSp(15),
                    weight = FontWeight.SemiBold,
                    align = TextAlign.Center,
                    maxLines = 3,
                )
            }
            item.progress?.let { ProgressBar(it, Modifier.align(Alignment.BottomStart)) }
            if (layout.showWatchedBadges && item.isWatched) {
                WatchedBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
        }
        if (layout.showTitlesUnderCards) {
            Spacer(Modifier.height(10.dp))
            SText(title, size = scaledSp(14), weight = FontWeight.SemiBold, maxLines = 1)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                SText(subtitle, size = scaledSp(12), color = c.muted, maxLines = 1)
            }
        }
    }
}

/** A titled horizontal row of cards. */
@Composable
fun MediaRow(
    title: String,
    items: List<BaseItem>,
    landscape: Boolean,
    imageFor: (BaseItem) -> String?,
    onClick: (BaseItem) -> Unit,
    modifier: Modifier = Modifier,
    subtitleFor: ((BaseItem) -> String?)? = null,
    firstItemFocus: FocusRequester? = null,
) {
    if (items.isEmpty()) return
    val layout = LocalLayout.current
    val cardWidth: Dp = if (landscape) (layout.posterWidth * 1.85f).dp else layout.posterWidth.dp
    Column(modifier) {
        SectionTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = EDGE.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(layout.cardSpacing.dp),
        ) {
            itemsIndexed(items, key = { index, it -> "$index-${it.id}" }) { index, item ->
                MediaCard(
                    item = item,
                    imageUrl = imageFor(item),
                    landscape = landscape,
                    onClick = { onClick(item) },
                    modifier = Modifier.width(cardWidth),
                    subtitle = subtitleFor?.invoke(item) ?: item.cardSubtitle,
                    focusRequester = if (index == 0) firstItemFocus else null,
                )
            }
        }
    }
}

/** Round headshot + name + role. */
@Composable
fun PersonCard(person: Person, imageUrl: String?, modifier: Modifier = Modifier) {
    val c = LocalSleekColors.current
    Column(modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SleekClickable(
            onClick = {},
            shape = CircleShape,
            modifier = Modifier.size(104.dp),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                SText(
                    text = person.name.orEmpty().split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
                    modifier = Modifier.align(Alignment.Center),
                    size = 26.sp,
                    weight = FontWeight.Bold,
                    color = c.muted,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        SText(person.name.orEmpty(), size = scaledSp(13), weight = FontWeight.SemiBold, maxLines = 1, align = TextAlign.Center)
        person.role?.takeIf { it.isNotBlank() }?.let {
            SText(it, size = scaledSp(11), color = c.muted, maxLines = 1, align = TextAlign.Center)
        }
    }
}

@Composable
fun CenterMessage(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SText(text, size = 18.sp, color = LocalSleekColors.current.muted, align = TextAlign.Center)
            if (action != null) {
                Spacer(Modifier.height(20.dp))
                action()
            }
        }
    }
}

/** Small row of dots under the hero showing which slide is showing. */
@Composable
fun PagerDots(count: Int, selected: Int, modifier: Modifier = Modifier) {
    if (count <= 1) return
    val c = LocalSleekColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .height(4.dp)
                    .width(if (i == selected) 22.dp else 8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i == selected) c.accent else c.faint),
            )
        }
    }
}
