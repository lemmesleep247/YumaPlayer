/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.ui.screens.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.PlaylistSortType
import moe.rukamori.archivetune.spotify.SPOTIFY_LIBRARY_PLAYLIST_SORT_TYPES
import moe.rukamori.archivetune.ui.settings.SettingsDimensions
import moe.rukamori.archivetune.ui.theme.LocalYumaColors
import moe.rukamori.archivetune.ui.theme.yumaClickable
import moe.rukamori.archivetune.ui.theme.yumaGlassCard

@Composable
internal fun SpotifyLibrarySortControls(
    sortType: PlaylistSortType,
    sortDescending: Boolean,
    isGridView: Boolean,
    onSortTypeChange: (PlaylistSortType) -> Unit,
    onSortDescendingChange: (Boolean) -> Unit,
    onGridViewChange: (Boolean) -> Unit,
) {
    val yumaColors = LocalYumaColors.current
    var showSortMenu by remember { mutableStateOf(false) }
    val currentSortLabel =
        when (sortType) {
            PlaylistSortType.CUSTOM -> stringResource(R.string.custom_order)
            PlaylistSortType.NAME -> stringResource(if (sortDescending) R.string.sort_z_to_a else R.string.sort_a_to_z)
            PlaylistSortType.SONG_COUNT -> stringResource(R.string.tracks_count_label)
            else -> stringResource(R.string.custom_order)
        }
    val sortDirectionRotation by animateFloatAsState(
        targetValue = if (sortDescending) 0f else 180f,
        label = "SpotifyLibrarySortDirection",
    )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsDimensions.ScreenHorizontalPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                Row(
                    modifier =
                        Modifier
                            .height(SettingsDimensions.LibraryChipHeight)
                            .yumaClickable { showSortMenu = true }
                            .yumaGlassCard(
                                shape = CircleShape,
                                backgroundColor = yumaColors.glassBackground,
                                borderColor = yumaColors.glassBorder,
                                strokeWidth = SettingsDimensions.GlassBorderThickness,
                            )
                            .clip(CircleShape)
                            .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = currentSortLabel,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        painter = painterResource(R.drawable.expand_more),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                }

                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                ) {
                    SPOTIFY_LIBRARY_PLAYLIST_SORT_TYPES.forEach { type ->
                        val label =
                            when (type) {
                                PlaylistSortType.CUSTOM -> stringResource(R.string.custom_order)
                                PlaylistSortType.NAME -> stringResource(R.string.sort_a_to_z)
                                PlaylistSortType.SONG_COUNT -> stringResource(R.string.tracks_count_label)
                                else -> stringResource(R.string.custom_order)
                            }
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onSortTypeChange(type)
                                if (type == PlaylistSortType.NAME) onSortDescendingChange(false)
                                showSortMenu = false
                            },
                        )
                    }
                }
            }

            if (sortType != PlaylistSortType.CUSTOM) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier =
                        Modifier
                            .size(SettingsDimensions.LibraryChipHeight)
                            .yumaClickable { onSortDescendingChange(!sortDescending) }
                            .yumaGlassCard(
                                shape = CircleShape,
                                backgroundColor = yumaColors.glassBackground,
                                borderColor = yumaColors.glassBorder,
                                strokeWidth = SettingsDimensions.GlassBorderThickness,
                            )
                            .clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_downward),
                        contentDescription =
                            stringResource(
                                if (sortDescending) R.string.sort_order_descending else R.string.sort_order_ascending,
                            ),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier =
                            Modifier
                                .size(16.dp)
                                .graphicsLayer { rotationZ = sortDirectionRotation },
                    )
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .height(SettingsDimensions.LibraryChipHeight)
                    .yumaGlassCard(
                        shape = CircleShape,
                        backgroundColor = yumaColors.glassBackground,
                        borderColor = yumaColors.glassBorder,
                        strokeWidth = SettingsDimensions.GlassBorderThickness,
                    )
                    .clip(CircleShape)
                    .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (!isGridView) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .yumaClickable { onGridViewChange(false) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.queue_music),
                    contentDescription = stringResource(R.string.list_view),
                    tint = if (!isGridView) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (isGridView) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .yumaClickable { onGridViewChange(true) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.album),
                    contentDescription = stringResource(R.string.grid_view),
                    tint = if (isGridView) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
