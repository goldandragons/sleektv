package com.sleektv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.sleektv.R
import com.sleektv.data.LayoutConfig

/** Inter, the same typeface SleekFin uses. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

data class SleekColors(
    val accent: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
) {
    val muted: Color get() = text.copy(alpha = 0.68f)
    val faint: Color get() = text.copy(alpha = 0.42f)
    val chip: Color get() = text.copy(alpha = 0.12f)
}

val LocalLayout = staticCompositionLocalOf { LayoutConfig() }
val LocalSleekColors = staticCompositionLocalOf {
    SleekColors(Color(0xFFE50914), Color.Black, Color(0xFF141414), Color.White)
}

fun String.toColorOr(fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(this.trim())) }.getOrDefault(fallback)

/** A text size from the layout, multiplied by the global font scale. */
@Composable
fun scaledSp(size: Int): TextUnit = (size * LocalLayout.current.fontScale).sp

@Composable
fun SleekTheme(layout: LayoutConfig, content: @Composable () -> Unit) {
    val colors = remember(layout) {
        SleekColors(
            accent = layout.accentColor.toColorOr(Color(0xFFE50914)),
            background = layout.backgroundColor.toColorOr(Color.Black),
            surface = layout.surfaceColor.toColorOr(Color(0xFF141414)),
            text = layout.textColor.toColorOr(Color.White),
        )
    }
    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = Color.White,
        background = colors.background,
        onBackground = colors.text,
        surface = colors.surface,
        onSurface = colors.text,
        surfaceVariant = colors.surface,
        onSurfaceVariant = colors.muted,
    )
    val base = Typography()
    fun TextStyle.inter() = copy(fontFamily = Inter)
    val typography = Typography(
        displayLarge = base.displayLarge.inter(),
        displayMedium = base.displayMedium.inter(),
        displaySmall = base.displaySmall.inter(),
        headlineLarge = base.headlineLarge.inter(),
        headlineMedium = base.headlineMedium.inter(),
        headlineSmall = base.headlineSmall.inter(),
        titleLarge = base.titleLarge.inter(),
        titleMedium = base.titleMedium.inter(),
        titleSmall = base.titleSmall.inter(),
        bodyLarge = base.bodyLarge.inter(),
        bodyMedium = base.bodyMedium.inter(),
        bodySmall = base.bodySmall.inter(),
        labelLarge = base.labelLarge.inter(),
        labelMedium = base.labelMedium.inter(),
        labelSmall = base.labelSmall.inter(),
    )

    CompositionLocalProvider(LocalLayout provides layout, LocalSleekColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            CompositionLocalProvider(LocalContentColor provides colors.text) {
                content()
            }
        }
    }
}
