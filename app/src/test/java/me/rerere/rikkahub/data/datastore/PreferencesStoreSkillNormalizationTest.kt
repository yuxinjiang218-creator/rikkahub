package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class PreferencesStoreSkillNormalizationTest {
    @Test
    fun `normalize enabled skill keys rewrites display names to directory names`() {
        val aliases = linkedMapOf(
            "terminal-helper" to "terminal-helper",
            "Terminal Helper" to "terminal-helper",
        )

        assertEquals(
            setOf("terminal-helper"),
            normalizeEnabledSkillKeys(
                enabledSkills = setOf("Terminal Helper"),
                skillDirectoryAliases = aliases,
            )
        )
    }

    @Test
    fun `normalize enabled skill keys keeps directory names and drops unknown values`() {
        val aliases = linkedMapOf(
            "skill-a" to "skill-a",
            "Shared Name" to "skill-a",
            "skill-b" to "skill-b",
        )

        assertEquals(
            setOf("skill-a", "skill-b"),
            normalizeEnabledSkillKeys(
                enabledSkills = setOf("skill-a", "skill-b", "missing"),
                skillDirectoryAliases = aliases,
            )
        )
    }
}
