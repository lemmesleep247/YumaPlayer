package moe.rukamori.archivetune.ui.screens.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp

object HomeLayoutConfig {
    val shelfSpacing: Dp = 10.dp
    val shelfHorizontalPadding: Dp = 16.dp

    fun cardWidth(availableWidth: Dp): Dp {
        val usableWidth = availableWidth - (shelfHorizontalPadding * 2)
        return (usableWidth / 2.35f).coerceIn(132.dp, 160.dp)
    }

    fun artistSize(availableWidth: Dp): Dp =
        (cardWidth(availableWidth) * 0.90f).coerceIn(122.dp, 146.dp)

    fun quickPicksHeroHeight(availableWidth: Dp): Dp = when {
        availableWidth >= 840.dp -> 370.dp
        availableWidth >= 600.dp -> 345.dp
        else -> 310.dp
    }
}