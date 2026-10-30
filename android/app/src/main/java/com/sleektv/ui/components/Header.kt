@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.sleektv.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sleektv.data.BaseItem
import com.sleektv.ui.theme.LocalLayout
import com.sleektv.ui.theme.LocalSleekColors
import com.sleektv.ui.theme.scaledSp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * SleekFin's compact navigation bar. "Floating" draws a translucent pill over the page,
 * "Solid" a full-width bar, "Hidden" nothing.
 */
@Composable
fun SleekHeader(
    views: List<BaseItem>,
    selectedId: String?, // null = Home
    onHome: () -> Unit,
    onView: (BaseItem) -> Unit,
    onAccount: () -> Unit,
    modifier: Modifier = Modifier,
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

    val barModifier = if (layout.headerSolid) {
        modifier
            .fillMaxWidth()
            .background(c.background)
            .padding(horizontal = EDGE.dp, vertical = 14.dp)
    } else {
        modifier
            .fillMaxWidth()
            .padding(start = EDGE.dp - 8.dp, end = EDGE.dp - 8.dp, top = 18.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 20.dp, vertical = 8.dp)
    }

    Row(barModifier, verticalAlignment = Alignment.CenterVertically) {
        // Wordmark
        Row(verticalAlignment = Alignment.CenterVertically) {
            SText("Sleek", size = scaledSp(20), weight = FontWeight.ExtraBold)
            SText("TV", size = scaledSp(20), weight = FontWeight.ExtraBold, color = c.accent)
        }
        Spacer(Modifier.width(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            NavPill("Home", selected = selectedId == null, onClick = onHome)
            views.take(7).forEach { view ->
                NavPill(view.name.orEmpty(), selected = selectedId == view.id, onClick = { onView(view) })
            }
        }
        SText(clock, size = scaledSp(14), color = c.muted, weight = FontWeight.Medium)
        Spacer(Modifier.width(12.dp))
        NavPill("Account", selected = false, onClick = onAccount)
    }
}

@Composable
private fun NavPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalSleekColors.current
    SleekClickable(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        focusScale = 1.04f,
        containerColor = if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent,
        focusedContainerColor = Color.White,
        contentColor = if (selected) c.text else c.muted,
        focusedContentColor = Color.Black,
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 7.dp)) {
            androidx.tv.material3.Text(
                text = label,
                fontFamily = com.sleektv.ui.theme.Inter,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = scaledSp(14),
                maxLines = 1,
            )
        }
    }
}

private fun now(): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
