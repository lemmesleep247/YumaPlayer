/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.spotify

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.constants.PlaylistSortType
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistTracksRef
import moe.rukamori.archivetune.utils.SyncUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyLibraryViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val repository: SpotifyLibraryRepository = mockk(relaxed = true)
    private val syncUtils: SyncUtils = mockk(relaxed = true)
    private val viewModelStore = ViewModelStore()

    private val repoPlaylistsFlow = MutableStateFlow<List<SpotifyPlaylist>>(emptyList())

    private val testPlaylists = listOf(
        SpotifyPlaylist(id = "0", name = "Zeta", tracks = SpotifyPlaylistTracksRef(total = 5)),
        SpotifyPlaylist(id = "1", name = "Alpha", tracks = SpotifyPlaylistTracksRef(total = 20)),
        SpotifyPlaylist(id = "2", name = "Beta", tracks = SpotifyPlaylistTracksRef(total = 10)),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repoPlaylistsFlow.value = testPlaylists
        every { repository.playlists } returns repoPlaylistsFlow
        every { repository.isRefreshing } returns MutableStateFlow(false)
        every { repository.errorMessage } returns MutableStateFlow(null)
        every { repository.likedSongsTotal } returns MutableStateFlow(0)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): SpotifyLibraryViewModel {
        val viewModel = SpotifyLibraryViewModel(savedStateHandle, repository, syncUtils)
        viewModelStore.put("spotify-library", viewModel)
        return viewModel
    }

    private suspend fun SpotifyLibraryViewModel.awaitSortedPlaylists(
        timeoutMs: Long = 3000L,
        condition: (List<SpotifyPlaylist>) -> Boolean,
    ): List<SpotifyPlaylist> =
        withTimeout(timeoutMs) {
            sortedPlaylists.first { condition(it) }
        }

    @Test
    fun init_restoresSavedPreferencesAndSortsInitially() = runBlocking {
        val savedStateHandle = SavedStateHandle(
            mapOf(
                SPOTIFY_LIBRARY_SORT_TYPE_KEY to "NAME",
                SPOTIFY_LIBRARY_SORT_DESCENDING_KEY to true,
            ),
        )
        val viewModel = createViewModel(savedStateHandle)

        assertEquals(PlaylistSortType.NAME, viewModel.sortType.value)
        assertTrue(viewModel.sortDescending.value)

        val initialSorted = viewModel.awaitSortedPlaylists { it.size == 3 }
        assertEquals(listOf("0", "2", "1"), initialSorted.map { it.id })
    }

    @Test
    fun init_unsupportedSortFallback_defaultsToCustom() = runBlocking {
        val savedStateHandle = SavedStateHandle(
            mapOf(
                SPOTIFY_LIBRARY_SORT_TYPE_KEY to "CREATE_DATE",
            ),
        )
        val viewModel = createViewModel(savedStateHandle)

        assertEquals(PlaylistSortType.CUSTOM, viewModel.sortType.value)
        assertFalse(viewModel.sortDescending.value)

        val initialSorted = viewModel.awaitSortedPlaylists { it.size == 3 }
        assertEquals(listOf("0", "1", "2"), initialSorted.map { it.id })
    }

    @Test
    fun setSortType_and_setSortDescending_persistToSavedStateHandleWithoutRepoRefresh() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val viewModel = createViewModel(savedStateHandle)

        val initial = viewModel.awaitSortedPlaylists { it.size == 3 }
        assertEquals(listOf("0", "1", "2"), initial.map { it.id })

        viewModel.setSortType(PlaylistSortType.NAME)
        assertEquals(PlaylistSortType.NAME, viewModel.sortType.value)
        assertEquals("NAME", savedStateHandle.get<String>(SPOTIFY_LIBRARY_SORT_TYPE_KEY))

        val sortedByName = viewModel.awaitSortedPlaylists { it.map(SpotifyPlaylist::id) == listOf("1", "2", "0") }
        assertEquals(listOf("1", "2", "0"), sortedByName.map { it.id })

        viewModel.setSortDescending(true)
        assertTrue(viewModel.sortDescending.value)
        assertEquals(true, savedStateHandle.get<Boolean>(SPOTIFY_LIBRARY_SORT_DESCENDING_KEY))

        val sortedByNameDesc = viewModel.awaitSortedPlaylists { it.map(SpotifyPlaylist::id) == listOf("0", "2", "1") }
        assertEquals(listOf("0", "2", "1"), sortedByNameDesc.map { it.id })

        coVerify(exactly = 0) { repository.refreshPlaylists() }
        verify(exactly = 0) { syncUtils.trySpotifyAutoSync(authoritative = true) }
    }

    @Test
    fun originalPlaylists_remainsUntouchedForLibraryMix() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val viewModel = createViewModel(savedStateHandle)

        val rawPlaylists = withTimeout(3000L) {
            viewModel.playlists.first { it.size == 3 }
        }
        assertEquals(listOf("0", "1", "2"), rawPlaylists.map { it.id })

        viewModel.setSortType(PlaylistSortType.NAME)
        val sortedPlaylists = viewModel.awaitSortedPlaylists { it.map(SpotifyPlaylist::id) == listOf("1", "2", "0") }
        assertEquals(listOf("1", "2", "0"), sortedPlaylists.map { it.id })

        assertEquals(listOf("0", "1", "2"), viewModel.playlists.value.map { it.id })
    }

    @Test
    fun sortedPlaylists_respondsToRepositorySourceUpdate_withPersistedSort() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val viewModel = createViewModel(savedStateHandle)

        viewModel.setSortType(PlaylistSortType.SONG_COUNT)
        viewModel.setSortDescending(true)
        val initial = viewModel.awaitSortedPlaylists { it.map(SpotifyPlaylist::id) == listOf("1", "2", "0") }
        assertEquals(listOf("1", "2", "0"), initial.map { it.id })

        val newPlaylists = listOf(
            SpotifyPlaylist(id = "10", name = "P10", tracks = SpotifyPlaylistTracksRef(total = 50)),
            SpotifyPlaylist(id = "11", name = "P11", tracks = SpotifyPlaylistTracksRef(total = 100)),
            SpotifyPlaylist(id = "12", name = "P12", tracks = SpotifyPlaylistTracksRef(total = 1)),
        )
        repoPlaylistsFlow.value = newPlaylists

        val updatedSorted = viewModel.awaitSortedPlaylists { it.map(SpotifyPlaylist::id) == listOf("11", "10", "12") }
        assertEquals(listOf("11", "10", "12"), updatedSorted.map { it.id })
    }

    @Test
    fun refreshPlaylists_invokesAuthoritativeSyncAndRepoRefresh() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val viewModel = createViewModel(savedStateHandle)

        val refreshDeferred = CompletableDeferred<Unit>()
        coEvery { repository.refreshPlaylists() } coAnswers {
            refreshDeferred.complete(Unit)
            emptyList()
        }

        viewModel.refreshPlaylists()
        withTimeout(3000L) { refreshDeferred.await() }

        verify(exactly = 1) { syncUtils.trySpotifyAutoSync(authoritative = true) }
        coVerify(exactly = 1) { repository.refreshPlaylists() }
    }

    @Test
    fun isGridView_defaultsToFalse() = runBlocking {
        val viewModel = createViewModel()
        assertFalse(viewModel.isGridView.value)
    }

    @Test
    fun setGridView_persistsToSavedStateHandleAndEmits() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val viewModel = createViewModel(savedStateHandle)

        assertFalse(viewModel.isGridView.value)

        viewModel.setGridView(true)
        assertTrue(viewModel.isGridView.value)
        assertEquals(true, savedStateHandle.get<Boolean>(SPOTIFY_LIBRARY_GRID_VIEW_KEY))

        viewModel.setGridView(false)
        assertFalse(viewModel.isGridView.value)
        assertEquals(false, savedStateHandle.get<Boolean>(SPOTIFY_LIBRARY_GRID_VIEW_KEY))
    }

    @Test
    fun isGridView_survivesPlaylistRefresh() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val viewModel = createViewModel(savedStateHandle)

        viewModel.setGridView(true)
        assertTrue(viewModel.isGridView.value)

        val refreshDeferred = CompletableDeferred<Unit>()
        coEvery { repository.refreshPlaylists() } coAnswers {
            refreshDeferred.complete(Unit)
            emptyList()
        }

        viewModel.refreshPlaylists()
        withTimeout(3000L) { refreshDeferred.await() }

        assertTrue(viewModel.isGridView.value)
        assertEquals(true, savedStateHandle.get<Boolean>(SPOTIFY_LIBRARY_GRID_VIEW_KEY))
    }

    @Test
    fun sortedPlaylists_whileSubscribed_deliversSortedValuesToActiveCollectorAfterSourceUpdate() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val viewModel = createViewModel(savedStateHandle)

        viewModel.setSortType(PlaylistSortType.NAME)
        viewModel.setSortDescending(false)

        val collectorJob = launch(testDispatcher) {
            viewModel.sortedPlaylists.collect()
        }

        try {
            val initial = viewModel.awaitSortedPlaylists { it.map(SpotifyPlaylist::id) == listOf("1", "2", "0") }
            assertEquals(listOf("1", "2", "0"), initial.map { it.id })

            val newPlaylists = listOf(
                SpotifyPlaylist(id = "10", name = "Zeta", tracks = SpotifyPlaylistTracksRef(total = 1)),
                SpotifyPlaylist(id = "11", name = "Alpha", tracks = SpotifyPlaylistTracksRef(total = 2)),
            )
            repoPlaylistsFlow.value = newPlaylists

            val updatedSorted = viewModel.awaitSortedPlaylists { it.map(SpotifyPlaylist::id) == listOf("11", "10") }
            assertEquals(listOf("11", "10"), updatedSorted.map { it.id })
        } finally {
            collectorJob.cancel()
        }
    }
}
