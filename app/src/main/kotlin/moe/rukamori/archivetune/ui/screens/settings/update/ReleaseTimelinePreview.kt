/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.ui.settings.SettingsDimensions
import moe.rukamori.archivetune.ui.theme.TestThemeWrapper
import moe.rukamori.archivetune.ui.theme.ThemePreviews
import moe.rukamori.archivetune.utils.ReleaseNotes
import moe.rukamori.archivetune.utils.ReleaseNotesParser
import moe.rukamori.archivetune.utils.ReleaseNotesSummary

@ThemePreviews
@Composable
private fun ReleaseTimelinePreview() {
    val parsed = ReleaseNotesParser.parse(PreviewNotes) as? ReleaseNotes.Parsed
    val summary = parsed?.summary ?: ReleaseNotesSummary()
    val sections =
        parsed?.sections.orEmpty().map { section ->
            ReleaseTimelineSectionState(
                id = section.id,
                title = section.title,
                category = section.category,
                items = section.items,
            )
        }
    TestThemeWrapper {
        ReleaseTimeline(
            state =
                ReleaseTimelineState(
                    entries =
                        listOf(
                            ReleaseTimelineEntry(
                                releaseKey = "v1.2.0",
                                version = "1.2.0",
                                name = "Timeline release",
                                publishedAt = "2026-10-21T12:00:00Z",
                                isLatest = true,
                                isInstalled = true,
                                isPrerelease = false,
                                imageUrl = null,
                                htmlUrl = "https://github.com/MuwMx/YumaPlayer/releases",
                                sections = sections,
                                summary = summary,
                                fallbackMarkdown = null,
                            ),
                            ReleaseTimelineEntry(
                                releaseKey = "v1.1.0",
                                version = "1.1.0",
                                name = null,
                                publishedAt = "2026-09-14T12:00:00Z",
                                isLatest = false,
                                isInstalled = false,
                                isPrerelease = false,
                                imageUrl = null,
                                htmlUrl = "https://github.com/MuwMx/YumaPlayer/releases",
                                sections = sections,
                                summary = summary,
                                fallbackMarkdown = null,
                            ),
                        ),
                ),
            actions = ReleaseTimelineActions(onOpenRelease = {}),
            listState = rememberLazyListState(),
            bottomPadding = SettingsDimensions.ScreenBottomPadding,
            modifier = Modifier.padding(horizontal = SettingsDimensions.ScreenHorizontalPadding),
        )
    }
}

private const val PreviewNotes =
    """
    # Changelog

    ## Features

    - Add a release timeline with independent sections — by @alex
    - Show readable badges for latest and installed releases — by @sam

    ## Fixes

    - Keep dates aligned with published release timestamps — by @kim

    ## Performance

    - Render long categories in compact previews — by @lee
    """
