/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.spotify

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpotifyLazyTrackMatcherTest {

    @Before
    fun setup() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val value = firstArg<String>()
            mockk<Uri>(relaxed = true).also { uri ->
                every { uri.toString() } returns value
            }
        }
        mockkConstructor(Bundle::class)
        every { anyConstructed<Bundle>().putBoolean(any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun matchInitial_resolvesOnlyBoundedWindowAroundSelectedTrack() = runTest {
        val requested = mutableListOf<String>()
        val matcher = matcher(requested)
        matcher.reset((0 until 1300).map(::track))

        val match = matcher.matchInitial(targetIndex = 650, preloadedItem = null)

        assertEquals(16, requested.size)
        assertEquals((645..660).map { "sp-$it" }, requested)
        assertEquals((645..660).map { "yt-sp-$it" }, match.items.map(MediaItem::mediaId))
        assertEquals(5, match.mediaItemIndex)
        assertEquals(0, match.failedCount)
        assertTrue(matcher.hasUnmatchedLoadedTracks)
    }

    @Test
    fun matchInitial_atCollectionEnd_hasNoForwardTail() = runTest {
        val requested = mutableListOf<String>()
        val matcher = matcher(requested)
        matcher.reset((0 until 1300).map(::track))

        val match = matcher.matchInitial(targetIndex = 1299, preloadedItem = null)

        assertEquals((1294..1299).map { "sp-$it" }, requested)
        assertEquals(6, match.items.size)
        assertEquals(5, match.mediaItemIndex)
        assertFalse(matcher.hasUnmatchedLoadedTracks)
    }

    @Test
    fun matchInitial_reusesPreloadedItemWithoutResolvingTarget() = runTest {
        val requested = mutableListOf<String>()
        val matcher = matcher(requested)
        matcher.reset((0 until 1300).map(::track))
        val preload = metadata("preload-sp-650")

        val match = matcher.matchInitial(targetIndex = 650, preloadedItem = preload)

        assertFalse("sp-650" in requested)
        assertEquals(15, requested.size)
        assertEquals(preload.toMediaItem().mediaId, match.items[match.mediaItemIndex].mediaId)
    }

    @Test
    fun matchInitial_partialFailuresSkipDeterministicallyAndKeepTargetStable() = runTest {
        val requested = mutableListOf<String>()
        val matcher = matcher(requested, failedIndices = setOf(0, 1, 2, 3))
        matcher.reset((0 until 100).map(::track))

        val match = matcher.matchInitial(targetIndex = 0, preloadedItem = null)

        assertEquals(7, match.items.size)
        assertEquals(4, match.failedCount)
        assertEquals("sp-3", requested[3])
        assertEquals("yt-sp-4", match.items[match.mediaItemIndex].mediaId)
    }

    @Test
    fun matchInitial_allFailuresReportEmptyWindow() = runTest {
        val matcher = matcher(mutableListOf(), failedIndices = (0 until 100).toSet())
        matcher.reset((0 until 100).map(::track))

        val match = matcher.matchInitial(targetIndex = 10, preloadedItem = null)

        assertTrue(match.items.isEmpty())
        assertEquals(16, match.failedCount)
    }

    @Test
    fun matchNext_preservesSourceOrderWithoutDuplicates() = runTest {
        val matcher = matcher(mutableListOf())
        matcher.reset((0 until 1300).map(::track))
        matcher.matchInitial(targetIndex = 650, preloadedItem = null)

        val resolved = buildList {
            repeat(4) {
                addAll(matcher.matchNext().items)
            }
        }

        assertEquals((661..740).map { "yt-sp-$it" }, resolved.map(MediaItem::mediaId))
        assertEquals(resolved.size, resolved.map(MediaItem::mediaId).distinct().size)
        assertTrue(matcher.hasUnmatchedLoadedTracks)
    }

    @Test
    fun matchNext_fullFailureAdvanceCursorAndTerminates() = runTest {
        var failTail = false
        val matcher =
            SpotifyLazyTrackMatcher(
                resolveTrack = { track ->
                    val index = track.id.removePrefix("sp-").toInt()
                    if (failTail) null else mediaItem(track)
                },
            )
        matcher.reset((0 until 45).map(::track))
        matcher.matchInitial(targetIndex = 0, preloadedItem = null)

        failTail = true
        val firstBatch = matcher.matchNext()
        assertTrue(firstBatch.items.isEmpty())
        assertEquals(20, firstBatch.failedCount)
        assertTrue(matcher.hasUnmatchedLoadedTracks)

        val lastBatch = matcher.matchNext()
        assertTrue(lastBatch.items.isEmpty())
        assertEquals(14, lastBatch.failedCount)
        assertFalse(matcher.hasUnmatchedLoadedTracks)

        val exhausted = matcher.matchNext()
        assertTrue(exhausted.items.isEmpty())
        assertFalse(matcher.hasUnmatchedLoadedTracks)
    }

    @Test
    fun matchInitial_cancellationLeavesCursorUnadvanced() = runTest {
        val started = CompletableDeferred<Unit>()
        val matcher =
            SpotifyLazyTrackMatcher(
                resolveTrack = {
                    started.complete(Unit)
                    awaitCancellation()
                },
            )
        matcher.reset((0 until 100).map(::track))

        val job = launch(Dispatchers.Default) { matcher.matchInitial(0, null) }
        started.await()
        job.cancelAndJoin()

        assertFalse(job.isActive)
        assertTrue(matcher.hasUnmatchedLoadedTracks)
    }

    @Test
    fun matchPrevious_walksBackwardInSourceOrderAndStopsAtBeginning() = runTest {
        val matcher = matcher(mutableListOf())
        matcher.reset((0 until 1300).map(::track))
        matcher.matchInitial(targetIndex = 650, preloadedItem = null)

        assertTrue(matcher.hasUnmatchedPreviousTracks)

        val firstBatch = matcher.matchPrevious()
        assertEquals((625..644).map { "yt-sp-$it" }, firstBatch.items.map(MediaItem::mediaId))
        assertEquals(0, firstBatch.failedCount)

        val batches = mutableListOf(firstBatch.items.map(MediaItem::mediaId))
        while (matcher.hasUnmatchedPreviousTracks) {
            val batch = matcher.matchPrevious().items.map(MediaItem::mediaId)
            assertTrue("backward pagination did not terminate", batches.size < 100)
            batches += batch
        }

        batches.forEach { batch ->
            assertEquals(batch.sortedBy { it.removePrefix("yt-sp-").toInt() }, batch)
        }
        val finalPlaylistOrder = batches.asReversed().flatten()
        assertEquals((0..644).map { "yt-sp-$it" }, finalPlaylistOrder)
        assertEquals(finalPlaylistOrder.size, finalPlaylistOrder.distinct().size)
    }

    @Test
    fun matchPrevious_beforeInitialWindowAndForwardTailStayDisjoint() = runTest {
        val matcher = matcher(mutableListOf())
        matcher.reset((0 until 1300).map(::track))
        matcher.matchInitial(targetIndex = 650, preloadedItem = null)

        val backward = matcher.matchPrevious().items.map(MediaItem::mediaId)
        val forward = matcher.matchNext().items.map(MediaItem::mediaId)

        assertEquals((625..644).map { "yt-sp-$it" }, backward)
        assertEquals((661..680).map { "yt-sp-$it" }, forward)
        assertTrue(backward.intersect(forward.toSet()).isEmpty())
    }

    @Test
    fun matchPrevious_atBeginningReturnsEmptyWithoutResolving() = runTest {
        val requested = mutableListOf<String>()
        val matcher = matcher(requested)
        matcher.reset((0 until 100).map(::track))
        matcher.matchInitial(targetIndex = 0, preloadedItem = null)

        assertFalse(matcher.hasUnmatchedPreviousTracks)
        assertTrue(matcher.matchPrevious().items.isEmpty())
        assertEquals((0..10).map { "sp-$it" }, requested)
    }

    @Test
    fun matchPrevious_fullFailureAdvancesCursorAndTerminates() = runTest {
        var failTail = false
        val matcher =
            SpotifyLazyTrackMatcher(
                resolveTrack = { track ->
                    if (failTail) null else mediaItem(track)
                },
            )
        matcher.reset((0 until 45).map(::track))
        matcher.matchInitial(targetIndex = 30, preloadedItem = null)
        assertTrue(matcher.hasUnmatchedPreviousTracks)

        failTail = true
        val firstBatch = matcher.matchPrevious()
        assertTrue(firstBatch.items.isEmpty())
        assertEquals(20, firstBatch.failedCount)
        assertTrue(matcher.hasUnmatchedPreviousTracks)

        val lastBatch = matcher.matchPrevious()
        assertTrue(lastBatch.items.isEmpty())
        assertEquals(5, lastBatch.failedCount)
        assertFalse(matcher.hasUnmatchedPreviousTracks)

        assertTrue(matcher.matchPrevious().items.isEmpty())
    }

    @Test
    fun matchPrevious_cancellationLeavesCursorUnadvanced() = runTest {
        val started = CompletableDeferred<Unit>()
        var blockResolution = false
        val matcher =
            SpotifyLazyTrackMatcher(
                resolveTrack = { track ->
                    if (blockResolution) {
                        started.complete(Unit)
                        awaitCancellation()
                    } else {
                        mediaItem(track)
                    }
                },
            )
        matcher.reset((0 until 100).map(::track))
        matcher.matchInitial(targetIndex = 50, preloadedItem = null)

        blockResolution = true
        val job = launch(Dispatchers.Default) { matcher.matchPrevious() }
        started.await()
        job.cancelAndJoin()

        assertFalse(job.isActive)
        assertTrue(matcher.hasUnmatchedPreviousTracks)

        blockResolution = false
        assertEquals(
            (25..44).map { "yt-sp-$it" },
            matcher.matchPrevious().items.map(MediaItem::mediaId),
        )
    }

    private fun matcher(
        requested: MutableList<String>,
        failedIndices: Set<Int> = emptySet(),
    ): SpotifyLazyTrackMatcher =
        SpotifyLazyTrackMatcher(
            resolveTrack = { track ->
                requested += track.id
                if (track.id.removePrefix("sp-").toInt() in failedIndices) null else mediaItem(track)
            },
        )

    private fun track(index: Int): SpotifyTrack =
        SpotifyTrack(id = "sp-$index", name = "Track $index", durationMs = 180_000)

    private fun metadata(id: String): MediaMetadata =
        MediaMetadata(
            id = id,
            title = id,
            artists = emptyList(),
            duration = 180,
        )

    private fun mediaItem(track: SpotifyTrack): MediaItem =
        MediaItem.Builder()
            .setMediaId("yt-${track.id}")
            .build()
}
