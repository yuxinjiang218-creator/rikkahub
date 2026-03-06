package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.SourcePreviewChunkDAO
import me.rerere.rikkahub.data.db.entity.SourcePreviewChunkEntity
import me.rerere.rikkahub.data.model.SourcePreviewChunk
import java.time.Instant
import kotlin.uuid.Uuid

class SourcePreviewRepository(
    private val sourcePreviewChunkDAO: SourcePreviewChunkDAO,
    private val database: AppDatabase,
) {
    suspend fun replaceConversationChunks(
        conversationId: Uuid,
        chunks: List<SourcePreviewChunk>,
    ) {
        database.withTransaction {
            sourcePreviewChunkDAO.deleteChunksOfConversation(conversationId.toString())
            if (chunks.isEmpty()) return@withTransaction
            sourcePreviewChunkDAO.insertAll(
                chunks.map { chunk ->
                    SourcePreviewChunkEntity(
                        assistantId = chunk.assistantId.toString(),
                        conversationId = chunk.conversationId.toString(),
                        messageId = chunk.messageId.toString(),
                        role = chunk.role,
                        chunkOrder = chunk.chunkOrder,
                        prefixText = chunk.prefixText,
                        searchText = chunk.searchText,
                        blockType = chunk.blockType,
                        updatedAt = chunk.updatedAt.toEpochMilli(),
                    )
                }
            )
        }
    }

    suspend fun getChunksOfAssistant(assistantId: Uuid): List<SourcePreviewChunk> {
        return sourcePreviewChunkDAO.getChunksOfAssistant(assistantId.toString()).mapNotNull { it.toModel() }
    }

    suspend fun getChunksOfConversations(
        assistantId: Uuid,
        conversationIds: List<Uuid>,
    ): List<SourcePreviewChunk> {
        if (conversationIds.isEmpty()) return emptyList()
        return sourcePreviewChunkDAO.getChunksOfConversations(
            assistantId = assistantId.toString(),
            conversationIds = conversationIds.map { it.toString() }
        ).mapNotNull { it.toModel() }
    }

    suspend fun deleteConversationChunks(conversationId: Uuid) {
        sourcePreviewChunkDAO.deleteChunksOfConversation(conversationId.toString())
    }

    private fun SourcePreviewChunkEntity.toModel(): SourcePreviewChunk? {
        val assistantUuid = runCatching { Uuid.parse(assistantId) }.getOrNull() ?: return null
        val conversationUuid = runCatching { Uuid.parse(conversationId) }.getOrNull() ?: return null
        val messageUuid = runCatching { Uuid.parse(messageId) }.getOrNull() ?: return null
        return SourcePreviewChunk(
            id = id,
            assistantId = assistantUuid,
            conversationId = conversationUuid,
            messageId = messageUuid,
            role = role,
            chunkOrder = chunkOrder,
            prefixText = prefixText,
            searchText = searchText,
            blockType = blockType,
            updatedAt = Instant.ofEpochMilli(updatedAt),
        )
    }
}
