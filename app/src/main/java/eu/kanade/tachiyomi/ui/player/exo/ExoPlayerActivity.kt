package eu.kanade.tachiyomi.ui.player.exo

import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.ActivityExoPlayerBinding
import eu.kanade.tachiyomi.ui.player.VideoAspect
import eu.kanade.tachiyomi.ui.player.exo.controls.*
import eu.kanade.tachiyomi.ui.player.exo.utils.*
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.history.anime.interactor.UpsertAnimeHistory
import tachiyomi.domain.history.anime.model.AnimeHistoryUpdate
import tachiyomi.domain.items.episode.interactor.UpdateEpisode
import tachiyomi.domain.items.episode.model.EpisodeUpdate
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

/**
 * Lean Media3 (ExoPlayer) player for the demuxed-fMP4 HLS case where MPV's
 * bundled FFmpeg demuxer wedges on seek (`sequence wrapped` / `Invalid NAL`).
 *
 * UX parity with the MPV player: custom overlay controls (top bar with
 * back/title/subtitle/audio/more, bottom bar with play/seekbar/lock/rotate/
 * speed/aspect/PiP), swipe gestures (horizontal seek, vertical volume and
 * brightness), double-tap seek, lock mode, auto-hide and auto-next episode.
 * Engine untouched: ExoPlayer for demuxed-fMP4 HLS seek correctness.
 *
 * Modularized: sheets/menus live in ExoPlayerSheets.kt, gestures in
 * ExoPlayerGestures.kt, subtitle styling in ExoPlayerStyling.kt, small
 * utils in ExoPlayerUtils.kt and PiP helpers in ExoPlayerPip.kt — all as
 * extension functions on this activity. Members they touch are `internal`.
 *
 * ponytail: no chapters, skip-intro, MPV panels (filters, delays, secondary
 * subs), quality picker lives in the "more" menu — add when needed; the MPV
 * path remains untouched.
 */
@UnstableApi
class ExoPlayerActivity : AppCompatActivity() {

    internal lateinit var binding: ActivityExoPlayerBinding
    internal lateinit var player: ExoPlayer
    internal lateinit var playerView: PlayerView

    private var animeId: Long = -1L
    private var episodeId: Long = -1L
    private var playbackStarted = false
    internal var locked = false
    internal var currentEpisodeTitle: String = ""
    internal var adjacentEpisodes: Pair<Long?, Long?> = null to null
    internal var subtitlesProvided = false
    internal var emptySubToastShown = false

    private val updateEpisode: UpdateEpisode = Injekt.get()
    private val upsertHistory: UpsertAnimeHistory = Injekt.get()
    internal val playerPreferences: PlayerPreferences = Injekt.get()
    internal val gesturePreferences = GesturePreferences(Injekt.get<tachiyomi.core.common.preference.PreferenceStore>())
    internal val subtitlePreferences = SubtitlePreferences(Injekt.get<tachiyomi.core.common.preference.PreferenceStore>())

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- Controls show / hide -------------------------------------------

    internal var controlsVisible = false
    internal val hideControlsRunnable = Runnable { hideControls() }

    internal fun showControls() {
        if (locked) return
        controlsVisible = true
        binding.exoTopBar.visibility = View.VISIBLE
        binding.exoOwnBottomBar.visibility = View.VISIBLE
        binding.exoCenterControls.visibility = View.VISIBLE
        updateSkipIntroLabel()
        binding.exoBtnSkipIntro.visibility = View.VISIBLE
        scheduleHide()
    }

    internal fun hideControls() {
        controlsVisible = false
        binding.exoTopBar.visibility = View.GONE
        binding.exoOwnBottomBar.visibility = View.GONE
        binding.exoCenterControls.visibility = View.GONE
        binding.exoBtnSkipIntro.visibility = View.GONE
    }

    internal fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    internal fun scheduleHide() {
        playerView.removeCallbacks(hideControlsRunnable)
        val timeout = playerPreferences.playerTimeToDisappear().get().coerceAtLeast(1500)
        playerView.postDelayed(hideControlsRunnable, timeout.toLong())
    }

