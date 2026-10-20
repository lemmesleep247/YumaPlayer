package moe.rukamori.archivetune.ui.player.lyrics_0.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.ui.settings.SettingsDimensions

private val LyricsMenuHeaderDragHeight = 24.dp

@Composable
internal fun LyricsMenuDragHandle(
    enabled: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(LyricsMenuHeaderDragHeight)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { onDragStart() },
                    onVerticalDrag = { _, dragAmount -> onDrag(dragAmount) },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(
                    width = SettingsDimensions.BottomSheetDragHandleWidth,
                    height = SettingsDimensions.BottomSheetDragHandleHeight,
                )
                .clip(CircleShape)
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(
                        alpha = SettingsDimensions.YumaRowArrowAlpha
                    )
                )
        )
    }
}
