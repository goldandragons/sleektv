package com.sleektv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything that can be changed remotely from the SleekTV plugin.
 * Defaults reproduce the SleekFin look: black UI, red accent, Inter, floating header.
 * Sizes are dp (layout) and sp (text).
 */
@Serializable
data class LayoutConfig(
    // Colors
    val accentColor: String = "#E50914",
    val backgroundColor: String = "#000000",
    val surfaceColor: String = "#141414",
    val textColor: String = "#FFFFFF",

    // Text and headers
    val fontScale: Double = 1.0,
    val heroTitleSize: Int = 40,
    val sectionTitleSize: Int = 22,
    val detailTitleSize: Int = 44,
    val headerStyle: String = "Floating", // Floating | Solid | Hidden

    // Home
    val showHero: Boolean = true,
    val heroHeight: Int = 380,
    val heroAutoRotateSeconds: Int = 8,
    val homeRows: String = "ContinueWatching,NextUp,Latest,Libraries",
    val latestItemsPerLibrary: Int = 20,

    // Cards
    val posterWidth: Int = 150,
    val posterStyle: String = "Portrait", // Portrait | Landscape
    val continueWatchingStyle: String = "Landscape",
    val cardCornerRadius: Int = 10,
    val rowSpacing: Int = 28,
    val cardSpacing: Int = 16,
    val focusScale: Double = 1.08,
    val showTitlesUnderCards: Boolean = true,
    val showWatchedBadges: Boolean = true,

    // Episodes
    val episodeLayout: String = "List", // List | Grid | Row
    val episodeGridColumns: Int = 4,
    val episodeThumbWidth: Int = 280,
    val seasonDisplay: String = "Tabs", // Tabs | Stacked
    val episodeSort: String = "Ascending", // Ascending | Descending
    val showEpisodeOverview: Boolean = true,

    // Details page
    val showCast: Boolean = true,
    val showSimilar: Boolean = true,

    // Player: skipping intros/credits (timestamps come from the Intro Skipper plugin)
    val skipIntroMode: String = "Button", // Button | Auto | Off
    val skipCreditsMode: String = "Button", // Button | Auto | Off
    val skipButtonSeconds: Int = 10, // how long the button stays up; 0 = whole segment

    // CSS-style overrides, applied last
    val customStyles: String = "",
) {
    val homeRowList: List<String>
        get() = homeRows.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    val postersLandscape: Boolean get() = posterStyle.equals("Landscape", true)
    val continueLandscape: Boolean get() = !continueWatchingStyle.equals("Portrait", true)
    val headerHidden: Boolean get() = headerStyle.equals("Hidden", true)
    val headerSolid: Boolean get() = headerStyle.equals("Solid", true)

    /** Keep values in ranges that can't break the UI, whatever gets typed on the server. */
    fun sanitized(): LayoutConfig = copy(
        fontScale = fontScale.coerceIn(0.5, 2.5),
        heroTitleSize = heroTitleSize.coerceIn(10, 120),
        sectionTitleSize = sectionTitleSize.coerceIn(8, 80),
        detailTitleSize = detailTitleSize.coerceIn(10, 120),
        heroHeight = heroHeight.coerceIn(120, 1000),
        heroAutoRotateSeconds = heroAutoRotateSeconds.coerceIn(0, 300),
        latestItemsPerLibrary = latestItemsPerLibrary.coerceIn(1, 100),
        posterWidth = posterWidth.coerceIn(60, 500),
        cardCornerRadius = cardCornerRadius.coerceIn(0, 60),
        rowSpacing = rowSpacing.coerceIn(0, 200),
        cardSpacing = cardSpacing.coerceIn(0, 100),
        focusScale = focusScale.coerceIn(1.0, 1.5),
        episodeGridColumns = episodeGridColumns.coerceIn(1, 10),
        episodeThumbWidth = episodeThumbWidth.coerceIn(80, 900),
        skipButtonSeconds = skipButtonSeconds.coerceIn(0, 600),
    )

    /** Button, Auto or Off for a given segment type. Recaps/previews follow the intro setting. */
    fun skipModeFor(segmentType: String): String = when (segmentType) {
        "Outro" -> skipCreditsMode
        "Intro", "Recap", "Preview", "Commercial" -> skipIntroMode
        else -> "Off"
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }

        /** Parse plugin JSON (or fall back to defaults), then apply the custom styles. */
        fun parse(raw: String?): LayoutConfig {
            val base = raw?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
                ?: LayoutConfig()
            return CustomStyles.apply(base, base.customStyles).sanitized()
        }
    }
}
