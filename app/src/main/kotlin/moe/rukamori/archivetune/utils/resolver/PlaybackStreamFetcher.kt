/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.utils.resolver

import android.net.ConnectivityManager
import androidx.media3.common.PlaybackException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.constants.PlayerStreamClient
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.models.YouTubeClient.Companion.VISIONOS
import moe.rukamori.archivetune.innertube.models.YouTubeClient.Companion.WEB_REMIX
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.utils.PlaybackErrorMapper
import moe.rukamori.archivetune.utils.StreamClientUtils
import moe.rukamori.archivetune.utils.StreamUrlCache
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.utils.YTPlayerUtils.BadStreamPlayerResponseException
import moe.rukamori.archivetune.utils.YTPlayerUtils.BotDetectionPlaybackException
import moe.rukamori.archivetune.utils.YTPlayerUtils.InvalidPlaybackLoginContextException
import moe.rukamori.archivetune.utils.YTPlayerUtils.LoginRequiredForPlaybackException
import moe.rukamori.archivetune.utils.YTPlayerUtils.PlaybackData
import moe.rukamori.archivetune.utils.getPlaybackPlayerResponseOrNull
import moe.rukamori.archivetune.utils.getPlaybackPlayerResponseOrThrow
import moe.rukamori.archivetune.utils.isInvalidPlaybackLoginContextFailure
import moe.rukamori.archivetune.utils.potoken.BotGuardTokenGenerator
import timber.log.Timber

object PlaybackStreamFetcher {
    private const val logTag = "PlaybackStreamFetcher"
    private val inFlightRequests = ConcurrentHashMap<String, CompletableDeferred<Result<PlaybackData>>>()

    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [PlaybackClientSelector.MAIN_CLIENT].
     * Format & stream can be from [PlaybackClientSelector.MAIN_CLIENT] or [PlaybackClientSelector.STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferredStreamClient: PlayerStreamClient = PlayerStreamClient.ANDROID_VR,
        // if provided, this preference overrides ConnectivityManager.isActiveNetworkMetered
        networkMetered: Boolean? = null,
    ): Result<PlaybackData> {
        val coalesceKey = "$videoId:$audioQuality"
        val deferred = CompletableDeferred<Result<PlaybackData>>()
        val existing = inFlightRequests.putIfAbsent(coalesceKey, deferred)
        if (existing != null) {
            return existing.await()
        }

        return try {
            val result =
                fetchPlayerResponseForPlayback(
                    videoId = videoId,
                    playlistId = playlistId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    preferredStreamClient = preferredStreamClient,
                    networkMetered = networkMetered,
                )
            deferred.complete(result)
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            deferred.cancel(e)
            throw e
        } catch (e: Throwable) {
            deferred.complete(Result.failure(e))
            throw e
        } finally {
            inFlightRequests.remove(coalesceKey, deferred)
        }
    }

