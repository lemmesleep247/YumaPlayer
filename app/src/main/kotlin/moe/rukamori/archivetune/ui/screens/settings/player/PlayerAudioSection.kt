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
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference

@Composable
internal fun PlayerAudioSection(
    state: PlayerSettingsUiState,
    actions: PlayerSettingsUiActions,
    modifier: Modifier = Modifier,
) {
    PreferenceGroup(
        title = stringResource(R.string.player_and_audio),
        modifier = modifier,
    ) {
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.skip_silence)) },
                icon = { Icon(painterResource(R.drawable.skip_next), null) },
                checked = state.skipSilence,
                onCheckedChange = actions.onSkipSilenceChange,
            )
        }
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.audio_normalization)) },
                icon = { Icon(painterResource(R.drawable.volume_up), null) },
                checked = state.audioNormalization,
                onCheckedChange = actions.onAudioNormalizationChange,
            )
        }
        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.equalizer)) },
                icon = { Icon(painterResource(R.drawable.equalizer), null) },
                onClick = actions.onEqualizerClick,
                showChevron = true,
            )
        }
    }
}
