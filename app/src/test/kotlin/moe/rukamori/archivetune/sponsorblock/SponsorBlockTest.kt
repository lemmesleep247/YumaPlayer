/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.sponsorblock

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.constants.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorBlockTest {
    private val repository = mockk<SponsorBlockRepository>()
    private val useCase = GetSponsorBlockSegmentsUseCase(repository)

    private val defaultSettings =
        SponsorBlockSettings(
            enabled = true,
            categories = setOf(SponsorBlockCategory.SPONSOR, SponsorBlockCategory.INTRO),
            apiUrl = DEFAULT_SPONSOR_BLOCK_API_URL,
        )

    @Test
    fun urlValidation_acceptsHttpsAndRejectsInsecureOrMalformedUrls() {
        val validate = ValidateSponsorBlockApiUrlUseCase()
        assertTrue(validate("https://sponsor.ajay.app"))
        assertTrue(validate("https://custom.sponsorblock.domain/api"))
        assertFalse(validate("http://sponsor.ajay.app"))
        assertFalse(validate("https://user:pass@sponsor.ajay.app"))
        assertFalse(validate("https://sponsor.ajay.app?query=param"))
        assertFalse(validate("https://sponsor.ajay.app#fragment"))
        assertEquals("https://sponsor.ajay.app", normalizeSponsorBlockApiUrl("https://sponsor.ajay.app/"))
    }

    @Test
    fun defaultSettings_hasDisabledStateAndNineCategories() {
        assertFalse(SponsorBlockSettings.Default.enabled)
        assertEquals(9, SponsorBlockCategory.entries.size)
        assertEquals(9, SponsorBlockSettings.Default.categories.size)
        assertEquals("music_offtopic", SponsorBlockCategory.MUSIC_OFF_TOPIC.apiValue)
        assertEquals(SponsorBlockCategory.MUSIC_OFF_TOPIC, SponsorBlockCategory.fromApiValue("music_offtopic"))
        assertNull(SponsorBlockCategory.fromApiValue("non_existent_category"))
    }

    @Test
    fun videoIdValidation_blocksInvalidIdsWithoutNetworkCall() = runTest {
        listOf("", "short", "invalid-length-id-123", "dQw4w9WgXc?", "   ").forEach { invalidId ->
            val result = useCase(invalidId, defaultSettings, source = PlaybackSource.YT_MUSIC)
            assertTrue(result.isEmpty())
        }
        coVerify(exactly = 0) { repository.fetchSegments(any(), any(), any()) }
    }

    @Test
    fun sourceGate_blocksFlacAndLocalMediaWithoutNetworkCall() = runTest {
        val validId = "dQw4w9WgXcQ"
        val flacResult = useCase(validId, defaultSettings, source = PlaybackSource.FLAC)
        assertTrue(flacResult.isEmpty())

        val localContentResult = useCase("content://media/external/audio/media/1", defaultSettings, source = PlaybackSource.YT_MUSIC)
        assertTrue(localContentResult.isEmpty())

        val localFileResult = useCase("file:///storage/emulated/0/track.flac", defaultSettings, source = PlaybackSource.YT_MUSIC)
        assertTrue(localFileResult.isEmpty())

        coVerify(exactly = 0) { repository.fetchSegments(any(), any(), any()) }
    }

    @Test
    fun disabledOrEmptyCategories_returnsEmptyListWithoutNetworkCall() = runTest {
        val validId = "dQw4w9WgXcQ"
        val disabledSettings = defaultSettings.copy(enabled = false)
        assertTrue(useCase(validId, disabledSettings, source = PlaybackSource.YT_MUSIC).isEmpty())

        val noCategorySettings = defaultSettings.copy(categories = emptySet())
        assertTrue(useCase(validId, noCategorySettings, source = PlaybackSource.YT_MUSIC).isEmpty())

        coVerify(exactly = 0) { repository.fetchSegments(any(), any(), any()) }
    }

    @Test
    fun badSegmentBounds_filteredOutCorrectly() = runTest {
        val validId = "dQw4w9WgXcQ"
        coEvery { repository.fetchSegments(validId, any(), any()) } returns
            listOf(
                SponsorBlockRawSegment(listOf(-1.0, 5.0), "sponsor", "skip"),
                SponsorBlockRawSegment(listOf(5.0, 5.0), "sponsor", "skip"),
                SponsorBlockRawSegment(listOf(10.0, 5.0), "sponsor", "skip"),
                SponsorBlockRawSegment(listOf(Double.NaN, 5.0), "sponsor", "skip"),
                SponsorBlockRawSegment(listOf(0.0, Double.POSITIVE_INFINITY), "sponsor", "skip"),
                SponsorBlockRawSegment(listOf(1.0, 1.05), "sponsor", "skip"),
                SponsorBlockRawSegment(listOf(1.0), "sponsor", "skip"),
                SponsorBlockRawSegment(listOf(10.0, 15.0), "sponsor", "skip"),
            )

        val result = useCase(validId, defaultSettings, source = PlaybackSource.YT_MUSIC)
        assertEquals(1, result.size)
        assertEquals(SponsorBlockSegment(10_000L, 15_000L), result.first())
    }

    @Test
    fun categoryAndActionTypeFilter_dropsMutedOrUnselectedCategories() = runTest {
        val validId = "dQw4w9WgXcQ"
        coEvery { repository.fetchSegments(validId, any(), any()) } returns
            listOf(
                SponsorBlockRawSegment(listOf(1.0, 5.0), "sponsor", "mute"),
                SponsorBlockRawSegment(listOf(10.0, 15.0), "selfpromo", "skip"),
                SponsorBlockRawSegment(listOf(20.0, 25.0), "sponsor", "skip"),
            )

        val result = useCase(validId, defaultSettings, source = PlaybackSource.YT_MUSIC)
        assertEquals(1, result.size)
        assertEquals(SponsorBlockSegment(20_000L, 25_000L), result.first())
    }

    @Test
    fun overlappingSegments_mergedIntoContiguousIntervals() = runTest {
        val validId = "dQw4w9WgXcQ"
        coEvery { repository.fetchSegments(validId, any(), any()) } returns
            listOf(
                SponsorBlockRawSegment(listOf(10.0, 20.0), "sponsor", "skip"),
                SponsorBlockRawSegment(listOf(15.0, 25.0), "sponsor", "skip"),
                SponsorBlockRawSegment(listOf(25.0, 30.0), "sponsor", "skip"),
                SponsorBlockRawSegment(listOf(40.0, 50.0), "sponsor", "skip"),
            )

        val result = useCase(validId, defaultSettings, source = PlaybackSource.YT_MUSIC)
        assertEquals(2, result.size)
        assertEquals(SponsorBlockSegment(10_000L, 30_000L), result[0])
        assertEquals(SponsorBlockSegment(40_000L, 50_000L), result[1])
    }

    @Test
    fun emptyApiResponse_returnsEmptyList() = runTest {
        val validId = "dQw4w9WgXcQ"
        coEvery { repository.fetchSegments(validId, any(), any()) } returns emptyList()

        val result = useCase(validId, defaultSettings, source = PlaybackSource.YT_MUSIC)
        assertTrue(result.isEmpty())
    }
}
