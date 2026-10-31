package com.sleektv.data

import kotlin.math.roundToInt

/**
 * Parses the plugin's "Custom styles" box. It looks like CSS, but each rule maps onto a
 * LayoutConfig setting, because the app is native and has no CSS engine.
 *
 *   :root    { accent; background; surface; text; font-scale }
 *   header   { style: floating|solid|hidden }
 *   hero     { display: none|block; height; title-size; rotate; source: continue|latest }
 *   section  { title-size; spacing }
 *   card     { width; radius; spacing; focus-scale; shape: portrait|landscape; titles; badges }
 *   continue { shape: portrait|landscape }
 *   details  { title-size; cast: show|hide; similar: show|hide }
 *   home     { rows: ContinueWatching, NextUp, Latest, Libraries; latest-count }
 *   episodes { layout: list|grid|row; columns; thumb-width; seasons: tabs|stacked;
 *              order: asc|desc; overview: show|hide }
 *   player   { skip-intro: button|auto|off; skip-credits: button|auto|off; button-seconds }
 *   subtitles { mode: jellyfin|default|always|forced|off; language: eng; size: 120%; color;
 *               background: outline|shadow|box|none; position: 10% }
 *
 * Unknown selectors or properties are ignored, so a typo never breaks the app.
 */
object CustomStyles {
    private val comments = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val rule = Regex("""([:\w-]+)\s*\{([^}]*)\}""")

    fun apply(base: LayoutConfig, css: String): LayoutConfig {
        if (css.isBlank()) return base
        var config = base
        val cleaned = css.replace(comments, "")
        for (match in rule.findAll(cleaned)) {
            val selector = match.groupValues[1].trim().lowercase()
            for (declaration in match.groupValues[2].split(';')) {
                val colon = declaration.indexOf(':')
                if (colon <= 0) continue
                val property = declaration.substring(0, colon).trim().lowercase()
                val value = declaration.substring(colon + 1).trim().removeSuffix("!important").trim()
                if (value.isEmpty()) continue
                config = applyOne(config, selector, property, value)
            }
        }
        return config
    }

    private fun num(v: String): Double? =
        v.lowercase().removeSuffix("px").removeSuffix("dp").removeSuffix("sp").removeSuffix("s").trim().toDoubleOrNull()

    private fun int(v: String): Int? = num(v)?.roundToInt()

    private fun bool(v: String): Boolean? = when (v.lowercase()) {
        "show", "true", "yes", "on", "block", "visible", "1" -> true
        "hide", "false", "no", "off", "none", "hidden", "0" -> false
        else -> null
    }

