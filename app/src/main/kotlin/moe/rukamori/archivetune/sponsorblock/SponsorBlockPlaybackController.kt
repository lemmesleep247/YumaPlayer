/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.sponsorblock

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.constants.PlaybackSource
import moe.rukamori.archivetune.utils.isLocalMediaId
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SponsorBlockPlaybackController
    @Inject
    constructor(
        private val observeSettings: ObserveSponsorBlockSettingsUseCase,
        private val getSegments: GetSponsorBlockSegmentsUseCase,
    ) {
        private val currentMediaId = MutableStateFlow<String?>(null)
        private var attachedPlayer: Player? = null
        private var observationJob: Job? = null
        private var playbackSourceProvider: (String) -> PlaybackSource = { PlaybackSource.FLAC }
        private var isTransitionActive: () -> Boolean = { false }
        internal var clock: () -> Long = System::currentTimeMillis

        @Volatile
        private var isInternalSeek = false

        @Volatile
        private var seekCooldownUntilMs = 0L

        @Volatile
        private var lastSkippedEndMs: Long? = null

        private val playerListener =
            object : Player.Listener {
                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    lastSkippedEndMs = null
                    currentMediaId.value = mediaItem.normalizedMediaId()
                }

                override fun onTimelineChanged(
                    timeline: Timeline,
                    reason: Int,
                ) {
                    currentMediaId.value = attachedPlayer?.currentMediaItem.normalizedMediaId()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    val isSeek =
                        reason == Player.DISCONTINUITY_REASON_SEEK ||
                            reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                    if (isSeek) {
                        if (isInternalSeek) {
                            isInternalSeek = false
                        } else {
                            lastSkippedEndMs = null
                            seekCooldownUntilMs = clock() + SEEK_COOLDOWN_MILLIS
                        }
                    }
                }
            }

        fun attach(
            player: Player,
            scope: CoroutineScope,
            playbackSourceProvider: (mediaId: String) -> PlaybackSource,
            isTransitionActive: () -> Boolean = { false },
        ) {
            if (attachedPlayer === player && observationJob?.isActive == true) return
            detach()

            attachedPlayer = player
            this.playbackSourceProvider = playbackSourceProvider
            this.isTransitionActive = isTransitionActive
            currentMediaId.value = player.currentMediaItem.normalizedMediaId()
            player.addListener(playerListener)
            observationJob =
                scope.launch {
                    try {
                        combine(
                            observeSettings(),
                            currentMediaId,
                        ) { settings, mediaId -> settings to mediaId }
                            .collectLatest { (settings, mediaId) ->
                                val activeMediaId = mediaId ?: return@collectLatest
                                if (!settings.enabled || settings.categories.isEmpty()) return@collectLatest

                                val source = playbackSourceProvider(activeMediaId)
                                if (
                                    source != PlaybackSource.YT_MUSIC ||
                                    activeMediaId.isLocalMediaId() ||
                                    !YOUTUBE_VIDEO_ID.matches(activeMediaId)
                                ) {
                                    return@collectLatest
                                }

                                val segments =
                                    try {
                                        getSegments(
                                            videoId = activeMediaId,
                                            settings = settings,
                                            source = source,
                                        )
                                    } catch (cancellation: CancellationException) {
                                        throw cancellation
                                    } catch (throwable: Throwable) {
                                        Timber
                                            .tag(TAG)
                                            .w(throwable, "SponsorBlock segment lookup failed")
                                        return@collectLatest
                                    }

                                if (segments.isEmpty() || currentMediaId.value != activeMediaId) {
                                    return@collectLatest
                                }

                                monitorPlayback(
                                    player = player,
                                    mediaId = activeMediaId,
                                    segments = segments,
                                )
                            }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        Timber.tag(TAG).w(throwable, "SponsorBlock settings observation failed")
                    }
                }
        }

        fun detach() {
            observationJob?.cancel()
            observationJob = null
            attachedPlayer?.removeListener(playerListener)
            attachedPlayer = null
            currentMediaId.value = null
            lastSkippedEndMs = null
            isInternalSeek = false
            seekCooldownUntilMs = 0L
        }

        private suspend fun monitorPlayback(
            player: Player,
            mediaId: String,
            segments: List<SponsorBlockSegment>,
        ) {
            lastSkippedEndMs = null
            while (
                currentCoroutineContext().isActive &&
                attachedPlayer === player &&
                player.currentMediaItem.normalizedMediaId() == mediaId
            ) {
                val currentSource = playbackSourceProvider(mediaId)
                if (
                    currentSource != PlaybackSource.YT_MUSIC ||
                    mediaId.isLocalMediaId() ||
                    !YOUTUBE_VIDEO_ID.matches(mediaId)
                ) {
                    return
                }

                if (!player.isPlaying || player.playbackState != Player.STATE_READY) {
                    delay(INACTIVE_POLL_INTERVAL_MILLIS)
                    continue
                }

                if (isTransitionActive()) {
                    delay(ACTIVE_POLL_INTERVAL_MILLIS)
                    continue
                }

                val now = clock()
                if (now < seekCooldownUntilMs) {
                    delay(ACTIVE_POLL_INTERVAL_MILLIS)
                    continue
                }

                val positionMs = player.currentPosition.coerceAtLeast(0L)
                val activeSegment =
                    segments.firstOrNull { segment ->
                        positionMs >= segment.startMs &&
                            positionMs < segment.endMs - END_TOLERANCE_MILLIS
                    }

                if (activeSegment == null) {
                    lastSkippedEndMs = null
                } else if (lastSkippedEndMs != activeSegment.endMs) {
                    lastSkippedEndMs = activeSegment.endMs
                    val knownDuration =
                        player.duration.takeIf { duration ->
                            duration != C.TIME_UNSET && duration > 0L
                        }
                    val targetPositionMs =
                        knownDuration?.let { duration -> activeSegment.endMs.coerceAtMost(duration) }
                            ?: activeSegment.endMs
                    if (targetPositionMs - positionMs >= MIN_SEEK_DISTANCE_MILLIS) {
                        if (
                            attachedPlayer === player &&
                            player.currentMediaItem.normalizedMediaId() == mediaId &&
                            playbackSourceProvider(mediaId) == PlaybackSource.YT_MUSIC &&
                            !isTransitionActive()
                        ) {
                            isInternalSeek = true
                            player.seekTo(targetPositionMs)
                        }
                    }
                }
                delay(ACTIVE_POLL_INTERVAL_MILLIS)
            }
        }

        private fun MediaItem?.normalizedMediaId(): String? =
            this
                ?.mediaId
                ?.trim()
                ?.takeIf(String::isNotEmpty)

        private companion object {
            const val TAG = "SponsorBlock"
            val YOUTUBE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
            const val ACTIVE_POLL_INTERVAL_MILLIS = 200L
            const val INACTIVE_POLL_INTERVAL_MILLIS = 750L
            const val END_TOLERANCE_MILLIS = 50L
            const val MIN_SEEK_DISTANCE_MILLIS = 100L
            const val SEEK_COOLDOWN_MILLIS = 1_500L
        }
    }
