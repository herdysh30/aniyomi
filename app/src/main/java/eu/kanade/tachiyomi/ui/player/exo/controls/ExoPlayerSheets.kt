package eu.kanade.tachiyomi.ui.player.exo.controls

import android.app.Dialog
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import com.google.android.material.bottomsheet.BottomSheetDialog
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.player.controls.components.panels.SubtitlesBorderStyle
import eu.kanade.tachiyomi.ui.player.exo.ExoPlayerActivity
import eu.kanade.tachiyomi.ui.player.exo.utils.applySubtitleStyling
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * All player pickers and menus as bottom sheets, plus the subtitle style
 * side sheet. Extracted verbatim from ExoPlayerActivity as extension
 * functions so call sites in the activity are unchanged.
 */

/** Settings menu: skip intro length, seek (skip) duration and sleep timer. */
internal fun ExoPlayerActivity.showMoreMenu() {
    val dialog = BottomSheetDialog(this)
    val density = resources.displayMetrics.density
    val pad = (16 * density).toInt()
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
    }
    container.addView(TextView(this).apply {
        text = "Pengaturan"
        textSize = 16f
        setPadding(0, 0, 0, pad / 2)
    })

    fun menuSeek(label: String, min: Int, max: Int, current: Int, onSet: (Int) -> Unit) {
        val labelView = TextView(this).apply {
            text = "$label: ${current}s"
            textSize = 14f
            setPadding(0, pad / 2, 0, 0)
        }
        container.addView(labelView)
        container.addView(SeekBar(this).apply {
            setMax(max - min)
            setProgress(current.coerceIn(min, max) - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    labelView.text = "$label: ${value + min}s"
                    onSet(value + min)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
    }

    menuSeek(
        "Skip intro",
        10,
        180,
        gesturePreferences.defaultIntroLength().get(),
    ) {
        gesturePreferences.defaultIntroLength().set(it)
        updateSkipIntroLabel()
    }
    menuSeek(
        "Skip durasi",
        5,
        60,
        gesturePreferences.skipLengthPreference().get(),
    ) {
        gesturePreferences.skipLengthPreference().set(it)
    }

    // Sleep timer row
    container.addView(TextView(this).apply {
        text = if (sleepJob?.isActive == true) "Sleep timer: aktif" else "Sleep timer"
        textSize = 14f
        setPadding(0, pad, 0, pad / 3)
    })
    val sleepRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    for (minutes in listOf(10, 20, 30, 45, 60)) {
        sleepRow.addView(TextView(this).apply {
            text = "${minutes}m"
            textSize = 14f
            setPadding(0, pad / 3, pad, pad / 3)
            setOnClickListener {
                startSleepTimer(minutes)
                dialog.dismiss()
            }
        })
    }
    sleepRow.addView(TextView(this).apply {
        text = "Off"
        textSize = 14f
        setPadding(0, pad / 3, 0, pad / 3)
        setOnClickListener {
            sleepJob?.cancel()
            sleepJob = null
            showGestureFeedback("Sleep timer off", longer = true)
            dialog.dismiss()
        }
    })
    container.addView(sleepRow)

    dialog.setContentView(container)
    dialog.show()
}

/** Pause playback after [minutes]; toast + pause on expiry. */
internal fun ExoPlayerActivity.startSleepTimer(minutes: Int) {
    sleepJob?.cancel()
    sleepJob = scope.launch {
        showGestureFeedback("Sleep ${minutes}m", longer = true)
        delay(minutes * 60_000L)
        if (player.isPlaying) {
            player.pause()
            Toast.makeText(this@startSleepTimer, "Sleep timer: $minutes menit selesai", Toast.LENGTH_LONG).show()
        }
    }
}

/** Playback speed picker as a bottom sheet. */
internal fun ExoPlayerActivity.showSpeedDialog() {
    val speeds = arrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    val dialog = BottomSheetDialog(this)
    val density = resources.displayMetrics.density
    val pad = (16 * density).toInt()
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
    }
    container.addView(TextView(this).apply {
        text = "Kecepatan"
        textSize = 16f
        setPadding(0, 0, 0, pad / 2)
    })
    for (speed in speeds) {
        val label = if (speed == 1f) "Normal" else "${speed}x"
        val selected = kotlin.math.abs(speed - player.playbackParameters.speed) < 0.01f
        container.addView(TextView(this).apply {
            text = if (selected) "●  $label" else "○  $label"
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
            setOnClickListener {
                player.setPlaybackSpeed(speed)
                binding.exoBtnSpeed.text = label
                dialog.dismiss()
            }
        })
    }
    dialog.setContentView(container)
    dialog.show()
}

/**
 * Custom quality dialog: the stock TrackSelectionDialogBuilder labels
 * variants without RESOLUTION/CODECS poorly (Idlix masters name them via
 * NAME= instead), which made the picker look like it had one quality.
 * Lists every video track with a readable name; pick applies a
 * selection override.
 */
@UnstableApi
internal fun ExoPlayerActivity.showQualityDialog() {
    val currentParams = player.trackSelectionParameters
    val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }

    val names = mutableListOf<String>("Otomatis")
    val overrides = mutableListOf<TrackSelectionOverride?>(null)
    var selected = 0
    for (group in groups) {
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            val name = when {
                !format.label.isNullOrBlank() -> format.label!!
                format.height > 0 -> "${format.height}p"
                format.bitrate > 0 -> "${format.bitrate / 1000} kbps"
                else -> "Varian ${overrides.size}"
            }
            if (group.isTrackSelected(i)) selected = names.size
            names.add(name)
            overrides.add(TrackSelectionOverride(group.mediaTrackGroup, i))
        }
    }

    val dialog = BottomSheetDialog(this)
    val density = resources.displayMetrics.density
    val pad = (16 * density).toInt()
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
    }
    container.addView(TextView(this).apply {
        text = "Kualitas"
        textSize = 16f
        setPadding(0, 0, 0, pad / 2)
    })
    for (i in names.indices) {
        val isSelected = i == selected
        container.addView(TextView(this).apply {
            text = if (isSelected) "●  ${names[i]}" else "○  ${names[i]}"
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
            setOnClickListener {
                val newParams = currentParams.buildUpon().apply {
                    val override = overrides.getOrNull(i)
                    if (override == null) {
                        clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    } else {
                        setOverrideForType(override)
                    }
                }
                player.trackSelectionParameters = newParams.build()
                dialog.dismiss()
            }
        })
    }
    dialog.setContentView(container)
    dialog.show()
}

