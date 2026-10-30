package com.sleektv.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.roundToInt

/**
 * Translates Jellyfin *web* themes (NetFin, Ultrachromic, ElegantFin, …) into SleekTV settings.
 *
 * The app is native, so most web CSS has nothing to apply to. What carries over:
 *  - colors from the theme's variables (--activeColor, --textColor, …) or body/card rules
 *  - card corner radius, focused-card zoom, row title and detail title sizes
 * Fonts, blur, animations and page-layout rules are ignored.
 *
 * `@import url(...)` lines are downloaded by [resolveImports] and passed in as plain text.
 */
object WebCss {

    private val comments = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val importRe = Regex("""@import\s+(?:url\(\s*)?["']?([^"')\s;]+)["']?\s*\)?[^;]*;""", RegexOption.IGNORE_CASE)
    private val varRe = Regex("""var\(\s*(--[\w-]+)\s*(?:,\s*([^()]*(?:\([^()]*\))?[^()]*))?\)""")

    // Font CSS can't be used by the app, so don't bother downloading it.
    private val skippedHosts = setOf("fonts.googleapis.com", "fonts.gstatic.com", "use.typekit.net")

    private data class Rule(val selectors: List<String>, val decls: Map<String, String>)

    /** URLs from `@import` lines, resolved against [base] for relative paths. */
    fun importUrls(css: String, base: String? = null): List<String> =
        importRe.findAll(css.replace(comments, "")).mapNotNull { m ->
            val raw = m.groupValues[1]
            val resolved = (base?.toHttpUrlOrNull()?.resolve(raw) ?: raw.toHttpUrlOrNull())?.toString()
            resolved?.takeIf { url -> url.toHttpUrlOrNull()?.host !in skippedHosts }
        }.distinct().toList()

    /**
     * Downloads every `@import` (and imports inside those, up to 3 levels / 10 files)
     * and returns all of their CSS joined together.
     */
    suspend fun resolveImports(css: String, fetch: suspend (String) -> String): String {
        val seen = mutableSetOf<String>()
        val out = StringBuilder()
        suspend fun walk(text: String, base: String?, depth: Int) {
            if (depth > 3) return
            for (url in importUrls(text, base)) {
                if (seen.size >= 10 || !seen.add(url)) continue
                val body = runCatching { fetch(url) }.getOrNull() ?: continue
                walk(body, url, depth + 1) // imported files first, so the importing file wins
                out.append(body).append('\n')
            }
        }
        walk(css, null, 0)
        return out.toString()
    }

    fun apply(base: LayoutConfig, css: String): LayoutConfig {
        if (css.isBlank() || !css.contains('{')) return base
        val rules = parseRules(css)
        if (rules.isEmpty()) return base

        // Variables: anything defined on :root / html / body (later definitions win).
        val vars = HashMap<String, String>()
        rules.filter { r -> r.selectors.any { isRootSelector(it) } }
            .forEach { r -> r.decls.forEach { (k, v) -> if (k.startsWith("--")) vars[k] = v } }

        fun resolve(value: String): String {
            var v = value
            repeat(6) {
                if (!v.contains("var(")) return v
                v = varRe.replace(v) { m -> vars[m.groupValues[1].lowercase()] ?: m.groupValues[2].ifBlank { "" } }
            }
            return v
        }

        fun variable(vararg names: String): String? =
            names.firstNotNullOfOrNull { vars[it.lowercase()] }?.let(::resolve)

        // Last matching declaration wins, like in a browser.
        fun ruleValue(props: List<String>, match: (String) -> Boolean): String? {
            var found: String? = null
            for (r in rules) {
                if (r.selectors.none(match)) continue
                for (p in props) r.decls[p]?.let { found = resolve(it) }
            }
            return found
        }

        var c = base

        // ---- Colors ----
        val accent = variable("--uiAccentColor", "--activeColor", "--accentColor", "--accent-color",
            "--theme-primary-color", "--primary-color", "--accent", "--theme-accent")
            ?: ruleValue(listOf("background-color", "background")) { it.contains(".button-submit") }
        findColor(accent)?.let { c = c.copy(accentColor = it) }

        val background = ruleValue(listOf("background-color", "background")) {
            it in setOf("body", "html", ".backgroundContainer", ".skinBody", ".mainAnimatedPage")
        } ?: variable("--backgroundColor", "--background-color", "--theme-background", "--bg-color",
            "--darkerGradientPoint", "--background")
        findColor(background)?.let { c = c.copy(backgroundColor = it) }

        val surface = ruleValue(listOf("background-color", "background")) { sel ->
            listOf(".cardContent", ".cardBox", ".cardImageContainer", ".cardScalable").any { sel.endsWith(it) }
        } ?: variable("--cardBackground", "--card-background", "--cardBackgroundColor", "--surfaceColor",
            "--surface-color", "--lighterGradientPoint")
        findColor(surface)?.let { c = c.copy(surfaceColor = it) }

        val text = variable("--textColor", "--text-color", "--theme-text-color", "--primaryTextColor")
            ?: ruleValue(listOf("color")) { it == "body" || it == "html" }
        findColor(text)?.let { c = c.copy(textColor = it) }

        // ---- Cards ----
        val radius = ruleValue(listOf("border-radius")) { sel ->
            !sel.contains(':') && listOf(".cardScalable", ".cardContent", ".cardImageContainer", ".cardBox", ".cardImage")
                .any { sel.endsWith(it) }
        } ?: variable("--cardRadius", "--card-radius", "--cardBorderRadius", "--largeRadius", "--borderRadius")
        length(radius)?.let { c = c.copy(cardCornerRadius = it.roundToInt()) }

        val focusTransform = ruleValue(listOf("transform", "scale")) { sel ->
            (sel.contains(".card") || sel.contains(".cardBox")) &&
                (sel.contains(":focus") || sel.contains(".show-focus") || sel.contains(":hover"))
        }
        scale(focusTransform)?.let { c = c.copy(focusScale = it) }

        // ---- Text sizes ----
        length(ruleValue(listOf("font-size")) { it.contains(".sectionTitle") })
            ?.let { c = c.copy(sectionTitleSize = it.roundToInt()) }
        length(ruleValue(listOf("font-size")) { sel ->
            sel.contains(".itemName") && (sel.contains("detail") || sel.contains("Detail")) ||
                sel.contains(".detailPagePrimaryContainer h1")
        })?.let { c = c.copy(detailTitleSize = it.roundToInt()) }

        return c
    }

