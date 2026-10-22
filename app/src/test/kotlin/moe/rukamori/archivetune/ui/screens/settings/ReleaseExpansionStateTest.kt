/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseExpansionStateTest {

    @Test
    fun `seeds the newest release and keeps previously seen defaults`() {
        val expansion = TimelineExpansion()

        expansion.seedReleases(listOf("v1.1.0", "v1.0.0"))
        expansion.seedReleases(listOf("v1.2.0", "v1.1.0", "v1.0.0"))

        assertTrue(expansion.isReleaseExpanded("v1.2.0", default = false))
        assertTrue(expansion.isReleaseExpanded("v1.1.0", default = false))
        assertFalse(expansion.isReleaseExpanded("v1.0.0", default = true))
    }

    @Test
    fun `explicit release choices survive list changes`() {
        val expansion = TimelineExpansion()
        expansion.seedReleases(listOf("v1.1.0", "v1.0.0"))
        expansion.toggleRelease("v1.1.0", default = true)

        expansion.seedReleases(listOf("v1.2.0", "v1.1.0", "v1.0.0"))

        assertFalse(expansion.isReleaseExpanded("v1.1.0", default = true))
        assertTrue(expansion.isReleaseExpanded("v1.2.0", default = false))
    }

    @Test
    fun `sections and previews start closed and are scoped by release`() {
        val expansion = TimelineExpansion()
        val releaseKey = "v1.2.0"
        val sectionId = "features"

        assertFalse(expansion.isSectionExpanded(releaseKey, sectionId))
        assertFalse(expansion.isSectionShowingAll(releaseKey, sectionId))

        expansion.toggleSection(releaseKey, sectionId)
        expansion.toggleSectionPreview(releaseKey, sectionId)

        assertTrue(expansion.isSectionExpanded(releaseKey, sectionId))
        assertTrue(expansion.isSectionShowingAll(releaseKey, sectionId))
        assertFalse(expansion.isSectionExpanded("v1.1.0", sectionId))
        assertFalse(expansion.isSectionShowingAll("v1.1.0", sectionId))
    }
}
