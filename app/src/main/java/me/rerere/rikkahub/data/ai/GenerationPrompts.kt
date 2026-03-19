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

internal fun buildKnowledgeBaseGuidancePrompt(): String = """
    **Knowledge Base Retrieval**
    Available tools:
    - `list_knowledge_base_documents()` lists searchable documents with document id, file name, mime type, and chunk count.
    - `search_knowledge_base(query, document_ids?)` finds relevant snippets, optionally inside chosen documents only.
    - `read_knowledge_base_chunks(document_id, chunk_orders)` reads exact chunk numbers from a chosen document.
    Rules:
    - Use this for uploaded manuals, PDFs, notes, reports, specs, and other document knowledge.
    - For broad teaching requests or a new topic, call `list_knowledge_base_documents()` first, then choose a document before searching.
    - When the user already names a document, search inside that document directly.
    - If a hit looks incomplete, truncated, or missing surrounding explanation, call `read_knowledge_base_chunks` before answering.
    - Do not treat truncated snippets as complete source text and do not silently fill in missing textbook wording.
    - Mention the source file name when answering from retrieved snippets.
    - If the search result quality is `weak`, prefer listing documents or narrowing the document scope before continuing.
    - If the tool returns no relevant match, explicitly say the information was not found in the knowledge base.
""".trimIndent()