    private fun color(v: String): String? =
        v.takeIf { Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$").matches(it) }

    private fun applyOne(c: LayoutConfig, selector: String, prop: String, v: String): LayoutConfig =
        when (selector) {
            ":root", "root", "app", "body" -> when (prop) {
                "accent", "accent-color" -> color(v)?.let { c.copy(accentColor = it) }
                "background", "background-color" -> color(v)?.let { c.copy(backgroundColor = it) }
                "surface", "surface-color" -> color(v)?.let { c.copy(surfaceColor = it) }
                "text", "color", "text-color" -> color(v)?.let { c.copy(textColor = it) }
                "font-scale", "zoom" -> num(v)?.let { c.copy(fontScale = it) }
                else -> null
            }

            "header", "nav" -> when (prop) {
                "style" -> when (v.lowercase()) {
                    "floating", "solid", "hidden" -> c.copy(headerStyle = v.replaceFirstChar { it.uppercase() })
                    else -> null
                }
                "display" -> bool(v)?.let { c.copy(headerStyle = if (it) "Floating" else "Hidden") }
                else -> null
            }

            "hero" -> when (prop) {
                "display" -> bool(v)?.let { c.copy(showHero = it) }
                "height" -> int(v)?.let { c.copy(heroHeight = it) }
                "title-size", "font-size" -> int(v)?.let { c.copy(heroTitleSize = it) }
                "rotate", "rotate-seconds", "interval" -> int(v)?.let { c.copy(heroAutoRotateSeconds = it) }
                "source", "content" -> when (v.lowercase()) {
                    "continue", "continue-watching", "continuewatching", "resume" -> c.copy(heroSource = "ContinueWatching")
                    "latest", "new" -> c.copy(heroSource = "Latest")
                    else -> null
                }
                else -> null
            }

            "section", "row", "h2" -> when (prop) {
                "title-size", "font-size" -> int(v)?.let { c.copy(sectionTitleSize = it) }
                "spacing", "gap", "margin" -> int(v)?.let { c.copy(rowSpacing = it) }
                else -> null
            }

            "card", "poster" -> when (prop) {
                "width" -> int(v)?.let { c.copy(posterWidth = it) }
                "radius", "border-radius" -> int(v)?.let { c.copy(cardCornerRadius = it) }
                "spacing", "gap" -> int(v)?.let { c.copy(cardSpacing = it) }
                "focus-scale", "scale" -> num(v)?.let { c.copy(focusScale = it) }
                "shape" -> shape(v)?.let { c.copy(posterStyle = it) }
                "titles" -> bool(v)?.let { c.copy(showTitlesUnderCards = it) }
                "badges" -> bool(v)?.let { c.copy(showWatchedBadges = it) }
                else -> null
            }

            "continue", "continue-watching" -> when (prop) {
                "shape" -> shape(v)?.let { c.copy(continueWatchingStyle = it) }
                else -> null
            }

            "details", "detail" -> when (prop) {
                "title-size", "font-size" -> int(v)?.let { c.copy(detailTitleSize = it) }
                "cast" -> bool(v)?.let { c.copy(showCast = it) }
                "similar" -> bool(v)?.let { c.copy(showSimilar = it) }
                else -> null
            }

            "home" -> when (prop) {
                "rows" -> c.copy(homeRows = v.replace(" ", ""))
                "latest-count" -> int(v)?.let { c.copy(latestItemsPerLibrary = it) }
                else -> null
            }

            "episodes", "episode" -> when (prop) {
                "layout", "display" -> when (v.lowercase()) {
                    "list", "grid", "row" -> c.copy(episodeLayout = v.replaceFirstChar { it.uppercase() })
                    else -> null
                }
                "columns" -> int(v)?.let { c.copy(episodeGridColumns = it) }
                "thumb-width", "width" -> int(v)?.let { c.copy(episodeThumbWidth = it) }
                "seasons" -> when (v.lowercase()) {
                    "tabs", "stacked" -> c.copy(seasonDisplay = v.replaceFirstChar { it.uppercase() })
                    else -> null
                }
                "order", "sort" -> when (v.lowercase()) {
                    "asc", "ascending" -> c.copy(episodeSort = "Ascending")
                    "desc", "descending" -> c.copy(episodeSort = "Descending")
                    else -> null
                }
                "overview", "description" -> bool(v)?.let { c.copy(showEpisodeOverview = it) }
                else -> null
            }

            "subtitles", "subtitle", "captions" -> when (prop) {
                "mode", "display" -> when (v.lowercase()) {
                    "jellyfin", "user" -> c.copy(subtitleMode = "Jellyfin")
                    "default", "auto" -> c.copy(subtitleMode = "Default")
                    "always", "on", "show", "block" -> c.copy(subtitleMode = "Always")
                    "forced", "forced-only", "forcedonly" -> c.copy(subtitleMode = "ForcedOnly")
                    "off", "none", "hide" -> c.copy(subtitleMode = "Off")
                    else -> null
                }
                "language", "lang" -> c.copy(subtitleLanguage = v.trim('"', '\''))
                "size", "font-size" -> int(v.removeSuffix("%"))?.let { c.copy(subtitleSize = it) }
                "color" -> color(v)?.let { c.copy(subtitleColor = it) }
                "background", "style", "edge" -> when (v.lowercase()) {
                    "outline" -> c.copy(subtitleBackground = "Outline")
                    "shadow", "drop-shadow" -> c.copy(subtitleBackground = "Shadow")
                    "box", "background" -> c.copy(subtitleBackground = "Box")
                    "none" -> c.copy(subtitleBackground = "None")
                    else -> null
                }
                "position", "bottom" -> int(v.removeSuffix("%"))?.let { c.copy(subtitlePosition = it) }
                else -> null
            }

            "player", "skip" -> when (prop) {
                "skip-intro", "intro" -> mode(v)?.let { c.copy(skipIntroMode = it) }
                "skip-credits", "credits" -> mode(v)?.let { c.copy(skipCreditsMode = it) }
                "skip", "mode" -> mode(v)?.let { c.copy(skipIntroMode = it, skipCreditsMode = it) }
                "button-seconds", "duration" -> int(v)?.let { c.copy(skipButtonSeconds = it) }
                else -> null
            }

            else -> null
        } ?: c

    private fun mode(v: String): String? = when (v.lowercase()) {
        "button", "show" -> "Button"
        "auto", "automatic" -> "Auto"
        "off", "none", "hide" -> "Off"
        else -> null
    }

    private fun shape(v: String): String? = when (v.lowercase()) {
        "portrait", "poster" -> "Portrait"
        "landscape", "wide" -> "Landscape"
        else -> null
    }
}
