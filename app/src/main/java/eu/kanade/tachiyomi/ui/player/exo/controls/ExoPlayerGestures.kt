package eu.kanade.tachiyomi.ui.player.exo.controls

import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.media3.common.util.UnstableApi
import eu.kanade.tachiyomi.ui.player.SingleActionGesture
import eu.kanade.tachiyomi.ui.player.exo.ExoPlayerActivity
import eu.kanade.tachiyomi.ui.player.exo.utils.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Swipe gestures: horizontal seek (anchored, commits on lift), vertical
 * brightness (left half) and volume (right half) with axis locking.
 * Extracted verbatim from ExoPlayerActivity as extension functions.
 */
@UnstableApi
internal fun ExoPlayerActivity.setupGestures() {
    val skipSeconds = gesturePreferences.skipLengthPreference().get().coerceAtLeast(1) * 1000L
    val leftAction = gesturePreferences.leftDoubleTapGesture().get()
    val centerAction = gesturePreferences.centerDoubleTapGesture().get()
    val rightAction = gesturePreferences.rightDoubleTapGesture().get()

    val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            seekStartPosMs = player.currentPosition
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (locked) {
                showUnlockHint()
                return true
            }
            toggleControls()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (locked) return true
            val action = when {
                e.x < playerView.width / 3f -> leftAction
                e.x > playerView.width * 2f / 3f -> rightAction
                else -> centerAction
            }
            when (action) {
                SingleActionGesture.Seek -> {
                    val forward = e.x > playerView.width / 2f
                    val target = (player.currentPosition + if (forward) skipSeconds else -skipSeconds)
                        .coerceIn(0L, player.duration.coerceAtLeast(0))
                    player.seekTo(target)
                    showGestureFeedback(if (forward) "+${skipSeconds / 1000}s" else "-${skipSeconds / 1000}s")
                }
                SingleActionGesture.PlayPause -> {
                    if (player.isPlaying) player.pause() else player.play()
                }
                else -> {}
            }
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            if (locked) return true
            if (controlsVisible || e1 == null) return false
            // Lock the axis on the first decisive movement so diagonal
            // swipes cannot flip between seek and volume/brightness.
            val axis = gestureAxis ?: run {
                val dx = kotlin.math.abs(e2.x - e1.x)
                val dy = kotlin.math.abs(e2.y - e1.y)
                if (dx < touchSlop && dy < touchSlop) return false
                (if (dy > dx) AXIS_VERTICAL else AXIS_HORIZONTAL).also { gestureAxis = it }
            }
            return when (axis) {
                AXIS_VERTICAL -> {
                    if (!gesturePreferences.gestureVolumeBrightness().get()) return false
                    val half = playerView.width / 2f
                    if (e1.x < half) handleBrightnessScroll(distanceY) else handleVolumeScroll(distanceY)
                }
                else -> {
                    if (!gesturePreferences.gestureHorizontalSeek().get()) return false
                    handleSeekScroll(e1, e2)
                }
            }
        }
    })

    playerView.setOnTouchListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            // Commit the anchored seek once, on finger lift.
            if (gestureAxis == AXIS_HORIZONTAL && pendingSeekMs >= 0) {
                player.seekTo(pendingSeekMs)
            }
            pendingSeekMs = -1L
            gestureAxis = null
        }
        val handled = detector.onTouchEvent(event)
        if (locked || controlsVisible) true else handled
    }
}

// Horizontal swipe = scrub. Full screen width spans 90 seconds,
// anchored at the position where the swipe began (MPV-style). The seek
// commits once on finger lift; feedback shows the absolute target time.
internal fun ExoPlayerActivity.handleSeekScroll(e1: MotionEvent, e2: MotionEvent): Boolean {
    val target = (seekStartPosMs + ((e2.x - e1.x) / playerView.width * 90_000f)).toLong()
        .coerceIn(0L, player.duration.coerceAtLeast(0))
    pendingSeekMs = target
    val delta = target - seekStartPosMs
    val sign = if (delta >= 0) "+" else "-"
    showGestureFeedback("$sign${kotlin.math.abs(delta) / 1000}s → ${formatTime(target)}")
    return true
}

internal fun ExoPlayerActivity.showGestureFeedback(text: String, longer: Boolean = false) {
    binding.exoGestureOverlay.text = text
    binding.exoGestureOverlay.visibility = android.view.View.VISIBLE
    binding.exoGestureOverlay.removeCallbacks(gestureFeedbackRunnable)
    binding.exoGestureOverlay.postDelayed(gestureFeedbackRunnable, if (longer) 2000 else 700)
}

// Brightness: 0..1 across a full screen-height swipe; up = brighter.
// Fractional accumulation pools tiny per-event deltas until a 5% step.
internal fun ExoPlayerActivity.handleBrightnessScroll(distanceY: Float): Boolean {
    brightnessAccum += distanceY / (playerView.height.toFloat() * 0.75f)
    if (kotlin.math.abs(brightnessAccum) < 0.05f) return true
    val current = if (currentBrightness < 0) 0.5f else currentBrightness
    val next = (current + brightnessAccum).coerceIn(0.02f, 1f)
    brightnessAccum = 0f
    currentBrightness = next
    val lp = window.attributes
    lp.screenBrightness = next
    window.attributes = lp
    showGestureFeedback("Cahaya ${(next * 100).toInt()}% ${levelBar((next * 100).toInt(), 100)}")
    return true
}

// Volume: music stream across a full screen-height swipe; up = louder.
internal fun ExoPlayerActivity.handleVolumeScroll(distanceY: Float): Boolean {
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    volumeAccum += distanceY / (playerView.height.toFloat() * 0.75f) * max
    if (kotlin.math.abs(volumeAccum) < 1f) return true
    val steps = volumeAccum.toInt()
    volumeAccum -= steps
    val next = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
    showGestureFeedback("Vol $next/$max ${levelBar(next, max)}")
    return true
}

internal fun ExoPlayerActivity.levelBar(value: Int, max: Int): String {
    val filled = if (max <= 0) 0 else (value * 10 / max).coerceIn(0, 10)
    return "▰".repeat(filled) + "▱".repeat(10 - filled)
}