    private suspend fun fetchPlayerResponseForPlayback(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferredStreamClient: PlayerStreamClient,
        networkMetered: Boolean?,
    ): Result<PlaybackData> {
        return try {
            val attempts =
                when (audioQuality) {
                    AudioQuality.HIGHEST -> listOf(AudioQuality.HIGHEST, AudioQuality.HIGH, AudioQuality.LOW)
                    AudioQuality.HIGH -> listOf(AudioQuality.HIGH, AudioQuality.LOW)
                    AudioQuality.AUTO -> listOf(AudioQuality.AUTO, AudioQuality.HIGH, AudioQuality.LOW)
                    AudioQuality.LOW -> listOf(AudioQuality.LOW, AudioQuality.HIGH, AudioQuality.AUTO)
                }.distinct()

            var lastError: Throwable? = null
            var didRefreshIpRotationAfterBotDetection = false
            for ((attemptIndex, attempt) in attempts.withIndex()) {
                val attemptResult =
                    try {
                        Result.success(
                            playerResponseForPlaybackOnce(
                                videoId = videoId,
                                playlistId = playlistId,
                                audioQuality = attempt,
                                connectivityManager = connectivityManager,
                                preferredStreamClient = preferredStreamClient,
                                networkMetered = networkMetered,
                            )
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Result.failure(e)
                    }
                if (attemptResult.isSuccess) return Result.success(attemptResult.getOrThrow())
                lastError = attemptResult.exceptionOrNull()
                if (lastError is kotlinx.coroutines.CancellationException) {
                    throw lastError
                }
                if (
                    !didRefreshIpRotationAfterBotDetection &&
                    lastError is BotDetectionPlaybackException &&
                    PlaybackAuthCoordinator.refreshIpRotationForBotDetection(videoId, lastError)
                ) {
                    didRefreshIpRotationAfterBotDetection = true
                    val rotatedAttemptResult =
                        try {
                            Result.success(
                                playerResponseForPlaybackOnce(
                                    videoId = videoId,
                                    playlistId = playlistId,
                                    audioQuality = attempt,
                                    connectivityManager = connectivityManager,
                                    preferredStreamClient = preferredStreamClient,
                                    networkMetered = networkMetered,
                                )
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Result.failure(e)
                        }
                    if (rotatedAttemptResult.isSuccess) return Result.success(rotatedAttemptResult.getOrThrow())
                    lastError = rotatedAttemptResult.exceptionOrNull()
                    if (lastError is kotlinx.coroutines.CancellationException) {
                        throw lastError
                    }
                }

                val isTerminalLogin = lastError is LoginRequiredForPlaybackException ||
                    lastError is YTPlayerUtils.InvalidPlaybackLoginContextException

                if (isTerminalLogin) {
                    if (attemptIndex < attempts.lastIndex) {
                        if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
                            moe.rukamori.archivetune.playback.PlaybackTiming.log(
                                "FALLBACK_LADDER_TERMINAL_LOGIN",
                                attemptId = moe.rukamori.archivetune.playback.PlaybackTiming.getAttemptId(videoId),
                                mediaId = videoId,
                                extra = "prevented_rerun=${attemptIndex + 1} remaining=${attempts.size - attemptIndex - 1} reason=${lastError.message.orEmpty()}",
                            )
                        }
                    }
                    break
                }

                if (lastError is BotDetectionPlaybackException) {
                    break
                }

                if (attemptIndex >= 1 && attemptIndex < attempts.lastIndex) {
                    if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
                        moe.rukamori.archivetune.playback.PlaybackTiming.log(
                            "FALLBACK_LADDER_RETRY_BOUNDED",
                            attemptId = moe.rukamori.archivetune.playback.PlaybackTiming.getAttemptId(videoId),
                            mediaId = videoId,
                            extra = "prevented_rerun=${attemptIndex + 1} err=${lastError?.javaClass?.simpleName}",
                        )
                    }
                    break
                }
            }
            throw lastError ?: IllegalStateException("Failed to resolve stream")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun playerResponseForDownload(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        networkMetered: Boolean? = null,
    ): Result<PlaybackData> {
        return try {
            Timber.tag(logTag).i("Fetching download response for videoId: $videoId, playlistId: $playlistId")
            var lastError: Throwable? = null

            for (preferredStreamClient in PlaybackClientSelector.downloadPreferredStreamClientAttempts) {
                val attemptResult =
                    playerResponseForPlayback(
                        videoId = videoId,
                        playlistId = playlistId,
                        audioQuality = audioQuality,
                        connectivityManager = connectivityManager,
                        preferredStreamClient = preferredStreamClient,
                        networkMetered = networkMetered,
                    )

                if (attemptResult.isSuccess) return Result.success(attemptResult.getOrThrow())

                lastError = attemptResult.exceptionOrNull()
                if (lastError is kotlinx.coroutines.CancellationException) {
                    throw lastError
                }
                Timber.tag(logTag).w(
                    lastError,
                    "Download stream resolution failed with preferred client %s for %s",
                    preferredStreamClient.name,
                    videoId,
                )
            }

            throw lastError ?: IllegalStateException("Failed to resolve download stream for $videoId")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    internal suspend fun playerResponseForPlaybackOnce(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferredStreamClient: PlayerStreamClient,
        networkMetered: Boolean?,
    ): PlaybackData {
        Timber.tag(logTag).i("Fetching player response for videoId: $videoId, playlistId: $playlistId")
        val fetchAttemptId = if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
            moe.rukamori.archivetune.playback.PlaybackTiming.getAttemptId(videoId)
        } else 0L
        val fetchStartNanos = if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
            moe.rukamori.archivetune.playback.PlaybackTiming.nowNanos()
        } else 0L
        val signatureTimestamp = PlaybackStreamUrlDecipher.getSignatureTimestampOrNull(videoId)
        Timber.tag(logTag).v("Signature timestamp: $signatureTimestamp")

        var authState = YouTube.currentPlaybackAuthState()
        val hasLoginCookie = authState.hasLoginCookie
        var canUseLoggedInPlayback = authState.hasPlaybackLoginContext
        if (!canUseLoggedInPlayback) {
            if (hasLoginCookie) {
                Timber.tag(logTag).w(
                    "Ignoring incomplete login context for %s because dataSyncId is missing; falling back to visitorData playback",
                    videoId,
                )
            }
            authState =
                PlaybackAuthCoordinator.ensureVisitorDataReady(
                    videoId = videoId,
                    authState = authState,
                    reason = if (hasLoginCookie) "cookie-only playback fallback" else "anonymous playback bootstrap",
                )
        }
        val sessionId = authState.visitorData
        val authStatus =
            when {
                canUseLoggedInPlayback -> "Logged in"
                hasLoginCookie -> "Cookie-only"
                else -> "Not logged in"
            }
        Timber.tag(logTag).v("Session authentication status: $authStatus (sessionId=${sessionId.orEmpty()})")

        if (moe.rukamori.archivetune.BuildConfig.DEBUG && canUseLoggedInPlayback) {
            val fastPathData =
                tryVisionOsFastPath(
                    videoId = videoId,
                    playlistId = playlistId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    networkMetered = networkMetered,
                    signatureTimestamp = signatureTimestamp,
                    authState = authState,
                    canUseLoggedInPlayback = canUseLoggedInPlayback,
                    fetchAttemptId = fetchAttemptId,
                    fetchStartNanos = fetchStartNanos,
                )
            if (fastPathData != null) {
                return fastPathData
            }
        }

        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null
        var streamClientUsed: YouTubeClient? = null
        var didRepairAuthAfterBotDetection = false

        val preferredYouTubeClient = PlaybackClientSelector.resolvePreferredPlaybackClient(preferredStreamClient, authState)
        if (
            preferredYouTubeClient == WEB_REMIX &&
            preferredStreamClient == PlayerStreamClient.ANDROID_VR &&
            canUseLoggedInPlayback
        ) {
            Timber.tag(logTag).i(
                "Promoting playback client to WEB_REMIX for %s because login and Web PoToken playback are available",
                videoId,
            )
        }

        val metadataClient = PlaybackClientSelector.resolveMetadataClient(authState)
        if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
            moe.rukamori.archivetune.playback.PlaybackTiming.log(
                "METADATA_CLIENT_SELECTED", attemptId = fetchAttemptId, mediaId = videoId,
                extra = "client=${PlaybackClientSelector.describeClient(metadataClient)}",
            )
        }

        Timber.tag(logTag).i("Fetching metadata response using client: ${metadataClient.clientName}")

        var metadataPoToken: String? = null
        if (metadataClient.useWebPoTokens && sessionId != null) {
            val cachedToken = StreamUrlCache.getCachedPoToken(sessionId)
            if (cachedToken != null) {
                Timber.tag(logTag).i("🔥 Using CACHED PoToken for metadata (saved ~10s)")
                metadataPoToken = cachedToken.playerToken
                YouTube.authState = YouTube.authState.copy(
                    poTokenGvs = cachedToken.sessionToken,
                    poTokenPlayer = cachedToken.playerToken,
                    webClientPoTokenEnabled = true,
                )
            } else {
                try {
                    Timber.tag(logTag).i("Generating NEW PoToken for metadata (heavy operation)")
                    val tokenResult = BotGuardTokenGenerator.mintToken(videoId, sessionId)
                    metadataPoToken = tokenResult?.playerToken
                    tokenResult?.let {
                        StreamUrlCache.cachePoToken(sessionId, it.playerToken, it.sessionToken)
                        YouTube.authState = YouTube.authState.copy(
                            poTokenGvs = it.sessionToken,
                            poTokenPlayer = it.playerToken,
                            webClientPoTokenEnabled = true,
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(logTag).w(e, "PoToken generation failed for metadata request")
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
                "METADATA_FETCH_END", attemptId = fetchAttemptId, mediaId = videoId,
                startNanos = metadataStartNanos,
                extra = "client=${PlaybackClientSelector.describeClient(metadataClient)} ok=${metadataResult.isSuccess}",
            )
        }
        val metadataFailure = metadataResult.exceptionOrNull()
        if (metadataFailure != null && canUseLoggedInPlayback && metadataFailure.isInvalidPlaybackLoginContextFailure()) {
            Timber.tag(logTag).w(
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
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(logTag).w(e, "PoToken generation failed for metadata retry request")
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
        var metadataPlayerResponse = metadataResult.getPlaybackPlayerResponseOrThrow(videoId, authState)
        var expectedDurationMs =
            metadataPlayerResponse.videoDetails
                ?.lengthSeconds
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.times(1000L)

        val orderedClients = PlaybackClientSelector.buildStreamClientOrder(preferredStreamClient, authState)
        if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
            moe.rukamori.archivetune.playback.PlaybackTiming.log(
                "STREAM_CLIENT_ORDER", attemptId = fetchAttemptId, mediaId = videoId,
                extra = "count=${orderedClients.size} first=${orderedClients.firstOrNull()?.let { PlaybackClientSelector.describeClient(it) }}",
            )
        }
        val streamClients =
            orderedClients.filterNot { client ->
                val blocked =
                    StreamUrlCache.isStreamClientTemporarilyBlocked(
                        videoId = videoId,
                        clientKey = StreamClientUtils.buildClientKey(client),
                        authFingerprint = authState.fingerprint,
                    )
                if (blocked) {
                    Timber.tag(logTag).w("Temporarily blocked stream client for $videoId: ${PlaybackClientSelector.describeClient(client)}")
                }
                blocked
            }

        val botDetectedClients = mutableSetOf<String>()
        var gateFailure: PlaybackErrorMapper.PlaybackGateFailure? = null

        fun authMode(): String =
            when {
                canUseLoggedInPlayback -> "logged-in"
                hasLoginCookie -> "cookie-only"
                else -> "visitor"
            }

        for ((index, candidateClient) in streamClients.withIndex()) {
            var client = candidateClient
            format = null
            streamUrl = null
            streamClientUsed = null
            streamExpiresInSeconds = null
            streamPlayerResponse = null
            val clientAttemptStartNanos = if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
                moe.rukamori.archivetune.playback.PlaybackTiming.nowNanos()
            } else 0L
            if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
                moe.rukamori.archivetune.playback.PlaybackTiming.log(
                    "STREAM_CLIENT_ATTEMPT_START", attemptId = fetchAttemptId, mediaId = videoId,
                    extra = "try=${index + 1}/${streamClients.size} client=${PlaybackClientSelector.describeClient(client)}",
                )
            }

            Timber.tag(logTag).v(
                "Trying ${if (client == PlaybackClientSelector.MAIN_CLIENT) "MAIN_CLIENT" else "fallback client"} ${index + 1}/${streamClients.size}: ${PlaybackClientSelector.describeClient(
                    client,
                )}",
            )

            if (client != PlaybackClientSelector.MAIN_CLIENT && client.loginRequired && !canUseLoggedInPlayback) {
                Timber.tag(logTag).i("Skipping client ${PlaybackClientSelector.describeClient(client)} - requires login but auth mode is ${authMode()}")
                if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
                    moe.rukamori.archivetune.playback.PlaybackTiming.log(
                        "STREAM_CLIENT_SKIPPED", attemptId = fetchAttemptId, mediaId = videoId,
                        startNanos = clientAttemptStartNanos,
                        extra = "try=${index + 1} client=${PlaybackClientSelector.describeClient(client)} reason=login-required",
                    )
                }
                continue
            }

            streamPlayerResponse =
                if (client == metadataClient) {
                    metadataPlayerResponse
                } else {
                    Timber.tag(logTag).i("Fetching player response for fallback client: ${PlaybackClientSelector.describeClient(client)}")
                    YouTube
                        .player(
                            videoId = videoId,
                            playlistId = playlistId,
                            client = client,
                            signatureTimestamp = signatureTimestamp,
                            setLogin = canUseLoggedInPlayback,
                            authState = authState,
                        ).getPlaybackPlayerResponseOrNull(videoId, authState)
                }

            if (streamPlayerResponse == null) {
                if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
                    moe.rukamori.archivetune.playback.PlaybackTiming.log(
                        "STREAM_CLIENT_FAILED", attemptId = fetchAttemptId, mediaId = videoId,
                        startNanos = clientAttemptStartNanos,
                        extra = "try=${index + 1} client=${PlaybackClientSelector.describeClient(client)} reason=empty-response",
                    )
                }
                continue
            }

            var playabilityStatus = streamPlayerResponse.playabilityStatus
            if (playabilityStatus.status != "OK") {
                var reason = playabilityStatus.reason.orEmpty()
                var isLoginRecovery = PlaybackErrorMapper.isLoginRecoveryError(playabilityStatus.status, reason)
                var isBotDetection = PlaybackErrorMapper.isBotDetectionError(reason)

                if (isBotDetection && !didRepairAuthAfterBotDetection) {
                    val botRepairStartNanos = if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
                        moe.rukamori.archivetune.playback.PlaybackTiming.nowNanos()
                    } else 0L
                    if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
                        moe.rukamori.archivetune.playback.PlaybackTiming.log(
                            "BOT_REPAIR_START", attemptId = fetchAttemptId, mediaId = videoId,
                            extra = "try=${index + 1} client=${PlaybackClientSelector.describeClient(client)}",
                        )
                    }
                    val repairedAuthState =
                        PlaybackAuthCoordinator.repairAuthStateAfterBotDetection(
                            videoId = videoId,
                            authState = authState,
                            reason = "bot-detection recovery on ${client.clientName}",
                        )
                    val shouldUseWebRemix =
                        repairedAuthState.hasPlaybackLoginContext &&
                            PlaybackAuthCoordinator.hasCompleteWebPlaybackPoToken(repairedAuthState) &&
                            client != WEB_REMIX

                    if (repairedAuthState.fingerprint != authState.fingerprint || shouldUseWebRemix) {
                        authState = repairedAuthState
                        canUseLoggedInPlayback = authState.hasPlaybackLoginContext
                        client = if (shouldUseWebRemix) WEB_REMIX else client
                        didRepairAuthAfterBotDetection = true
                        if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
                            moe.rukamori.archivetune.playback.PlaybackTiming.log(
                                "BOT_REPAIR_END", attemptId = fetchAttemptId, mediaId = videoId,
                                startNanos = botRepairStartNanos,
                                extra = "try=${index + 1} client=${PlaybackClientSelector.describeClient(client)}",
                            )
                        }
                        Timber.tag(logTag).i(
                            "Retrying %s for %s after repairing playback auth",
                            PlaybackClientSelector.describeClient(client),
                            videoId,
                        )
                        streamPlayerResponse =
                            YouTube
                                .player(
                                    videoId = videoId,
                                    playlistId = playlistId,
                                    client = client,
                                    signatureTimestamp = signatureTimestamp,
                                    setLogin = canUseLoggedInPlayback,
                                    authState = authState,
                                ).getPlaybackPlayerResponseOrNull(videoId, authState)

                        if (streamPlayerResponse == null) continue

                        playabilityStatus = streamPlayerResponse.playabilityStatus
                        reason = playabilityStatus.reason.orEmpty()
                        isLoginRecovery = PlaybackErrorMapper.isLoginRecoveryError(playabilityStatus.status, reason)
                        isBotDetection = PlaybackErrorMapper.isBotDetectionError(reason)
                    }
                }

                if (playabilityStatus.status == "OK") {
                    if (client == metadataClient) {
                        metadataPlayerResponse = streamPlayerResponse
                        expectedDurationMs =
                            metadataPlayerResponse.videoDetails
                                ?.lengthSeconds
                                ?.toLongOrNull()
                                ?.takeIf { it > 0 }
                                ?.times(1000L)
                    }
                    Timber.tag(logTag).i(
                        "Recovered playback with %s after auth repair",
                        PlaybackClientSelector.describeClient(client),
                    )
                } else {
                    val statusMessage =
                        "Player response status not OK for ${PlaybackClientSelector.describeClient(client)} [auth=${authMode()}]: " +
                            "${playabilityStatus.status}, reason: $reason, loginRecovery: $isLoginRecovery, botDetection: $isBotDetection"
                    if (isLoginRecovery) {
                        Timber.tag(logTag).i(statusMessage)
                    } else {
                        Timber.tag(logTag).w(statusMessage)
                    }
                    if (isLoginRecovery) {
                        gateFailure =
                            PlaybackErrorMapper.PlaybackGateFailure(
                                clientName = PlaybackClientSelector.describeClient(client),
                                status = playabilityStatus.status,
                                reason = playabilityStatus.reason,
                            )
                    } else if (isBotDetection) {
                        botDetectedClients.add(PlaybackClientSelector.describeClient(client))
                    }
                    if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
                        moe.rukamori.archivetune.playback.PlaybackTiming.log(
                            "STREAM_CLIENT_FAILED", attemptId = fetchAttemptId, mediaId = videoId,
                            startNanos = clientAttemptStartNanos,
                            extra = "try=${index + 1} client=${PlaybackClientSelector.describeClient(client)} reason=status-${playabilityStatus.status}",
                        )
                    }
                    continue
                }
            }

            val isMetered = networkMetered ?: connectivityManager.isActiveNetworkMetered
            val candidates =
                PlaybackFormatSelector.selectAudioFormatCandidates(
                    streamPlayerResponse,
                    audioQuality,
                    isMetered,
                )

            if (candidates.isEmpty()) {
                if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
                    moe.rukamori.archivetune.playback.PlaybackTiming.log(
                        "STREAM_CLIENT_FAILED", attemptId = fetchAttemptId, mediaId = videoId,
                        startNanos = clientAttemptStartNanos,
                        extra = "try=${index + 1} client=${PlaybackClientSelector.describeClient(client)} reason=no-candidates",
                    )
                }
                continue
            }

            var selectedFormat: PlayerResponse.StreamingData.Format? = null
            var selectedUrl: String? = null

            for (candidate in candidates) {
                if (canUseLoggedInPlayback && expectedDurationMs != null && PlaybackFormatSelector.isLikelyPreview(candidate, expectedDurationMs)) continue
                if (PlaybackFormatSelector.shouldSkipCipheredWebCandidate(client, candidate, authState)) continue
                val cacheKey = StreamUrlCache.buildStreamCacheKey(videoId, candidate.itag, client, authState.fingerprint)
                val cached = StreamUrlCache.getCachedStreamUrl(cacheKey)
                val candidateUrl =
                    if (cached != null && cached.expiresAtMs > System.currentTimeMillis() + StreamUrlCache.STREAM_URL_EXPIRY_SAFETY_MS) {
                        cached.url
                    } else {
                        PlaybackStreamUrlDecipher.findUrlOrNull(candidate, videoId, client, authState)
                    } ?: continue
                selectedFormat = candidate
                selectedUrl = candidateUrl
                break
            }

            if (selectedFormat == null || selectedUrl == null) {
                Timber.tag(logTag).w(
                    "No playable stream candidate resolved for %s at quality %s after checking %d formats",
                    PlaybackClientSelector.describeClient(client),
                    audioQuality,
                    candidates.size,
                )
                if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
                    moe.rukamori.archivetune.playback.PlaybackTiming.log(
                        "STREAM_CLIENT_FAILED", attemptId = fetchAttemptId, mediaId = videoId,
                        startNanos = clientAttemptStartNanos,
                        extra = "try=${index + 1} client=${PlaybackClientSelector.describeClient(client)} reason=no-url formats=${candidates.size}",
                    )
                }
                continue
            }

            format = selectedFormat
            streamUrl = selectedUrl
            streamClientUsed = client
            if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
                moe.rukamori.archivetune.playback.PlaybackTiming.log(
                    "STREAM_CLIENT_SUCCESS", attemptId = fetchAttemptId, mediaId = videoId,
                    startNanos = clientAttemptStartNanos,
                    extra = "try=${index + 1} client=${PlaybackClientSelector.describeClient(client)} itag=${selectedFormat.itag}",
                )
            }
            streamExpiresInSeconds =
                StreamUrlCache.resolveExpireSeconds(
                    apiExpire = streamPlayerResponse.streamingData?.expiresInSeconds,
                    streamUrl = selectedUrl,
                )

            Timber.tag(logTag).i("Format found: ${format.mimeType}, bitrate: ${format.bitrate}")
            Timber.tag(logTag).v("Stream expires in: $streamExpiresInSeconds seconds")

            StreamUrlCache.lastSuccessfulClientKey = StreamClientUtils.buildClientKey(client)
            break
        }

        if (streamPlayerResponse == null) {
            if (botDetectedClients.isNotEmpty()) {
                Timber.tag(logTag).e("Bot detection triggered on clients: $botDetectedClients - all clients failed")
                throw BotDetectionPlaybackException(
                    videoId = videoId,
                    clients = botDetectedClients.toSet(),
                )
            }
            gateFailure?.let { failure ->
                Timber.tag(logTag).w(
                    "Playback requires login recovery for $videoId via ${failure.clientName} (${failure.status}): ${failure.reason.orEmpty()}",
                )
                if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
                    moe.rukamori.archivetune.playback.PlaybackTiming.log(
                        "LOGIN_REQUIRED_RESOLVER_BUILT",
                        attemptId = fetchAttemptId,
                        mediaId = videoId,
                        extra = "status=${failure.status} reason=${failure.reason.orEmpty()}",
                    )
                }
                throw LoginRequiredForPlaybackException(
                    videoId = videoId,
                    targetUrl = "https://music.youtube.com/watch?v=$videoId",
                    reason = failure.reason,
                )
            }
            Timber.tag(logTag).e("Bad stream player response - all clients failed")
            throw BadStreamPlayerResponseException(videoId)
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val status = streamPlayerResponse.playabilityStatus.status
            val errorReason = streamPlayerResponse.playabilityStatus.reason.orEmpty()

            if (PlaybackErrorMapper.isBotDetectionError(errorReason)) {
                botDetectedClients.add(streamClientUsed?.let { PlaybackClientSelector.describeClient(it) } ?: "last client")
                throw BotDetectionPlaybackException(
                    videoId = videoId,
                    clients = botDetectedClients.toSet(),
                )
            }

            if (PlaybackErrorMapper.isLoginRecoveryError(status, errorReason)) {
                Timber.tag(logTag).w("Playback requires login recovery for $videoId: $errorReason")
                if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
                    moe.rukamori.archivetune.playback.PlaybackTiming.log(
                        "LOGIN_REQUIRED_RESOLVER_BUILT",
                        attemptId = fetchAttemptId,
                        mediaId = videoId,
                        extra = "status=$status reason=$errorReason",
                    )
                }
                throw LoginRequiredForPlaybackException(
                    videoId = videoId,
                    targetUrl = "https://music.youtube.com/watch?v=$videoId",
                    reason = errorReason,
                )
            }

            if (botDetectedClients.isNotEmpty()) {
                Timber.tag(logTag).e("Bot detection triggered on clients: $botDetectedClients")
                throw BotDetectionPlaybackException(
                    videoId = videoId,
                    clients = botDetectedClients.toSet(),
                )
            }

            gateFailure?.let { failure ->
                Timber.tag(logTag).w(
                    "Playback requires login recovery for $videoId via earlier client ${failure.clientName} (${failure.status}): ${failure.reason.orEmpty()}",
                )
                if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
                    moe.rukamori.archivetune.playback.PlaybackTiming.log(
                        "LOGIN_REQUIRED_RESOLVER_BUILT",
                        attemptId = fetchAttemptId,
                        mediaId = videoId,
                        extra = "status=${failure.status} reason=${failure.reason.orEmpty()}",
                    )
                }
                throw LoginRequiredForPlaybackException(
                    videoId = videoId,
                    targetUrl = "https://music.youtube.com/watch?v=$videoId",
                    reason = failure.reason,
                )
            }

            Timber.tag(logTag).e("Playability status not OK: $errorReason")
            throw PlaybackException(
                errorReason,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )
        }

        if (streamExpiresInSeconds == null) {
            streamExpiresInSeconds =
                StreamUrlCache.resolveExpireSeconds(
                    apiExpire = null,
                    streamUrl = streamUrl,
                )
        }

        if (format == null) {
            Timber
                .tag(
                    logTag,
                ).e(
                    "Could not find suitable format for quality: $audioQuality. Available formats from last client: ${streamPlayerResponse.streamingData?.adaptiveFormats?.filter {
                        it.isAudio
                    }?.map { "${it.mimeType} @ ${it.bitrate}bps (itag: ${it.itag})" }}",
                )
            throw Exception("Could not find format for quality: $audioQuality")
        }

        if (streamUrl == null) {
            Timber.tag(logTag).e("Could not find stream url for format: ${format.mimeType}, itag: ${format.itag}")
            throw Exception("Could not find stream url")
        }

        Timber.tag(logTag).i("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")

        val resolvedStreamClient =
            requireNotNull(streamClientUsed) {
                "No resolved stream client for validated playback URL"
            }
        if (moe.rukamori.archivetune.BuildConfig.DEBUG) {
            moe.rukamori.archivetune.playback.PlaybackTiming.log(
                "FINAL_CLIENT_USED", attemptId = fetchAttemptId, mediaId = videoId,
                startNanos = fetchStartNanos,
                extra = "client=${PlaybackClientSelector.describeClient(resolvedStreamClient)} itag=${format.itag}",
            )
        }

        StreamUrlCache.putCachedStreamUrl(
            StreamUrlCache.buildStreamCacheKey(videoId, format.itag, resolvedStreamClient, authState.fingerprint),
            streamUrl,
            streamExpiresInSeconds,
            authState.fingerprint,
        )

        val finalResponse = streamPlayerResponse ?: metadataPlayerResponse
        return PlaybackData(
            finalResponse.playerConfig?.audioConfig,
            finalResponse.videoDetails,
            finalResponse.playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
            authState.fingerprint,
        )
    }

    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
        authState: PlaybackAuthState = YouTube.currentPlaybackAuthState(),
    ): Result<PlayerResponse> {
        Timber.tag(logTag).i("Fetching metadata player response for videoId: $videoId")

        val signatureTimestamp = PlaybackStreamUrlDecipher.getSignatureTimestampOrNull(videoId)
        val sessionId = authState.visitorData
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
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(logTag).w(e, "PoToken generation failed for metadata request")
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
                authState = authState,
            ).onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private suspend fun tryVisionOsFastPath(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        networkMetered: Boolean?,
        signatureTimestamp: Int?,
        authState: PlaybackAuthState,
        canUseLoggedInPlayback: Boolean,
        fetchAttemptId: Long,
        fetchStartNanos: Long,
    ): PlaybackData? {
        val fastPathStartNanos = moe.rukamori.archivetune.playback.PlaybackTiming.nowNanos()
        moe.rukamori.archivetune.playback.PlaybackTiming.log(
            "FAST_PATH_START",
            attemptId = fetchAttemptId,
            mediaId = videoId,
            startNanos = fastPathStartNanos,
            extra = "status=START client=VISIONOS",
        )

        var fallbackReason = "unknown"
        try {
            val response =
                YouTube
                    .player(
                        videoId = videoId,
                        playlistId = playlistId,
                        client = VISIONOS,
                        signatureTimestamp = signatureTimestamp,
                        setLogin = canUseLoggedInPlayback,
                        authState = authState,
                    ).getPlaybackPlayerResponseOrNull(videoId, authState)

            if (response == null) {
                fallbackReason = "null_response"
            } else if (response.playabilityStatus.status != "OK") {
                val reason = response.playabilityStatus.reason.orEmpty()
                fallbackReason = when {
                    PlaybackErrorMapper.isBotDetectionError(reason) -> "bot_detection"
                    PlaybackErrorMapper.isLoginRecoveryError(reason) -> "login_recovery"
                    else -> "status_${response.playabilityStatus.status}"
                }
            } else if ((response.videoDetails?.lengthSeconds?.toLongOrNull() ?: 0L) <= 0L) {
                fallbackReason = "invalid_duration"
            } else {
                val expectedDurationMs = (response.videoDetails?.lengthSeconds?.toLongOrNull() ?: 0L) * 1000L
                val isMetered = networkMetered ?: connectivityManager.isActiveNetworkMetered
                val candidates =
                    PlaybackFormatSelector.selectAudioFormatCandidates(
                        response,
                        audioQuality,
                        isMetered,
                    )

                if (candidates.isEmpty()) {
                    fallbackReason = "no_audio_candidates"
                } else {
                    var selectedFormat: PlayerResponse.StreamingData.Format? = null
                    var selectedUrl: String? = null

                    for (candidate in candidates) {
                        if (PlaybackFormatSelector.isLikelyPreview(candidate, expectedDurationMs)) continue
                        if (PlaybackFormatSelector.shouldSkipCipheredWebCandidate(VISIONOS, candidate, authState)) continue

                        val cacheKey = StreamUrlCache.buildStreamCacheKey(videoId, candidate.itag, VISIONOS, authState.fingerprint)
                        val cached = StreamUrlCache.getCachedStreamUrl(cacheKey)
                        val candidateUrl =
                            if (cached != null && cached.expiresAtMs > System.currentTimeMillis() + StreamUrlCache.STREAM_URL_EXPIRY_SAFETY_MS) {
                                cached.url
                            } else {
                                PlaybackStreamUrlDecipher.findUrlOrNull(candidate, videoId, VISIONOS, authState)
                            } ?: continue

                        selectedFormat = candidate
                        selectedUrl = candidateUrl
                        break
                    }

                    if (selectedFormat == null || selectedUrl == null) {
                        fallbackReason = "no_playable_url"
                    } else {
                        val streamExpiresInSeconds =
                            StreamUrlCache.resolveExpireSeconds(
                                apiExpire = response.streamingData?.expiresInSeconds,
                                streamUrl = selectedUrl,
                            )

                        StreamUrlCache.putCachedStreamUrl(
                            StreamUrlCache.buildStreamCacheKey(videoId, selectedFormat.itag, VISIONOS, authState.fingerprint),
                            selectedUrl,
                            streamExpiresInSeconds,
                            authState.fingerprint,
                        )

                        moe.rukamori.archivetune.playback.PlaybackTiming.log(
                            "FAST_PATH_SUCCESS",
                            attemptId = fetchAttemptId,
                            mediaId = videoId,
                            startNanos = fastPathStartNanos,
                            extra = "status=OK itag=${selectedFormat.itag}",
                        )
                        moe.rukamori.archivetune.playback.PlaybackTiming.log(
                            "FAST_PATH_TOTAL",
                            attemptId = fetchAttemptId,
                            mediaId = videoId,
                            startNanos = fastPathStartNanos,
                            extra = "status=SUCCESS",
                        )
                        moe.rukamori.archivetune.playback.PlaybackTiming.log(
                            "FINAL_CLIENT_USED",
                            attemptId = fetchAttemptId,
                            mediaId = videoId,
                            startNanos = fetchStartNanos,
                            extra = "client=${PlaybackClientSelector.describeClient(VISIONOS)} itag=${selectedFormat.itag}",
                        )

                        return PlaybackData(
                            response.playerConfig?.audioConfig,
                            response.videoDetails,
                            response.playbackTracking,
                            selectedFormat,
                            selectedUrl,
                            streamExpiresInSeconds,
                            authState.fingerprint,
                        )
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.tag(logTag).w(e, "VISIONOS fast path encountered unexpected error for %s", videoId)
            fallbackReason = "exception_${e.javaClass.simpleName}"
        }

        moe.rukamori.archivetune.playback.PlaybackTiming.log(
            "FAST_PATH_FALLBACK",
            attemptId = fetchAttemptId,
            mediaId = videoId,
            startNanos = fastPathStartNanos,
            extra = "status=FALLBACK reason=$fallbackReason",
        )
        moe.rukamori.archivetune.playback.PlaybackTiming.log(
            "FAST_PATH_TOTAL",
            attemptId = fetchAttemptId,
            mediaId = videoId,
            startNanos = fastPathStartNanos,
            extra = "status=FALLBACK",
        )
        return null
    }
}
