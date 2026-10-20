package moe.rukamori.archivetune.ui.player.lyrics_0.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.player.player_0.buttons.PlayerAction
import moe.rukamori.archivetune.ui.state.PlayerUiState
import moe.rukamori.archivetune.ui.theme.LocalArchiveTuneFontFamily

@Composable
internal fun LyricsMenuEdit(
    state: PlayerUiState,
    onAction: (PlayerAction) -> Unit,
    onBack: () -> Unit
) {
    val font = LocalArchiveTuneFontFamily.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.edit_lyrics),
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = font,
            modifier = Modifier.padding(start = 4.dp, bottom = 16.dp)
        )

        OutlinedTextField(
            value = state.lyricsEditText,
            onValueChange = { onAction(PlayerAction.UpdateLyricsEditText(it)) },
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = font),
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .focusRequester(focusRequester),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(state.vibrantColor),
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
            )
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.action_cancel), color = Color.White, fontFamily = font)
            }
            TextButton(onClick = {
                onAction(PlayerAction.SaveLyrics(state.lyricsEditText))
                onBack()
            }) {
                Text(stringResource(R.string.action_save), color = Color(state.vibrantColor), fontWeight = FontWeight.Bold, fontFamily = font)
            }
        }
    }
}