    // ---- Lifecycle -------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        requestedOrientation = orientationFromPref()
        hideSystemBars()

        animeId = intent.getLongExtra(EXTRA_ANIME_ID, -1L)
        episodeId = intent.getLongExtra(EXTRA_EPISODE_ID, -1L)
        if (animeId == -1L || episodeId == -1L) {
            finish()
            return
        }

        setupPlayer()
        setupControls()
        setupGestures()
        scope.launch {
            val resolved = withContext(Dispatchers.IO) {
                ExoPlayerLauncher.resolve(
                    context = this@ExoPlayerActivity,
                    animeId = animeId,
                    episodeId = episodeId,
                    hosterListSerialized = intent.getStringExtra(EXTRA_HOSTER_LIST),
                    hosterIndex = intent.getIntExtra(EXTRA_HOSTER_INDEX, -1),
                    videoIndex = intent.getIntExtra(EXTRA_VIDEO_INDEX, -1),
                )
            }
            if (resolved == null) {
                finish()
                return@launch
            }
            binding.exoTitle.text = resolved.animeTitle
            binding.exoEpisodeTitle.text = resolved.episodeName
            currentEpisodeTitle = resolved.title
            adjacentEpisodes = withContext(Dispatchers.IO) {
                ExoPlayerLauncher.resolveAdjacent(
                    animeId = animeId,
                    episodeId = episodeId,
                )
            }
            subtitlesProvided = resolved.subtitles.isNotEmpty()
            binding.exoBtnAutonext.visibility =
                if (adjacentEpisodes.second != null) View.VISIBLE else View.GONE
            binding.exoBtnAutonext.alpha = if (playerPreferences.autoplayEnabled().get()) 1f else 0.5f
            binding.exoBtnPrevEpisode.visibility =
                if (adjacentEpisodes.first != null) View.VISIBLE else View.GONE
            binding.exoBtnNextEpisode.visibility =
                if (adjacentEpisodes.second != null) View.VISIBLE else View.GONE
            play(resolved)
        }
    }

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        playerView = binding.exoPlayerView
        playerView.player = player
        playerView.subtitleView?.setApplyEmbeddedStyles(true)
        attachPlayerListener()
        applySubtitleStyling()
    }

    // ---- Overlay controls ------------------------------------------------

    private fun setupControls() {
        binding.exoBack.setOnClickListener {
            if (playerPreferences.pipOnExit().get() && player.isPlaying &&
                packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
            ) {
                enterPictureInPictureMode(buildPipParams())
            } else {
                finish()
            }
        }
        binding.exoBtnSub.setOnClickListener { showTrackDialog(C.TRACK_TYPE_TEXT, "Subtitle") }
        binding.exoBtnAudio.setOnClickListener { showTrackDialog(C.TRACK_TYPE_AUDIO, "Audio") }
        binding.exoBtnHd.setOnClickListener { showQualityDialog() }
        binding.exoBtnAutonext.setOnClickListener {
            val next = !playerPreferences.autoplayEnabled().get()
            playerPreferences.autoplayEnabled().set(next)
            binding.exoBtnAutonext.alpha = if (next) 1f else 0.5f
        }
        binding.exoOwnPlayPause.setOnClickListener { if (player.isPlaying) player.pause() else player.play() }
        binding.exoCenterPlayPause.setOnClickListener { if (player.isPlaying) player.pause() else player.play() }
        binding.exoBtnSpeed.setOnClickListener { showSpeedDialog() }
        binding.exoBtnMore.setOnClickListener { showMoreMenu() }
        binding.exoBtnRewind.setOnClickListener {
            val skip = gesturePreferences.skipLengthPreference().get().coerceAtLeast(1) * 1000L
            player.seekTo((player.currentPosition - skip).coerceAtLeast(0))
            scheduleHide()
        }
        binding.exoBtnForward.setOnClickListener {
            val skip = gesturePreferences.skipLengthPreference().get().coerceAtLeast(1) * 1000L
            player.seekTo((player.currentPosition + skip).coerceIn(0L, player.duration.coerceAtLeast(0)))
            scheduleHide()
        }
        binding.exoBtnPrevEpisode.setOnClickListener { playPrevEpisode() }
        binding.exoBtnNextEpisode.setOnClickListener { playNextEpisode() }
        binding.exoBtnSkipIntro.setOnClickListener {
            val introLen = gesturePreferences.defaultIntroLength().get().coerceAtLeast(1) * 1000L
            player.seekTo((player.currentPosition + introLen).coerceIn(0L, player.duration.coerceAtLeast(0)))
            scheduleHide()
        }
        binding.exoBtnAspect.setOnClickListener { cycleAspect() }
        binding.exoBtnPip.setOnClickListener { enterPipIfSupported() }
        binding.exoBtnRotate.setOnClickListener {
            requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        binding.exoBtnLock.setOnClickListener {
            locked = true
            hideControls()
            showUnlockHint()
        }
        binding.exoBtnUnlock.setOnClickListener {
            locked = false
            binding.exoBtnUnlock.visibility = View.GONE
            showControls()
        }
        binding.exoTimebar.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                playerView.removeCallbacks(hideControlsRunnable)
                binding.exoOwnPosition.text = formatTime(position)
            }
            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                binding.exoOwnPosition.text = formatTime(position)
            }
            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                if (!canceled) player.seekTo(position)
                scheduleHide()
            }
        })
    }

    /**
     * Position ticker: our DefaultTimeBar has no PlayerControlView driving
     * it, so the bar and time labels are updated from here instead.
     */
    private var positionTickerJob: kotlinx.coroutines.Job? = null

    private fun startPositionTicker() {
        if (positionTickerJob?.isActive == true) return
        positionTickerJob = scope.launch {
            while (isActive) {
                if (player.isPlaying) updateProgressUi()
                delay(500)
            }
        }
    }

    private fun updateProgressUi() {
        val pos = player.currentPosition.coerceAtLeast(0)
        val dur = player.duration.coerceAtLeast(0)
        binding.exoTimebar.setPosition(pos)
        binding.exoTimebar.setDuration(dur)
        binding.exoOwnPosition.text = formatTime(pos)
        binding.exoOwnDuration.text = formatTime(dur)
    }

    /** Locked: flash the Unlock button on every tap. */
    internal fun showUnlockHint() {
        binding.exoBtnUnlock.visibility = View.VISIBLE
        unlockHintRunnable?.let { binding.exoBtnUnlock.removeCallbacks(it) }
        unlockHintRunnable = Runnable {
            if (locked) binding.exoBtnUnlock.visibility = View.GONE
        }
        binding.exoBtnUnlock.postDelayed(unlockHintRunnable, 2500)
    }

    internal var unlockHintRunnable: Runnable? = null

    // ---- Sleep timer -------------------------------------------------------

    internal var sleepJob: kotlinx.coroutines.Job? = null

    // ---- Gesture state (handled in ExoPlayerGestures.kt) -----------------

    /** Swipe-seek anchor state; anchor set on down, target on scroll, commit on lift. */
    internal var seekStartPosMs = -1L
    internal var pendingSeekMs = -1L

    /** Axis lock: null until the first onScroll past touch slop, then fixed. */
    internal var gestureAxis: Int? = null
    internal val AXIS_VERTICAL = 0
    internal val AXIS_HORIZONTAL = 1
    internal val touchSlop by lazy { android.view.ViewConfiguration.get(this).scaledTouchSlop }

    // Fractional accumulators so slow swipes still clear a step (volume/brightness).
    internal var brightnessAccum = 0f
    internal var volumeAccum = 0f
    internal var currentBrightness: Float = -1f

    internal val gestureFeedbackRunnable = Runnable { binding.exoGestureOverlay.visibility = View.GONE }

    // ---- Skip intro countdown -------------------------------------------

    /**
     * Skip intro button is ALWAYS visible while controls are up (no
     * countdown), showing a static "+Ns" label where N = intro length pref.
     * Tapping seeks forward by that duration from the current position.
     */
    internal fun updateSkipIntroLabel() {
        val introLen = gesturePreferences.defaultIntroLength().get().coerceAtLeast(1)
        binding.exoBtnSkipIntro.text = "+${introLen}s"
    }

    private fun attachPlayerListener() {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.exoOwnPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause_24dp else R.drawable.ic_play_arrow_24dp,
                )
                binding.exoCenterPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause_24dp else R.drawable.ic_play_arrow_24dp,
                )
                if (isPlaying) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else if (playbackStarted) {
                    savePosition()
                }
                if (isInPictureInPictureMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setPictureInPictureParams(buildPipParams())
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    playbackStarted = true
                    binding.exoLoading.visibility = View.GONE

                    updateProgressUi()
                    startPositionTicker()
                    updateSkipIntroLabel()
                    selectTextTrackIfNeeded()
                }
                if (playbackState == Player.STATE_ENDED) {
                    markSeenAndSave()
                    if (playerPreferences.autoplayEnabled().get()) playNextEpisode()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.exoLoading.visibility = View.GONE
                this@ExoPlayerActivity.logcat(LogPriority.ERROR) { "ExoPlayer error: ${error.errorCodeName}" }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val textGroups = tracks.groups.count { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
                logcat(LogPriority.INFO) { "ExoTrack: text groups=$textGroups of ${tracks.groups.size} total" }
                tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }.forEach { group ->
                    for (i in 0 until group.length) {
                        val f = group.getTrackFormat(i)
                        logcat(LogPriority.INFO) {
                            "ExoTrack: text fmt label=${f.label} lang=${f.language} mime=${f.sampleMimeType}"
                        }
                    }
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                val stripped = cueGroup.cues.map { cue ->
                    if (cue.line == Cue.DIMEN_UNSET && cue.position == Cue.DIMEN_UNSET) {
                        cue
                    } else {
                        cue.buildUpon()
                            .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
                            .setLineAnchor(Cue.TYPE_UNSET)
                            .setPosition(Cue.DIMEN_UNSET)
                            .setPositionAnchor(Cue.TYPE_UNSET)
                            .build()
                    }
                }
                playerView.subtitleView?.setCues(stripped)
            }
        })
    }

    private fun play(resolved: ResolvedPlayback) {
        val requestProperties = mutableMapOf<String, String>()
        resolved.headers.forEach { (k, v) -> requestProperties[k] = v }

        // Rebuild the data source factory with the video's own headers, then
        // rebuild the player around it — the factory is only settable at
        // Builder time in Media3.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent(resolved.userAgent ?: DEFAULT_UA)
            .setDefaultRequestProperties(requestProperties)

        player.release()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        playerView.player = player
        attachPlayerListener()
        applySubtitleStyling()

        val builder = MediaItem.Builder()
            .setUri(resolved.videoUrl.withAllVariants())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(resolved.title).build())
            .setMimeType(guessMimeType(resolved.videoUrl))

        if (resolved.subtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(
                resolved.subtitles.map { (url, lang) ->
                    // Idlix serves .srt/.ass too — hardcoding VTT made the
                    // parser fail and the track never registered.
                    val mime = subtitleMimeFor(url)
                    logcat(LogPriority.INFO) { "ExoSub: lang=$lang mime=$mime url=$url" }
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(url))
                        .setMimeType(mime)
                        .setLabel(lang.ifBlank { "Subtitle" })
                        .setLanguage(lang.ifBlank { null })
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                },
            )
        }

        player.setMediaItem(builder.build(), resolved.positionMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true
    }

    internal fun playNextEpisode() {
        val nextId = adjacentEpisodes.second ?: return
        startActivity(newIntent(this, animeId, nextId))
        finish()
    }

    private fun playPrevEpisode() {
        val prevId = adjacentEpisodes.first ?: return
        startActivity(newIntent(this, animeId, prevId))
        finish()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun savePosition() {
        if (!::player.isInitialized || episodeId == -1L || !playbackStarted) return
        val positionMs = player.currentPosition.coerceAtLeast(0)
        val durationMs = player.duration.coerceAtLeast(0)
        // The rest of Aniyomi stores these fields in MILLIS (see
        // PlayerViewModel.onSecondReached), even though the columns are named
        // "seconds". Persist the same unit or the episode list shows a wrong
        // progress bar / resume time.
        scope.launch {
            val progress = 0.9
            val seen = durationMs > 0 && positionMs >= durationMs * progress
            updateEpisode.await(
                EpisodeUpdate(
                    id = episodeId,
                    seen = seen,
                    lastSecondSeen = positionMs,
                    totalSeconds = durationMs,
                ),
            )
            if (positionMs > 0) {
                upsertHistory.await(AnimeHistoryUpdate(episodeId, Date()))
            }
        }
    }

    private fun markSeenAndSave() {
        if (episodeId == -1L) return
        val durationMs = player.duration.coerceAtLeast(0)
        scope.launch {
            updateEpisode.await(
                EpisodeUpdate(id = episodeId, seen = true, lastSecondSeen = durationMs, totalSeconds = durationMs),
            )
            upsertHistory.await(AnimeHistoryUpdate(episodeId, Date()))
        }
    }

    // ---- Aspect ratio ----------------------------------------------------

    private fun cycleAspect() {
        val next = when (playerPreferences.aspectState().get()) {
            VideoAspect.Fit -> VideoAspect.Crop
            VideoAspect.Crop -> VideoAspect.Stretch
            VideoAspect.Stretch -> VideoAspect.Fit
        }
        playerPreferences.aspectState().set(next)
        applyAspect(next)
        showGestureFeedback(
            when (next) {
                VideoAspect.Fit -> "Fit Screen"
                VideoAspect.Crop -> "Crop Screen"
                VideoAspect.Stretch -> "Stretch Screen"
            },
            longer = true,
        )
    }

    private fun applyAspect(aspect: VideoAspect) {
        when (aspect) {
            VideoAspect.Fit -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            VideoAspect.Crop -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            VideoAspect.Stretch -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
    }

    // ---- PiP lifecycle ---------------------------------------------------

    override fun onUserLeaveHint() {
        if (playerPreferences.pipOnExit().get() && player.isPlaying &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            enterPictureInPictureMode(buildPipParams())
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        if (isInPictureInPictureMode) {
            registerPipReceiver()
        } else {
            unregisterPipReceiver()
        }
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    internal var pipReceiver: BroadcastReceiver? = null

    override fun onPause() {
        super.onPause()
        if (isInPictureInPictureMode) return
        if (playbackStarted) savePosition()
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized && playbackStarted && !isInPictureInPictureMode) player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized && playbackStarted) {
            savePosition()
            playerView.player = null
            player.release()
        }
        scope.cancel()
    }

    companion object {
        private const val EXTRA_ANIME_ID = "exo_anime_id"
        private const val EXTRA_EPISODE_ID = "exo_episode_id"
        private const val EXTRA_HOSTER_LIST = "hostList"
        private const val EXTRA_HOSTER_INDEX = "hostIndex"
        private const val EXTRA_VIDEO_INDEX = "vidIndex"

        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

        fun newIntent(
            context: Context,
            animeId: Long,
            episodeId: Long,
            hosterListSerialized: String? = null,
            hosterIndex: Int = -1,
            videoIndex: Int = -1,
        ): Intent {
            return Intent(context, ExoPlayerActivity::class.java).apply {
                putExtra(EXTRA_ANIME_ID, animeId)
                putExtra(EXTRA_EPISODE_ID, episodeId)
                hosterListSerialized?.let { putExtra(EXTRA_HOSTER_LIST, it) }
                putExtra(EXTRA_HOSTER_INDEX, hosterIndex)
                putExtra(EXTRA_VIDEO_INDEX, videoIndex)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}
