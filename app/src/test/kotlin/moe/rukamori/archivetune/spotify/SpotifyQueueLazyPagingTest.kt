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
import moe.rukamori.archivetune.spotify.models.SpotifyPaging
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistTrack
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpotifyQueueLazyPagingTest {

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
    fun tracksQueue_matchesLoadedTailBeforeFetchingNextSourcePage() = runTest {
        val source = (0 until 100).map(::track)
        val fetchedOffsets = mutableListOf<Int>()
        val queue =
            TestTracksQueue(
                initialTracks = source.take(40),
                totalCount = 100,
                pageProvider = { offset, limit ->
                    fetchedOffsets += offset
                    val tracks = source.drop(offset).take(limit)
                    SpotifyTracksQueue.PageResult(tracks = tracks, total = source.size, rawCount = tracks.size)
                },
            )

        val status = queue.getInitialStatus()

        assertEquals((0..10).map { "yt-sp-$it" }, status.items.map(MediaItem::mediaId))
        assertEquals(0, status.mediaItemIndex)
        assertTrue(queue.hasNextPage())

        assertEquals((11..30).map { "yt-sp-$it" }, queue.nextPage().map(MediaItem::mediaId))
        assertEquals((31..39).map { "yt-sp-$it" }, queue.nextPage().map(MediaItem::mediaId))
        assertTrue(fetchedOffsets.isEmpty())

        assertEquals((40..59).map { "yt-sp-$it" }, queue.nextPage().map(MediaItem::mediaId))
        assertEquals(listOf(40), fetchedOffsets)

        assertEquals((60..79).map { "yt-sp-$it" }, queue.nextPage().map(MediaItem::mediaId))
        assertEquals((80..89).map { "yt-sp-$it" }, queue.nextPage().map(MediaItem::mediaId))
        assertEquals((90..99).map { "yt-sp-$it" }, queue.nextPage().map(MediaItem::mediaId))
        assertEquals(listOf(40, 90), fetchedOffsets)

        assertTrue(queue.nextPage().isEmpty())
        assertFalse(queue.hasNextPage())
    }

    @Test
    fun tracksQueue_sourceOffsetCountsFilteredLocalEntries() = runTest {
        val source = (0 until 60).map(::track).toMutableList()
        source[5] = source[5].copy(isLocal = true)
        val fetchedOffsets = mutableListOf<Int>()
        val queue =
            TestTracksQueue(
                initialTracks = source.take(40),
                totalCount = 60,
                pageProvider = { offset, limit ->
                    fetchedOffsets += offset
                    val raw = source.drop(offset).take(limit)
                    SpotifyTracksQueue.PageResult(
                        tracks = raw.filter { !it.isLocal },
                        total = source.size,
                        rawCount = raw.size,
                    )
                },
            )

        val status = queue.getInitialStatus()

        assertEquals(
            listOf(0, 1, 2, 3, 4, 6, 7, 8, 9, 10, 11).map { "yt-sp-$it" },
            status.items.map(MediaItem::mediaId),
        )
        assertEquals((12..31).map { "yt-sp-$it" }, queue.nextPage().map(MediaItem::mediaId))
        assertEquals((32..39).map { "yt-sp-$it" }, queue.nextPage().map(MediaItem::mediaId))
        assertEquals((40..59).map { "yt-sp-$it" }, queue.nextPage().map(MediaItem::mediaId))
        assertEquals(listOf(40), fetchedOffsets)
    }

    @Test
    fun tracksQueue_customOrderNeverFetchesSourceAndKeepsOrder() = runTest {
        val source = (0 until 25).map(::track)
        var fetches = 0
        val queue =
            TestTracksQueue(
                initialTracks = source,
                startIndex = 12,
                totalCount = source.size,
                hasCustomOrder = true,
                pageProvider = { _, _ ->
                    fetches += 1
                    SpotifyTracksQueue.PageResult(emptyList(), 0, 0)
                },
            )

        val status = queue.getInitialStatus()

        assertEquals((7..22).map { "yt-sp-$it" }, status.items.map(MediaItem::mediaId))
        assertEquals(5, status.mediaItemIndex)
        assertEquals((23..24).map { "yt-sp-$it" }, queue.nextPage().map(MediaItem::mediaId))
        assertTrue(queue.nextPage().isEmpty())
        assertFalse(queue.hasNextPage())
        assertEquals(0, fetches)
    }

    @Test
    fun tracksQueue_reusesPreloadedSelectedTrackAtStableIndex() = runTest {
        val source = (0 until 40).map(::track)
        val preload = metadata("preload-sp-20")
        val requested = mutableListOf<String>()
        val queue =
            TestTracksQueue(
                initialTracks = source,
                startIndex = 20,
                totalCount = source.size,
                hasCustomOrder = true,
                preloadItem = preload,
                resolver = { track ->
                    requested += track.id
                    mediaItem(track)
                },
            )

        val status = queue.getInitialStatus()

        assertEquals((15..30).map { "yt-sp-$it" }.toMutableList().apply {
            this[5] = preload.toMediaItem().mediaId
        }, status.items.map(MediaItem::mediaId))
        assertEquals(5, status.mediaItemIndex)
        assertEquals(preload.toMediaItem().mediaId, status.items[status.mediaItemIndex].mediaId)
        assertFalse("sp-20" in requested)
    }

    @Test
    fun playlistQueue_middleStartKeepsSourceOffsetSeparateFromMatchCursor() = runTest {
        val source = (0 until 100).map(::track)
        val fetchedOffsets = mutableListOf<Int>()
        val queue =
            TestPlaylistQueue(
                initialTracks = source.take(40),
                startIndex = 20,
                totalCount = 100,
                pageProvider = { offset, limit ->
                    fetchedOffsets += offset
                    val tracks = source.drop(offset).take(limit)
                    SpotifyPaging(
                        items = tracks.map { SpotifyPlaylistTrack(track = it) },
                        total = source.size,
                        limit = limit,
                        offset = offset,
                    )
                },
            )

        val status = queue.getInitialStatus()

        assertEquals((15..30).map { "yt-sp-$it" }, status.items.map(MediaItem::mediaId))
        assertEquals(5, status.mediaItemIndex)
        assertEquals((31..39).map { "yt-sp-$it" }, queue.nextPage().map(MediaItem::mediaId))
        assertEquals((40..59).map { "yt-sp-$it" }, queue.nextPage().map(MediaItem::mediaId))
        assertEquals(listOf(40), fetchedOffsets)
    }

    @Test
    fun queue_fullMatchingFailureIsExplicitAndTerminal() = runTest {
        val queue =
            TestTracksQueue(
                initialTracks = (0 until 16).map(::track),
                totalCount = 16,
                hasCustomOrder = true,
                resolver = { null },
            )

        val status = queue.getInitialStatus()

        assertTrue(status.items.isEmpty())
        assertTrue(queue.isLoadFailed)
        assertFalse(queue.hasNextPage())
    }

    @Test
    fun queue_emptyResolutionTailStopsAfterCursorAdvance() = runTest {
        var failTail = false
        val queue =
            TestTracksQueue(
                initialTracks = (0 until 20).map(::track),
                totalCount = 20,
                hasCustomOrder = true,
                resolver = { track ->
                    val index = track.id.removePrefix("sp-").toInt()
                    if (failTail && index >= 11) null else mediaItem(track)
                },
            )

        assertEquals(11, queue.getInitialStatus().items.size)
        failTail = true

        assertTrue(queue.nextPage().isEmpty())
        assertFalse(queue.hasNextPage())
        assertFalse(queue.isLoadFailed)
    }

    @Test
    fun queue_reinitializationResetsSourceAndCursors() = runTest {
        val fetchedOffsets = mutableListOf<Int>()
        val queue =
            TestTracksQueue(
                initialTracks = emptyList(),
                totalCount = 60,
                pageProvider = { offset, limit ->
                    fetchedOffsets += offset
                    val tracks = (0 until 60).map(::track).drop(offset).take(limit)
                    SpotifyTracksQueue.PageResult(tracks = tracks, total = 60, rawCount = tracks.size)
                },
            )

        val first = queue.getInitialStatus()
        val second = queue.getInitialStatus()

        assertEquals(first.items.map(MediaItem::mediaId), second.items.map(MediaItem::mediaId))
        assertEquals(listOf(0, 0), fetchedOffsets)
        assertEquals(11, second.items.size)
    }

    @Test
    fun tracksQueue_cancellationClearsLoadingWithoutPublishingStatus() = runTest {
        val started = CompletableDeferred<Unit>()
        val queue =
            TestTracksQueue(
                initialTracks = (0 until 40).map(::track),
                totalCount = 40,
                hasCustomOrder = true,
                resolver = {
                    started.complete(Unit)
                    awaitCancellation()
                },
            )

        val job = launch(Dispatchers.Default) { queue.getInitialStatus() }
        started.await()
        job.cancelAndJoin()

        assertFalse(job.isActive)
        assertFalse(queue.isContextLoading)
    }

    @Test
    fun tracksQueue_previousPageWalksBackwardWithoutFetchingSource() = runTest {
        val source = (0 until 100).map(::track)
        var fetches = 0
        val queue =
            TestTracksQueue(
                initialTracks = source,
                startIndex = 60,
                totalCount = source.size,
                hasCustomOrder = true,
                pageProvider = { _, _ ->
                    fetches += 1
                    SpotifyTracksQueue.PageResult(emptyList(), 0, 0)
                },
            )

        val status = queue.getInitialStatus()

        assertEquals((55..70).map { "yt-sp-$it" }, status.items.map(MediaItem::mediaId))
        assertTrue(queue.hasPreviousPage())

        assertEquals((35..54).map { "yt-sp-$it" }, queue.previousPage().items.map(MediaItem::mediaId))
        assertEquals((15..34).map { "yt-sp-$it" }, queue.previousPage().items.map(MediaItem::mediaId))
        assertEquals((0..14).map { "yt-sp-$it" }, queue.previousPage().items.map(MediaItem::mediaId))

        assertFalse(queue.hasPreviousPage())
        assertTrue(queue.previousPage().items.isEmpty())
        assertFalse(queue.isLoadFailed)
        assertEquals(0, fetches)
    }

    @Test
    fun tracksQueue_previousPageDoesNotConsumeForwardTail() = runTest {
        val source = (0 until 100).map(::track)
        val queue =
            TestTracksQueue(
                initialTracks = source,
                startIndex = 60,
                totalCount = source.size,
                hasCustomOrder = true,
            )

        assertEquals(16, queue.getInitialStatus().items.size)
        assertEquals((35..54).map { "yt-sp-$it" }, queue.previousPage().items.map(MediaItem::mediaId))
        assertEquals((71..90).map { "yt-sp-$it" }, queue.nextPage().map(MediaItem::mediaId))
    }

    @Test
    fun tracksQueue_previousPageNullTailAdvancesCursorAndTerminates() = runTest {
        var failTail = false
        val queue =
            TestTracksQueue(
                initialTracks = (0 until 41).map(::track),
                startIndex = 30,
                totalCount = 41,
                hasCustomOrder = true,
                resolver = { track ->
                    val index = track.id.removePrefix("sp-").toInt()
                    if (failTail && index < 25) null else mediaItem(track)
                },
            )

        assertEquals(16, queue.getInitialStatus().items.size)
        failTail = true

        assertTrue(queue.previousPage().items.isEmpty())
        assertTrue(queue.previousPage().items.isEmpty())
        assertFalse(queue.hasPreviousPage())
        assertFalse(queue.isLoadFailed)
    }

    @Test
    fun tracksQueue_previousPageCancellationKeepsCursor() = runTest {
        val started = CompletableDeferred<Unit>()
        var blockResolution = false
        val queue =
            TestTracksQueue(
                initialTracks = (0 until 41).map(::track),
                startIndex = 30,
                totalCount = 41,
                hasCustomOrder = true,
                resolver = { track ->
                    if (blockResolution) {
                        started.complete(Unit)
                        awaitCancellation()
                    } else {
                        mediaItem(track)
                    }
                },
            )

        assertEquals(16, queue.getInitialStatus().items.size)
        blockResolution = true

        val job = launch(Dispatchers.Default) { queue.previousPage() }
        started.await()
        job.cancelAndJoin()

        assertFalse(job.isActive)
        assertTrue(queue.hasPreviousPage())
        assertFalse(queue.isLoadFailed)

        blockResolution = false
        assertEquals((5..24).map { "yt-sp-$it" }, queue.previousPage().items.map(MediaItem::mediaId))
    }

    @Test
    fun playlistQueue_previousPageContinuesBackwardFromInitialWindow() = runTest {
        val source = (0 until 60).map(::track)
        val queue =
            TestPlaylistQueue(
                initialTracks = source,
                startIndex = 20,
                totalCount = source.size,
                hasCustomOrder = true,
                pageProvider = { _, _ ->
                    SpotifyPaging(items = emptyList(), total = source.size, limit = 0, offset = 0)
                },
            )

        val status = queue.getInitialStatus()

        assertEquals((15..30).map { "yt-sp-$it" }, status.items.map(MediaItem::mediaId))
        assertTrue(queue.hasPreviousPage())
        assertEquals((0..14).map { "yt-sp-$it" }, queue.previousPage().items.map(MediaItem::mediaId))
        assertFalse(queue.hasPreviousPage())
        assertTrue(queue.previousPage().items.isEmpty())
    }

    @Test
    fun tracksQueue_previousPageAfterReinitializationUsesFreshWindow() = runTest {
        val queue =
            TestTracksQueue(
                initialTracks = (0 until 60).map(::track),
                startIndex = 20,
                totalCount = 60,
                hasCustomOrder = true,
            )

        queue.getInitialStatus()
        val firstGeneration = queue.contextGeneration
        assertEquals((0..14).map { "yt-sp-$it" }, queue.previousPage().items.map(MediaItem::mediaId))
        assertFalse(queue.hasPreviousPage())

        queue.getInitialStatus()
        val secondGeneration = queue.contextGeneration
        assertTrue(secondGeneration > firstGeneration)
        assertTrue(queue.hasPreviousPage())
        assertEquals((0..14).map { "yt-sp-$it" }, queue.previousPage().items.map(MediaItem::mediaId))
    }

    private class TestTracksQueue(
        initialTracks: List<SpotifyTrack>,
        startIndex: Int = 0,
        totalCount: Int? = null,
        hasCustomOrder: Boolean = false,
        preloadItem: MediaMetadata? = null,
        private val pageProvider: suspend (Int, Int) -> SpotifyTracksQueue.PageResult = { _, _ ->
            SpotifyTracksQueue.PageResult(emptyList(), 0, 0)
        },
        private val resolver: suspend (SpotifyTrack) -> MediaItem? = { mediaItem(it) },
    ) : SpotifyTracksQueue(
        title = "test",
        initialTracks = initialTracks,
        startIndex = startIndex,
        preloadItem = preloadItem,
        totalCount = totalCount,
        hasCustomOrder = hasCustomOrder,
    ) {
        override suspend fun fetchPage(offset: Int, limit: Int): PageResult = pageProvider(offset, limit)

        override suspend fun resolveToMediaItem(track: SpotifyTrack): MediaItem? = resolver(track)
    }

    private class TestPlaylistQueue(
        initialTracks: List<SpotifyTrack>,
        startIndex: Int = 0,
        totalCount: Int? = null,
        hasCustomOrder: Boolean = false,
        private val pageProvider: suspend (Int, Int) -> SpotifyPaging<SpotifyPlaylistTrack>,
    ) : SpotifyPlaylistQueue(
        playlistId = "playlist",
        title = "test",
        initialTracks = initialTracks,
        startIndex = startIndex,
        totalCount = totalCount,
        hasCustomOrder = hasCustomOrder,
    ) {
        override suspend fun fetchPlaylistPage(
            offset: Int,
            limit: Int,
        ): SpotifyPaging<SpotifyPlaylistTrack> = pageProvider(offset, limit)

        override suspend fun resolveToMediaItem(track: SpotifyTrack): MediaItem = mediaItem(track)
    }

    companion object {
        private fun track(index: Int): SpotifyTrack =
            SpotifyTrack(id = "sp-$index", name = "Track $index", durationMs = 180_000)

        private fun metadata(id: String): MediaMetadata =
            MediaMetadata(id = id, title = id, artists = emptyList(), duration = 180)

        private fun mediaItem(track: SpotifyTrack): MediaItem =
            MediaItem.Builder()
                .setMediaId("yt-${track.id}")
                .build()
    }
}
