/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.home.recommendations

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.SpeedDialSongIdsKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.Album
import moe.rukamori.archivetune.db.entities.Artist
import moe.rukamori.archivetune.db.entities.LocalItem
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.utils.SpeedDialPinType
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.parseSpeedDialPins
import javax.inject.Inject

class HomeLocalRecommendationsUseCase
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
    ) {
        suspend fun loadSpeedDial(): List<LocalItem> =
            withContext(Dispatchers.IO) {
                val pins = parseSpeedDialPins(context.dataStore.get(SpeedDialSongIdsKey, ""))
                if (pins.isEmpty()) {
                    return@withContext emptyList()
                }
                val songIds = pins.filter { it.type == SpeedDialPinType.SONG }.map { it.id }
                val albumIds = pins.filter { it.type == SpeedDialPinType.ALBUM }.map { it.id }
                val artistIds = pins.filter { it.type == SpeedDialPinType.ARTIST }.map { it.id }
                val playlistIds = pins.filter { it.type == SpeedDialPinType.PLAYLIST }.map { it.id }

                val songsById = database.getSongsByIds(songIds).associateBy { it.id }
                val albumsById = albumIds.mapNotNull { id -> database.album(id).first() }.associateBy { it.id }
                val artistsById = artistIds.mapNotNull { id -> database.artist(id).first() }.associateBy { it.id }
                val playlistsById = playlistIds.mapNotNull { id -> database.getPlaylistById(id) }.associateBy { it.id }

                pins
                    .mapNotNull { pin ->
                        when (pin.type.value) {
                            SpeedDialPinType.SONG.value -> songsById[pin.id]
                            SpeedDialPinType.ALBUM.value -> albumsById[pin.id]
                            SpeedDialPinType.ARTIST.value -> artistsById[pin.id]
                            SpeedDialPinType.PLAYLIST.value -> playlistsById[pin.id]
                            else -> null
                        }
                    }.filter { item ->
                        when (item) {
                            is Song -> item.artists.none { it.blockedAt != null }
                            is Album -> item.artists.none { it.blockedAt != null }
                            is Artist -> item.artist.blockedAt == null
                            else -> true
                        }
                    }
            }

        suspend fun loadForgottenFavorites(): List<Song> =
            withContext(Dispatchers.IO) {
                database
                    .forgottenFavorites()
                    .first()
                    .filter { song -> song.artists.none { it.blockedAt != null } }
                    .shuffled()
                    .take(20)
            }

        suspend fun loadKeepListening(): List<LocalItem> =
            withContext(Dispatchers.IO) {
                val fromTimeStamp = System.currentTimeMillis() - 86400000 * 7 * 2
                val keepListeningSongs =
                    database
                        .mostPlayedSongs(fromTimeStamp, limit = 15, offset = 5)
                        .first()
                        .filter { song -> song.artists.none { it.blockedAt != null } }
                        .shuffled()
                        .take(10)
                val keepListeningAlbums =
                    database
                        .mostPlayedAlbums(fromTimeStamp, limit = 8, offset = 2)
                        .first()
                        .filter { it.album.thumbnailUrl != null && it.artists.none { artist -> artist.blockedAt != null } }
                        .shuffled()
                        .take(5)
                val keepListeningArtists =
                    database
                        .mostPlayedArtists(fromTimeStamp)
                        .first()
                        .filter {
                            it.artist.blockedAt == null &&
                                it.artist.isYouTubeArtist &&
                                it.artist.thumbnailUrl != null
                        }.shuffled()
                        .take(5)
                (keepListeningSongs + keepListeningAlbums + keepListeningArtists).shuffled()
            }
    }
