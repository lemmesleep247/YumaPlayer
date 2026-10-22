/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.utils

import java.util.Locale

enum class ReleaseNoteCategory {
    FEATURES,
    FIXES,
    PERFORMANCE,
    IMPROVEMENTS,
    REFACTORS,
    DOCUMENTATION,
    BUILD_CI,
    OTHER,
}

data class ReleaseNoteItem(
    val markdown: String,
)

data class ReleaseNoteSection(
    val id: String,
    val category: ReleaseNoteCategory,
    val title: String?,
    val items: List<ReleaseNoteItem>,
)

data class ReleaseNotesSummary(
    val features: Int = 0,
    val fixes: Int = 0,
    val performance: Int = 0,
)

sealed interface ReleaseNotes {
    val source: String

    data class Parsed(
        override val source: String,
        val sections: List<ReleaseNoteSection>,
        val summary: ReleaseNotesSummary,
    ) : ReleaseNotes

    data class Fallback(
        override val source: String,
    ) : ReleaseNotes
}

object ReleaseNotesParser {

    private val headingPattern = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val headingPrefixPattern = Regex("""^(?:>\s*|[-*+]\s+)+""")
    private val listPattern = Regex("""^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$""")
    private val fenceOpenPattern = Regex("""^\s{0,3}(`{3,}|~{3,})(.*)$""")
    private val detailsOpenPattern = Regex("""^<details(?:\s[^>]*)?>\s*$""", RegexOption.IGNORE_CASE)
    private val detailsClosePattern = Regex("""^</details>\s*$""", RegexOption.IGNORE_CASE)
    private val summaryPattern = Regex("""^<summary>(.*?)</summary>\s*$""", RegexOption.IGNORE_CASE)

    private val canonicalHeadings: Map<String, ReleaseNoteCategory> =
        mapOf(
            // Features / New
            "features" to ReleaseNoteCategory.FEATURES,
            "feature" to ReleaseNoteCategory.FEATURES,
            "new" to ReleaseNoteCategory.FEATURES,
            "new feature" to ReleaseNoteCategory.FEATURES,
            "new features" to ReleaseNoteCategory.FEATURES,
            "added" to ReleaseNoteCategory.FEATURES,
            "additions" to ReleaseNoteCategory.FEATURES,

            // Fixes / Bugs
            "fixes" to ReleaseNoteCategory.FIXES,
            "fix" to ReleaseNoteCategory.FIXES,
            "bug fixes" to ReleaseNoteCategory.FIXES,
            "bug fix" to ReleaseNoteCategory.FIXES,
            "bugs" to ReleaseNoteCategory.FIXES,
            "fixed" to ReleaseNoteCategory.FIXES,

            // Performance
            "performance" to ReleaseNoteCategory.PERFORMANCE,
            "performance improvement" to ReleaseNoteCategory.PERFORMANCE,
            "performance improvements" to ReleaseNoteCategory.PERFORMANCE,
            "perf" to ReleaseNoteCategory.PERFORMANCE,

            // Improvements
            "improvements" to ReleaseNoteCategory.IMPROVEMENTS,
            "improvement" to ReleaseNoteCategory.IMPROVEMENTS,
            "improved" to ReleaseNoteCategory.IMPROVEMENTS,

            // Refactors
            "refactors" to ReleaseNoteCategory.REFACTORS,
            "refactor" to ReleaseNoteCategory.REFACTORS,
            "refactoring" to ReleaseNoteCategory.REFACTORS,

            // Documentation
            "documentation" to ReleaseNoteCategory.DOCUMENTATION,
            "docs" to ReleaseNoteCategory.DOCUMENTATION,

            // Build / CI
            "build / ci" to ReleaseNoteCategory.BUILD_CI,
            "build/ci" to ReleaseNoteCategory.BUILD_CI,
            "build ci" to ReleaseNoteCategory.BUILD_CI,
            "build & ci" to ReleaseNoteCategory.BUILD_CI,
            "build and ci" to ReleaseNoteCategory.BUILD_CI,
            "build" to ReleaseNoteCategory.BUILD_CI,
            "ci" to ReleaseNoteCategory.BUILD_CI,

            // GitHub Auto-generated defaults & Others
            "what's changed" to ReleaseNoteCategory.OTHER,
            "whats changed" to ReleaseNoteCategory.OTHER,
            "new contributors" to ReleaseNoteCategory.DOCUMENTATION,
            "contributors" to ReleaseNoteCategory.DOCUMENTATION,
            "other" to ReleaseNoteCategory.OTHER,
            "others" to ReleaseNoteCategory.OTHER,
            "other changes" to ReleaseNoteCategory.OTHER,
            "misc" to ReleaseNoteCategory.OTHER,
            "miscellaneous" to ReleaseNoteCategory.OTHER,
            "uncategorized" to ReleaseNoteCategory.OTHER,
            "uncategorised" to ReleaseNoteCategory.OTHER,
        )

