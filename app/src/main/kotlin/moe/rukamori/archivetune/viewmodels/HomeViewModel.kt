/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import timber.log.Timber
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.aicontentfilter.FilterAiContentUseCase
import moe.rukamori.archivetune.aicontentfilter.LoadAiContentFilterPolicyUseCase
import moe.rukamori.archivetune.aicontentfilter.ObserveAiContentFilterUseCase
import moe.rukamori.archivetune.auth.SwitchSavedYouTubeAccountUseCase
import moe.rukamori.archivetune.constants.SpeedDialSongIdsKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.Album
import moe.rukamori.archivetune.db.entities.LocalItem
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.home.FilterCommunityPreviewsUseCase
import moe.rukamori.archivetune.home.HomeAction
import moe.rukamori.archivetune.home.HomeScreenState
import moe.rukamori.archivetune.home.LoadCommunityPlaylistPreviewsUseCase
import moe.rukamori.archivetune.home.ObserveHomePresentationPreferencesUseCase
import moe.rukamori.archivetune.home.PrepareCommunityHomePageUseCase
import moe.rukamori.archivetune.home.account.HomeAccountCoordinator
import moe.rukamori.archivetune.home.feed.HomeFeedCoordinator
import moe.rukamori.archivetune.home.quickpicks.HomeQuickPicksManager
import moe.rukamori.archivetune.home.recommendations.HomeLocalRecommendationsUseCase
import moe.rukamori.archivetune.home.recommendations.HomeSimilarRecommendationsUseCase
import moe.rukamori.archivetune.home.state.AccountChannelCollection
import moe.rukamori.archivetune.home.state.AccountChannelUiModel
import moe.rukamori.archivetune.home.state.AccountChannelsState
import moe.rukamori.archivetune.home.state.HomeContent
import moe.rukamori.archivetune.home.state.HomeLocalContent
import moe.rukamori.archivetune.home.state.HomeRemoteContent
import moe.rukamori.archivetune.home.state.HomeStateInputs
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.pages.HomePage
import moe.rukamori.archivetune.models.SimilarRecommendation
import moe.rukamori.archivetune.utils.SavedAccount
import moe.rukamori.archivetune.utils.SyncUtils
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.reportException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

