package com.sleektv.ui.screens

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.SubtitleView
import android.view.LayoutInflater
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.sleektv.R
import com.sleektv.data.BaseItem
import com.sleektv.data.LayoutConfig
import com.sleektv.data.MediaSegment
import com.sleektv.data.UserPlaybackConfig
import com.sleektv.data.isEpisode
import com.sleektv.data.isSeries
import com.sleektv.data.isWatched
import com.sleektv.ui.Nav
import com.sleektv.ui.Screen
import com.sleektv.ui.components.CenterMessage
import com.sleektv.ui.components.SText
import com.sleektv.ui.components.SleekButton
import com.sleektv.ui.theme.LocalLayout
import com.sleektv.ui.theme.scaledSp
import com.sleektv.ui.theme.toColorOr
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/** Everything the player needs before it starts. */
private data class PlaybackPlan(
    val item: BaseItem,
    val previous: BaseItem?,
    val next: BaseItem?,
    val userConfig: UserPlaybackConfig?,
)

@Composable
fun PlayerScreen(nav: Nav, itemId: String) {
    val api = nav.app.api
    var plan by remember { mutableStateOf<PlaybackPlan?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(itemId) {
        try {
            var target = api.item(itemId)
            // "Play" on a show: pick the next episode to watch.
            if (target.isSeries) {
                val episode = api.nextUp(target.id).firstOrNull()
                    ?: api.episodes(target.id).let { eps -> eps.firstOrNull { !it.isWatched } ?: eps.firstOrNull() }
                    ?: throw IllegalStateException("This show has no episodes")
                target = api.item(episode.id)
            }

            // Neighbouring episodes for the previous/next buttons. Specials only count if you're in them.
            var previous: BaseItem? = null
            var next: BaseItem? = null
            val seriesId = target.seriesId
            if (target.isEpisode && seriesId != null) {
                val inSpecials = target.parentIndexNumber == 0
                val episodes = runCatching { api.episodes(seriesId) }.getOrDefault(emptyList())
                    .filter { inSpecials || it.parentIndexNumber != 0 }
                val at = episodes.indexOfFirst { it.id == target.id }
                if (at >= 0) {
                    previous = episodes.getOrNull(at - 1)
                    next = episodes.getOrNull(at + 1)
                }
            }

            plan = PlaybackPlan(target, previous, next, api.userPlaybackConfig())
        } catch (e: Exception) {
            error = e.message ?: "Couldn't start playback"
        }
    }

    val current = plan
    when {
        error != null -> CenterMessage(error.orEmpty()) { SleekButton("Back", onClick = { nav.back() }, primary = true) }
        current == null -> CenterMessage("Loading…")
        else -> VideoPlayer(nav, current)
    }
}

/**
 * Direct-plays the original file first. If the box can't decode it, switches to a
 * server transcode (H.264/AAC HLS) at the same position. Reports progress to Jellyfin
 * so Continue Watching and watched status stay in sync. Shows Skip Intro / Skip Credits
 * using segments from the Intro Skipper plugin, previous/next episode buttons, and
 * subtitles styled from the plugin settings.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(nav: Nav, plan: PlaybackPlan) {
    val context = LocalContext.current
    val api = nav.app.api
    val layout = LocalLayout.current
    val item = plan.item
    val playSessionId = remember(item.id) { UUID.randomUUID().toString() }
    val source = item.mediaSources.firstOrNull()
    val mediaSourceId = source?.id ?: item.id
    var playMethod by remember { mutableStateOf("DirectPlay") }
    var failure by remember { mutableStateOf<String?>(null) }

    fun goNext() {
        val next = plan.next ?: return
        nav.replace(Screen.Player(next.id))
    }

    fun goPrevious() {
        val previous = plan.previous ?: return
        nav.replace(Screen.Player(previous.id))
    }

    // Roll straight into the next episode; otherwise leave the player.
    fun onEnded() {
        if (plan.next != null) goNext() else nav.back()
    }

    // Subtitle files the server can hand over as WebVTT. When direct playing, embedded tracks
    // come from the file itself, so only add external files; when transcoding, add every text track.
    fun subtitleConfigs(transcoding: Boolean): List<MediaItem.SubtitleConfiguration> =
        source?.mediaStreams.orEmpty()
            .filter { it.isSubtitle && it.isTextSubtitleStream && (transcoding || it.isExternal) }
            .map { s ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(api.subtitleUrl(item.id, mediaSourceId, s.index)))
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage(s.language)
                    .setLabel(s.displayTitle ?: s.title ?: s.language)
                    .setId("jf-${s.index}")
                    .setSelectionFlags(
                        (if (s.isDefault) C.SELECTION_FLAG_DEFAULT else 0) or
                            (if (s.isForced) C.SELECTION_FLAG_FORCED else 0),
                    )
                    .build()
            }

    val player = remember(item.id) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(api.http)))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { applyTrackPreferences(it, layout, plan.userConfig) }
    }

    // What the on-screen controls talk to: adds previous/next episode to the normal controls.
    val controlsPlayer = remember(player, plan) {
        object : ForwardingPlayer(player) {
            override fun isCommandAvailable(command: Int): Boolean = when (command) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> plan.next != null
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> plan.previous != null
                else -> super.isCommandAvailable(command)
            }

            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .addIf(Player.COMMAND_SEEK_TO_NEXT, plan.next != null)
                .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, plan.next != null)
                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, plan.previous != null)
                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, plan.previous != null)
                .build()

            override fun hasNextMediaItem(): Boolean = plan.next != null
            override fun hasPreviousMediaItem(): Boolean = plan.previous != null
            override fun seekToNext() = goNext()
            override fun seekToNextMediaItem() = goNext()
            override fun seekToPrevious() = goPrevious()
            override fun seekToPreviousMediaItem() = goPrevious()
        }
    }
    var playerView by remember { mutableStateOf<SleekPlayerView?>(null) }

    DisposableEffect(player) {
        val startMs = (item.userData?.playbackPositionTicks ?: 0L) / 10_000L
        val direct = MediaItem.Builder()
            .setUri(api.directStreamUrl(item.id, mediaSourceId))
            .setSubtitleConfigurations(subtitleConfigs(transcoding = false))
            .build()
        player.setMediaItem(direct, startMs)
        player.prepare()
        player.playWhenReady = true

        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (playMethod == "DirectPlay") {
                    playMethod = "Transcode"
                    val position = maxOf(player.currentPosition, startMs)
                    val hls = MediaItem.Builder()
                        .setUri(api.transcodeUrl(item.id, mediaSourceId, playSessionId))
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .setSubtitleConfigurations(subtitleConfigs(transcoding = true))
                        .build()
                    player.setMediaItem(hls, position)
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    failure = error.errorCodeName
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded()
            }
        }
        player.addListener(listener)

        onDispose {
            val ticks = player.currentPosition * 10_000L
            player.removeListener(listener)
            player.release()
            nav.app.appScope.launch {
                runCatching { api.reportStopped(item.id, mediaSourceId, playSessionId, ticks) }
            }
        }
    }

    // Tell the server what's playing, then check in every 10 seconds.
    LaunchedEffect(player) {
        runCatching {
            api.reportStart(item.id, mediaSourceId, playSessionId, player.currentPosition * 10_000L, playMethod)
        }
        while (isActive) {
            delay(10_000)
            val ticks = player.currentPosition * 10_000L
            val paused = !player.isPlaying
            launch {
                runCatching { api.reportProgress(item.id, mediaSourceId, playSessionId, ticks, paused, playMethod) }
            }
        }
    }

    // ---------- Skip intro / credits ----------

    var segments by remember { mutableStateOf<List<MediaSegment>>(emptyList()) }
    LaunchedEffect(item.id) {
        segments = runCatching { api.segments(item.id) }.getOrDefault(emptyList())
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (isActive) {
            positionMs = player.currentPosition
            // Right-hand side of the timeline: time remaining.
            val duration = player.duration
            playerView?.findViewById<TextView>(R.id.sleek_remaining)?.text =
                if (duration == C.TIME_UNSET || duration <= 0) "" else "-" + formatTime(duration - positionMs)
            delay(250)
        }
    }

    // The segment we're inside right now (ignoring its last half second, so skipping can't loop).
    val activeSegment = segments.firstOrNull {
        layout.skipModeFor(it.type) != "Off" && positionMs >= it.startMs && positionMs < it.endMs - 500
    }
    var buttonFor by remember { mutableStateOf<MediaSegment?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val skipFocus = remember { FocusRequester() }

    fun skip(segment: MediaSegment) {
        // Credits running to the very end: go straight to the next episode if there is one.
        val duration = player.duration
        if (segment.type == "Outro" && plan.next != null && duration != C.TIME_UNSET && segment.endMs >= duration - 2_000) {
            goNext()
            return
        }
        val target = if (duration != C.TIME_UNSET && duration > 0) minOf(segment.endMs, duration) else segment.endMs
        player.seekTo(target)
        positionMs = target
    }

    LaunchedEffect(activeSegment) {
        buttonFor = null
        val segment = activeSegment ?: return@LaunchedEffect
        when (layout.skipModeFor(segment.type)) {
            "Auto" -> {
                skip(segment)
                notice = "Skipped ${segment.label.removePrefix("Skip ").lowercase()}"
                delay(2_500)
                notice = null
            }
            "Button" -> {
                buttonFor = segment
                if (layout.skipButtonSeconds > 0) {
                    delay(layout.skipButtonSeconds * 1000L)
                    buttonFor = null
                }
            }
        }
    }

    // Move focus to the button when it appears, and back to the player when it goes.
    LaunchedEffect(buttonFor) {
        if (buttonFor != null) {
            delay(60)
            runCatching { skipFocus.requestFocus() }
        } else {
            playerView?.requestFocus()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.sleek_player_view, null) as SleekPlayerView).apply {
                    this.player = controlsPlayer
                    useController = true
                    controllerShowTimeoutMs = 4000
                    // Timeline in the theme's accent color.
                    findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.setPlayedColor(
                        layout.accentColor.toColorOr(Color(0xFFE50914)).toArgb(),
                    )
                    // ⏮ previous episode · ⏪ rewind · ⏯ · ⏩ fast-forward · ⏭ next episode
                    setShowPreviousButton(plan.previous != null || plan.next != null)
                    setShowNextButton(plan.previous != null || plan.next != null)
                    setShowRewindButton(true)
                    setShowFastForwardButton(true)
                    setShowSubtitleButton(true)
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    subtitleView?.let { styleSubtitles(it, layout) }
                    post { requestFocus() }
                    playerView = this
                }
            },
            update = { view -> view.subtitleView?.let { styleSubtitles(it, layout) } },
            modifier = Modifier.fillMaxSize(),
        )

        // SleekFin-style pill, bottom right, above the controller bar.
        AnimatedVisibility(
            visible = buttonFor != null,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 56.dp, bottom = 120.dp),
        ) {
            val segment = buttonFor
            val nextLabel = if (segment?.type == "Outro" && plan.next != null) "Next Episode" else segment?.label
            SleekButton(
                text = (nextLabel ?: "Skip") + "  ⏭",
                onClick = {
                    segment?.let { skip(it) }
                    buttonFor = null
                },
                modifier = Modifier.focusRequester(skipFocus),
            )
        }

        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 56.dp),
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                SText(notice.orEmpty(), size = scaledSp(15), weight = FontWeight.SemiBold)
            }
        }

        failure?.let {
            CenterMessage("This video couldn't be played ($it).") {
                SleekButton("Back", onClick = { nav.back() }, primary = true)
            }
        }
    }
}

/**
 * Which subtitle and audio tracks start selected. "Jellyfin" follows the signed-in user's own
 * Jellyfin playback settings; the other modes come from the SleekTV plugin.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun applyTrackPreferences(player: ExoPlayer, layout: LayoutConfig, user: UserPlaybackConfig?) {
    val followUser = layout.subtitleMode.equals("Jellyfin", true)
    val mode = if (followUser) {
        when (user?.subtitleMode) {
            "Always" -> "Always"
            "OnlyForced" -> "ForcedOnly"
            "None" -> "Off"
            else -> "Default" // Default, Smart, or not set
        }
    } else {
        layout.subtitleMode
    }
    val language = (if (followUser) user?.subtitleLanguage else layout.subtitleLanguage)?.takeIf { it.isNotBlank() }
    val audioLanguage = user?.audioLanguage?.takeIf { it.isNotBlank() }

    val params = player.trackSelectionParameters.buildUpon()
    audioLanguage?.let { params.setPreferredAudioLanguage(it) }
    language?.let { params.setPreferredTextLanguage(it) }
    when (mode) {
        "Off" -> params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        "ForcedOnly" -> params.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        "Always" -> {
            // Pick a track even when none is flagged default: prefer the chosen language, else the TV's.
            if (language == null) params.setPreferredTextLanguage(Locale.getDefault().language)
            params.setSelectUndeterminedTextLanguage(true)
        }
        else -> Unit
    }
    player.trackSelectionParameters = params.build()
}

/** 1:02:03 or 12:34. */
private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) + 500) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

/** Size, color, background and height of subtitles, from the plugin settings. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun styleSubtitles(view: SubtitleView, layout: LayoutConfig) {
    val text = layout.subtitleColor.toColorOr(Color.White)
    val textArgb = AndroidColor.argb(255, (text.red * 255).toInt(), (text.green * 255).toInt(), (text.blue * 255).toInt())
    val (background, edgeType) = when (layout.subtitleBackground.lowercase()) {
        "box" -> AndroidColor.argb(190, 0, 0, 0) to CaptionStyleCompat.EDGE_TYPE_NONE
        "shadow" -> AndroidColor.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        "none" -> AndroidColor.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_NONE
        else -> AndroidColor.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_OUTLINE
    }
    view.setApplyEmbeddedStyles(false) // so the plugin's look wins over styles baked into the file
    view.setApplyEmbeddedFontSizes(false)
    view.setStyle(CaptionStyleCompat(textArgb, background, AndroidColor.TRANSPARENT, edgeType, AndroidColor.BLACK, null))
    view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * layout.subtitleSize / 100f)
    view.setBottomPaddingFraction(layout.subtitlePosition / 100f)
}
