/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.home

import android.content.Context
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.aicontentfilter.FilterAiContentUseCase
import moe.rukamori.archivetune.aicontentfilter.LoadAiContentFilterPolicyUseCase
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.constants.HideVideoKey
import moe.rukamori.archivetune.constants.QuickPicksDisplayMode
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.extensions.filterBlockedArtists
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.models.filterExplicit
import moe.rukamori.archivetune.innertube.models.filterVideo
import moe.rukamori.archivetune.innertube.pages.HomePage
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import java.util.Locale
import javax.inject.Inject

class ObserveHomePresentationPreferencesUseCase
    @Inject
    constructor(
        private val repository: HomeRepository,
    ) {
        operator fun invoke(): Flow<HomePresentationPreferences> =
            combine(
                repository.showCategoryChips,
                repository.quickPicksDisplayMode,
                repository.showTonalBackdrop,
            ) { showCategoryChips, quickPicksDisplayMode, showTonalBackdrop ->
                HomePresentationPreferences(
                    showCategoryChips = showCategoryChips,
                    quickPicksDisplayMode = quickPicksDisplayMode,
                    showTonalBackdrop = showTonalBackdrop,
                )
            }
    }

data class PreparedCommunityHomePage(
    val homePage: HomePage,
    val communitySection: HomePage.Section?,
)

class PrepareCommunityHomePageUseCase
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val localizedCommunityLabel = context.getString(R.string.filter_community_playlists)
        private val localizedPlaylistLabel = context.getString(R.string.filter_playlists)
        private val extraCommunityMarkers = context.resources.getStringArray(R.array.community_label_markers)
        private val userPlaylistMarkers = context.resources.getStringArray(R.array.user_playlist_markers)

        operator fun invoke(
            homePage: HomePage,
            allowStructuralFallback: Boolean = true,
        ): PreparedCommunityHomePage {
            val candidates =
                homePage.sections.withIndex().filter { (_, section) ->
                    section.featuredCards.isNotEmpty() || section.isPlaylistShelf()
                }
            val selected =
                candidates.firstOrNull { (_, section) -> section.title.matchesCommunityLabel() }
                    ?: candidates.firstOrNull { (_, section) ->
                        section.isPlaylistShelf() && section.title.containsUserPlaylistMarker()
                    }
                    ?: candidates
                        .filter { (_, section) -> allowStructuralFallback && section.endpoint == null }
                        .firstOrNull()
                    ?: return PreparedCommunityHomePage(homePage = homePage, communitySection = null)
            val communitySection = selected.value.toFeaturedCommunitySection()
            return PreparedCommunityHomePage(
                homePage = homePage.copy(sections = homePage.sections.filterIndexed { index, _ -> index != selected.index }),
                communitySection = communitySection,
            )
        }

        private fun HomePage.Section.isPlaylistShelf(): Boolean =
            items.size >= MINIMUM_COMMUNITY_PLAYLISTS && items.all { item -> item is PlaylistItem }

        private fun HomePage.Section.toFeaturedCommunitySection(): HomePage.Section {
            if (featuredCards.isNotEmpty()) return this
            val playlists = items.filterIsInstance<PlaylistItem>()
            return copy(
                featuredCards =
                    playlists.map { playlist ->
                        HomePage.Section.FeaturedCard(
                            id = playlist.id,
                            title = playlist.title,
                            subtitle =
                                listOfNotNull(playlist.author?.name, playlist.songCountText)
                                    .distinct()
                                    .joinToString(separator = " • ")
                                    .takeIf(String::isNotBlank),
                            thumbnail = playlist.thumbnail,
                            endpoint = null,
                            playEndpoint = playlist.playEndpoint,
                            shuffleEndpoint = playlist.shuffleEndpoint,
                            radioEndpoint = playlist.radioEndpoint,
                            itemIds = emptyList(),
                        )
                    },
            )
        }

        private fun String.matchesCommunityLabel(): Boolean {
            val normalizedTitle = lowercase(Locale.getDefault())
            if (normalizedTitle.contains(ENGLISH_COMMUNITY_TOKEN)) return true
            if (extraCommunityMarkers.any { normalizedTitle.contains(it) }) return true
            val titleTokens = normalizedTitle.tokens()
            val localizedCommunityTokens =
                localizedCommunityLabel
                    .replace(localizedPlaylistLabel, newValue = "", ignoreCase = true)
                    .lowercase(Locale.getDefault())
                    .tokens()
                    .ifEmpty { localizedCommunityLabel.lowercase(Locale.getDefault()).tokens() }
            return titleTokens.any { titleToken ->
                localizedCommunityTokens.any { labelToken ->
                    titleToken == labelToken || titleToken.commonPrefixWith(labelToken).length >= MINIMUM_SHARED_PREFIX_LENGTH
                }
            }
        }

        private fun String.containsUserPlaylistMarker(): Boolean {
            val normalizedTitle = lowercase(Locale.getDefault())
            return userPlaylistMarkers.any { normalizedTitle.contains(it) }
        }

        private fun String.tokens(): List<String> =
            split(TOKEN_SEPARATOR).filter { token -> token.length >= MINIMUM_TOKEN_LENGTH }

        private companion object {
            val TOKEN_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
            const val ENGLISH_COMMUNITY_TOKEN = "community"
            const val MINIMUM_COMMUNITY_PLAYLISTS = 2
            const val MINIMUM_SHARED_PREFIX_LENGTH = 6
            const val MINIMUM_TOKEN_LENGTH = 4
        }
    }