/** Audio (or non-text) track picker as a bottom sheet, mirrors quality/subtitle sheets. */
@UnstableApi
internal fun ExoPlayerActivity.showTrackDialog(trackType: Int, title: String) {
    if (trackType == C.TRACK_TYPE_TEXT) {
        showSubtitleDialog()
        return
    }
    // Audio picker as a bottom sheet (mirrors quality/subtitle sheets).
    val groups = player.currentTracks.groups.filter { it.type == trackType && it.length > 0 }
    val names = mutableListOf("Nonaktif")
    val overrides = mutableListOf<TrackSelectionOverride?>(null)
    var selected = 0
    for (group in groups) {
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            val name = when {
                !format.label.isNullOrBlank() -> format.label!!
                !format.language.isNullOrBlank() -> format.language!!
                format.channelCount > 0 -> "Audio ${format.channelCount}ch"
                else -> "Audio ${overrides.size}"
            }
            if (group.isTrackSelected(i)) selected = names.size
            names.add(name)
            overrides.add(TrackSelectionOverride(group.mediaTrackGroup, i))
        }
    }
    val dialog = BottomSheetDialog(this)
    val density = resources.displayMetrics.density
    val pad = (16 * density).toInt()
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
    }
    container.addView(TextView(this).apply {
        text = title
        textSize = 16f
        setPadding(0, 0, 0, pad / 2)
    })
    for (i in names.indices) {
        val isSelected = i == selected
        container.addView(TextView(this).apply {
            text = if (isSelected) "●  ${names[i]}" else "○  ${names[i]}"
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
            setOnClickListener {
                val newParams = player.trackSelectionParameters.buildUpon().apply {
                    setTrackTypeDisabled(trackType, i == 0)
                    val override = overrides.getOrNull(i)
                    if (override == null) clearOverridesOfType(trackType) else setOverrideForType(override)
                }
                player.trackSelectionParameters = newParams.build()
                dialog.dismiss()
            }
        })
    }
    dialog.setContentView(container)
    dialog.show()
}

/**
 * Subtitle track picker with a "Gaya teks..." entry opening the style
 * editor. TrackSelectionDialogBuilder can't host a neutral button, so
 * TEXT tracks get a custom dialog (mirrors showQualityDialog).
 */
