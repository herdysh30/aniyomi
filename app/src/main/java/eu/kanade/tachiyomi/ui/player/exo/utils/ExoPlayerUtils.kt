package eu.kanade.tachiyomi.ui.player.exo.utils

import android.content.pm.ActivityInfo
import android.os.Build
import androidx.media3.common.MimeTypes
import eu.kanade.tachiyomi.ui.player.PlayerOrientation
import eu.kanade.tachiyomi.ui.player.exo.ExoPlayerActivity

/** Utilities: time formatting, MIME sniffing, HLS variant hint, orientation. */

internal fun ExoPlayerActivity.formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Sniff subtitle MIME from the URL extension (query/fragment stripped). */
internal fun ExoPlayerActivity.subtitleMimeFor(url: String): String {
    val clean = url.substringBefore('#').substringBefore('?').lowercase()
    return when {
        clean.endsWith(".ass") || clean.endsWith(".ssa") -> MimeTypes.TEXT_SSA
        clean.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
        else -> MimeTypes.TEXT_VTT
    }
}

internal fun ExoPlayerActivity.guessMimeType(url: String): String {
    val clean = url.substringBefore('#').substringBefore('?').lowercase()
    return when {
        clean.endsWith(".m3u8") || clean.endsWith(".json") -> MimeTypes.APPLICATION_M3U8
        clean.endsWith(".mp4") || clean.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
        clean.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
        else -> MimeTypes.APPLICATION_M3U8
    }
}

/**
 * Ask the m3u8 proxy to serve the master with EVERY rendition intact
 * (`#pv=all` skips the proxy's collapse-to-best step), so the quality
 * picker lists all variants (1080p / 720p / …) in one dialog. Media3's
 * ABR/track selection doesn't suffer from the oscillation the collapse
 * was added to fix on MPV, so full masters are safe here.
 *
 * The hint must live INSIDE the proxied `url=` param (the proxy strips
 * fragments from the fetch and reads the hint off that param), not on
 * the localhost URL itself — a trailing fragment there would pollute
 * the last query param instead.
 */
internal fun String.withAllVariants(): String {
    if (!contains("/m3u8")) return this
    val marker = "url="
    val start = indexOf(marker)
    if (start == -1) return this
    val valueStart = start + marker.length
    val valueEnd = indexOf('&', valueStart).let { if (it == -1) length else it }
    val upstream = android.net.Uri.decode(substring(valueStart, valueEnd))
    val rebuilt = upstream.substringBefore("#pv=") + "#pv=all"
    return substring(0, valueStart) + android.net.Uri.encode(rebuilt) + substring(valueEnd)
}

internal fun ExoPlayerActivity.orientationFromPref(): Int {
    val pref = playerPreferences.defaultPlayerOrientationType().get()
    return when (pref) {
        PlayerOrientation.Free -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        PlayerOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        PlayerOrientation.ReversePortrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        PlayerOrientation.SensorPortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        PlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        PlayerOrientation.ReverseLandscape -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        PlayerOrientation.SensorLandscape, PlayerOrientation.Video -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
}
