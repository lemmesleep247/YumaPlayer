/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.home.recommendations

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.aicontentfilter.FilterAiContentUseCase
import moe.rukamori.archivetune.aicontentfilter.LoadAiContentFilterPolicyUseCase
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.constants.HideVideoKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.extensions.filterBlockedArtists
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.models.filterExplicit
import moe.rukamori.archivetune.innertube.models.filterVideo
import moe.rukamori.archivetune.models.SimilarRecommendation
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import javax.inject.Inject

class HomeSimilarRecommendationsUseCase
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        private val loadAiContentFilterPolicy: LoadAiContentFilterPolicyUseCase,
        private val filterAiContent: FilterAiContentUseCase,
    ) {
        suspend operator fun invoke(): List<SimilarRecommendation> =
            withContext(Dispatchers.IO) {
                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                val hideVideo = context.dataStore.get(HideVideoKey, false)
                val blockedArtistIds = database.getBlockedArtistIds().toSet()
                val aiContentFilterPolicy = loadAiContentFilterPolicy()
                val fromTimeStamp = System.currentTimeMillis() - 86400000 * 7 * 2

                var topArtists =
                    database
                        .mostPlayedArtists(fromTimeStamp, limit = 10)
                        .first()
                        .filter { it.artist.blockedAt == null && it.artist.isYouTubeArtist }
                if (topArtists.isEmpty()) {
                    topArtists =
                        database
                            .mostPlayedArtists(0L, limit = 10)
                            .first()
                            .filter { it.artist.blockedAt == null && it.artist.isYouTubeArtist }
                }

                val artistRecommendations =
                    topArtists
                        .shuffled()
                        .take(3)
                        .mapNotNull {
                            val items = mutableListOf<YTItem>()
                            YouTube.artist(it.id).onSuccess { page ->
                                items +=
                                    page.sections
                                        .getOrNull(page.sections.size - 2)
                                        ?.items
                                        .orEmpty()
                                items +=
                                    page.sections
                                        .lastOrNull()
                                        ?.items
                                        .orEmpty()
                            }
                            SimilarRecommendation(
                                title = it,
                                items =
                                    filterAiContent(
                                        items
                                            .filterExplicit(hideExplicit)
                                            .filterVideo(hideVideo)
                                            .filterBlockedArtists(blockedArtistIds),
                                        aiContentFilterPolicy,
                                    ).shuffled()
                                        .ifEmpty { return@mapNotNull null },
                            )
                        }

                var topSongs =
                    database
                        .mostPlayedSongs(fromTimeStamp, limit = 10)
                        .first()
                        .filter { it.album != null }
                if (topSongs.isEmpty()) {
                    topSongs =
                        database
                            .mostPlayedSongs(0L, limit = 10)
                            .first()
                            .filter { it.album != null }
                }

                val songRecommendations =
                    topSongs
                        .shuffled()
                        .take(2)
                        .mapNotNull { song ->
                            val endpoint =
                                YouTube.next(WatchEndpoint(videoId = song.id)).getOrNull()?.relatedEndpoint
                                    ?: return@mapNotNull null
                            val page = YouTube.related(endpoint).getOrNull() ?: return@mapNotNull null
                            SimilarRecommendation(
                                title = song,
                                items =
                                    filterAiContent(
                                        (
                                            page.songs.shuffled().take(8) +
                                                page.albums.shuffled().take(4) +
                                                page.artists.shuffled().take(4) +
                                                page.playlists.shuffled().take(4)
                                        ).filterExplicit(hideExplicit)
                                            .filterVideo(hideVideo)
                                            .filterBlockedArtists(blockedArtistIds),
                                        aiContentFilterPolicy,
                                    ).shuffled()
                                        .ifEmpty { return@mapNotNull null },
                            )
                        }

                (artistRecommendations + songRecommendations).shuffled()
            }
    }
