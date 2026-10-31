package com.sleektv.ui.screens

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/**
 * PlayerView with TV-friendly OK/Enter handling: while the control bar is hidden, OK toggles
 * play/pause and brings the bar up. Once the bar is showing, OK presses the focused button as usual.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SleekPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : PlayerView(context, attrs) {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isSelect = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (isSelect && !isControllerFullyVisible) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                showController()
            }
            return true // swallow the matching key-up too, so it doesn't also press a button
        }
        return super.dispatchKeyEvent(event)
    }
}
