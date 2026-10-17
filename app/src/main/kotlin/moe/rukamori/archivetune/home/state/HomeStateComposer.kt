/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.home.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import moe.rukamori.archivetune.home.HomePresentationPreferences
import moe.rukamori.archivetune.home.HomeScreenState
import moe.rukamori.archivetune.innertube.pages.HomePage
import javax.inject.Inject

class HomeStateComposer
    @Inject
    constructor() {
        fun compose(
            scope: CoroutineScope,
            localContent: Flow<HomeLocalContent>,
            remoteContent: Flow<HomeRemoteContent>,
            selectedChip: StateFlow<HomePage.Chip?>,
            presentationPreferences: Flow<HomePresentationPreferences>,
            isLoading: StateFlow<Boolean>,
            isInitialLoadComplete: StateFlow<Boolean>,
            loadError: StateFlow<Int?>,
            isRefreshing: StateFlow<Boolean>,
            isLoadingMore: StateFlow<Boolean>,
        ): StateFlow<HomeScreenState> {
            val homeContent =
                combine(
                    localContent,
                    remoteContent,
                    selectedChip,
                ) { local, remote, chip ->
                    HomeContent(
                        local = local,
                        remote = remote,
                        selectedChip = chip,
                    )
                }

            return combine(
                homeContent,
                presentationPreferences,
                isLoading,
                isInitialLoadComplete,
                loadError,
            ) { content, preferences, loading, initialComplete, error ->
                HomeStateInputs(
                    content = content,
                    preferences = preferences,
                    isLoading = loading,
                    isInitialLoadComplete = initialComplete,
                    loadError = error,
                )
            }.combine(
                combine(isRefreshing, isLoadingMore) { refreshing, loadingMore ->
                    refreshing to loadingMore
                },
            ) { inputs, loadingState ->
                inputs.toScreenState(
                    isRefreshing = loadingState.first,
                    isLoadingMore = loadingState.second,
                )
            }.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = HomeScreenState.Loading,
            )
        }
    }
