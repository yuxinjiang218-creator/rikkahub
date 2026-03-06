package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
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
    return buildString {
        appendLine()
        append("**Rolling Summary (Compressed Context)**")
        appendLine()
        append(
            "This is maintained summary context. Treat it as high-priority background facts, " +
                "but allow newer user messages to override stale details."
        )
        appendLine()
        append(rollingSummaryJson)
        appendLine()
    }
}

internal fun buildRecallMemoryGuidancePrompt(): String = """
    **Historical Memory Retrieval**
    A tool named `recall_memory(query)` is available for retrieving manually indexed history.
    Use it sparingly and only when:
    1) the user explicitly asks to recall prior chats, or
    2) missing historical context blocks a correct answer.
    If relevance is uncertain, do not call it.
""".trimIndent()
