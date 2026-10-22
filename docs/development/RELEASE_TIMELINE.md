# Release Timeline

`ChangelogScreen` loads releases through `Updater` and builds an immutable `ReleaseTimelineState`.
`ReleaseTimeline` renders that state and owns only presentation state. Fetching, caching, channel
selection, navigation, and URL opening stay outside the timeline.

## Parser contract

`ReleaseNotesParser.parse(body)` is a pure Kotlin parser with no Android dependencies.

- Structured parsing accepts ATX headings and unordered or ordered list entries.
- Canonical headings map to categories; unknown headings with entries stay in `OTHER` and keep their
  cleaned title.
- Leading quote and bullet markers are removed from section titles, while inline Markdown such as
  `**Migration**` is preserved.
- Nested lists, fenced blocks, tables, blockquotes, and continuation paragraphs stay attached to
  their parent entry and are not counted.
- A fenced block closes only on the same marker with at least the opening fence length.
- `<details><summary>` wrappers start a separate scope; an uncategorized body does not inherit the
  previous category.
- Unsupported top-level content (prose outside entries, top-level code, flush-left tables, HTML,
  quotes, thematic breaks, malformed details) returns `ReleaseNotes.Fallback` with the exact source.
- Empty unknown headings fall back unless the heading is the top-level `# Changelog` wrapper.
- Empty notes also return the fallback.

## Disclosure state

`ReleaseTimeline` keeps a local `TimelineExpansion` instance in `remember`. It is intentionally not
saved across process recreation.

- Release keys are `tagName`, or name/URL plus `publishedAt` when the tag is empty.
- Releases are seeded when the entry list changes. The newest release starts expanded; previously
  seeded releases keep their value, so inserting a newer release does not collapse the old latest.
- Section and preview keys are `"$releaseKey#$sectionId"`. Sections start closed and previews start
  collapsed.
- Cards and sections stay stateless; they receive only booleans and callbacks.
- `ReleaseTimelineActions` exposes only `onOpenRelease`; all toggle consumers are local.

## Presentation

- The height of each timeline entry includes its bottom gap, so the rail is continuous.
- Node Y is the measured first baseline of the version text, propagated as pixels relative to the
  entry. It does not use line-height or fixed-offset approximations, and the rail does not take part
  in the card's measurement.
- The rail stops before each node by the node radius plus a 4 dp gap.
- Installed nodes use the theme tertiary role and a pulsing halo (`alpha 0.15..0.45`, extra radius
  `2..5.5 dp`). The animation is created only for installed nodes, is skipped when
  `LocalAnimationsDisabled` is set, and its state is read inside the draw lambda.
- Category sections are independent glass cards with a 12 dp radius and 8 dp gap. Icons come from
  the existing Material icon set: sparkles, bug, bolt, trend, code, book, build, and info.
- Sections preview seven entries, render each parsed item separately, and offer a centered
  Show all / Collapse action.
