/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.MarkdownText
import moe.rukamori.archivetune.ui.settings.SettingsAnimations
import moe.rukamori.archivetune.ui.settings.SettingsDimensions
import moe.rukamori.archivetune.ui.theme.LocalYumaColors
import moe.rukamori.archivetune.ui.theme.yumaClickable
import moe.rukamori.archivetune.ui.theme.yumaGlassCard
import moe.rukamori.archivetune.utils.ReleaseNoteCategory

@Composable
internal fun ReleaseTimelineSection(
    releaseKey: String,
    section: ReleaseTimelineSectionState,
    onToggle: (String, String) -> Unit,
    onTogglePreview: (String, String) -> Unit,
) {
    val title = section.title ?: stringResource(sectionTitleRes(section.category))
    val categoryColors = releaseCategoryColors(section.category)
    val colors = LocalYumaColors.current
    val sectionShape = RoundedCornerShape(SettingsDimensions.LibrarySmallRadius)

    val stateLabel =
        stringResource(
            if (section.isExpanded) R.string.release_state_expanded else R.string.release_state_collapsed,
        )
    val rotation by animateFloatAsState(
        targetValue = if (section.isExpanded) 180f else 0f,
        animationSpec = SettingsAnimations.resizeSpring(),
        label = "release_section_chevron",
    )
    val actionLabel =
        if (section.isShowingAll) {
            stringResource(R.string.release_notes_show_less)
        } else {
            stringResource(R.string.release_notes_show_all, section.items.size)
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .yumaGlassCard(
                    shape = sectionShape,
                    backgroundColor = colors.glassBackground,
                    borderColor = colors.glassBorder,
                    strokeWidth = SettingsDimensions.GlassBorderThickness,
                ).padding(SettingsDimensions.BadgeSpacing),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(sectionShape)
                    .minimumInteractiveComponentSize()
                    .yumaClickable(onClick = { onToggle(releaseKey, section.id) })
                    .padding(
                        horizontal = SettingsDimensions.BadgePaddingH,
                        vertical = SettingsDimensions.BadgeSpacing,
                    ).semantics { stateDescription = stateLabel },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = categoryColors.container,
                modifier = Modifier.size(SettingsDimensions.BannerIconInnerSize),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = categoryIcon(section.category),
                        contentDescription = null,
                        tint = categoryColors.accent,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(SettingsDimensions.BadgePaddingH))

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = categoryColors.accent,
                modifier = Modifier.weight(1f),
            )

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = categoryColors.container,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                Text(
                    text = section.items.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = categoryColors.content,
                    modifier =
                        Modifier.padding(
                            horizontal = SettingsDimensions.BadgePaddingH,
                            vertical = SettingsDimensions.BadgePaddingV,
                        ),
                )
            }

            Spacer(modifier = Modifier.width(SettingsDimensions.BadgeSpacing))

            Icon(
                painter = painterResource(R.drawable.expand_more),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .size(SettingsDimensions.ChevronSize)
                        .rotate(rotation),
            )
        }

        AnimatedVisibility(
            visible = section.isExpanded,
            enter = expandVertically(animationSpec = SettingsAnimations.resizeSpring()),
            exit = shrinkVertically(animationSpec = SettingsAnimations.resizeSpring()),
            label = "release_section_content",
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = SettingsDimensions.BadgePaddingH,
                            vertical = SettingsDimensions.BadgeSpacing,
                        ).animateContentSize(SettingsAnimations.resizeSpring()),
                verticalArrangement = Arrangement.spacedBy(SettingsDimensions.LibraryItemGap),
            ) {
                val visibleItems =
                    if (section.isShowingAll) {
                        section.items
                    } else {
                        section.items.take(ReleaseTimelinePreviewLimit)
                    }

                visibleItems.forEach { item ->
                    MarkdownText(
                        markdown = item.markdown,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (section.items.size > ReleaseTimelinePreviewLimit) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(6.dp))
                                .yumaClickable(onClick = { onTogglePreview(releaseKey, section.id) })
                                .padding(vertical = SettingsDimensions.BadgeSpacing),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReleaseTimelineFallback(markdown: String?) {
    val colors = LocalYumaColors.current
    val sectionShape = RoundedCornerShape(SettingsDimensions.LibrarySmallRadius)

    if (markdown.isNullOrBlank()) {
        Text(
            text = stringResource(R.string.release_notes_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(SettingsDimensions.BadgeSpacing),
        )
    } else {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .yumaGlassCard(
                        shape = sectionShape,
                        backgroundColor = colors.glassBackground,
                        borderColor = colors.glassBorder,
                        strokeWidth = SettingsDimensions.GlassBorderThickness,
                    ).padding(SettingsDimensions.BadgePaddingH),
        ) {
            MarkdownText(
                markdown = markdown,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun categoryIcon(category: ReleaseNoteCategory): ImageVector =
    when (category) {
        ReleaseNoteCategory.FEATURES -> Icons.Rounded.AutoAwesome
        ReleaseNoteCategory.FIXES -> Icons.Rounded.BugReport
        ReleaseNoteCategory.PERFORMANCE -> Icons.Rounded.Bolt
        ReleaseNoteCategory.IMPROVEMENTS -> Icons.Rounded.TrendingUp
        ReleaseNoteCategory.REFACTORS -> Icons.Rounded.Code
        ReleaseNoteCategory.DOCUMENTATION -> Icons.Rounded.MenuBook
        ReleaseNoteCategory.BUILD_CI -> Icons.Rounded.Build
        ReleaseNoteCategory.OTHER -> Icons.Rounded.Info
    }

@StringRes
private fun sectionTitleRes(category: ReleaseNoteCategory): Int =
    when (category) {
        ReleaseNoteCategory.FEATURES -> R.string.release_notes_section_features
        ReleaseNoteCategory.FIXES -> R.string.release_notes_section_fixes
        ReleaseNoteCategory.PERFORMANCE -> R.string.release_notes_section_performance
        ReleaseNoteCategory.IMPROVEMENTS -> R.string.release_notes_section_improvements
        ReleaseNoteCategory.REFACTORS -> R.string.release_notes_section_refactors
        ReleaseNoteCategory.DOCUMENTATION -> R.string.release_notes_section_documentation
        ReleaseNoteCategory.BUILD_CI -> R.string.release_notes_section_build_ci
        ReleaseNoteCategory.OTHER -> R.string.release_notes_section_other
    }

internal data class ReleaseCategoryColors(
    val container: Color,
    val content: Color,
    val accent: Color,
)

@Composable
internal fun releaseCategoryColors(category: ReleaseNoteCategory): ReleaseCategoryColors {
    val scheme = MaterialTheme.colorScheme
    return when (category) {
        ReleaseNoteCategory.FEATURES ->
            ReleaseCategoryColors(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary)
        ReleaseNoteCategory.FIXES ->
            ReleaseCategoryColors(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary)
        ReleaseNoteCategory.PERFORMANCE ->
            ReleaseCategoryColors(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.secondary)
        ReleaseNoteCategory.IMPROVEMENTS ->
            ReleaseCategoryColors(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary)
        ReleaseNoteCategory.REFACTORS ->
            ReleaseCategoryColors(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.secondary)
        ReleaseNoteCategory.DOCUMENTATION ->
            ReleaseCategoryColors(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary)
        ReleaseNoteCategory.BUILD_CI ->
            ReleaseCategoryColors(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.secondary)
        ReleaseNoteCategory.OTHER ->
            ReleaseCategoryColors(scheme.surfaceContainerHighest, scheme.onSurfaceVariant, scheme.onSurfaceVariant)
    }
}

private const val ReleaseTimelinePreviewLimit = 7