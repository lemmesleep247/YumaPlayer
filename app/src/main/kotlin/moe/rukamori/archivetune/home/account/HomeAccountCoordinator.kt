/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.home.account

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.auth.SwitchSavedYouTubeAccountUseCase
import moe.rukamori.archivetune.constants.AccountChannelHandleKey
import moe.rukamori.archivetune.constants.AccountEmailKey
import moe.rukamori.archivetune.constants.AccountNameKey
import moe.rukamori.archivetune.constants.DataSyncIdKey
import moe.rukamori.archivetune.constants.InnerTubeCookieKey
import moe.rukamori.archivetune.constants.YtmSyncKey
import moe.rukamori.archivetune.home.state.AccountChannelCollection
import moe.rukamori.archivetune.home.state.AccountChannelUiModel
import moe.rukamori.archivetune.home.state.AccountChannelsState
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.AccountChannel
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.withHighResAvatar
import moe.rukamori.archivetune.innertube.utils.completed
import moe.rukamori.archivetune.innertube.utils.hasYouTubeLoginCookie
import moe.rukamori.archivetune.utils.SavedAccount
import moe.rukamori.archivetune.utils.SyncUtils
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.reportException
import moe.rukamori.archivetune.utils.toPlaybackAuthState
import timber.log.Timber
import javax.inject.Inject

