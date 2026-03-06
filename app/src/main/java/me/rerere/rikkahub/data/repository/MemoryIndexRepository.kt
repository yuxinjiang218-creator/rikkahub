package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MemoryIndexChunkDAO
import me.rerere.rikkahub.data.db.entity.MemoryIndexChunkEntity
import me.rerere.rikkahub.data.model.MemoryChunkMetadata
import me.rerere.rikkahub.data.model.MemoryIndexChunk
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

class MemoryIndexRepository(
    private val memoryIndexChunkDAO: MemoryIndexChunkDAO,
    private val conversationDAO: ConversationDAO,
    private val database: AppDatabase,
) {
    suspend fun replaceConversationChunks(
        assistantId: Uuid,
        conversationId: Uuid,
        chunks: List<MemoryIndexChunk>,
    ) {
        database.withTransaction {
            memoryIndexChunkDAO.deleteChunksOfConversation(conversationId.toString())
            if (chunks.isEmpty()) return@withTransaction
            memoryIndexChunkDAO.insertAll(
                chunks.map { chunk ->
                    MemoryIndexChunkEntity(
                        assistantId = assistantId.toString(),
                        conversationId = conversationId.toString(),
                        sectionKey = chunk.sectionKey,
                        chunkOrder = chunk.chunkOrder,
                        content = chunk.content,
                        tokenEstimate = chunk.tokenEstimate,
                        embeddingJson = JsonInstant.encodeToString(chunk.embedding),
                        metadataJson = JsonInstant.encodeToString(chunk.metadata),
                        updatedAt = chunk.updatedAt.toEpochMilli(),
                    )
                }
            )
        }
    }

    suspend fun getChunksOfAssistant(assistantId: Uuid): List<IndexedChunkWithConversation> {
        val chunks = memoryIndexChunkDAO.getChunksOfAssistant(assistantId.toString())
        if (chunks.isEmpty()) return emptyList()

        val titleByConversation = chunks
            .map { it.conversationId }
            .distinct()
            .associateWith { conversationId ->
                conversationDAO.getConversationById(conversationId)?.title.orEmpty()
            }

        return chunks.mapNotNull { entity ->
            entity.toMemoryIndexChunk()?.let { chunk ->
                IndexedChunkWithConversation(
                    chunk = chunk,
                    conversationTitle = titleByConversation[entity.conversationId].orEmpty(),
                )
            }
        }
    }

    suspend fun deleteConversationChunks(conversationId: Uuid) {
        memoryIndexChunkDAO.deleteChunksOfConversation(conversationId.toString())
    }

    private fun MemoryIndexChunkEntity.toMemoryIndexChunk(): MemoryIndexChunk? {
        val embedding = runCatching {
            JsonInstant.decodeFromString<List<Float>>(embeddingJson)
        }.getOrNull() ?: return null
        val metadata = runCatching {
            JsonInstant.decodeFromString<MemoryChunkMetadata>(metadataJson)
        }.getOrElse { MemoryChunkMetadata(sectionKey = sectionKey) }
        val assistantUuid = runCatching { Uuid.parse(assistantId) }.getOrNull() ?: return null
        val conversationUuid = runCatching { Uuid.parse(conversationId) }.getOrNull() ?: return null
        return MemoryIndexChunk(
            id = id,
            assistantId = assistantUuid,
            conversationId = conversationUuid,
            sectionKey = sectionKey,
            chunkOrder = chunkOrder,
            content = content,
            tokenEstimate = tokenEstimate,
            embedding = embedding,
            metadata = metadata.copy(sectionKey = metadata.sectionKey.ifBlank { sectionKey }),
            updatedAt = Instant.ofEpochMilli(updatedAt),
        )
    }
}

data class IndexedChunkWithConversation(
    val chunk: MemoryIndexChunk,
    val conversationTitle: String,
)
