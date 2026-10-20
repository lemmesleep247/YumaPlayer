package moe.rukamori.archivetune.ui.player.lyrics_0.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AiApiKeyKey
import moe.rukamori.archivetune.constants.AiApiValidationStatus
import moe.rukamori.archivetune.constants.AiApiValidationStatusKey
import moe.rukamori.archivetune.constants.AiCustomEndpointKey
import moe.rukamori.archivetune.constants.AiProvider
import moe.rukamori.archivetune.constants.AiProviderKey
import moe.rukamori.archivetune.ui.player.player_0.buttons.PlayerAction
import moe.rukamori.archivetune.ui.state.PlayerUiState
import moe.rukamori.archivetune.ui.theme.LocalArchiveTuneFontFamily
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

@Composable
internal fun LyricsMenuTranslate(
    state: PlayerUiState,
    onAction: (PlayerAction) -> Unit,
    onBack: () -> Unit
) {
    val font = LocalArchiveTuneFontFamily.current
    val context = LocalContext.current
    val languages = remember(context) { moe.rukamori.archivetune.utils.TranslatorLanguages.load(context) }

    val (aiProvider) = rememberEnumPreference(AiProviderKey, AiProvider.NONE)
    val (aiApiKey) = rememberPreference(AiApiKeyKey, "")
    val (aiCustomEndpoint) = rememberPreference(AiCustomEndpointKey, "")
    val (aiValidationStatus) = rememberEnumPreference(AiApiValidationStatusKey, AiApiValidationStatus.UNKNOWN)

    val isAiProviderConfigured = aiProvider != AiProvider.NONE
    val isAiTranslationEnabled = isAiProviderConfigured &&
            aiApiKey.isNotBlank() &&
            (aiProvider != moe.rukamori.archivetune.constants.AiProvider.CUSTOM || aiCustomEndpoint.isNotBlank()) &&
            aiValidationStatus != moe.rukamori.archivetune.constants.AiApiValidationStatus.FAILED

    var useAi by remember { mutableStateOf(isAiTranslationEnabled) }
    var langExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (state.lyricsTranslateLanguage.isEmpty()) {
            val defaultLangCode = context.resources.configuration.locales.get(0)
                .getDisplayLanguage(java.util.Locale.ENGLISH)
                .uppercase(java.util.Locale.US)
                .replace(' ', '_')
            onAction(PlayerAction.UpdateTranslateLanguage(defaultLangCode))
        }
    }

    val selectedLang = languages.find { it.code == state.lyricsTranslateLanguage } ?: languages.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.translate_lyrics),
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = font,
            modifier = Modifier.padding(start = 4.dp, bottom = 16.dp)
        )

        if (state.isAiTranslating || state.isStandardTranslating) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(state.vibrantColor))
            }
        } else {
            if (state.aiTranslationError != null) {
                Text(
                    text = stringResource(R.string.error_with_message, state.aiTranslationError),
                    color = Color(0xFFFF5252),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            if (isAiTranslationEnabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.use_ai_translation),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = useAi,
                        onCheckedChange = { useAi = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(state.vibrantColor),
                            checkedTrackColor = Color(state.vibrantColor).copy(alpha = 0.5f)
                        )
                    )
                }
            }

            Text(
                text = stringResource(R.string.target_language_label),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = selectedLang?.name ?: stringResource(R.string.select_language),
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { langExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                    },
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = font),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { langExpanded = true },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(state.vibrantColor),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    )
                )

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { langExpanded = true }
                )

                DropdownMenu(
                    expanded = langExpanded,
                    onDismissRequest = { langExpanded = false },
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .heightIn(max = 280.dp)
                ) {
                    languages.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang.name) },
                            onClick = {
                                onAction(PlayerAction.UpdateTranslateLanguage(lang.code))
                                langExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.action_cancel), color = Color.White, fontFamily = font)
                }
                TextButton(
                    onClick = {
                        onAction(PlayerAction.TranslateLyrics(state.lyricsTranslateLanguage, useAi))
                    }
                ) {
                    Text(stringResource(R.string.action_translate), color = Color(state.vibrantColor), fontWeight = FontWeight.Bold, fontFamily = font)
                }
            }
        }
    }
}
