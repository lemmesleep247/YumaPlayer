/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.constants.GridThumbnailHeight
import moe.rukamori.archivetune.constants.ListItemHeight
import moe.rukamori.archivetune.ui.theme.LocalYumaColors

// --- Layout Parameters ---
const val ActiveBoxAlpha = 0.6f

private val GridItemHorizontalPadding = 4.dp
private val GridItemVerticalPadding = 4.dp
private val GridItemContentSpacing = 6.dp

@PublishedApi internal val ListItemHorizontalPadding = 8.dp
@PublishedApi internal val ListItemThumbnailPadding = 8.dp
@PublishedApi internal val ListItemTextHorizontalPadding = 6.dp
@PublishedApi internal val ListItemTextSpacing = 2.dp
@PublishedApi internal val ListItemBadgeSpacing = 4.dp
@PublishedApi internal val ActiveItemCornerRadius = 12.dp
@PublishedApi internal const val ActiveGlassAlpha = 0.2f

@Composable
inline fun ListItem(
    modifier: Modifier = Modifier,
    title: String,
    noinline subtitle: (@Composable RowScope.() -> Unit)? = null,
    thumbnailContent: @Composable () -> Unit,
    crossinline trailingContent: @Composable RowScope.() -> Unit = {},
    isActive: Boolean = false,
    isSheetActive: Boolean = true,
) {
    val yumaColors = LocalYumaColors.current
    val titleColor = if (isActive) yumaColors.textPrimary else yumaColors.textPrimary.copy(alpha = 0.85f)
    val subtitleContentColor = if (isActive) yumaColors.textPrimary.copy(alpha = 0.75f) else yumaColors.textSecondary
    val trailingContentColor = if (isActive) yumaColors.textPrimary else yumaColors.textSecondary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .focusable()
                .height(ListItemHeight)
                .padding(horizontal = ListItemHorizontalPadding)
                .then(
                    if (isActive) {
                        Modifier
                            .clip(RoundedCornerShape(ActiveItemCornerRadius))
                            .background(yumaColors.glassBorder.copy(alpha = ActiveGlassAlpha))
                    } else {
                        Modifier
                    },
                ),
    ) {
        Box(Modifier.padding(ListItemThumbnailPadding), contentAlignment = Alignment.Center) { thumbnailContent() }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = ListItemTextHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemTextSpacing),
        ) {
            MarqueeText(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                ),
                maxLines = 1,
                modifier = Modifier,
                isVisible = isSheetActive,
            )
            if (subtitle != null) {
                CompositionLocalProvider(LocalContentColor provides subtitleContentColor) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ListItemBadgeSpacing),
                    ) { subtitle() }
                }
            }
        }
        CompositionLocalProvider(LocalContentColor provides trailingContentColor) {
            trailingContent()
        }
    }
}

@Composable
fun ListItem(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String?,
    badges: @Composable RowScope.() -> Unit = {},
    thumbnailContent: @Composable () -> Unit,
    trailingContent: @Composable RowScope.() -> Unit = {},
    isActive: Boolean = false,
    isSheetActive: Boolean = true,
) = ListItem(
    title = title,
    modifier = modifier,
    isActive = isActive,
    isSheetActive = isSheetActive,
    subtitle = {
        badges()
        if (!subtitle.isNullOrEmpty()) {
            val subtitleColor =
                if (isActive) {
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(
                        alpha = 0.7f,
                    )
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            MarqueeText(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(color = subtitleColor),
                maxLines = 1,
                modifier = Modifier,
                isVisible = isSheetActive,
            )
        }
    },
    thumbnailContent = thumbnailContent,
    trailingContent = trailingContent,
)

@Composable
fun GridItem(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    subtitle: @Composable () -> Unit,
    badges: @Composable RowScope.() -> Unit = {},
    thumbnailContent: @Composable BoxWithConstraintsScope.() -> Unit,
    thumbnailRatio: Float = 1f,
    fillMaxWidth: Boolean = false,
    thumbnailHeight: Dp = GridThumbnailHeight,
) {
    Column(
        modifier =
            if (fillMaxWidth) {
                modifier
                    .focusable()
                    .padding(horizontal = GridItemHorizontalPadding, vertical = GridItemVerticalPadding)
                    .fillMaxWidth()
            } else {
                modifier
                    .focusable()
                    .padding(horizontal = GridItemHorizontalPadding, vertical = GridItemVerticalPadding)
                    .width(thumbnailHeight * thumbnailRatio)
            },
    ) {
        BoxWithConstraints(
            contentAlignment = Alignment.Center,
            modifier =
                if (fillMaxWidth) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.height(thumbnailHeight)
                }.aspectRatio(thumbnailRatio),
        ) {
            thumbnailContent()
        }

        Spacer(modifier = Modifier.height(GridItemContentSpacing))

        title()

        Row(verticalAlignment = Alignment.CenterVertically) {
            badges()

            subtitle()
        }
    }
}

@Composable
fun GridItem(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    badges: @Composable RowScope.() -> Unit = {},
    thumbnailContent: @Composable BoxWithConstraintsScope.() -> Unit,
    thumbnailRatio: Float = 1f,
    fillMaxWidth: Boolean = false,
    thumbnailHeight: Dp = GridThumbnailHeight,
) = GridItem(
    modifier = modifier,
    title = {
        MarqueeText(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    },
    subtitle = {
        MarqueeText(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
        )
    },
    badges = badges,
    thumbnailContent = thumbnailContent,
    thumbnailRatio = thumbnailRatio,
    fillMaxWidth = fillMaxWidth,
    thumbnailHeight = thumbnailHeight,
)