class LoadCommunityPlaylistPreviewsUseCase
    @Inject
    constructor(
        private val repository: HomeRepository,
    ) {
        suspend operator fun invoke(section: HomePage.Section): HomePage.Section =
            supervisorScope {
                val semaphore = Semaphore(PREVIEW_LOAD_CONCURRENCY)
                val existingSongs = section.items.filterIsInstance<SongItem>().associateBy(SongItem::id)
                val previews =
                    section.featuredCards.map { card ->
                        async {
                            val currentPreview = card.itemIds.mapNotNull(existingSongs::get)
                            if (currentPreview.isNotEmpty()) {
                                card.id to currentPreview
                            } else {
                                val result =
                                    semaphore.withPermit {
                                        repository.loadPlaylistPreview(card.id, PREVIEW_SONG_COUNT)
                                    }
                                result.exceptionOrNull()?.let { throwable ->
                                    if (throwable is CancellationException) throw throwable
                                }
                                card.id to result.getOrDefault(emptyList())
                            }
                        }
                    }.awaitAll()
                        .toMap()

                val newSongs = previews.values.flatten().distinctBy(SongItem::id)
                val updatedCards =
                    section.featuredCards.map { card ->
                        card.copy(itemIds = previews[card.id]?.map(SongItem::id) ?: card.itemIds)
                    }

                section.copy(
                    items = (section.items + newSongs).distinctBy(YTItem::id),
                    featuredCards = updatedCards,
                )
            }

        companion object {
            const val PREVIEW_LOAD_CONCURRENCY = 3
            const val PREVIEW_SONG_COUNT = 3
        }
    }

class FilterCommunityPreviewsUseCase
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        private val loadAiContentFilterPolicy: LoadAiContentFilterPolicyUseCase,
        private val filterAiContent: FilterAiContentUseCase,
    ) {
        suspend operator fun invoke(section: HomePage.Section): HomePage.Section {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideo = context.dataStore.get(HideVideoKey, false)
            val blockedArtistIds = database.getBlockedArtistIds().toSet()
            val filteredYTItems: List<YTItem> =
                filterAiContent(
                    section.items
                        .filterExplicit(hideExplicit)
                        .filterVideo(hideVideo)
                        .filterBlockedArtists(blockedArtistIds),
                    loadAiContentFilterPolicy(),
                )
            val visibleSongIds = filteredYTItems.filterIsInstance<SongItem>().mapTo(mutableSetOf(), SongItem::id)
            return section.copy(
                items = filteredYTItems,
                featuredCards =
                    section.featuredCards.map { card ->
                        card.copy(itemIds = card.itemIds.filter(visibleSongIds::contains))
                    },
            )
        }
    }

@Immutable
data class HomePresentationPreferences(
    val showCategoryChips: Boolean,
    val quickPicksDisplayMode: QuickPicksDisplayMode,
    val showTonalBackdrop: Boolean,
)
