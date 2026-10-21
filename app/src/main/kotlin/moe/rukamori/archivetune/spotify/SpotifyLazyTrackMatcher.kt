/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.spotify

import androidx.media3.common.MediaItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.spotify.models.SpotifyTrack

internal object SpotifyQueuePolicy {
    const val SOURCE_PAGE_SIZE = 50
    const val MATCH_BATCH_SIZE = 20
    const val INITIAL_BEFORE = 5
    const val INITIAL_AFTER = 10
}

/**
 * Owns the loaded Spotify source order and both matching cursors.
 *
 * Source pagination is intentionally outside this class: queues append fetched pages via [append],
 * while [cursor] and [backwardCursor] track which loaded entries have been resolved to MediaItems
 * ahead of and behind the initial window.
 */
internal class SpotifyLazyTrackMatcher(
    private val resolveTrack: suspend (SpotifyTrack) -> MediaItem?,
    private val initialBefore: Int = SpotifyQueuePolicy.INITIAL_BEFORE,
    private val initialAfter: Int = SpotifyQueuePolicy.INITIAL_AFTER,
    private val batchSize: Int = SpotifyQueuePolicy.MATCH_BATCH_SIZE,
) {
    data class InitialMatch(
        val items: List<MediaItem>,
        val mediaItemIndex: Int,
        val failedCount: Int,
    )

    data class BatchMatch(
        val items: List<MediaItem>,
        val failedCount: Int,
    )

    private val loaded = mutableListOf<SpotifyTrack>()

    @Volatile
    private var loadedCount = 0

    @Volatile
    private var cursor = 0

    @Volatile
    private var backwardCursor = 0

    @Volatile
    private var generation = 0L

    val contextGeneration: Long
        get() = generation

    val hasUnmatchedPreviousTracks: Boolean
        get() = backwardCursor > 0

    val loadedTrackCount: Int
        get() = loadedCount

    val hasUnmatchedLoadedTracks: Boolean
        get() = cursor < loadedCount

    fun reset(tracks: List<SpotifyTrack> = emptyList()) {
        loaded.clear()
        tracks.forEach { track -> if (!track.isLocal) loaded += track }
        loadedCount = loaded.size
        cursor = 0
        backwardCursor = 0
        generation += 1
    }

    fun append(tracks: List<SpotifyTrack>) {
        tracks.forEach { track -> if (!track.isLocal) loaded += track }
        loadedCount = loaded.size
    }

    fun containsLoadedTrack(anchorId: String?): Boolean =
        anchorId != null && loaded.any { it.id == anchorId }

    fun targetIndex(requestedIndex: Int, anchorId: String?): Int {
        if (loaded.isEmpty()) return 0
        if (anchorId != null) {
            if (requestedIndex in loaded.indices && loaded[requestedIndex].id == anchorId) return requestedIndex
            val found = loaded.indexOfFirst { it.id == anchorId }
            if (found >= 0) return found
        }
        return requestedIndex.coerceIn(loaded.indices)
    }

    suspend fun matchInitial(
        targetIndex: Int,
        preloadedItem: MediaMetadata?,
    ): InitialMatch {
        if (loaded.isEmpty()) return InitialMatch(emptyList(), 0, 0)

        val target = targetIndex.coerceIn(loaded.indices)
        val windowStart = (target - initialBefore).coerceAtLeast(0)
        val windowEnd = (target + initialAfter + 1).coerceAtMost(loaded.size)
        val resolved = resolveRange(windowStart, windowEnd, target, preloadedItem)

        cursor = windowEnd
        backwardCursor = windowStart

        if (resolved.isEmpty()) {
            return InitialMatch(emptyList(), 0, windowEnd - windowStart)
        }

        val mediaItemIndex =
            resolved.indexOfFirst { it.first == target }
                .takeIf { it >= 0 }
                ?: resolved.indexOfFirst { it.first > target }.takeIf { it >= 0 }
                ?: resolved.lastIndex

        return InitialMatch(
            items = resolved.map { it.second },
            mediaItemIndex = mediaItemIndex,
            failedCount = (windowEnd - windowStart) - resolved.size,
        )
    }

    suspend fun matchNext(): BatchMatch {
        val start = cursor
        if (start >= loaded.size) return BatchMatch(emptyList(), 0)

        val end = (start + batchSize).coerceAtMost(loaded.size)
        val resolved = resolveRange(start, end, null, null)
        cursor = end

        return BatchMatch(
            items = resolved.map { it.second },
            failedCount = (end - start) - resolved.size,
        )
    }

    suspend fun matchPrevious(): BatchMatch {
        val end = backwardCursor
        if (end <= 0) return BatchMatch(emptyList(), 0)

        val start = (end - batchSize).coerceAtLeast(0)
        val resolved = resolveRange(start, end, null, null)

        backwardCursor = start

        return BatchMatch(
            items = resolved.map { it.second },
            failedCount = (end - start) - resolved.size,
        )
    }

    private suspend fun resolveRange(
        start: Int,
        end: Int,
        preloadedGlobalIndex: Int?,
        preloadedItem: MediaMetadata?,
    ): List<Pair<Int, MediaItem>> =
        buildList {
            (start until end).chunked(batchSize).forEach { indices ->
                val resolvedChunk =
                    coroutineScope {
                        indices
                            .map { index ->
                                async {
                                    val item =
                                        if (index == preloadedGlobalIndex && preloadedItem != null) {
                                            preloadedItem.toMediaItem()
                                        } else {
                                            resolveTrack(loaded[index])
                                        }
                                    item?.let { index to it }
                                }
                            }.awaitAll()
                            .filterNotNull()
                    }
                addAll(resolvedChunk)
            }
        }
}
