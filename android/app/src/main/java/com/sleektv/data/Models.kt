package com.sleektv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthResult(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("User") val user: UserDto,
)

@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
)

/** Quick Connect: the TV shows [code]; the user approves it from another signed-in device. */
@Serializable
data class QuickConnectState(
    @SerialName("Secret") val secret: String,
    @SerialName("Code") val code: String = "",
    @SerialName("Authenticated") val authenticated: Boolean = false,
)

@Serializable
data class ItemsResult(
    @SerialName("Items") val items: List<BaseItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

@Serializable
data class UserData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
)

@Serializable
data class MediaSource(
    @SerialName("Id") val id: String,
    @SerialName("Container") val container: String? = null,
)

@Serializable
data class Person(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
)

@Serializable
data class BaseItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("SortName") val sortName: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("IsFolder") val isFolder: Boolean? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("CommunityRating") val communityRating: Float? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("People") val people: List<Person> = emptyList(),
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String> = emptyList(),
    @SerialName("ParentThumbItemId") val parentThumbItemId: String? = null,
    @SerialName("ParentThumbImageTag") val parentThumbImageTag: String? = null,
    @SerialName("ParentLogoItemId") val parentLogoItemId: String? = null,
    @SerialName("ParentLogoImageTag") val parentLogoImageTag: String? = null,
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryImageTag: String? = null,
    @SerialName("UserData") val userData: UserData? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSource> = emptyList(),
)

/** A skippable part of a video (intro, recap, credits...), from Jellyfin's Media Segments API. */
@Serializable
data class MediaSegment(
    @SerialName("Type") val type: String = "Unknown", // Intro | Outro | Recap | Preview | Commercial
    @SerialName("StartTicks") val startTicks: Long = 0,
    @SerialName("EndTicks") val endTicks: Long = 0,
) {
    val startMs: Long get() = startTicks / 10_000L
    val endMs: Long get() = endTicks / 10_000L

    val label: String
        get() = when (type) {
            "Intro" -> "Skip Intro"
            "Outro" -> "Skip Credits"
            "Recap" -> "Skip Recap"
            "Preview" -> "Skip Preview"
            "Commercial" -> "Skip Ad"
            else -> "Skip"
        }
}

@Serializable
data class MediaSegmentsResult(
    @SerialName("Items") val items: List<MediaSegment> = emptyList(),
)

// ---- Display helpers ----

val BaseItem.isEpisode: Boolean get() = type == "Episode"

val BaseItem.isSeries: Boolean get() = type == "Series"

private val folderTypes = setOf("Folder", "CollectionFolder", "UserView", "BoxSet", "Playlist")

val BaseItem.isBrowsableFolder: Boolean get() = type in folderTypes

/** 0..1 when partly watched, otherwise null. */
val BaseItem.progress: Float?
    get() = userData?.playedPercentage?.takeIf { it > 0.5 && it < 99.5 }?.let { (it / 100.0).toFloat() }

val BaseItem.isWatched: Boolean get() = userData?.played == true

val BaseItem.runtimeText: String?
    get() {
        val minutes = (runTimeTicks ?: return null) / 600_000_000L
        if (minutes <= 0) return null
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }

val BaseItem.episodeCode: String?
    get() {
        val s = parentIndexNumber ?: return indexNumber?.let { "E$it" }
        val e = indexNumber ?: return "S$s"
        return "S$s:E$e"
    }

/** "2019 · PG-13 · 1h 52m · ★ 7.8" */
fun BaseItem.metaLine(): String = listOfNotNull(
    productionYear?.toString(),
    officialRating,
    if (isSeries) childCount?.let { if (it == 1) "1 season" else "$it seasons" } else runtimeText,
    communityRating?.takeIf { it > 0 }?.let { "★ " + String.format("%.1f", it) },
).joinToString("  ·  ")

/** Card title: show name for episodes, item name otherwise. */
val BaseItem.cardTitle: String get() = (if (isEpisode) seriesName else null) ?: name.orEmpty()

/** Short line under a card, SleekFin-style. */
val BaseItem.cardSubtitle: String?
    get() = when {
        isEpisode -> listOfNotNull(episodeCode, name).joinToString(" · ")
        else -> productionYear?.toString()
    }
