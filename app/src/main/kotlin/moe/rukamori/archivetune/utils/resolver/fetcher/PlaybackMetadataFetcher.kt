/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.utils.resolver.fetcher

import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.utils.StreamUrlCache
import moe.rukamori.archivetune.utils.getPlaybackPlayerResponseOrThrow
import moe.rukamori.archivetune.utils.isInvalidPlaybackLoginContextFailure
import moe.rukamori.archivetune.utils.potoken.BotGuardTokenGenerator
import moe.rukamori.archivetune.utils.resolver.PlaybackAuthCoordinator
import moe.rukamori.archivetune.utils.resolver.PlaybackClientSelector
import moe.rukamori.archivetune.utils.resolver.PlaybackStreamUrlDecipher
import timber.log.Timber

internal data class PlaybackMetadataResult(
    val playerResponse: PlayerResponse,
    val expectedDurationMs: Long?,
    val authState: PlaybackAuthState,
    val canUseLoggedInPlayback: Boolean,
    val metadataClient: YouTubeClient,
)

internal object PlaybackMetadataFetcher {
    private const val TAG = "PlaybackMetadataFetcher"

    suspend fun fetchMetadataForPlayback(
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        initialAuthState: PlaybackAuthState,
        initialCanUseLoggedInPlayback: Boolean,
        fetchAttemptId: Long,
    ): PlaybackMetadataResult {
        var authState = initialAuthState
        var canUseLoggedInPlayback = initialCanUseLoggedInPlayback
        val sessionId = authState.visitorData

        val metadataClient = PlaybackClientSelector.resolveMetadataClient(authState)
        if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
            moe.rukamori.archivetune.playback.PlaybackTiming.log(
                "METADATA_CLIENT_SELECTED",
                attemptId = fetchAttemptId,
                mediaId = videoId,
                extra = "client=${PlaybackClientSelector.describeClient(metadataClient)}",
            )
        }

        Timber.tag(TAG).i("Fetching metadata response using client: ${metadataClient.clientName}")

        var metadataPoToken: String? = null
        if (metadataClient.useWebPoTokens && sessionId != null) {
            val cachedToken = StreamUrlCache.getCachedPoToken(sessionId)
            if (cachedToken != null) {
                Timber.tag(TAG).i("🔥 Using CACHED PoToken for metadata (saved ~10s)")
                metadataPoToken = cachedToken.playerToken
                YouTube.authState = YouTube.authState.copy(
                    poTokenGvs = cachedToken.sessionToken,
                    poTokenPlayer = cachedToken.playerToken,
                    webClientPoTokenEnabled = true,
                )
                authState = YouTube.authState
            } else {
                try {
                    Timber.tag(TAG).i("Generating NEW PoToken for metadata (heavy operation)")
                    val tokenResult = BotGuardTokenGenerator.mintToken(videoId, sessionId)
                    metadataPoToken = tokenResult?.playerToken
                    tokenResult?.let {
                        StreamUrlCache.cachePoToken(sessionId, it.playerToken, it.sessionToken)
                        YouTube.authState = YouTube.authState.copy(
                            poTokenGvs = it.sessionToken,
                            poTokenPlayer = it.playerToken,
                            webClientPoTokenEnabled = true,
                        )
                        authState = YouTube.authState
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "PoToken generation failed for metadata request")
                }
            }
        }

