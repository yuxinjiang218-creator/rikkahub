package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.KnowledgeBaseDocumentSummary

fun buildListKnowledgeBaseDocumentsTool(
    json: Json,
    onList: suspend () -> List<KnowledgeBaseDocumentSummary>,
): Tool {
    return Tool(
        name = "list_knowledge_base_documents",
        description = """
            List the current assistant's searchable knowledge base documents before choosing which document to search.
            Use this first when the user starts a broad teaching topic or when you need to pick the most suitable document.
        """.trimIndent(),
        execute = {
            val documents = onList()
            val payload = buildJsonObject {
                put("returned_count", documents.size)
                put(
                    "documents",
                    buildJsonArray {
                        documents.forEach { document ->
                            add(
                                buildJsonObject {
                                    put("document_id", document.documentId)
                                    put("document_name", document.documentName)
                                    put("mime_type", document.mimeType)
                                    put("chunk_count", document.chunkCount)
                                }
                            )
                        }
                    }
                )
            }
            listOf(UIMessagePart.Text(json.encodeToString(payload)))
        }
    )
}
