package moe.rukamori.archivetune.ui.player.lyrics_0.menu

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val LyricsMenuDismissThreshold = 64.dp
private const val LyricsMenuExitTranslatePx = 300f

@Stable
internal class LyricsMenuState internal constructor(
    private val scope: CoroutineScope,
    private val visibleState: State<Boolean>,
    private val mainScreenState: State<Boolean>,
    private val onDismissState: State<() -> Unit>,
    private val densityState: State<Density>,
) {
    private val panelOffset = mutableFloatStateOf(
        if (visibleState.value) 0f else LyricsMenuExitTranslatePx
    )
    private var dismissRequested = false
    private var offsetAnimationJob: Job? = null
    private val panelSpring = spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessLow)

    val panelOffsetPx: State<Float> get() = panelOffset

    fun onVisibilityChanged(visible: Boolean) {
        dismissRequested = !visible
        animatePanelOffsetTo(
            if (visible) 0f else maxOf(panelOffset.floatValue, LyricsMenuExitTranslatePx)
        )
    }

    fun requestDismiss() {
        if (dismissRequested) return
        dismissRequested = true
        onDismissState.value()
    }

    fun onDragStart() {
        if (!canDrag()) return
        offsetAnimationJob?.cancel()
    }

    fun onDrag(dragAmount: Float) {
        if (!canDrag()) return
        panelOffset.floatValue = (panelOffset.floatValue + dragAmount).coerceAtLeast(0f)
    }

    fun onDragEnd() {
        when {
            !visibleState.value || dismissRequested -> Unit
            !mainScreenState.value -> animatePanelOffsetTo(0f)
            panelOffset.floatValue >= dismissThresholdPx() -> requestDismiss()
            else -> animatePanelOffsetTo(0f)
        }
    }

    fun onDragCancel() {
        if (visibleState.value && !dismissRequested) {
            animatePanelOffsetTo(0f)
        }
    }

    private fun canDrag(): Boolean =
        visibleState.value && !dismissRequested && mainScreenState.value

    private fun dismissThresholdPx(): Float =
        with(densityState.value) { LyricsMenuDismissThreshold.toPx() }

    private fun animatePanelOffsetTo(target: Float) {
        offsetAnimationJob?.cancel()
        if (panelOffset.floatValue == target) return
        offsetAnimationJob = scope.launch {
            animate(
                initialValue = panelOffset.floatValue,
                targetValue = target,
                animationSpec = panelSpring
            ) { value, _ ->
                panelOffset.floatValue = value
            }
        }
    }
}

@Composable
internal fun rememberLyricsMenuState(
    isVisible: Boolean,
    isMainScreen: Boolean,
    onDismiss: () -> Unit,
): LyricsMenuState {
    val latestVisible = rememberUpdatedState(isVisible)
    val latestMainScreen = rememberUpdatedState(isMainScreen)
    val latestOnDismiss = rememberUpdatedState(onDismiss)
    val latestDensity = rememberUpdatedState(LocalDensity.current)
    val scope = rememberCoroutineScope()
    return remember {
        LyricsMenuState(
            scope = scope,
            visibleState = latestVisible,
            mainScreenState = latestMainScreen,
            onDismissState = latestOnDismiss,
            densityState = latestDensity,
        )
    }
}