@UnstableApi
internal fun ExoPlayerActivity.showSubtitleDialog() {
    val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
    val names = mutableListOf("Nonaktif")
    val overrides = mutableListOf<TrackSelectionOverride?>(null)
    var selected = 0
    for (group in groups) {
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            val name = when {
                !format.label.isNullOrBlank() -> format.label!!
                !format.language.isNullOrBlank() -> format.language!!
                else -> "Subtitle ${overrides.size}"
            }
            if (group.isTrackSelected(i)) selected = names.size
            names.add(name)
            overrides.add(TrackSelectionOverride(group.mediaTrackGroup, i))
        }
    }
    val dialog = BottomSheetDialog(this)
    val density = resources.displayMetrics.density
    val pad = (16 * density).toInt()
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
    }
    container.addView(TextView(this).apply {
        text = "Subtitle"
        textSize = 16f
        setPadding(0, 0, 0, pad / 2)
    })
    for (i in names.indices) {
        val isSelected = i == selected
        container.addView(TextView(this).apply {
            text = if (isSelected) "●  ${names[i]}" else "○  ${names[i]}"
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
            setOnClickListener {
                val newParams = player.trackSelectionParameters.buildUpon().apply {
                    setTrackTypeDisabled(C.TRACK_TYPE_TEXT, i == 0)
                    val override = overrides.getOrNull(i)
                    if (override == null) clearOverridesOfType(C.TRACK_TYPE_TEXT) else setOverrideForType(override)
                }
                player.trackSelectionParameters = newParams.build()
                dialog.dismiss()
            }
        })
    }
    container.addView(TextView(this).apply {
        text = "⚙  Gaya teks..."
        textSize = 16f
        setPadding(0, pad / 2, 0, pad / 2)
        setOnClickListener {
            dialog.dismiss()
            showSubtitleStyleSheet()
        }
    })
    dialog.setContentView(container)
    dialog.show()
}

/**
 * Subtitle style editor as a TRANSPARENT SIDE SHEET on the right edge —
 * the video stays visible behind it so changes preview live. Two
 * collapsible sections: Typography (bold/italic, font, size, border
 * style + sizes, reset) and Colors (text/outline/background swatches).
 */
