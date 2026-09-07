package eu.kanade.tachiyomi.ui.player.exo.utils

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import eu.kanade.tachiyomi.ui.player.exo.ExoPlayerActivity
import eu.kanade.tachiyomi.ui.player.controls.components.panels.SubtitlesBorderStyle
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Subtitle appearance — mirrors Aniyomi's SubtitlePreferences so the
 * values set in MPV's subtitle panel also drive Media3's SubtitleView.
 * Also drives bold/italic via CaptionStyleCompat's typeface field.
 */
internal fun ExoPlayerActivity.applySubtitleStyling() {
    val view = playerView.subtitleView ?: return
    view.setApplyEmbeddedStyles(true)
    view.setApplyEmbeddedFontSizes(false)
    val fontSizeDp = (subtitlePreferences.subtitleFontSize().get() * subtitlePreferences.subtitleFontScale().get())
        .toFloat()
        .coerceIn(12f, 30f)
    view.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fontSizeDp)

    // Font family pref (MPV shares this string); fall back to default.
    val baseTypeface = when (subtitlePreferences.subtitleFont().get()) {
        "Serif" -> android.graphics.Typeface.SERIF
        "Monospace" -> android.graphics.Typeface.MONOSPACE
        else -> android.graphics.Typeface.DEFAULT
    }
    val typeface = if (subtitlePreferences.boldSubtitles().get() || subtitlePreferences.italicSubtitles().get()) {
        android.graphics.Typeface.create(
            baseTypeface,
            (if (subtitlePreferences.boldSubtitles().get()) android.graphics.Typeface.BOLD else 0) or
                (if (subtitlePreferences.italicSubtitles().get()) android.graphics.Typeface.ITALIC else 0),
        )
    } else {
        baseTypeface
    }

    // Border style enum -> CaptionStyleCompat edge type.
    val edgeType = when (subtitlePreferences.borderStyleSubtitles().get()) {
        SubtitlesBorderStyle.OutlineAndShadow -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubtitlesBorderStyle.OpaqueBox -> CaptionStyleCompat.EDGE_TYPE_NONE
        SubtitlesBorderStyle.BackgroundBox -> CaptionStyleCompat.EDGE_TYPE_NONE
        else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
    }
    val bg = subtitlePreferences.backgroundColorSubtitles().get()

    view.setStyle(
        CaptionStyleCompat(
            subtitlePreferences.textColorSubtitles().get(),
            bg,
            bg,
            edgeType,
            subtitlePreferences.borderColorSubtitles().get(),
            typeface,
        ),
    )
    view.visibility = android.view.View.VISIBLE
}

/**
 * Force-select the first text track once tracks are known (Media3 only
 * auto-picks text tracks flagged DEFAULT; Idlix subs aren't). Idempotent.
 */
@UnstableApi
internal fun ExoPlayerActivity.selectTextTrackIfNeeded() {
    if (player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isTrackSelected(0) }) return
    val textGroup = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
    if (textGroup == null) {
        logcat(LogPriority.INFO) { "ExoTrack: no text track registered (subsProvided=$subtitlesProvided)" }
        if (subtitlesProvided && !emptySubToastShown) {
            emptySubToastShown = true
            android.widget.Toast.makeText(this, "Tidak ada subtitle", android.widget.Toast.LENGTH_SHORT).show()
        }
        return
    }
    logcat(LogPriority.INFO) { "ExoTrack: selecting first text track (${textGroup.mediaTrackGroup.length} formats)" }
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .addOverride(TrackSelectionOverride(textGroup.mediaTrackGroup, 0))
        .build()
}
