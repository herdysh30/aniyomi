package eu.kanade.tachiyomi.ui.player.exo.controls

import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Rational
import android.widget.Toast
import eu.kanade.tachiyomi.ui.player.PIP_INTENT_ACTION
import eu.kanade.tachiyomi.ui.player.PIP_INTENTS_FILTER
import eu.kanade.tachiyomi.ui.player.PIP_NEXT
import eu.kanade.tachiyomi.ui.player.PIP_PAUSE
import eu.kanade.tachiyomi.ui.player.PIP_PLAY
import eu.kanade.tachiyomi.ui.player.PIP_SKIP
import eu.kanade.tachiyomi.ui.player.exo.ExoPlayerActivity

/**
 * Picture-in-Picture helpers. Extracted from ExoPlayerActivity as
 * extension functions; the lifecycle overrides stay in the activity and
 * delegate here.
 */

internal fun ExoPlayerActivity.enterPipIfSupported() {
    if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
        enterPictureInPictureMode(buildPipParams())
    } else {
        Toast.makeText(this, "PiP tidak didukung", Toast.LENGTH_SHORT).show()
    }
}

internal fun ExoPlayerActivity.buildPipParams(): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        builder.setTitle(currentEpisodeTitle)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val autoEnter = playerPreferences.pipOnExit().get() && player.isPlaying
        builder.setAutoEnterEnabled(autoEnter)
        builder.setSeamlessResizeEnabled(autoEnter)
    }
    val videoSize = player.videoSize
    if (videoSize.width > 0 && videoSize.height > 0) {
        val ratio = videoSize.width.toFloat() / videoSize.height
        if (ratio in 0.42f..2.38f) builder.setAspectRatio(Rational(videoSize.width, videoSize.height))
    }
    return builder.build()
}

/** Enter-PiP side: hide controls and wire the media-notification receiver. */
@android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
internal fun ExoPlayerActivity.registerPipReceiver() {
    hideControls()
    pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action != PIP_INTENTS_FILTER) return
            when (intent.getIntExtra(PIP_INTENT_ACTION, 0)) {
                PIP_PAUSE -> player.pause()
                PIP_PLAY -> player.play()
                PIP_NEXT -> playNextEpisode()
                PIP_SKIP -> player.seekTo(player.currentPosition + 10_000)
            }
            if (isInPictureInPictureMode) setPictureInPictureParams(buildPipParams())
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(pipReceiver, IntentFilter(PIP_INTENTS_FILTER), Context.RECEIVER_NOT_EXPORTED)
    } else {
        registerReceiver(pipReceiver, IntentFilter(PIP_INTENTS_FILTER))
    }
}

/** Exit-PiP side: drop the receiver. */
internal fun ExoPlayerActivity.unregisterPipReceiver() {
    pipReceiver?.let {
        unregisterReceiver(it)
        pipReceiver = null
    }
}
