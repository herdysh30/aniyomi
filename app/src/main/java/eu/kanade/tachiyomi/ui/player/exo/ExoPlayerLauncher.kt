package eu.kanade.tachiyomi.ui.player.exo

import android.content.Context
import androidx.media3.common.util.UnstableApi
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.toHosterList
import eu.kanade.domain.items.episode.model.toDbEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.items.episode.service.getEpisodeSort
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Playback data resolved for [ExoPlayerActivity]. Resolution runs INSIDE the
 * player (the activity opens instantly with a spinner), mirroring the MPV
 * player's "loading inside the player" UX.
 */
@UnstableApi
data class ResolvedPlayback(
    val videoUrl: String,
    val title: String,
    val animeTitle: String,
    val episodeName: String,
    val positionMs: Long,
    val headers: List<Pair<String, String>>,
    val subtitles: List<Pair<String, String>>,
    val userAgent: String?,
)

@UnstableApi
object ExoPlayerLauncher {

    /**
     * Resolve hosters/videos for [animeId]/[episodeId]. When [hosterList]
     * (serialized) + indices are supplied, that exact video is used (quality
     * pickers); otherwise the best video is chosen. Must be called off the
     * main thread. Shows a toast and returns null on any failure.
     */
    suspend fun resolve(
        context: Context,
        animeId: Long,
        episodeId: Long,
        hosterListSerialized: String?,
        hosterIndex: Int,
        videoIndex: Int,
    ): ResolvedPlayback? {
        return try {
            val getAnime: GetAnime = Injekt.get()
            val sourceManager: AnimeSourceManager = Injekt.get()
            val getEpisodes: GetEpisodesByAnimeId = Injekt.get()

            val anime = getAnime.await(animeId) ?: run {
                withUIContext { context.toast("Anime not found") }
                return null
            }
            val source = sourceManager.get(anime.source) ?: run {
                withUIContext { context.toast("Source not found") }
                return null
            }
            val episode = getEpisodes.await(anime.id).find { it.id == episodeId } ?: run {
                withUIContext { context.toast("Episode not found") }
                return null
            }

            resolveVideo(context, anime, episode, source, hosterListSerialized, hosterIndex, videoIndex)
        } catch (e: Exception) {
            this.logcat(LogPriority.ERROR, e) { "ExoPlayer resolve failed" }
            withUIContext { context.toast(e.message ?: "Failed to open episode") }
            null
        }
    }

    /**
     * Previous/next episode IDs for auto-next, mirroring PlayerViewModel's
     * playlist order (getEpisodeSort ascending, same as the episode list).
     * Returns (previousId, nextId).
     */
    suspend fun resolveAdjacent(
        animeId: Long,
        episodeId: Long,
    ): Pair<Long?, Long?> {
        return try {
            val getAnime: GetAnime = Injekt.get()
            val getEpisodes: GetEpisodesByAnimeId = Injekt.get()
            val anime = getAnime.await(animeId) ?: return null to null
            val episodes = getEpisodes.await(animeId)
                .sortedWith(getEpisodeSort(anime, sortDescending = false))
                .map { it.toDbEpisode() }
            val index = episodes.indexOfFirst { it.id == episodeId }
            if (index == -1) return null to null
            episodes.getOrNull(index - 1)?.id to episodes.getOrNull(index + 1)?.id
        } catch (e: Exception) {
            this.logcat(LogPriority.ERROR, e) { "ExoPlayer adjacent resolve failed" }
            null to null
        }
    }

    /** Shared tail of [resolve]: pick video, route through http server, map to [ResolvedPlayback]. */
    private suspend fun resolveVideo(
        context: Context,
        anime: Anime,
        episode: Episode,
        source: eu.kanade.tachiyomi.animesource.AnimeSource,
        hosterListSerialized: String?,
        hosterIndex: Int,
        videoIndex: Int,
    ): ResolvedPlayback? {
        var video: Video? = null
        if (hosterListSerialized != null && hosterIndex >= 0 && videoIndex >= 0) {
            val hoster = hosterListSerialized.toHosterList().getOrNull(hosterIndex)
            if (hoster != null) {
                val state = EpisodeLoader.loadHosterVideos(source, hoster)
                video = (state as? HosterState.Ready)?.videoList?.getOrNull(videoIndex)
            }
        }
        if (video == null) {
            val hosters = EpisodeLoader.getHosters(episode, anime, source)
            video = HosterLoader.getBestVideo(source, hosters)
        }
        if (video == null) {
            withUIContext { context.toast("No video found") }
            return null
        }

        var resolved = HosterLoader.getResolvedVideo(source, video) ?: run {
            withUIContext { context.toast("Video URL is empty") }
            return null
        }

        if (resolved.usesHttpServer()) {
            val httpSource = source as? AnimeHttpSource
            if (httpSource != null) {
                // Same pipeline the MPV player uses: the extension's
                // embedded m3u8 server processes/rewrites the stream
                // (junk-strip, header injection) and serves segments on
                // localhost. copyHttpServer rewrites video, audio AND
                // subtitle URLs to the running port.
                val (success, port) = MainActivity.startHttpServerService(context, source.id)
                if (!success) {
                    withUIContext { context.toast("Failed to start stream server") }
                    return null
                }
                resolved = resolved.copyHttpServer(port)
            }
        }

        if (resolved.videoUrl.isEmpty()) {
            withUIContext { context.toast("Video URL is empty") }
            return null
        }

        return ResolvedPlayback(
            videoUrl = resolved.videoUrl,
            title = anime.title + " - " + episode.name,
            animeTitle = anime.title,
            episodeName = episode.name,
            // lastSecondSeen is stored in MILLIS by Aniyomi's player
            // pipeline (see PlayerViewModel.onSecondReached).
            positionMs = episode.lastSecondSeen,
            headers = resolved.headers?.toList()?.map { it.first to it.second } ?: emptyList(),
            subtitles = resolved.subtitleTracks.map { it.url to it.lang },
            userAgent = resolved.headers?.get("User-Agent"),
        )
    }
}


