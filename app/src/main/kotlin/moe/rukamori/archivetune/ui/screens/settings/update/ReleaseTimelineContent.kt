/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.settings.SettingsDimensions

@Composable
internal fun ReleaseNotesContent(
    entry: ReleaseTimelineEntry,
    onToggleSection: (String, String) -> Unit,
    onToggleSectionPreview: (String, String) -> Unit,
) {
    when {
        entry.sections.isNotEmpty() -> {
            Column(verticalArrangement = Arrangement.spacedBy(SettingsDimensions.YumaCategorySpacing)) {
                entry.sections.forEach { section ->
                    key(section.id) {
                        ReleaseTimelineSection(
                            releaseKey = entry.releaseKey,
                            section = section,
                            onToggle = onToggleSection,
                            onTogglePreview = onToggleSectionPreview,
                        )
                    }
                }
            }
        }

        entry.fallbackMarkdown != null -> {
            val fallbackMarkdown = entry.fallbackMarkdown
            ReleaseTimelineFallback(markdown = fallbackMarkdown)
        }

        else -> {
            Text(
                text = stringResource(R.string.release_notes_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ReleaseImage(
    imageUrl: String?,
    shape: RoundedCornerShape,
) {
    if (imageUrl.isNullOrBlank()) return

    val context = LocalContext.current
    val imageModel =
        remember(context, imageUrl) {
            ImageRequest
                .Builder(context)
                .data(imageUrl)
                .crossfade(true)
                .build()
        }
    AsyncImage(
        model = imageModel,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = releaseImageMaxHeight)
                .clip(shape),
    )
}

@Composable
internal fun ReleaseGithubAction(
    url: String,
    onOpenRelease: (String) -> Unit,
) {
    TextButton(
        onClick = { onOpenRelease(url) },
        modifier = Modifier.minimumInteractiveComponentSize(),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_github),
            contentDescription = null,
            modifier = Modifier.size(SettingsDimensions.ChevronSize),
        )
        Spacer(modifier = Modifier.width(SettingsDimensions.BadgeSpacing))
        Text(
            text = stringResource(R.string.release_notes_open_on_github),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private val releaseImageMaxHeight = 180.dp