class HomeAccountCoordinator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val syncUtils: SyncUtils,
        private val switchSavedYouTubeAccount: SwitchSavedYouTubeAccountUseCase,
    ) {
        private val _accountName = MutableStateFlow("")
        val accountName: StateFlow<String> = _accountName.asStateFlow()

        private val _accountImageUrl = MutableStateFlow<String?>(null)
        val accountImageUrl: StateFlow<String?> = _accountImageUrl.asStateFlow()

        private val _accountChannelsState = MutableStateFlow<AccountChannelsState>(AccountChannelsState.Empty)
        val accountChannelsState: StateFlow<AccountChannelsState> = _accountChannelsState.asStateFlow()

        private val _accountPlaylists = MutableStateFlow<List<PlaylistItem>?>(null)
        val accountPlaylists: StateFlow<List<PlaylistItem>?> = _accountPlaylists.asStateFlow()

        fun clearAccountData() {
            _accountName.value = ""
            _accountImageUrl.value = null
            _accountPlaylists.value = null
            _accountChannelsState.value = AccountChannelsState.Empty
        }

        private fun prepareYouTubeAccount(cookie: String): Boolean =
            try {
                YouTube.cookie = cookie
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to set YouTube cookie")
                false
            }

        suspend fun refreshIdentity() =
            withContext(Dispatchers.IO) {
                _accountName.value = ""
                _accountImageUrl.value = null
                _accountChannelsState.value = AccountChannelsState.Loading

                try {
                    YouTube
                        .accountInfo()
                        .onSuccess { info ->
                            _accountName.value = info.name
                            _accountImageUrl.value = info.thumbnailUrl?.withHighResAvatar()
                        }.onFailure { error ->
                            Timber.w(error, "Failed to fetch account info")
                        }

                    YouTube
                        .accountChannels()
                        .onSuccess { channels ->
                            _accountChannelsState.value =
                                channels
                                    .map { it.toUiModel() }
                                    .takeIf { it.size > 1 }
                                    ?.let { AccountChannelsState.Success(AccountChannelCollection(it)) }
                                    ?: AccountChannelsState.Empty
                        }.onFailure { error ->
                            Timber.w(error, "Failed to fetch account channels")
                            reportException(error)
                            _accountChannelsState.value = AccountChannelsState.Error(error.message.orEmpty())
                        }
                } catch (e: CancellationException) {
                    _accountChannelsState.value = AccountChannelsState.Empty
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Exception fetching account info")
                    reportException(e)
                    _accountChannelsState.value = AccountChannelsState.Error(e.message.orEmpty())
                }
            }

        private fun AccountChannel.toUiModel(): AccountChannelUiModel =
            AccountChannelUiModel(
                name = name,
                byline = byline.orEmpty(),
                channelHandle = channelHandle.orEmpty(),
                thumbnailUrl = thumbnailUrl,
                dataSyncId = dataSyncId,
                isSelected = isSelected,
            )

        suspend fun refreshPlaylists() =
            withContext(Dispatchers.IO) {
                try {
                    YouTube
                        .library("FEmusic_liked_playlists")
                        .completed()
                        .onSuccess {
                            val lists =
                                it.items.filterIsInstance<PlaylistItem>().filterNot { playlist ->
                                    playlist.id == "SE"
                                }
                            _accountPlaylists.value = lists
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            Timber.w(error, "Failed to fetch account playlists")
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Exception fetching account playlists")
                }
            }

        suspend fun switchAccount(
            account: SavedAccount,
            forceSyncOnSwitch: Boolean,
        ) = withContext(Dispatchers.IO) {
            try {
                val authState = switchSavedYouTubeAccount(account).getOrThrow()

                if (forceSyncOnSwitch && account.ytmSync && authState.hasLoginCookie) {
                    syncUtils.performFullSync(authoritative = true)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error switching account")
                reportException(e)
            }
        }

        suspend fun switchChannel(
            channel: AccountChannelUiModel,
            forceSyncOnSwitch: Boolean,
        ) = withContext(Dispatchers.IO) {
            if (channel.dataSyncId.isBlank()) return@withContext

            try {
                _accountChannelsState.value = AccountChannelsState.Loading

                context.dataStore.edit { preferences ->
                    preferences[DataSyncIdKey] = channel.dataSyncId
                    preferences[AccountNameKey] = channel.name
                    preferences[AccountChannelHandleKey] = channel.channelHandle
                    if (channel.byline.contains("@")) {
                        preferences[AccountEmailKey] = channel.byline
                    }
                }

                val authState =
                    context.dataStore.data
                        .first()
                        .toPlaybackAuthState()
                YouTube.authState = authState

                supervisorScope {
                    launch { refreshIdentity() }
                    launch { refreshPlaylists() }
                }

                if (forceSyncOnSwitch && context.dataStore.get(YtmSyncKey, true) && authState.hasLoginCookie) {
                    syncUtils.performFullSync(authoritative = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error switching account channel")
                reportException(e)
                _accountChannelsState.value = AccountChannelsState.Error(e.message.orEmpty())
            }
        }

        fun observeCookies(
            scope: CoroutineScope,
            onAuthSuccess: suspend () -> Unit = {},
            onAuthCleared: () -> Unit = {},
        ) {
            scope.launch(Dispatchers.IO) {
                var isFirstEmission = true
                var wasLoggedIn = false
                context.dataStore.data
                    .map { it[InnerTubeCookieKey] }
                    .distinctUntilChanged()
                    .collect { cookie ->
                        try {
                            val isLoggedIn = hasYouTubeLoginCookie(cookie)
                            if (isFirstEmission) {
                                wasLoggedIn = isLoggedIn
                                isFirstEmission = false
                                return@collect
                            }
                            val loginTransition = isLoggedIn && !wasLoggedIn
                            wasLoggedIn = isLoggedIn

                            if (isLoggedIn && cookie != null && cookie.isNotEmpty()) {
                                if (!prepareYouTubeAccount(cookie)) {
                                    clearAccountData()
                                    onAuthCleared()
                                    return@collect
                                }

                                supervisorScope {
                                    delay(100)
                                    launch { refreshIdentity() }
                                    launch { refreshPlaylists() }
                                }

                                if (loginTransition) {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            if (context.dataStore.get(YtmSyncKey, true)) {
                                                syncUtils.performFullSync()
                                            }
                                        } catch (e: Exception) {
                                            Timber.e(e, "Error during login-triggered sync")
                                            reportException(e)
                                        }
                                    }
                                }
                                onAuthSuccess()
                            } else {
                                clearAccountData()
                                onAuthCleared()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.e(e, "Error processing cookie change")
                            clearAccountData()
                            onAuthCleared()
                        }
                    }
            }
        }
    }
