package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.parseRollingSummaryDocument
import me.rerere.rikkahub.utils.JsonInstantPretty

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        val json = buildJsonArray {
            memories.forEach { memory ->
                add(
                    buildJsonObject {
                        put("id", memory.id)
                        put("content", memory.content)
                    }
                )
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }

internal fun buildRollingSummaryPrompt(rollingSummaryJson: String): String {
    if (rollingSummaryJson.isBlank()) return ""
    val summaryProjection = parseRollingSummaryDocument(rollingSummaryJson).toCurrentViewProjection()
    if (summaryProjection.isBlank()) return ""
    return buildString {
        appendLine()
        append("**Rolling Summary (Compressed Context)**")
        appendLine()
        append(
            "This is maintained compressed context projected from the structured rolling summary. " +
                "Treat it as high-priority background state, but let newer messages override stale details."
        )
        appendLine()
        append(summaryProjection)
        appendLine()
    }
}

internal fun buildRecallMemoryGuidancePrompt(): String = """
    **Historical Memory Retrieval**
    `recall_memory(query, channel, role)` retrieves structured historical memory for this assistant.
    - Use `channel=current` for still-effective facts, preferences, constraints, tasks, artifacts, and recent compressed context.
    - Use `channel=history` for old versions, change history, and decision evolution.
    - Use `role=user|assistant|any` to focus on user-originated, assistant-originated, or all history.
    If you need the exact original wording after finding relevant history, call `search_source(query, role, candidate_conversation_ids)` and then `read_source(source_ref)`.
""".trimIndent()