        val metadataStartNanos = if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
            moe.rukamori.archivetune.playback.PlaybackTiming.nowNanos()
        } else 0L
        var metadataResult =
            YouTube.player(
                videoId = videoId,
                playlistId = playlistId,
                client = metadataClient,
                signatureTimestamp = signatureTimestamp,
                poToken = metadataPoToken,
                setLogin = true,
                authState = authState,
            )
        if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
            moe.rukamori.archivetune.playback.PlaybackTiming.log(
                "METADATA_FETCH_END",
                attemptId = fetchAttemptId,
                mediaId = videoId,
                startNanos = metadataStartNanos,
                extra = "client=${PlaybackClientSelector.describeClient(metadataClient)} ok=${metadataResult.isSuccess}",
            )
        }
        val metadataFailure = metadataResult.exceptionOrNull()
        if (metadataFailure != null && canUseLoggedInPlayback && metadataFailure.isInvalidPlaybackLoginContextFailure()) {
            Timber.tag(TAG).w(
                metadataFailure,
                "Logged-in playback context is stale for %s; retrying metadata with visitor playback",
                videoId,
            )
            authState =
                PlaybackAuthCoordinator.ensureVisitorDataReady(
                    videoId = videoId,
                    authState = authState.copy(dataSyncId = null).normalized(),
                    forceRefresh = true,
                    reason = "stale logged-in playback context",
                )
            canUseLoggedInPlayback = false
            YouTube.authState = authState
            StreamUrlCache.clearPlaybackAuthCaches()

            val newSessionId = authState.visitorData
            if (metadataClient.useWebPoTokens && newSessionId != null) {
                val cachedRetryToken = StreamUrlCache.getCachedPoToken(newSessionId)
                if (cachedRetryToken != null) {
                    metadataPoToken = cachedRetryToken.playerToken
                    YouTube.authState = YouTube.authState.copy(
                        poTokenGvs = cachedRetryToken.sessionToken,
                        poTokenPlayer = cachedRetryToken.playerToken,
                        webClientPoTokenEnabled = true,
                    )
                    authState = YouTube.authState
                } else {
                    try {
                        val tokenResult = BotGuardTokenGenerator.mintToken(videoId, newSessionId)
                        metadataPoToken = tokenResult?.playerToken
                        tokenResult?.let {
                            StreamUrlCache.cachePoToken(newSessionId, it.playerToken, it.sessionToken)
                            YouTube.authState = YouTube.authState.copy(
                                poTokenGvs = it.sessionToken,
                                poTokenPlayer = it.playerToken,
                                webClientPoTokenEnabled = true,
                            )
                            authState = YouTube.authState
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "PoToken generation failed for metadata retry request")
                    }
                }
            }

            metadataResult =
                YouTube.player(
                    videoId = videoId,
                    playlistId = playlistId,
                    client = metadataClient,
                    signatureTimestamp = signatureTimestamp,
                    poToken = metadataPoToken,
                    setLogin = true,
                    authState = authState,
                )
        }

        val metadataPlayerResponse = metadataResult.getPlaybackPlayerResponseOrThrow(videoId, authState)
        val expectedDurationMs =
            metadataPlayerResponse.videoDetails
                ?.lengthSeconds
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.times(1000L)

        return PlaybackMetadataResult(
            playerResponse = metadataPlayerResponse,
            expectedDurationMs = expectedDurationMs,
            authState = authState,
            canUseLoggedInPlayback = canUseLoggedInPlayback,
            metadataClient = metadataClient,
        )
    }

    suspend fun fetchMetadataOnly(
        videoId: String,
        playlistId: String? = null,
        authState: PlaybackAuthState = YouTube.currentPlaybackAuthState(),
    ): Result<PlayerResponse> {
        Timber.tag(TAG).i("Fetching metadata player response for videoId: $videoId")

        val signatureTimestamp = PlaybackStreamUrlDecipher.getSignatureTimestampOrNull(videoId)
        var currentAuthState = authState
        val sessionId = currentAuthState.visitorData
        var poToken: String? = null

        if (PlaybackClientSelector.MAIN_CLIENT.useWebPoTokens && sessionId != null) {
            val cachedToken = StreamUrlCache.getCachedPoToken(sessionId)
            if (cachedToken != null) {
                poToken = cachedToken.playerToken
                YouTube.authState = YouTube.authState.copy(
                    poTokenGvs = cachedToken.sessionToken,
                    poTokenPlayer = cachedToken.playerToken,
                    webClientPoTokenEnabled = true,
                )
                currentAuthState = YouTube.authState
            } else {
                try {
                    val tokenResult = BotGuardTokenGenerator.mintToken(videoId, sessionId)
                    poToken = tokenResult?.playerToken
                    tokenResult?.let {
                        StreamUrlCache.cachePoToken(sessionId, it.playerToken, it.sessionToken)
                        YouTube.authState = YouTube.authState.copy(
                            poTokenGvs = it.sessionToken,
                            poTokenPlayer = it.playerToken,
                            webClientPoTokenEnabled = true,
                        )
                        currentAuthState = YouTube.authState
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "PoToken generation failed for metadata request")
                }
            }
        }

        return YouTube
            .player(
                videoId = videoId,
                playlistId = playlistId,
                client = PlaybackClientSelector.MAIN_CLIENT,
                signatureTimestamp = signatureTimestamp,
                poToken = poToken,
                setLogin = true,
                authState = currentAuthState,
            ).onSuccess { Timber.tag(TAG).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(TAG).e(it, "Failed to fetch metadata") }
    }
}
