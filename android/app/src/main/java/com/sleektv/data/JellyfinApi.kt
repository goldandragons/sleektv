package com.sleektv.data

import android.os.Build
import com.sleektv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String, val lastBody: String? = null) : Exception(message)

/**
 * A small REST client for the Jellyfin endpoints the app needs.
 * Auth goes in the `Authorization: MediaBrowser ...` header on every request (including
 * images and video), which is the scheme Jellyfin 12 accepts with legacy auth turned off.
 */
class JellyfinApi(private val session: Session) {

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // Only send the Jellyfin sign-in to the Jellyfin server, never to TMDB or other hosts.
            val request = chain.request()
            val server = session.serverUrl?.let { normalizeServer(it).toHttpUrlOrNull() }
            if (server != null && request.url.host == server.host && request.url.port == server.port) {
                chain.proceed(request.newBuilder().header("Authorization", authHeader()).build())
            } else {
                chain.proceed(request)
            }
        }
        .build()

    /** For outside files like @import themes: no Jellyfin credentials attached. */
    private val plainHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Downloads a CSS file (max 2 MB) from anywhere on the web. */
    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        plainHttp.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "Couldn't download $url")
            val source = response.body ?: throw ApiException(response.code, "Empty response from $url")
            if (source.contentLength() > 2_000_000) throw ApiException(413, "$url is too large")
            source.string().take(2_000_000)
        }
    }

    private val fields = "Overview,Genres,People,MediaSources,PrimaryImageAspectRatio,ChildCount"
    private val userId: String get() = session.userId.orEmpty()

    fun authHeader(): String {
        val device = Build.MODEL.replace("\"", "").ifBlank { "Android TV" }
        val base = "MediaBrowser Client=\"SleekTV\", Device=\"$device\", " +
            "DeviceId=\"${session.deviceId}\", Version=\"${BuildConfig.VERSION_NAME}\""
        val token = session.token
        return if (token.isNullOrBlank()) base else "$base, Token=\"$token\""
    }

    // ---------- URL + request helpers ----------

    fun url(path: String, params: Map<String, Any?> = emptyMap()): HttpUrl {
        val builder = normalizeServer(session.serverUrl.orEmpty()).toHttpUrl().newBuilder()
        path.trim('/').split('/').forEach { builder.addPathSegment(it) }
        params.forEach { (k, v) -> if (v != null) builder.addQueryParameter(k, v.toString()) }
        return builder.build()
    }

    private suspend fun getText(path: String, params: Map<String, Any?> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url(path, params)).get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiException(response.code, "Server returned ${response.code}")
                body
            }
        }

    private suspend fun <T> get(
        deserializer: DeserializationStrategy<T>,
        path: String,
        params: Map<String, Any?> = emptyMap(),
    ): T {
        val text = getText(path, params)
        return withContext(Dispatchers.Default) { json.decodeFromString(deserializer, text) }
    }

    private suspend fun post(path: String, body: JsonObject): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(path))
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(
                    response.code,
                    if (response.code == 401) "Wrong username or password" else "Server returned ${response.code}",
                    text,
                )
            }
            text
        }
    }

    // ---------- Auth + layout ----------

    suspend fun login(server: String, username: String, password: String) {
        session.serverUrl = normalizeServer(server)
        session.token = null
        val text = post(
            "Users/AuthenticateByName",
            buildJsonObject {
                put("Username", username)
                put("Pw", password)
            },
        )
        storeAuth(text)
    }

    private fun storeAuth(text: String) {
        val auth = json.decodeFromString(AuthResult.serializer(), text)
        session.token = auth.accessToken
        session.userId = auth.user.id
        session.userName = auth.user.name
    }

    // ---------- Quick Connect ----------

    /** Points the app at [server] and asks whether Quick Connect is turned on there. */
    suspend fun quickConnectEnabled(server: String): Boolean {
        session.serverUrl = normalizeServer(server)
        session.token = null
        return getText("QuickConnect/Enabled").trim().equals("true", ignoreCase = true)
    }

    /** Starts a request; the returned code is what the user types on their phone. */
    suspend fun quickConnectInitiate(): QuickConnectState =
        json.decodeFromString(QuickConnectState.serializer(), post("QuickConnect/Initiate", buildJsonObject { }))

    /** Poll this until [QuickConnectState.authenticated] is true. */
    suspend fun quickConnectStatus(secret: String): QuickConnectState =
        get(QuickConnectState.serializer(), "QuickConnect/Connect", mapOf("secret" to secret))

    suspend fun loginWithQuickConnect(secret: String) {
        storeAuth(post("Users/AuthenticateWithQuickConnect", buildJsonObject { put("Secret", secret) }))
    }

    /** Raw layout JSON from the SleekTV plugin (GET /SleekTV/Layout). */
    suspend fun layoutJson(): String = getText("SleekTV/Layout")

    // ---------- Browsing ----------

    suspend fun views(): List<BaseItem> =
        get(ItemsResult.serializer(), "UserViews", mapOf("userId" to userId)).items

    suspend fun resume(): List<BaseItem> = get(
        ItemsResult.serializer(), "UserItems/Resume",
        mapOf("userId" to userId, "limit" to 24, "mediaTypes" to "Video", "fields" to fields),
    ).items

    suspend fun nextUp(seriesId: String? = null): List<BaseItem> = get(
        ItemsResult.serializer(), "Shows/NextUp",
        mapOf("userId" to userId, "limit" to 24, "fields" to fields, "seriesId" to seriesId),
    ).items

    suspend fun latest(parentId: String, limit: Int): List<BaseItem> = get(
        ListSerializer(BaseItem.serializer()), "Items/Latest",
        mapOf("userId" to userId, "parentId" to parentId, "limit" to limit, "fields" to fields),
    )

    suspend fun items(
        parentId: String,
        includeTypes: String?,
        recursive: Boolean,
        startIndex: Int,
        limit: Int,
    ): ItemsResult = get(
        ItemsResult.serializer(), "Items",
        mapOf(
            "userId" to userId, "parentId" to parentId, "includeItemTypes" to includeTypes,
            "recursive" to recursive, "sortBy" to "SortName", "sortOrder" to "Ascending",
            "startIndex" to startIndex, "limit" to limit, "fields" to fields,
        ),
    )

    /**
     * Every title's sort name in the same order as [items], without images or user data,
     * so the A–Z rail knows where each letter starts.
     */
    suspend fun sortNames(parentId: String, includeTypes: String?, recursive: Boolean): List<String> = get(
        ItemsResult.serializer(), "Items",
        mapOf(
            "userId" to userId, "parentId" to parentId, "includeItemTypes" to includeTypes,
            "recursive" to recursive, "sortBy" to "SortName", "sortOrder" to "Ascending",
            "fields" to "SortName", "enableImages" to false, "enableUserData" to false,
            "enableTotalRecordCount" to false,
        ),
    ).items.map { it.sortName ?: it.name.orEmpty() }

    /** Library-wide search; the server orders results by relevance. */
    suspend fun search(term: String, includeTypes: String, limit: Int = 30): List<BaseItem> = get(
        ItemsResult.serializer(), "Items",
        mapOf(
            "userId" to userId, "searchTerm" to term, "includeItemTypes" to includeTypes,
            "recursive" to true, "limit" to limit, "fields" to fields,
            "imageTypeLimit" to 1, "enableTotalRecordCount" to false,
        ),
    ).items

    suspend fun item(id: String): BaseItem =
        get(BaseItem.serializer(), "Items/$id", mapOf("userId" to userId))

    suspend fun seasons(seriesId: String): List<BaseItem> = get(
        ItemsResult.serializer(), "Shows/$seriesId/Seasons",
        mapOf("userId" to userId, "fields" to fields),
    ).items

    suspend fun episodes(seriesId: String): List<BaseItem> = get(
        ItemsResult.serializer(), "Shows/$seriesId/Episodes",
        mapOf("userId" to userId, "fields" to fields),
    ).items

    suspend fun similar(id: String): List<BaseItem> = get(
        ItemsResult.serializer(), "Items/$id/Similar",
        mapOf("userId" to userId, "limit" to 16, "fields" to fields),
    ).items

    // ---------- Seerr (through the SleekTV plugin) ----------

    suspend fun seerrSearch(query: String): List<SeerrItem> =
        get(SeerrPage.serializer(), "SleekTV/Seerr/Search", mapOf("query" to query)).results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }

    /** kind: trending | movies | tv | upcoming */
    suspend fun seerrDiscover(kind: String, page: Int = 1): List<SeerrItem> =
        get(SeerrPage.serializer(), "SleekTV/Seerr/Discover/$kind", mapOf("page" to page)).results
            .map {
                // Discover/movies and discover/tv leave mediaType out.
                if (it.mediaType != null) it else it.copy(mediaType = if (kind == "tv") "tv" else "movie")
            }
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }

    suspend fun seerrDetail(mediaType: String, tmdbId: Int): SeerrItem =
        get(SeerrItem.serializer(), if (mediaType == "tv") "SleekTV/Seerr/Tv/$tmdbId" else "SleekTV/Seerr/Movie/$tmdbId")
            .copy(mediaType = mediaType)

    /** Requests a movie, or all seasons of a show. Throws with Seerr's message if it refuses. */
    suspend fun seerrRequest(mediaType: String, tmdbId: Int) {
        try {
            post("SleekTV/Seerr/Request", buildJsonObject {
                put("MediaType", mediaType)
                put("MediaId", tmdbId)
            })
        } catch (e: ApiException) {
            throw ApiException(e.code, e.lastBody?.let { readMessage(it) } ?: when (e.code) {
                403 -> "You don't have permission to request this."
                409 -> "This has already been requested."
                else -> "Request failed (${e.code})"
            })
        }
    }

    private fun readMessage(body: String): String? =
        runCatching { json.decodeFromString(ServerMessage.serializer(), body).message }.getOrNull()?.takeIf { it.isNotBlank() }

    // ---------- Skip intro / credits ----------

    /**
     * Intro/credits timestamps. Jellyfin's Media Segments API is filled by the Intro Skipper
     * plugin; if that comes back empty, fall back to Intro Skipper's own endpoint.
     */
    suspend fun segments(itemId: String): List<MediaSegment> {
        val official = runCatching {
            get(MediaSegmentsResult.serializer(), "MediaSegments/$itemId").items
        }.getOrDefault(emptyList())
        if (official.isNotEmpty()) return official.filter { it.endTicks > it.startTicks }

        return runCatching {
            val obj = json.parseToJsonElement(getText("Episode/$itemId/IntroSkipperSegments")).jsonObject
            listOfNotNull(
                legacySegment(obj["Introduction"], "Intro"),
                legacySegment(obj["Credits"], "Outro"),
                legacySegment(obj["Recap"], "Recap"),
                legacySegment(obj["Preview"], "Preview"),
            )
        }.getOrDefault(emptyList())
    }

    /** Intro Skipper's own format: { "Start": seconds, "End": seconds, "Valid": bool }. */
    private fun legacySegment(element: JsonElement?, type: String): MediaSegment? {
        val o = (element as? JsonObject) ?: return null
        val valid = o["Valid"]?.jsonPrimitive?.booleanOrNull ?: true
        val start = o["Start"]?.jsonPrimitive?.doubleOrNull ?: o["IntroStart"]?.jsonPrimitive?.doubleOrNull ?: return null
        val end = o["End"]?.jsonPrimitive?.doubleOrNull ?: o["IntroEnd"]?.jsonPrimitive?.doubleOrNull ?: return null
        if (!valid || end <= start) return null
        return MediaSegment(type, (start * 10_000_000).toLong(), (end * 10_000_000).toLong())
    }

    // ---------- Playback ----------

    /** The signed-in user's own subtitle/audio preferences from Jellyfin. */
    suspend fun userPlaybackConfig(): UserPlaybackConfig? =
        runCatching { get(CurrentUser.serializer(), "Users/Me").configuration }.getOrNull()

    /** A text subtitle stream converted to WebVTT by the server (works for srt/ass/vtt/embedded text). */
    fun subtitleUrl(itemId: String, mediaSourceId: String, streamIndex: Int): String =
        url("Videos/$itemId/$mediaSourceId/Subtitles/$streamIndex/0/Stream.vtt").toString()

    fun directStreamUrl(itemId: String, mediaSourceId: String): String =
        url("Videos/$itemId/stream", mapOf("static" to true, "mediaSourceId" to mediaSourceId)).toString()

    /** Fallback when the box can't play the original file: server transcodes to H.264/AAC HLS. */
    fun transcodeUrl(itemId: String, mediaSourceId: String, playSessionId: String): String = url(
        "Videos/$itemId/master.m3u8",
        mapOf(
            "mediaSourceId" to mediaSourceId,
            "deviceId" to session.deviceId,
            "playSessionId" to playSessionId,
            "videoCodec" to "h264",
            "audioCodec" to "aac",
            "maxStreamingBitrate" to 20_000_000,
            "transcodingMaxAudioChannels" to 2,
            "segmentContainer" to "ts",
        ),
    ).toString()

    private fun playbackBody(
        itemId: String, mediaSourceId: String, playSessionId: String,
        positionTicks: Long, paused: Boolean = false, playMethod: String = "DirectPlay",
    ) = buildJsonObject {
        put("ItemId", itemId)
        put("MediaSourceId", mediaSourceId)
        put("PlaySessionId", playSessionId)
        put("PositionTicks", positionTicks)
        put("IsPaused", paused)
        put("PlayMethod", playMethod)
        put("CanSeek", true)
    }

    suspend fun reportStart(itemId: String, mediaSourceId: String, playSessionId: String, positionTicks: Long, playMethod: String) {
        post("Sessions/Playing", playbackBody(itemId, mediaSourceId, playSessionId, positionTicks, playMethod = playMethod))
    }

    suspend fun reportProgress(itemId: String, mediaSourceId: String, playSessionId: String, positionTicks: Long, paused: Boolean, playMethod: String) {
        post("Sessions/Playing/Progress", playbackBody(itemId, mediaSourceId, playSessionId, positionTicks, paused, playMethod))
    }

    suspend fun reportStopped(itemId: String, mediaSourceId: String, playSessionId: String, positionTicks: Long) {
        post("Sessions/Playing/Stopped", playbackBody(itemId, mediaSourceId, playSessionId, positionTicks))
    }

    // ---------- Images ----------

    private fun image(itemId: String, type: String, tag: String?, width: Int, index: Int? = null): String {
        val path = if (index != null) "Items/$itemId/Images/$type/$index" else "Items/$itemId/Images/$type"
        return url(path, mapOf("fillWidth" to width, "quality" to 80, "tag" to tag)).toString()
    }

    /** Portrait poster. Episodes use their show's poster. */
    fun poster(item: BaseItem, width: Int = 400): String? = when {
        item.isEpisode && item.seriesId != null && item.seriesPrimaryImageTag != null ->
            image(item.seriesId, "Primary", item.seriesPrimaryImageTag, width)
        item.imageTags["Primary"] != null -> image(item.id, "Primary", item.imageTags["Primary"], width)
        else -> null
    }

    fun backdrop(item: BaseItem, width: Int = 1280): String? = when {
        item.backdropImageTags.isNotEmpty() -> image(item.id, "Backdrop", item.backdropImageTags[0], width, 0)
        item.parentBackdropItemId != null && item.parentBackdropImageTags.isNotEmpty() ->
            image(item.parentBackdropItemId, "Backdrop", item.parentBackdropImageTags[0], width, 0)
        else -> null
    }

    private fun thumb(item: BaseItem, width: Int): String? = when {
        item.imageTags["Thumb"] != null -> image(item.id, "Thumb", item.imageTags["Thumb"], width)
        item.parentThumbItemId != null && item.parentThumbImageTag != null ->
            image(item.parentThumbItemId, "Thumb", item.parentThumbImageTag, width)
        else -> null
    }

    /** 16:9 card art: episode still, then thumb, then backdrop, then poster. */
    fun landscape(item: BaseItem, width: Int = 640): String? {
        if (item.isEpisode && item.imageTags["Primary"] != null) {
            return image(item.id, "Primary", item.imageTags["Primary"], width)
        }
        if (!item.isEpisode && item.imageTags["Primary"] != null && item.isBrowsableFolder) {
            return image(item.id, "Primary", item.imageTags["Primary"], width)
        }
        return thumb(item, width) ?: backdrop(item, width) ?: poster(item, width)
    }

    /** Title art ("logo"), used instead of text titles like SleekFin does. */
    fun logo(item: BaseItem, width: Int = 900): String? = when {
        item.imageTags["Logo"] != null -> image(item.id, "Logo", item.imageTags["Logo"], width)
        item.parentLogoItemId != null && item.parentLogoImageTag != null ->
            image(item.parentLogoItemId, "Logo", item.parentLogoImageTag, width)
        else -> null
    }

    fun personImage(person: Person, width: Int = 240): String? =
        person.primaryImageTag?.let { image(person.id, "Primary", it, width) }

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()

        fun normalizeServer(input: String): String {
            var s = input.trim().trimEnd('/')
            if (s.isEmpty()) return "http://localhost:8096"
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            return s
        }
    }
}
