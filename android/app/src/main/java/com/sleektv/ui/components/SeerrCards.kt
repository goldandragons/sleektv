package com.sleektv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sleektv.data.SeerrItem
import com.sleektv.ui.theme.LocalLayout
import com.sleektv.ui.theme.LocalSleekColors
import com.sleektv.ui.theme.scaledSp

/** Small pill in the corner of a Seerr card: Available / Requested / etc. */
@Composable
fun StatusPill(item: SeerrItem, modifier: Modifier = Modifier) {
    val label = item.statusLabel ?: return
    val c = LocalSleekColors.current
    val bg = when {
        item.isAvailable -> Color(0xFF2E7D32)
        item.isPartlyAvailable -> Color(0xFF1565C0)
        else -> c.accent
    }
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg.copy(alpha = 0.92f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        SText(label, size = 11.sp, weight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
    }
}

/** Poster card for a Seerr title, styled like the library cards. */
@Composable
fun SeerrCard(item: SeerrItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val layout = LocalLayout.current
    val c = LocalSleekColors.current
    Column(modifier) {
        SleekClickable(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            val poster = item.posterUrl()
            if (poster != null) {
                AsyncImage(
                    model = poster,
                    contentDescription = item.displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                SText(
                    item.displayTitle,
                    modifier = Modifier.align(Alignment.Center).padding(12.dp),
                    size = scaledSp(15),
                    weight = FontWeight.SemiBold,
                    align = TextAlign.Center,
                    maxLines = 3,
                )
            }
            StatusPill(item, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        if (layout.showTitlesUnderCards) {
            Spacer(Modifier.height(10.dp))
            SText(item.displayTitle, size = scaledSp(14), weight = FontWeight.SemiBold, maxLines = 1)
            val sub = listOfNotNull(item.year, if (item.mediaType == "tv") "Series" else "Movie").joinToString(" · ")
            Spacer(Modifier.height(2.dp))
            SText(sub, size = scaledSp(12), color = c.muted, maxLines = 1)
        }
    }
}

@Composable
fun SeerrRow(
    title: String,
    items: List<SeerrItem>,
    onClick: (SeerrItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val layout = LocalLayout.current
    Column(modifier) {
        SectionTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = EDGE.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(layout.cardSpacing.dp),
        ) {
            itemsIndexed(items, key = { i, it -> "$i-${it.mediaType}-${it.id}" }) { _, item ->
                SeerrCard(item, onClick = { onClick(item) }, modifier = Modifier.width(layout.posterWidth.dp))
            }
        }
    }
}
