/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.spotify

import moe.rukamori.archivetune.constants.PlaylistSortType
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistTracksRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyLibrarySortTest {

    private fun createPlaylist(
        id: String,
        name: String,
        trackTotal: Int? = null,
        hasTracksRef: Boolean = true,
    ): SpotifyPlaylist =
        SpotifyPlaylist(
            id = id,
            name = name,
            tracks = if (hasTracksRef) SpotifyPlaylistTracksRef(total = trackTotal) else null,
        )

    @Test
    fun sort_nameAscendingAndDescending_sortsAlphabeticallyWithStableAscendingTies() {
        val p0 = createPlaylist("0", "Zeta")
        val p1 = createPlaylist("1", "alpha")
        val p2 = createPlaylist("2", "Alpha")
        val playlists = listOf(p0, p1, p2)

        val asc = sortSpotifyLibraryPlaylists(playlists, PlaylistSortType.NAME, descending = false)
        assertEquals(listOf("1", "2", "0"), asc.map { it.id })

        val desc = sortSpotifyLibraryPlaylists(playlists, PlaylistSortType.NAME, descending = true)
        assertEquals(listOf("0", "1", "2"), desc.map { it.id })
    }

    @Test
    fun sort_countAscendingAndDescending_ordersKnownCountsAndPlacesUnknownLastWithStableTies() {
        val p0 = createPlaylist("p0", "UnknownRef", hasTracksRef = false)
        val p1 = createPlaylist("p1", "Ten", trackTotal = 10)
        val p2 = createPlaylist("p2", "UnknownTotal", trackTotal = null, hasTracksRef = true)
        val p3 = createPlaylist("p3", "Zero", trackTotal = 0)
        val p4 = createPlaylist("p4", "FiveA", trackTotal = 5)
        val p5 = createPlaylist("p5", "FiveB", trackTotal = 5)
        val playlists = listOf(p0, p1, p2, p3, p4, p5)

        val asc = sortSpotifyLibraryPlaylists(playlists, PlaylistSortType.SONG_COUNT, descending = false)
        assertEquals(listOf("p3", "p4", "p5", "p1", "p0", "p2"), asc.map { it.id })

        val desc = sortSpotifyLibraryPlaylists(playlists, PlaylistSortType.SONG_COUNT, descending = true)
        assertEquals(listOf("p1", "p4", "p5", "p3", "p0", "p2"), desc.map { it.id })
    }

    @Test
    fun sort_customIgnoresDescendingAndPreservesSourceOrder() {
        val p0 = createPlaylist("0", "Gamma")
        val p1 = createPlaylist("1", "Alpha")
        val p2 = createPlaylist("2", "Beta")
        val playlists = listOf(p0, p1, p2)

        val asc = sortSpotifyLibraryPlaylists(playlists, PlaylistSortType.CUSTOM, descending = false)
        val desc = sortSpotifyLibraryPlaylists(playlists, PlaylistSortType.CUSTOM, descending = true)

        assertEquals(listOf("0", "1", "2"), asc.map { it.id })
        assertEquals(listOf("0", "1", "2"), desc.map { it.id })
    }

    @Test
    fun sort_unsupportedTypesFallbackToCustomOrder() {
        val playlists = listOf(
            createPlaylist("0", "Gamma"),
            createPlaylist("1", "Alpha"),
            createPlaylist("2", "Beta"),
        )

        val createDateResult = sortSpotifyLibraryPlaylists(playlists, PlaylistSortType.CREATE_DATE, descending = true)
        val lastUpdatedResult = sortSpotifyLibraryPlaylists(playlists, PlaylistSortType.LAST_UPDATED, descending = true)

        assertEquals(listOf("0", "1", "2"), createDateResult.map { it.id })
        assertEquals(listOf("0", "1", "2"), lastUpdatedResult.map { it.id })
    }

    @Test
    fun sort_snapshotIsIndependentOfInputListMutation() {
        val p0 = createPlaylist("0", "P0")
        val p1 = createPlaylist("1", "P1")
        val p2 = createPlaylist("2", "P2")
        val mutableList = mutableListOf(p0, p1)

        val sorted = sortSpotifyLibraryPlaylists(mutableList, PlaylistSortType.CUSTOM, descending = false)
        mutableList.add(p2)
        mutableList[0] = p2

        assertEquals(2, sorted.size)
        assertEquals("0", sorted[0].id)
        assertEquals("1", sorted[1].id)
    }

    @Test
    fun sort_preservesAllDuplicatesWithStableOrdering() {
        val p0 = createPlaylist("dup", "Alpha", trackTotal = 5)
        val p1 = createPlaylist("dup", "Alpha", trackTotal = 5)
        val playlists = listOf(p0, p1)

        val asc = sortSpotifyLibraryPlaylists(playlists, PlaylistSortType.NAME, descending = false)
        assertEquals(2, asc.size)
        assertTrue(asc[0] === p0)
        assertTrue(asc[1] === p1)

        val desc = sortSpotifyLibraryPlaylists(playlists, PlaylistSortType.NAME, descending = true)
        assertEquals(2, desc.size)
        assertTrue(desc[0] === p0)
        assertTrue(desc[1] === p1)
    }

    @Test
    fun parseSortType_validAndUnsupportedFallbacks() {
        assertEquals(PlaylistSortType.CUSTOM, parseSpotifyLibraryPlaylistSortType("CUSTOM"))
        assertEquals(PlaylistSortType.NAME, parseSpotifyLibraryPlaylistSortType("NAME"))
        assertEquals(PlaylistSortType.SONG_COUNT, parseSpotifyLibraryPlaylistSortType("SONG_COUNT"))

        assertEquals(PlaylistSortType.CUSTOM, parseSpotifyLibraryPlaylistSortType("CREATE_DATE"))
        assertEquals(PlaylistSortType.CUSTOM, parseSpotifyLibraryPlaylistSortType("LAST_UPDATED"))
        assertEquals(PlaylistSortType.CUSTOM, parseSpotifyLibraryPlaylistSortType("UNKNOWN"))
        assertEquals(PlaylistSortType.CUSTOM, parseSpotifyLibraryPlaylistSortType(null))
        assertEquals(PlaylistSortType.CUSTOM, parseSpotifyLibraryPlaylistSortType(42))

        assertEquals(PlaylistSortType.NAME, parseSpotifyLibraryPlaylistSortType(PlaylistSortType.NAME))
        assertEquals(PlaylistSortType.CUSTOM, parseSpotifyLibraryPlaylistSortType(PlaylistSortType.CREATE_DATE))
    }
}
