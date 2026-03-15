package me.rerere.rikkahub.data.skills

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant

fun shouldInjectSkillsCatalog(
    assistant: Assistant,
    model: Model,
): Boolean {
    return assistant.localTools.contains(LocalToolOption.Container) &&
        model.abilities.contains(ModelAbility.TOOL)
}

fun buildSkillsCatalogPrompt(
    assistant: Assistant,
    model: Model,
    catalog: SkillsCatalogState,
): String? {
    if (!shouldInjectSkillsCatalog(assistant, model)) return null

    val selectedEntries = catalog.entries
        .filter { it.directoryName in assistant.enabledSkills }
        .sortedBy { it.directoryName }

    return buildString {
        appendLine("Local skills are available inside the container.")
        appendLine("The writable skill library is mounted at /skills.")
        appendLine("Enabled skills are mirrored read-only at /opt/rikkahub/skills.")
        appendLine("Only inspect a skill when it is relevant to the user's request. Do not read every SKILL.md preemptively.")
        appendLine("When a user wants you to create or update a reusable skill package, write it under /skills/<directory> with a SKILL.md frontmatter containing name and description.")
        if (selectedEntries.isEmpty()) {
            appendLine("There are currently no enabled skills for this assistant.")
        } else {
            appendLine()
            appendLine("Enabled skills:")
            selectedEntries.forEach { skill ->
                appendLine("- directory: ${skill.directoryName}")
                appendLine("  name: ${skill.name}")
                appendLine("  description: ${skill.description}")
                appendLine("  path: /opt/rikkahub/skills/${skill.directoryName}")
            }
        }
    }.trim()
}
