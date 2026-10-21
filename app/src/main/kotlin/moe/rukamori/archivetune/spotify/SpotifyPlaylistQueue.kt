/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.spotify

import androidx.media3.common.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.SpotifyQueue
import moe.rukamori.archivetune.spotify.models.SpotifyPaging
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistTrack
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import timber.log.Timber

open class SpotifyPlaylistQueue(
    private val playlistId: String,
    private val title: String? = null,
    private val initialTracks: List<SpotifyTrack> = emptyList(),
    private val startIndex: Int = 0,
    override val preloadItem: MediaMetadata? = null,
    private val totalCount: Int? = null,
    private val hasCustomOrder: Boolean = false,
) : SpotifyQueue {
    override val isContextQueue: Boolean = true

    @Volatile
    override var isContextLoading: Boolean = false
        protected set

    @Volatile
    override var isLoadFailed: Boolean = false
        protected set

    @Volatile
    private var isInitialized: Boolean = false

    override val hasPendingContextItems: Boolean
        get() = !isInitialized || hasUnmatchedWork()

    override val isFullyLoaded: Boolean
        get() = isInitialized && !isContextLoading && !hasPendingContextItems && !isLoadFailed

    override fun hasNextPage(): Boolean = isInitialized && !isLoadFailed && hasUnmatchedWork()

    private val pagingMutex = Mutex()
    private val matcher = SpotifyLazyTrackMatcher(resolveTrack = { track -> resolveToMediaItem(track) })

    private var apiFetchOffset = 0
    private var apiTotal = 0

    @Volatile
    private var apiHasMore = true

    protected open suspend fun resolveToMediaItem(track: SpotifyTrack): MediaItem? =
        SpotifyPlaybackResolver.resolveToMediaItem(track)

    protected open suspend fun fetchPlaylistPage(
        offset: Int,
        limit: Int,
    ): SpotifyPaging<SpotifyPlaylistTrack> =
        Spotify
            .playlistTracks(
                playlistId = playlistId,
                limit = limit,
                offset = offset,
            ).getOrThrow()

    override suspend fun getInitialStatus(): Queue.Status =
        withContext(Dispatchers.IO) {
            pagingMutex.withLock {
                isContextLoading = true
                isLoadFailed = false
                try {
                    resetSource()
                    seedSource()
                    val anchorId = initialTracks.getOrNull(startIndex)?.id
                    loadUntilTargetReached(anchorId)

                    if (matcher.loadedTrackCount == 0) {
                        isLoadFailed = true
                        Timber.tag("SpotifyPipeline").w("No tracks found for initial status")
                        return@withContext Queue.Status(title = title, items = emptyList(), mediaItemIndex = 0)
                    }

                    val targetIndex = matcher.targetIndex(startIndex, anchorId)
                    val match = matcher.matchInitial(targetIndex = targetIndex, preloadedItem = preloadItem)

                    if (match.items.isEmpty()) {
                        isLoadFailed = true
                        Timber.tag("SpotifyPipeline").w(
                            "Could not resolve any track for initial status (failed=${match.failedCount})"
                        )
                        return@withContext Queue.Status(title = title, items = emptyList(), mediaItemIndex = 0)
                    }

                    if (match.failedCount > 0) {
                        Timber.tag("SpotifyPipeline").w(
                            "Initial window skipped ${match.failedCount} unresolved tracks"
                        )
                    }
                    Timber.tag("SpotifyPipeline").d(
                        "Initial status resolved ${match.items.size} tracks " +
                            "(targetIndex=$targetIndex, mediaItemIndex=${match.mediaItemIndex}, " +
                            "loaded=${matcher.loadedTrackCount}, total=$apiTotal)"
                    )

                    Queue.Status(
                        title = title,
                        items = match.items,
                        mediaItemIndex = match.mediaItemIndex,
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    isLoadFailed = true
                    Timber.tag("SpotifyPipeline").e(e, "Failed initial status in SpotifyPlaylistQueue")
                    Queue.Status(title = title, items = emptyList(), mediaItemIndex = 0)
                } finally {
                    isContextLoading = false
                    isInitialized = true
                }
            }
        }

    override suspend fun nextPage(): List<MediaItem> =
        withContext(Dispatchers.IO) {
            pagingMutex.withLock {
                if (isLoadFailed || !isInitialized || !hasUnmatchedWork()) return@withContext emptyList()

                isContextLoading = true
                try {
                    if (!matcher.hasUnmatchedLoadedTracks && apiHasMore) {
                        fetchNextApiPage()
                    }

                    val batch = matcher.matchNext()
                    if (batch.failedCount > 0) {
                        Timber.tag("SpotifyPipeline").w(
                            "nextPage skipped ${batch.failedCount} unresolved tracks"
                        )
                    }
                    Timber.tag("SpotifyPipeline").d(
                        "nextPage resolved ${batch.items.size} tracks " +
                            "(loaded=${matcher.loadedTrackCount}, apiOffset=$apiFetchOffset)"
                    )
                    batch.items
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    isLoadFailed = true
                    Timber.tag("SpotifyPipeline").e(e, "Failed nextPage in SpotifyPlaylistQueue")
                    emptyList()
                } finally {
                    isContextLoading = false
                }
            }
        }

    override fun hasPreviousPage(): Boolean =
        isInitialized &&
            !isLoadFailed &&
            matcher.hasUnmatchedPreviousTracks

    override val contextGeneration: Long
        get() = matcher.contextGeneration

    override suspend fun previousPage(): SpotifyQueue.PreviousPage =
        withContext(Dispatchers.IO) {
            pagingMutex.withLock {
                if (!hasPreviousPage()) {
                    return@withContext SpotifyQueue.PreviousPage(emptyList(), matcher.contextGeneration)
                }

                try {
                    val batch = matcher.matchPrevious()
                    if (batch.failedCount > 0) {
                        Timber.tag("SpotifyPipeline").w(
                            "previousPage skipped ${batch.failedCount} unresolved tracks"
                        )
                    }
                    Timber.tag("SpotifyPipeline").d(
                        "previousPage resolved ${batch.items.size} tracks " +
                            "(loaded=${matcher.loadedTrackCount})"
                    )
                    SpotifyQueue.PreviousPage(batch.items, matcher.contextGeneration)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    isLoadFailed = true
                    Timber.tag("SpotifyPipeline").e(e, "Failed previousPage in SpotifyPlaylistQueue")
                    SpotifyQueue.PreviousPage(emptyList(), matcher.contextGeneration)
                }
            }
        }

    private fun hasUnmatchedWork(): Boolean =
        !isLoadFailed && (matcher.hasUnmatchedLoadedTracks || apiHasMore)

    private fun resetSource() {
        matcher.reset()
        apiFetchOffset = 0
        apiTotal = 0
        apiHasMore = true
    }

    private suspend fun seedSource() {
        if (initialTracks.isNotEmpty()) {
            matcher.append(initialTracks)
            apiTotal = totalCount ?: initialTracks.size
            apiFetchOffset = initialTracks.size
            apiHasMore = !hasCustomOrder && apiFetchOffset < apiTotal
        } else {
            fetchNextApiPage()
        }
    }

    private suspend fun loadUntilTargetReached(anchorId: String?) {
        while (apiHasMore) {
            val targetReached =
                matcher.containsLoadedTrack(anchorId) || matcher.loadedTrackCount > startIndex
            if (targetReached) return

            val previousCount = matcher.loadedTrackCount
            fetchNextApiPage()
            if (matcher.loadedTrackCount <= previousCount) break
        }
    }

    private suspend fun fetchNextApiPage() {
        if (!apiHasMore) return
        val result = fetchPlaylistPage(offset = apiFetchOffset, limit = SpotifyQueuePolicy.SOURCE_PAGE_SIZE)
        apiTotal = result.total
        val fetched = result.items.mapNotNull { it.track?.takeUnless(SpotifyTrack::isLocal) }
        matcher.append(fetched)
        val rawCount = result.items.size
        apiFetchOffset += rawCount
        if (rawCount == 0) {
            if (apiFetchOffset < apiTotal) {
                isLoadFailed = true
                Timber.tag("SpotifyPipeline").e(
                    "Empty API page with offset $apiFetchOffset < total $apiTotal"
                )
            }
            apiHasMore = false
        } else {
            apiHasMore = apiFetchOffset < apiTotal
        }
    }
}
