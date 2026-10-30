package com.sleektv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Seerr media status: whether a title is on the server, requested, or neither. */
@Serializable
data class SeerrMediaInfo(
    val status: Int = 1, // 1 unknown, 2 pending, 3 processing, 4 partially available, 5 available
    val jellyfinMediaId: String? = null,
)

@Serializable
data class SeerrGenre(val name: String = "")

/** A search/discover result or a movie/tv detail page from Seerr (TMDB data). */
@Serializable
data class SeerrItem(
    val id: Int,
    val mediaType: String? = null, // movie | tv | person (search/discover only)
    val title: String? = null, // movies
    val name: String? = null, // shows
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val voteAverage: Double? = null,
    val runtime: Int? = null,
    val numberOfSeasons: Int? = null,
    val genres: List<SeerrGenre> = emptyList(),
    val mediaInfo: SeerrMediaInfo? = null,
) {
    val displayTitle: String get() = title ?: name ?: ""

    val year: String? get() = (releaseDate ?: firstAirDate)?.take(4)?.takeIf { it.length == 4 }

    val status: Int get() = mediaInfo?.status ?: 1

    val isAvailable: Boolean get() = status == 5

    val isPartlyAvailable: Boolean get() = status == 4

    val isRequested: Boolean get() = status == 2 || status == 3

    val statusLabel: String?
        get() = when (status) {
            5 -> "Available"
            4 -> "Partly available"
            3 -> "Processing"
            2 -> "Requested"
            else -> null
        }

    fun posterUrl(size: String = "w342"): String? = posterPath?.let { "https://image.tmdb.org/t/p/$size$it" }

    fun backdropUrl(size: String = "w1280"): String? = backdropPath?.let { "https://image.tmdb.org/t/p/$size$it" }

    fun metaLine(isTv: Boolean): String = listOfNotNull(
        year,
        if (isTv) numberOfSeasons?.let { if (it == 1) "1 season" else "$it seasons" }
        else runtime?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
        voteAverage?.takeIf { it > 0 }?.let { "★ " + String.format("%.1f", it) },
    ).joinToString("  ·  ")
}

@Serializable
data class SeerrPage(
    val page: Int = 1,
    val totalPages: Int = 1,
    val results: List<SeerrItem> = emptyList(),
)

@Serializable
data class SeerrStatus(
    val configured: Boolean = false,
    val reachable: Boolean = false,
    val message: String? = null,
)

@Serializable
data class ServerMessage(
    @SerialName("message") val message: String? = null,
)