internal fun ExoPlayerActivity.showSubtitleStyleSheet() {
    val density = resources.displayMetrics.density
    val pad = (16 * density).toInt()

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
    }

    fun sectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit): TextView =
        TextView(this).apply {
            text = (if (expanded) "▾ " else "▸ ") + title
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, pad / 2, 0, pad / 2)
            setOnClickListener { onToggle() }
        }

    // ---- Typography section ----
    val typoContent = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    fun typoCheck(label: String, get: () -> Boolean, set: (Boolean) -> Unit) =
        CheckBox(this).apply {
            text = label
            isChecked = get()
            setOnCheckedChangeListener { _, checked ->
                set(checked)
                applySubtitleStyling()
            }
        }
    typoContent.addView(typoCheck("Bold", { subtitlePreferences.boldSubtitles().get() }, { subtitlePreferences.boldSubtitles().set(it) }))
    typoContent.addView(typoCheck("Italic", { subtitlePreferences.italicSubtitles().get() }, { subtitlePreferences.italicSubtitles().set(it) }))

    // Font family (a few always-available system faces)
    val fontLabel = TextView(this).apply { text = "Font: ${subtitlePreferences.subtitleFont().get()}" }
    typoContent.addView(fontLabel)
    val fonts = mapOf(
        "Sans Serif" to android.graphics.Typeface.SANS_SERIF,
        "Serif" to android.graphics.Typeface.SERIF,
        "Monospace" to android.graphics.Typeface.MONOSPACE,
    )
    val fontRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    for (name in fonts.keys) {
        fontRow.addView(TextView(this).apply {
            text = name.substringBefore(' ')
            textSize = 14f
            setPadding(0, pad / 3, pad, pad / 3)
            setOnClickListener {
                subtitlePreferences.subtitleFont().set(name)
                fontLabel.text = "Font: $name"
                applySubtitleStyling()
            }
        })
    }
    typoContent.addView(fontRow)

    fun typoSeek(label: String, min: Int, max: Int, current: Int, onSet: (Int) -> Unit): TextView {
        val labelView = TextView(this).apply { text = "$label: $current" }
        labelView.textSize = 14f
        typoContent.addView(labelView)
        typoContent.addView(SeekBar(this).apply {
            setMax(max - min)
            setProgress(current.coerceIn(min, max) - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    labelView.text = "$label: ${value + min}"
                    onSet(value + min)
                    applySubtitleStyling()
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
        return labelView
    }

    typoSeek("Ukuran font", 12, 30, subtitlePreferences.subtitleFontSize().get()) {
        subtitlePreferences.subtitleFontSize().set(it)
    }
    typoSeek("Border size", 0, 8, subtitlePreferences.subtitleBorderSize().get()) {
        subtitlePreferences.subtitleBorderSize().set(it)
    }
    typoSeek("Shadow offset", 0, 10, subtitlePreferences.shadowOffsetSubtitles().get()) {
        subtitlePreferences.shadowOffsetSubtitles().set(it)
    }

    // Border style chips
    val styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    for (style in SubtitlesBorderStyle.entries) {
        styleRow.addView(TextView(this).apply {
            text = style.name.substringBefore("And").replace("Box", " Box").replace("Outline", "Outline+")
            textSize = 12f
            setPadding(0, pad / 3, pad, pad / 3)
            setOnClickListener {
                subtitlePreferences.borderStyleSubtitles().set(style)
                applySubtitleStyling()
            }
        })
    }
    typoContent.addView(styleRow)

    // Reset
    typoContent.addView(TextView(this).apply {
        text = "⟲ Reset"
        textSize = 14f
        setPadding(0, pad / 2, 0, 0)
        setOnClickListener {
            subtitlePreferences.subtitleFontSize().set(22)
            subtitlePreferences.subtitleBorderSize().set(3)
            subtitlePreferences.shadowOffsetSubtitles().set(0)
            subtitlePreferences.boldSubtitles().set(false)
            subtitlePreferences.italicSubtitles().set(false)
            subtitlePreferences.subtitleFont().set("Sans Serif")
            subtitlePreferences.borderStyleSubtitles().set(SubtitlesBorderStyle.OutlineAndShadow)
            applySubtitleStyling()
        }
    })

    // ---- Colors section ----
    val colorContent = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    fun swatchRow(title: String, colors: List<Int>, current: Int, onPick: (Int) -> Unit) {
        colorContent.addView(TextView(this).apply { text = title; textSize = 14f })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (color in colors) {
            val sw = View(this)
            sw.layoutParams = LinearLayout.LayoutParams((30 * density).toInt(), (30 * density).toInt())
                .apply { marginEnd = (8 * density).toInt() }
            sw.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(color)
                setStroke(
                    if (color == current) (3 * density).toInt() else 1,
                    if (color == current) 0xFFFFFFFF.toInt() else 0x40000000,
                )
            }
            sw.setOnClickListener {
                onPick(color)
                applySubtitleStyling()
            }
            row.addView(sw)
        }
        colorContent.addView(row)
    }
    swatchRow(
        "Warna teks",
        listOf(0xFFFFFFFF.toInt(), 0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFF8BC34A.toInt(),
            0xFFFF4081.toInt(), 0xFFFFA726.toInt(), 0xFFB0BEC5.toInt(), 0xFF000000.toInt()),
        subtitlePreferences.textColorSubtitles().get(),
    ) { subtitlePreferences.textColorSubtitles().set(it) }
    swatchRow(
        "Warna outline",
        listOf(0xFF000000.toInt(), 0xFF424242.toInt(), 0xFFFFFFFF.toInt(),
            0xFF1A237E.toInt(), 0xFFB71C1C.toInt(), 0xFF4E342E.toInt()),
        subtitlePreferences.borderColorSubtitles().get(),
    ) { subtitlePreferences.borderColorSubtitles().set(it) }
    swatchRow(
        "Latar",
        listOf(0x00000000, 0x80000000.toInt(), 0xCC000000.toInt(), 0xFF000000.toInt()),
        subtitlePreferences.backgroundColorSubtitles().get(),
    ) { subtitlePreferences.backgroundColorSubtitles().set(it) }

    // Assemble with collapsible headers
    root.addView(sectionHeader("Typography", expanded = true) {
        typoContent.visibility = if (typoContent.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    })
    root.addView(typoContent)
    root.addView(sectionHeader("Colors", expanded = false) {
        colorContent.visibility = if (colorContent.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    })
    root.addView(colorContent.apply { visibility = View.GONE })

    val scroll = ScrollView(this).apply { addView(root) }

    val dialog = Dialog(this, R.style.ThemeOverlay_Tachiyomi_MaterialAlertDialog)
    dialog.setContentView(scroll)
    val window = dialog.window ?: return
    window.setGravity(android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL)
    window.setLayout(
        (resources.displayMetrics.widthPixels * 0.55f).toInt().coerceAtMost((320 * density).toInt()),
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
    )
    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x99000000.toInt()))
    dialog.show()
}