    fun parse(body: String?): ReleaseNotes {
        val source = body.orEmpty()
        val normalized = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')

        val sections = mutableListOf<ReleaseNoteSection>()
        val sectionIdCounts = mutableMapOf<String, Int>()
        var currentSection: SectionBuilder? = null
        var currentItem: ItemBuilder? = null
        var pendingBlankLine = false
        var inFence = false
        var fenceMarker = '`'
        var fenceLength = 0
        var detailsDepth = 0

        fun nextSectionId(base: String): String {
            val count = (sectionIdCounts[base] ?: 0) + 1
            sectionIdCounts[base] = count
            return if (count == 1) base else "$base-$count"
        }

        fun flushItem() {
            val item = currentItem ?: return
            val markdown = item.build().markdown
            if (markdown.isNotBlank()) {
                currentSection?.items?.add(ReleaseNoteItem(markdown = markdown))
            }
            currentItem = null
            pendingBlankLine = false
        }

        fun flushSection() {
            flushItem()
            val section = currentSection ?: return
            if (section.items.isNotEmpty()) {
                sections += section.build()
            }
            currentSection = null
        }

        fun beginSection(rawTitle: String) {
            flushSection()
            val cleanedTitle = cleanHeadingTitle(rawTitle)
            if (cleanedTitle.isBlank()) return

            val normalizedTitle = normalizeHeading(cleanedTitle)
            val category = canonicalHeadings[normalizedTitle]
            val title = if (category == null) cleanedTitle else null
            val baseId = if (category == null) slugify(cleanedTitle) else category.name.lowercase(Locale.ROOT)
            currentSection =
                SectionBuilder(
                    category = category ?: ReleaseNoteCategory.OTHER,
                    title = title,
                    id = nextSectionId(baseId.ifBlank { "section" }),
                )
        }

        fun beginUncategorizedSection() {
            if (currentSection != null) return
            currentSection =
                SectionBuilder(
                    category = ReleaseNoteCategory.OTHER,
                    title = null,
                    id = nextSectionId("other-uncategorized"),
                )
        }

        fun beginItem(line: String, indent: Int) {
            flushItem()
            currentItem = ItemBuilder(indent).apply { add(line) }
        }

        fun handleStructuralWrapper(line: String): Boolean {
            if (line.isEmpty() || line.first().isWhitespace()) return false

            if (detailsOpenPattern.matches(line)) {
                flushSection()
                detailsDepth += 1
                pendingBlankLine = false
                return true
            }

            if (detailsClosePattern.matches(line)) {
                if (detailsDepth > 0) {
                    flushSection()
                    detailsDepth -= 1
                }
                pendingBlankLine = false
                return true
            }

            val summary = summaryPattern.matchEntire(line)
            if (summary != null) {
                beginSection(summary.groupValues[1].trim())
                pendingBlankLine = false
                return true
            }

            return false
        }

        for (line in lines) {
            if (inFence) {
                if (currentItem != null) {
                    currentItem?.add(line)
                }
                if (isClosingFence(line, fenceMarker, fenceLength)) {
                    inFence = false
                    fenceLength = 0
                }
                continue
            }

            if (handleStructuralWrapper(line)) continue
            if (line.trim().matches(Regex("""^[-*_]{3,}\s*$"""))) continue

            val fence = fenceOpenPattern.matchEntire(line)
            if (fence != null) {
                val fenceRun = fence.groupValues[1]
                fenceMarker = fenceRun.first()
                fenceLength = fenceRun.length
                inFence = true
                if (currentItem != null) {
                    if (pendingBlankLine) {
                        currentItem?.add("")
                        pendingBlankLine = false
                    }
                    currentItem?.add(line)
                }
                continue
            }

            val heading = headingPattern.matchEntire(line)
            if (heading != null && line == line.trimStart()) {
                val title = heading.groupValues[2].trim()
                if (title.isNotBlank()) {
                    beginSection(title)
                }
                continue
            }

            val listItem = listPattern.matchEntire(line)
            if (listItem != null) {
                val indent = listItem.groupValues[1].length
                val activeItem = currentItem
                if (activeItem == null) {
                    beginUncategorizedSection()
                    beginItem(line, indent)
                } else if (indent > activeItem.indent) {
                    if (pendingBlankLine) {
                        activeItem.add("")
                        pendingBlankLine = false
                    }
                    activeItem.add(line)
                } else {
                    beginItem(line, indent)
                }
                continue
            }

            if (line.isBlank()) {
                if (currentItem != null) {
                    pendingBlankLine = true
                }
                continue
            }

            val activeItem = currentItem
            if (activeItem == null) {
                beginUncategorizedSection()
                beginItem(line, indent = 0)
            } else {
                if (pendingBlankLine) {
                    activeItem.add("")
                    pendingBlankLine = false
                }
                activeItem.add(line)
            }
        }

        flushSection()

        if (sections.isEmpty()) {
            return ReleaseNotes.Fallback(source = source)
        }

        return ReleaseNotes.Parsed(
            source = source,
            sections = sections,
            summary =
                ReleaseNotesSummary(
                    features = sections.countItems(ReleaseNoteCategory.FEATURES),
                    fixes = sections.countItems(ReleaseNoteCategory.FIXES),
                    performance = sections.countItems(ReleaseNoteCategory.PERFORMANCE),
                ),
        )
    }

