/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.runtime.Immutable
import moe.rukamori.archivetune.utils.ReleaseNoteCategory
import moe.rukamori.archivetune.utils.ReleaseNoteItem
import moe.rukamori.archivetune.utils.ReleaseNotesSummary

@Immutable
data class ReleaseTimelineState(
    val entries: List<ReleaseTimelineEntry>,
)

@Immutable
data class ReleaseTimelineEntry(
    val releaseKey: String,
    val version: String,
    val name: String?,
    val publishedAt: String,
    val isLatest: Boolean,
    val isInstalled: Boolean,
    val isPrerelease: Boolean,
    val imageUrl: String?,
    val htmlUrl: String?,
    val sections: List<ReleaseTimelineSectionState>,
    val summary: ReleaseNotesSummary,
    val fallbackMarkdown: String?,
    val isExpanded: Boolean = false,
)

@Immutable
data class ReleaseTimelineSectionState(
    val id: String,
    val title: String?,
    val category: ReleaseNoteCategory,
    val items: List<ReleaseNoteItem>,
    val isExpanded: Boolean = false,
    val isShowingAll: Boolean = false,
)

@Immutable
data class ReleaseTimelineActions(
    val onOpenRelease: (String) -> Unit,
)