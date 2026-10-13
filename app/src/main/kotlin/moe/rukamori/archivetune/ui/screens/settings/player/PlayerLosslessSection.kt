/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.ui.screens.settings.player

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.constants.FlacQuality
import moe.rukamori.archivetune.constants.PlaybackSource
import moe.rukamori.archivetune.ui.component.EditTextPreference
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.PreferenceGroupScope
import moe.rukamori.archivetune.ui.component.SwitchPreference

@Composable
fun PlaybackSourceSelector(
    playbackSource: PlaybackSource,
    onPlaybackSourceChange: (PlaybackSource) -> Unit,
    onEnableLosslessChange: (Boolean) -> Unit,
) {
    EnumListPreference(
        title = { Text(stringResource(R.string.playback_source)) },
        icon = { Icon(painterResource(R.drawable.album), null) },
        selectedValue = playbackSource,
        onValueSelected = {
            onPlaybackSourceChange(it)
            onEnableLosslessChange(it == PlaybackSource.FLAC)
        },
        valueText = { source ->
            when (source) {
                PlaybackSource.YT_MUSIC -> stringResource(R.string.source_yt_music)
                PlaybackSource.FLAC -> stringResource(R.string.source_flac)
            }
        },
    )
}

fun PreferenceGroupScope.FlacTokenInputs(
    qobuzAppId: String,
    onQobuzAppIdChange: (String) -> Unit,
    qobuzAppSecret: String,
    onQobuzAppSecretChange: (String) -> Unit,
    qobuzUserAuthToken: String,
    onQobuzUserAuthTokenChange: (String) -> Unit,
) {
    item {
        EditTextPreference(
            title = { Text(stringResource(R.string.qobuz_app_id)) },
            icon = { Icon(painterResource(R.drawable.lock), null) },
            value = qobuzAppId,
            onValueChange = onQobuzAppIdChange,
            isMasked = true,
        )
    }
    item {
        EditTextPreference(
            title = { Text(stringResource(R.string.qobuz_app_secret)) },
            icon = { Icon(painterResource(R.drawable.lock), null) },
            value = qobuzAppSecret,
            onValueChange = onQobuzAppSecretChange,
            isMasked = true,
        )
    }
    item {
        EditTextPreference(
            title = { Text(stringResource(R.string.qobuz_user_auth_token)) },
            icon = { Icon(painterResource(R.drawable.lock), null) },
            value = qobuzUserAuthToken,
            onValueChange = onQobuzUserAuthTokenChange,
            isMasked = true,
        )
    }
}

@Composable
internal fun PlayerLosslessSection(
    state: PlayerSettingsUiState,
    actions: PlayerSettingsUiActions,
    modifier: Modifier = Modifier,
) {
    PreferenceGroup(
        title = stringResource(R.string.lossless_integration),
        modifier = modifier,
    ) {
        item {
            PlaybackSourceSelector(
                playbackSource = state.playbackSource,
                onPlaybackSourceChange = actions.onPlaybackSourceChange,
                onEnableLosslessChange = actions.onEnableLosslessChange,
            )
        }
        if (state.playbackSource == PlaybackSource.FLAC) {
            item {
                EnumListPreference(
                    title = { Text(stringResource(R.string.flac_streaming_quality)) },
                    icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                    selectedValue = state.flacStreamingQuality,
                    onValueSelected = actions.onFlacStreamingQualityChange,
                    valueText = { quality ->
                        when (quality) {
                            FlacQuality.CD -> stringResource(R.string.flac_quality_cd)
                            FlacQuality.HI_RES -> stringResource(R.string.flac_quality_hi_res)
                            FlacQuality.MAX -> stringResource(R.string.flac_quality_max)
                        }
                    },
                )
            }
            item {
                EnumListPreference(
                    title = { Text(stringResource(R.string.flac_download_quality)) },
                    icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                    selectedValue = state.flacDownloadQuality,
                    onValueSelected = actions.onFlacDownloadQualityChange,
                    valueText = { quality ->
                        when (quality) {
                            FlacQuality.CD -> stringResource(R.string.flac_quality_cd)
                            FlacQuality.HI_RES -> stringResource(R.string.flac_quality_hi_res)
                            FlacQuality.MAX -> stringResource(R.string.flac_quality_max)
                        }
                    },
                )
            }
            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.memory_cache_toggle)) },
                    icon = { Icon(painterResource(R.drawable.cached), null) },
                    checked = state.memoryCacheToggle,
                    onCheckedChange = actions.onMemoryCacheToggleChange,
                )
            }
            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.select_flac_download_folder)) },
                    description = state.flacFolderPath,
                    icon = { Icon(painterResource(R.drawable.snippet_folder), null) },
                    onClick = actions.onSelectFlacDownloadFolder,
                )
            }
            FlacTokenInputs(
                qobuzAppId = state.qobuzAppId,
                onQobuzAppIdChange = actions.onQobuzAppIdChange,
                qobuzAppSecret = state.qobuzAppSecret,
                onQobuzAppSecretChange = actions.onQobuzAppSecretChange,
                qobuzUserAuthToken = state.qobuzUserAuthToken,
                onQobuzUserAuthTokenChange = actions.onQobuzUserAuthTokenChange,
            )
        } else {
            item {
                EnumListPreference(
                    title = { Text(stringResource(R.string.audio_quality)) },
                    icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                    selectedValue = state.audioQuality,
                    onValueSelected = actions.onAudioQualityChange,
                    valueText = { quality ->
                        when (quality) {
                            AudioQuality.AUTO -> stringResource(R.string.audio_quality_auto)
                            AudioQuality.HIGH -> stringResource(R.string.audio_quality_high)
                            AudioQuality.LOW -> stringResource(R.string.audio_quality_low)
                            AudioQuality.HIGHEST -> stringResource(R.string.audio_quality_highest)
                        }
                    },
                )
            }
        }
    }
}
