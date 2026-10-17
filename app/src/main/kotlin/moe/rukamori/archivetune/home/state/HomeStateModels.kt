/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.home.state

import androidx.compose.runtime.Immutable
import com.google.common.collect.ImmutableList
import moe.rukamori.archivetune.db.entities.LocalItem
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.home.HomePresentationPreferences
import moe.rukamori.archivetune.home.HomeScreenState
import moe.rukamori.archivetune.home.HomeUiState
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.pages.HomePage
import moe.rukamori.archivetune.models.SimilarRecommendation

sealed interface AccountChannelsState {
    data object Loading : AccountChannelsState

    data class Success(
        val channels: AccountChannelCollection,
    ) : AccountChannelsState

    data object Empty : AccountChannelsState

    data class Error(
        val message: String,
    ) : AccountChannelsState
}

@Immutable
data class AccountChannelCollection(
    val items: List<AccountChannelUiModel>,
)

@Immutable
data class AccountChannelUiModel(
    val name: String,
    val byline: String,
    val channelHandle: String,
    val thumbnailUrl: String?,
    val dataSyncId: String,
    val isSelected: Boolean,
)

data class HomeLocalContent(
    val quickPicks: List<Song>,
    val speedDialItems: List<LocalItem>,
    val forgottenFavorites: List<Song>,
    val keepListening: List<LocalItem>,
)

data class HomeRemoteContent(
    val homePage: HomePage?,
    val similarRecommendations: List<SimilarRecommendation>,
    val accountPlaylists: List<PlaylistItem>,
    val accountName: String,
    val accountImageUrl: String?,
    val communitySection: HomePage.Section? = null,
)

data class HomeContent(
    val local: HomeLocalContent,
    val remote: HomeRemoteContent,
    val selectedChip: HomePage.Chip?,
) {
    val hasContent: Boolean
        get() =
            local.quickPicks.isNotEmpty() ||
                local.speedDialItems.isNotEmpty() ||
                local.forgottenFavorites.isNotEmpty() ||
                local.keepListening.isNotEmpty() ||
                remote.similarRecommendations.isNotEmpty() ||
                remote.accountPlaylists.isNotEmpty() ||
                remote.communitySection?.featuredCards?.isNotEmpty() == true ||
                remote.homePage?.sections?.any { it.items.isNotEmpty() } == true
}

data class HomeStateInputs(
    val content: HomeContent,
    val preferences: HomePresentationPreferences,
    val isLoading: Boolean,
    val isInitialLoadComplete: Boolean,
    val loadError: Int?,
) {
    fun toScreenState(
        isRefreshing: Boolean,
        isLoadingMore: Boolean,
    ): HomeScreenState {
        if (!content.hasContent) {
            if (loadError != null && isInitialLoadComplete) {
                return HomeScreenState.Error(loadError)
            }
            if (isLoading || !isInitialLoadComplete) {
                return HomeScreenState.Loading
            }
            return HomeScreenState.Empty
        }

        return HomeScreenState.Success(
            HomeUiState(
                quickPicks = ImmutableList.copyOf(content.local.quickPicks),
                speedDialItems = ImmutableList.copyOf(content.local.speedDialItems),
                forgottenFavorites = ImmutableList.copyOf(content.local.forgottenFavorites),
                keepListening = ImmutableList.copyOf(content.local.keepListening),
                similarRecommendations = ImmutableList.copyOf(content.remote.similarRecommendations),
                accountPlaylists = ImmutableList.copyOf(content.remote.accountPlaylists),
                homePage = content.remote.homePage,
                selectedChip = content.selectedChip,
                accountName = content.remote.accountName,
                accountImageUrl = content.remote.accountImageUrl,
                quickPicksDisplayMode = preferences.quickPicksDisplayMode,
                showCategoryChips = preferences.showCategoryChips,
                showTonalBackdrop = preferences.showTonalBackdrop,
                isRefreshing = isRefreshing,
                isLoadingMore = isLoadingMore,
                communitySection = content.remote.communitySection,
            ),
        )
    }
}
