package me.rerere.rikkahub.ui.components.ai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.data.files.SkillMetadata

class ExtensionContentTest {
    @Test
    fun `skill toggle uses directory name instead of display name`() {
        val skill = skillMetadata(
            directoryName = "terminal-helper",
            name = "Terminal Helper",
        )

        assertEquals("terminal-helper", skillToggleKey(skill))
        assertTrue(isSkillEnabled(setOf("terminal-helper"), skill))
        assertFalse(isSkillEnabled(setOf("Terminal Helper"), skill))
    }

    @Test
    fun `skills with same display name keep distinct toggle keys`() {
        val first = skillMetadata(directoryName = "skill-a", name = "Shared Name")
        val second = skillMetadata(directoryName = "skill-b", name = "Shared Name")

        assertEquals("skill-a", skillToggleKey(first))
        assertEquals("skill-b", skillToggleKey(second))
        assertTrue(isSkillEnabled(setOf("skill-a"), first))
        assertFalse(isSkillEnabled(setOf("skill-a"), second))
    }

    private fun skillMetadata(
        directoryName: String,
        name: String,
    ): SkillMetadata {
        return SkillMetadata(
            directoryName = directoryName,
            name = name,
            description = "desc",
            skillDir = File("/tmp/$directoryName"),
        )
    }
}