typealias AccountChannelsState = moe.rukamori.archivetune.home.state.AccountChannelsState
typealias AccountChannelCollection = moe.rukamori.archivetune.home.state.AccountChannelCollection
typealias AccountChannelUiModel = moe.rukamori.archivetune.home.state.AccountChannelUiModel

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val syncUtils: SyncUtils,
        observeHomePresentationPreferences: ObserveHomePresentationPreferencesUseCase,
        observeAiContentFilter: ObserveAiContentFilterUseCase,
        private val prepareCommunityHomePage: PrepareCommunityHomePageUseCase,
        private val loadCommunityPlaylistPreviews: LoadCommunityPlaylistPreviewsUseCase,
        private val filterCommunityPreviews: FilterCommunityPreviewsUseCase,
        private val quickPicksManager: HomeQuickPicksManager,
        private val localRecommendations: HomeLocalRecommendationsUseCase,
        private val similarRecommendationsUseCase: HomeSimilarRecommendationsUseCase,
        private val accountCoordinator: HomeAccountCoordinator,
        private val feedCoordinator: HomeFeedCoordinator,
    ) : ViewModel() {
        constructor(
            context: Context,
            database: MusicDatabase,
            syncUtils: SyncUtils,
            switchSavedYouTubeAccount: SwitchSavedYouTubeAccountUseCase,
            observeHomePresentationPreferences: ObserveHomePresentationPreferencesUseCase,
            observeAiContentFilter: ObserveAiContentFilterUseCase,
            loadAiContentFilterPolicy: LoadAiContentFilterPolicyUseCase,
            filterAiContent: FilterAiContentUseCase,
            prepareCommunityHomePage: PrepareCommunityHomePageUseCase,
            loadCommunityPlaylistPreviews: LoadCommunityPlaylistPreviewsUseCase,
            filterCommunityPreviews: FilterCommunityPreviewsUseCase,
        ) : this(
            context = context,
            syncUtils = syncUtils,
            observeHomePresentationPreferences = observeHomePresentationPreferences,
            observeAiContentFilter = observeAiContentFilter,
            prepareCommunityHomePage = prepareCommunityHomePage,
            loadCommunityPlaylistPreviews = loadCommunityPlaylistPreviews,
            filterCommunityPreviews = filterCommunityPreviews,
            quickPicksManager = HomeQuickPicksManager(context, database),
            localRecommendations = HomeLocalRecommendationsUseCase(context, database),
            similarRecommendationsUseCase = HomeSimilarRecommendationsUseCase(context, database, loadAiContentFilterPolicy, filterAiContent),
            accountCoordinator = HomeAccountCoordinator(context, syncUtils, switchSavedYouTubeAccount),
            feedCoordinator = HomeFeedCoordinator(context, database, loadAiContentFilterPolicy, filterAiContent),
        )

        private val isRefreshing = MutableStateFlow(false)
        private val isLoading = MutableStateFlow(false)
        private val isInitialLoadComplete = MutableStateFlow(false)
        private val loadError = MutableStateFlow<Int?>(null)
        private val isLoadingMore = MutableStateFlow(false)

        private val quickPicks = quickPicksManager.observe(viewModelScope)
        private val speedDialItems = MutableStateFlow<List<LocalItem>>(emptyList())
        private val forgottenFavorites = MutableStateFlow<List<Song>?>(null)
        private val keepListening = MutableStateFlow<List<LocalItem>?>(null)
        private val similarRecommendations = MutableStateFlow<List<SimilarRecommendation>?>(null)
        private val homePage = MutableStateFlow<HomePage?>(null)
        private val _communitySection = MutableStateFlow<HomePage.Section?>(null)
        val communitySection: StateFlow<HomePage.Section?> = _communitySection.asStateFlow()
        private val selectedChip = MutableStateFlow<HomePage.Chip?>(null)
        private val previousHomePage = MutableStateFlow<HomePage?>(null)
        private val previousCommunitySection = MutableStateFlow<HomePage.Section?>(null)
        private val communityPreviewGeneration = AtomicLong(0L)
        private var communityPreviewJob: Job? = null

        private val _allLocalItems = MutableStateFlow<List<LocalItem>>(emptyList())
        val allLocalItems: StateFlow<List<LocalItem>> = _allLocalItems.asStateFlow()
        private val _allYtItems = MutableStateFlow<List<YTItem>>(emptyList())
        val allYtItems: StateFlow<List<YTItem>> = _allYtItems.asStateFlow()

        val accountName: StateFlow<String> = accountCoordinator.accountName
        val accountImageUrl: StateFlow<String?> = accountCoordinator.accountImageUrl
        val accountChannelsState: StateFlow<AccountChannelsState> = accountCoordinator.accountChannelsState

        private val presentationPreferences = observeHomePresentationPreferences()
        private val aiContentFilterSettings =
            observeAiContentFilter()
                .map { (settings, _) -> settings }
                .distinctUntilChanged()

        private val localContent =
            combine(
                quickPicks,
                speedDialItems,
                forgottenFavorites,
                keepListening,
            ) { quickPicks, speedDialItems, forgottenFavorites, keepListening ->
                HomeLocalContent(
                    quickPicks = quickPicks.orEmpty(),
                    speedDialItems = speedDialItems,
                    forgottenFavorites = forgottenFavorites.orEmpty(),
                    keepListening = keepListening.orEmpty(),
                )
            }

        private val remoteContent =
            combine(
                homePage,
                similarRecommendations,
                accountCoordinator.accountPlaylists,
                combine(accountCoordinator.accountName, accountCoordinator.accountImageUrl) { name, img -> name to img },
                _communitySection,
            ) { homePage, similarRecommendations, accountPlaylists, (accountName, accountImageUrl), communitySection ->
                HomeRemoteContent(
                    homePage = homePage,
                    similarRecommendations = similarRecommendations.orEmpty(),
                    accountPlaylists = accountPlaylists.orEmpty(),
                    accountName = accountName,
                    accountImageUrl = accountImageUrl,
                    communitySection = communitySection,
                )
            }

        private val homeContent =
            combine(
                localContent,
                remoteContent,
                selectedChip,
            ) { localContent, remoteContent, selectedChip ->
                HomeContent(
                    local = localContent,
                    remote = remoteContent,
                    selectedChip = selectedChip,
                )
            }

        val screenState: StateFlow<HomeScreenState> =
            combine(
                homeContent,
                presentationPreferences,
                isLoading,
                isInitialLoadComplete,
                loadError,
            ) { content, preferences, isLoading, isInitialLoadComplete, loadError ->
                HomeStateInputs(
                    content = content,
                    preferences = preferences,
                    isLoading = isLoading,
                    isInitialLoadComplete = isInitialLoadComplete,
                    loadError = loadError,
                )
            }.combine(
                combine(isRefreshing, isLoadingMore) { isRefreshing, isLoadingMore ->
                    isRefreshing to isLoadingMore
                },
            ) { inputs, loadingState ->
                inputs.toScreenState(
                    isRefreshing = loadingState.first,
                    isLoadingMore = loadingState.second,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = HomeScreenState.Loading,
            )

        private var chipLoadJob: Job? = null

        private fun updateAllLocalItems() {
            _allLocalItems.value =
                (quickPicks.value.orEmpty() + forgottenFavorites.value.orEmpty() + keepListening.value.orEmpty())
                    .filter { it is Song || it is Album }
        }

        private fun updateAllYtItems() {
            val allItems: List<YTItem> =
                similarRecommendations.value?.flatMap { it.items }.orEmpty() +
                    homePage.value
                        ?.sections
                        ?.flatMap { it.items }
                        .orEmpty() +
                    _communitySection.value?.items.orEmpty()
            _allYtItems.value = allItems
        }

        private fun updateCommunitySection(section: HomePage.Section?) {
            val generation = communityPreviewGeneration.incrementAndGet()
            communityPreviewJob?.cancel()
            communityPreviewJob = null
            _communitySection.value = section
            if (section == null || section.featuredCards.all { card -> card.itemIds.isNotEmpty() }) return

            communityPreviewJob =
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val enrichedSection =
                            filterCommunityPreviews(
                                loadCommunityPlaylistPreviews(section),
                            )
                        if (communityPreviewGeneration.get() == generation) {
                            _communitySection.value = enrichedSection
                            updateAllYtItems()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportException(e)
                    }
                }
        }

        fun loadCommunityPreviews(section: HomePage.Section? = _communitySection.value) {
            updateCommunitySection(section)
        }

        private suspend fun load() {
            delay(150)
            if (isLoading.value) return
            isLoading.value = true
            loadError.value = null

            try {
                supervisorScope {
                    launch {
                        speedDialItems.value = localRecommendations.loadSpeedDial()
                    }
                    launch {
                        forgottenFavorites.value = localRecommendations.loadForgottenFavorites()
                    }
                    launch {
                        keepListening.value = localRecommendations.loadKeepListening()
                    }
                    launch {
                        feedCoordinator.loadHome()
                            .onSuccess { page ->
                                val prepared = prepareCommunityHomePage(page, allowStructuralFallback = true)
                                homePage.value = prepared.homePage
                                updateCommunitySection(prepared.communitySection)
                            }.onFailure {
                                reportException(it)
                                loadError.value = R.string.error_unknown
                                updateCommunitySection(null)
                            }
                    }
                }

                updateAllLocalItems()

                viewModelScope.launch(Dispatchers.IO) {
                    similarRecommendations.value = similarRecommendationsUseCase()
                    updateAllYtItems()
                }

                updateAllYtItems()

                isInitialLoadComplete.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                loadError.value = R.string.error_unknown
            } finally {
                isInitialLoadComplete.value = true
                isLoading.value = false
            }
        }

        private fun loadMoreYouTubeItems(continuation: String?) {
            if (continuation == null || isLoadingMore.value) return

            viewModelScope.launch(Dispatchers.IO) {
                isLoadingMore.value = true
                try {
                    val nextSections = feedCoordinator.loadMore(continuation).getOrNull() ?: return@launch
                    val prepared =
                        prepareCommunityHomePage(
                            homePage = nextSections,
                            allowStructuralFallback = _communitySection.value == null,
                        )
                    if (_communitySection.value == null && prepared.communitySection != null) {
                        updateCommunitySection(prepared.communitySection)
                    }
                    homePage.value =
                        nextSections.copy(
                            chips = homePage.value?.chips,
                            sections = homePage.value?.sections.orEmpty() + prepared.homePage.sections,
                        )
                    updateAllYtItems()
                } finally {
                    isLoadingMore.value = false
                }
            }
        }

        private fun toggleChip(chip: HomePage.Chip?) {
            Timber.tag("HomeDebug").d(
                "toggleChip clicked: title=%s, hasParams=%b",
                chip?.title,
                !chip?.endpoint?.params.isNullOrBlank(),
            )
            chipLoadJob?.cancel()
            if (chip == null || chip == selectedChip.value && previousHomePage.value != null) {
                Timber.tag("HomeDebug").d("toggleChip deselected, restoring previous homePage")
                homePage.value = previousHomePage.value
                updateCommunitySection(previousCommunitySection.value)
                previousHomePage.value = null
                previousCommunitySection.value = null
                selectedChip.value = null
                updateAllYtItems()
                return
            }

            if (selectedChip.value == null) {
                previousHomePage.value = homePage.value
                previousCommunitySection.value = _communitySection.value
            }

            chipLoadJob =
                viewModelScope.launch(Dispatchers.IO) {
                    feedCoordinator.loadHome(chipParams = chip.endpoint?.params)
                        .onSuccess { nextSections ->
                            Timber.tag("HomeDebug").d(
                                "toggleChip SUCCESS: loaded %d sections: %s",
                                nextSections.sections.size,
                                nextSections.sections.map { it.title },
                            )
                            if (nextSections.sections.isEmpty()) {
                                Timber.tag("HomeDebug").w("toggleChip returned empty sections, ignoring")
                                if (selectedChip.value == null) {
                                    previousHomePage.value = null
                                    previousCommunitySection.value = null
                                }
                                return@onSuccess
                            }
                            selectedChip.value = chip
                            val filteredPage = nextSections.copy(chips = homePage.value?.chips)
                            val prepared = prepareCommunityHomePage(filteredPage, allowStructuralFallback = false)
                            homePage.value = prepared.homePage
                            updateCommunitySection(prepared.communitySection)
                            updateAllYtItems()
                        }.onFailure {
                            Timber.tag("HomeDebug").e(it, "toggleChip FAILED")
                            reportException(it)
                            if (selectedChip.value == null) {
                                previousHomePage.value = null
                                previousCommunitySection.value = null
                            }
                        }
                }
        }

        fun onAction(action: HomeAction) {
            when (action) {
                HomeAction.Refresh -> refresh()
                is HomeAction.SelectChip -> toggleChip(action.chip)
                is HomeAction.LoadMore -> loadMoreYouTubeItems(action.continuation)
            }
        }

        private fun refresh() {
            if (isRefreshing.value) return
            viewModelScope.launch(Dispatchers.IO) {
                isRefreshing.value = true
                try {
                    supervisorScope {
                        launch { load() }
                        launch {
                            quickPicksManager.refresh()
                            updateAllLocalItems()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportException(e)
                } finally {
                    isRefreshing.value = false
                }
            }
        }

        fun switchToAccount(
            account: SavedAccount,
            forceSyncOnSwitch: Boolean,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                accountCoordinator.switchAccount(account, forceSyncOnSwitch)
            }
        }

        fun switchToAccountChannel(
            channel: AccountChannelUiModel,
            forceSyncOnSwitch: Boolean,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                accountCoordinator.switchChannel(channel, forceSyncOnSwitch)
            }
        }

        init {
            viewModelScope.launch(Dispatchers.IO) {
                quickPicks.collect {
                    updateAllLocalItems()
                }
            }

            viewModelScope.launch(Dispatchers.IO) {
                load()
            }

            viewModelScope.launch(Dispatchers.IO) {
                aiContentFilterSettings
                    .drop(1)
                    .collectLatest {
                        isLoading.filter { loading -> !loading }.first()
                        load()
                    }
            }

            viewModelScope.launch(Dispatchers.IO) {
                context.dataStore.data
                    .map { it[SpeedDialSongIdsKey].orEmpty() }
                    .distinctUntilChanged()
                    .collect {
                        speedDialItems.value = localRecommendations.loadSpeedDial()
                        updateAllLocalItems()
                    }
            }

            viewModelScope.launch(Dispatchers.IO) {
                delay(3000)
                syncUtils.cleanupDuplicatePlaylists()
            }

            accountCoordinator.observeCookies(viewModelScope)
        }
    }
