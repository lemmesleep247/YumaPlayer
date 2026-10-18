/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.home.feed

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.aicontentfilter.FilterAiContentUseCase
import moe.rukamori.archivetune.aicontentfilter.LoadAiContentFilterPolicyUseCase
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.constants.HideVideoKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.extensions.filterBlockedArtists
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.filterExplicit
import moe.rukamori.archivetune.innertube.models.filterVideo
import moe.rukamori.archivetune.innertube.pages.HomePage
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import timber.log.Timber
import javax.inject.Inject

class HomeFeedCoordinator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        private val loadAiContentFilterPolicy: LoadAiContentFilterPolicyUseCase,
        private val filterAiContent: FilterAiContentUseCase,
    ) {
        suspend fun loadHome(chipParams: String? = null): Result<HomePage> =
            withContext(Dispatchers.IO) {
                val result =
                    if (chipParams != null) {
                        YouTube.home(params = chipParams)
                    } else {
                        YouTube.home()
                    }
                result.map { page ->
                    val filtered = filterHomePageSections(page, filterChips = true)
                    Timber.tag("HomeDebug").d(
                        "loadHome(params=%s): sections=%s, chips=%s",
                        chipParams,
                        filtered.sections.map { it.title },
                        filtered.chips?.map { "${it.title}(hasParams=${!it.endpoint?.params.isNullOrBlank()})" },
                    )
                    filtered
                }.onFailure {
                    Timber.tag("HomeDebug").e(it, "loadHome(params=%s) FAILED", chipParams)
                }
            }

        suspend fun loadMore(continuation: String): Result<HomePage> =
            withContext(Dispatchers.IO) {
                YouTube.home(continuation = continuation).map { page ->
                    filterHomePageSections(page, filterChips = false)
                }
            }

        private fun filterHomeChips(chips: List<HomePage.Chip>?): List<HomePage.Chip>? =
            chips?.filterNot {
                it.title.contains("podcasts", ignoreCase = true)
            }

        private suspend fun filterHomePageSections(
            page: HomePage,
            filterChips: Boolean,
        ): HomePage {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideo = context.dataStore.get(HideVideoKey, false)
            val blockedArtistIds = database.getBlockedArtistIds().toSet()
            val aiContentFilterPolicy = loadAiContentFilterPolicy()

            return page.copy(
                chips = if (filterChips) filterHomeChips(page.chips) else page.chips,
                sections =
                    page.sections.map { section ->
                        section.copy(
                            items =
                                filterAiContent(
                                    section.items
                                        .filterExplicit(hideExplicit)
                                        .filterVideo(hideVideo)
                                        .filterBlockedArtists(blockedArtistIds),
                                    aiContentFilterPolicy,
                                ),
                        )
                    },
            )
        }
    }
