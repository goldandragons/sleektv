package com.sleektv.ui.screens

import android.view.View
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.sleektv.data.BaseItem
import com.sleektv.data.MediaSegment
import com.sleektv.data.isSeries
import com.sleektv.data.isWatched
import com.sleektv.ui.Nav
import com.sleektv.ui.components.CenterMessage
import com.sleektv.ui.components.SText
import com.sleektv.ui.components.SleekButton
import com.sleektv.ui.theme.LocalLayout
import com.sleektv.ui.theme.scaledSp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun PlayerScreen(nav: Nav, itemId: String) {
    val api = nav.app.api
    var item by remember { mutableStateOf<BaseItem?>(null) }
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
            item = target
        } catch (e: Exception) {
            error = e.message ?: "Couldn't start playback"
        }
    }

    val current = item
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
 * using segments from the Intro Skipper plugin.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(nav: Nav, item: BaseItem) {
    val context = LocalContext.current
    val api = nav.app.api
    val layout = LocalLayout.current
    val playSessionId = remember(item.id) { UUID.randomUUID().toString() }
    val mediaSourceId = item.mediaSources.firstOrNull()?.id ?: item.id
    var playMethod by remember { mutableStateOf("DirectPlay") }
    var failure by remember { mutableStateOf<String?>(null) }
    val onEnded by rememberUpdatedState { nav.back() }

    val player = remember(item.id) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(api.http)))
            .build()
    }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    DisposableEffect(player) {
        val startMs = (item.userData?.playbackPositionTicks ?: 0L) / 10_000L
        player.setMediaItem(MediaItem.fromUri(api.directStreamUrl(item.id, mediaSourceId)), startMs)
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
            delay(400)
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
        val duration = player.duration
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
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 4000
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    descendantFocusability = View.FOCUS_AFTER_DESCENDANTS
                    post { requestFocus() }
                    playerView = this
                }
            },
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
            SleekButton(
                text = (segment?.label ?: "Skip") + "  ⏭",
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
