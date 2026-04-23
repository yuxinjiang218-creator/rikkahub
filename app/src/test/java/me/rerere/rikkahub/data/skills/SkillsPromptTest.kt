package me.rerere.rikkahub.data.skills

import java.util.UUID
import kotlin.uuid.toKotlinUuid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant

class SkillsPromptTest {
    @Test
    fun `build skills catalog prompt filters by directory name`() {
        val assistant = Assistant(
            enabledSkills = setOf("terminal-helper"),
            localTools = listOf(LocalToolOption.Container),
        )
        val model = toolModel()
        val catalog = SkillsCatalogState(
            entries = listOf(
                SkillCatalogEntry(
                    directoryName = "terminal-helper",
                    path = "/skills/terminal-helper",
                    name = "Terminal Helper",
                    description = "desc",
                ),
                SkillCatalogEntry(
                    directoryName = "writer",
                    path = "/skills/writer",
                    name = "Writer",
                    description = "desc",
                ),
            )
        )

        val prompt = buildSkillsCatalogPrompt(assistant, model, catalog)

        assertTrue(prompt!!.contains("directory: terminal-helper"))
        assertFalse(prompt.contains("directory: writer"))
    }

    @Test
    fun `build skills catalog prompt reports empty enabled skills`() {
        val assistant = Assistant(
            enabledSkills = emptySet(),
            localTools = listOf(LocalToolOption.Container),
        )

        val prompt = buildSkillsCatalogPrompt(assistant, toolModel(), SkillsCatalogState(entries = emptyList()))

        assertTrue(prompt!!.contains("There are currently no enabled skills for this assistant."))
    }

    private fun toolModel(): Model {
        return Model(
            id = UUID.randomUUID().toKotlinUuid(),
            displayName = "tool-model",
            type = ModelType.CHAT,
            abilities = listOf(ModelAbility.TOOL),
        )
    }
}