    // ---------- Parsing ----------

    private fun isRootSelector(s: String): Boolean {
        val t = s.trim()
        return t == ":root" || t == "html" || t == "body" || t.startsWith(":root") || t.startsWith("html.") || t.startsWith("html[")
    }

    /** Top-level rules only; @media, @supports, @keyframes and @font-face blocks are skipped. */
    private fun parseRules(css: String): List<Rule> {
        val s = css.replace(comments, "")
        val out = mutableListOf<Rule>()
        var i = 0
        while (i < s.length) {
            val open = s.indexOf('{', i)
            if (open < 0) break
            var depth = 1
            var j = open + 1
            while (j < s.length && depth > 0) {
                when (s[j]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                j++
            }
            val head = s.substring(i, open).substringAfterLast(';').substringAfterLast('}').trim()
            val body = s.substring(open + 1, maxOf(open + 1, j - 1))
            if (!head.startsWith("@") && !body.contains('{') && head.isNotEmpty()) {
                out += Rule(
                    selectors = head.split(',').map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() },
                    decls = parseDecls(body),
                )
            }
            i = j
        }
        return out
    }

    private fun parseDecls(body: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (part in body.split(';')) {
            val colon = part.indexOf(':')
            if (colon <= 0) continue
            val prop = part.substring(0, colon).trim().lowercase()
            val value = part.substring(colon + 1).replace("!important", "", ignoreCase = true).trim()
            if (prop.isNotEmpty() && value.isNotEmpty()) map[prop] = value
        }
        return map
    }

    // ---------- Values ----------

    private val colorToken = Regex(
        """#[0-9a-fA-F]{3,8}\b|rgba?\([^)]*\)|hsla?\([^)]*\)|\b(black|white|red|transparent)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** First usable color in a value (also works on gradients), as #RRGGBB. */
    fun findColor(value: String?): String? {
        if (value.isNullOrBlank()) return null
        parseColor(value.trim())?.let { return it }
        return colorToken.findAll(value).firstNotNullOfOrNull { parseColor(it.value) }
    }

    private fun parseColor(v: String): String? {
        val s = v.trim().lowercase()
        when (s) {
            "black" -> return "#000000"
            "white" -> return "#FFFFFF"
            "red" -> return "#FF0000"
            "transparent" -> return null
        }
        if (s.startsWith("#")) {
            val h = s.substring(1)
            return when (h.length) {
                3, 4 -> "#" + h.take(3).map { "$it$it" }.joinToString("").uppercase()
                6, 8 -> "#" + h.take(6).uppercase()
                else -> null
            }
        }
        val nums = Regex("""-?\d*\.?\d+%?""").findAll(s.substringAfter('(')).map { it.value }.toList()
        if (s.startsWith("rgb") && nums.size >= 3) {
            val (r, g, b) = nums.take(3).map { n ->
                if (n.endsWith('%')) (n.dropLast(1).toDouble() * 2.55) else n.toDouble()
            }
            return hex(r, g, b)
        }
        if (s.startsWith("hsl") && nums.size >= 3) {
            val h = nums[0].trimEnd('%').toDouble()
            val sat = nums[1].trimEnd('%').toDouble() / 100.0
            val l = nums[2].trimEnd('%').toDouble() / 100.0
            val k = { n: Double -> (n + h / 30.0) % 12 }
            val a = sat * minOf(l, 1 - l)
            val f = { n: Double -> l - a * maxOf(-1.0, minOf(k(n) - 3, minOf(9 - k(n), 1.0))) }
            return hex(f(0.0) * 255, f(8.0) * 255, f(4.0) * 255)
        }
        return null
    }

    private fun hex(r: Double, g: Double, b: Double): String =
        "#%02X%02X%02X".format(
            r.roundToInt().coerceIn(0, 255),
            g.roundToInt().coerceIn(0, 255),
            b.roundToInt().coerceIn(0, 255),
        )

    /** px stays px; em/rem become px at 16px per em. Percentages and other units are skipped. */
    private fun length(value: String?): Double? {
        val m = Regex("""^(-?\d*\.?\d+)\s*(px|em|rem)?$""").find(value?.trim()?.lowercase() ?: return null) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2]) {
            "em", "rem" -> n * 16
            else -> n
        }
    }

    private fun scale(value: String?): Double? {
        val v = value?.lowercase() ?: return null
        Regex("""scale\(\s*(\d*\.?\d+)""").find(v)?.let { return it.groupValues[1].toDoubleOrNull() }
        return v.trim().toDoubleOrNull()
    }
}