    private fun List<ReleaseNoteSection>.countItems(category: ReleaseNoteCategory): Int =
        filter { it.category == category }.sumOf { it.items.size }

    private fun cleanHeadingTitle(text: String): String =
        text
            .trim()
            .replace(headingPrefixPattern, "")
            .trim()

    private val emojiPattern = Regex("""[\p{So}\p{Sk}\p{Cs}\uFE0F]""")

    private fun normalizeHeading(text: String): String =
        text
            .trim()
            .trimEnd('#')
            .trim()
            .replace(emojiPattern, "")
            .replace("*", "")
            .replace("_", "")
            .replace("`", "")
            .trimEnd(':')
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")

    private fun isClosingFence(line: String, marker: Char, minLength: Int): Boolean {
        val trimmed = line.trimStart(' ')
        if (line.length - trimmed.length > 3) return false
        val runLength = trimmed.takeWhile { it == marker }.length
        if (runLength < minLength) return false
        return trimmed.drop(runLength).isBlank()
    }

    private fun slugify(text: String): String =
        text
            .trim()
            .lowercase(Locale.ROOT)
            .map { character -> if (character.isLetterOrDigit()) character else '-' }
            .joinToString(separator = "")
            .trim('-')
            .replace(Regex("""-{2,}"""), "-")
            .take(48)

    private class SectionBuilder(
        val category: ReleaseNoteCategory,
        val title: String?,
        val id: String,
    ) {
        val items = mutableListOf<ReleaseNoteItem>()

        fun build(): ReleaseNoteSection =
            ReleaseNoteSection(
                id = id,
                category = category,
                title = title,
                items = items.toList(),
            )
    }

    private class ItemBuilder(
        val indent: Int,
    ) {
        private val lines = mutableListOf<String>()

        fun add(line: String) {
            lines += line
        }

        fun build(): ReleaseNoteItem {
            while (lines.isNotEmpty() && lines.last().isBlank()) {
                lines.removeAt(lines.lastIndex)
            }
            val dedented =
                lines.map { line ->
                    if (line.isBlank()) {
                        ""
                    } else {
                        val leadingWhitespace = line.length - line.trimStart().length
                        line.drop(minOf(indent, leadingWhitespace))
                    }
                }
            return ReleaseNoteItem(markdown = dedented.joinToString("\n").trim())
        }
    }
}