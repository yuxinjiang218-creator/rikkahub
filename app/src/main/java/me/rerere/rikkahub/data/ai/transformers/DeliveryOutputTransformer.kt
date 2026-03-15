package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

object DeliveryOutputTransformer : OutputMessageTransformer {
    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistantIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (assistantIndex < 0) return messages

        val assistantMessage = messages[assistantIndex]
        val deliveryDocuments = assistantMessage.parts
            .filterIsInstance<UIMessagePart.Tool>()
            .flatMap { tool ->
                tool.output.filterIsInstance<UIMessagePart.Document>()
            }
            .distinctBy { "${it.url}|${it.fileName}|${it.mime}" }

        if (deliveryDocuments.isEmpty()) return messages

        val existingDocumentKeys = assistantMessage.parts
            .filterIsInstance<UIMessagePart.Document>()
            .mapTo(mutableSetOf()) { "${it.url}|${it.fileName}|${it.mime}" }

        val newDocuments = deliveryDocuments.filter {
            existingDocumentKeys.add("${it.url}|${it.fileName}|${it.mime}")
        }
        if (newDocuments.isEmpty()) return messages

        return messages.toMutableList().also { updated ->
            updated[assistantIndex] = assistantMessage.copy(
                parts = assistantMessage.parts + newDocuments
            )
        }
    }
}
