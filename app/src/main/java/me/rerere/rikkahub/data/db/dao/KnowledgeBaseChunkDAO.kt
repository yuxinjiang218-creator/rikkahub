package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.KnowledgeBaseChunkEntity

data class KnowledgeBaseChunkWithDocumentRow(
    val chunkId: Long,
    val documentId: Long,
    val assistantId: String,
    val generation: Int,
    val chunkOrder: Int,
    val content: String,
    val tokenEstimate: Int,
    val embeddingJson: String,
    val chunkUpdatedAt: Long,
    val documentName: String,
    val mimeType: String,
)

data class KnowledgeBaseChunkFtsRow(
    val chunkId: Long,
    val documentId: Long,
    val assistantId: String,
    val generation: Int,
    val content: String,
)

@Dao
interface KnowledgeBaseChunkDAO {
    @Insert
    suspend fun insertAll(chunks: List<KnowledgeBaseChunkEntity>)

    @Query("DELETE FROM knowledge_base_chunk WHERE document_id = :documentId")
    suspend fun deleteChunksOfDocument(documentId: Long)

    @Query("DELETE FROM knowledge_base_chunk WHERE assistant_id = :assistantId")
    suspend fun deleteChunksOfAssistant(assistantId: String)

    @Query("DELETE FROM knowledge_base_chunk WHERE document_id = :documentId AND generation = :generation")
    suspend fun deleteChunksOfDocumentGeneration(documentId: Long, generation: Int)

    @Query("SELECT COUNT(*) FROM knowledge_base_chunk WHERE document_id = :documentId AND generation = :generation")
    suspend fun countChunksOfDocumentGeneration(documentId: Long, generation: Int): Int

    @Query(
        """
        SELECT
            c.id AS chunkId,
            c.document_id AS documentId,
            c.assistant_id AS assistantId,
            c.generation AS generation,
            c.chunk_order AS chunkOrder,
            c.content AS content,
            c.token_estimate AS tokenEstimate,
            c.embedding_json AS embeddingJson,
            c.updated_at AS chunkUpdatedAt,
            d.display_name AS documentName,
            d.mime_type AS mimeType
        FROM knowledge_base_chunk c
        INNER JOIN knowledge_base_document d ON d.id = c.document_id
        WHERE c.assistant_id = :assistantId
            AND d.published_generation > 0
            AND c.generation = d.published_generation
        ORDER BY d.updated_at DESC, c.chunk_order ASC
        """
    )
    suspend fun getReadyChunksOfAssistant(
        assistantId: String,
    ): List<KnowledgeBaseChunkWithDocumentRow>

    @Query(
        """
        SELECT
            c.id AS chunkId,
            c.document_id AS documentId,
            c.assistant_id AS assistantId,
            c.generation AS generation,
            c.chunk_order AS chunkOrder,
            c.content AS content,
            c.token_estimate AS tokenEstimate,
            c.embedding_json AS embeddingJson,
            c.updated_at AS chunkUpdatedAt,
            d.display_name AS documentName,
            d.mime_type AS mimeType
        FROM knowledge_base_chunk c
        INNER JOIN knowledge_base_document d ON d.id = c.document_id
        WHERE c.assistant_id = :assistantId
            AND c.document_id IN (:documentIds)
            AND d.published_generation > 0
            AND c.generation = d.published_generation
        ORDER BY d.updated_at DESC, c.chunk_order ASC
        """
    )
    suspend fun getReadyChunksOfAssistantDocuments(
        assistantId: String,
        documentIds: List<Long>,
    ): List<KnowledgeBaseChunkWithDocumentRow>

    @Query(
        """
        SELECT
            c.id AS chunkId,
            c.document_id AS documentId,
            c.assistant_id AS assistantId,
            c.generation AS generation,
            c.chunk_order AS chunkOrder,
            c.content AS content,
            c.token_estimate AS tokenEstimate,
            c.embedding_json AS embeddingJson,
            c.updated_at AS chunkUpdatedAt,
            d.display_name AS documentName,
            d.mime_type AS mimeType
        FROM knowledge_base_chunk c
        INNER JOIN knowledge_base_document d ON d.id = c.document_id
        WHERE c.id IN (:chunkIds)
            AND c.assistant_id = :assistantId
            AND c.generation = d.published_generation
        """
    )
    suspend fun getChunksByIds(
        assistantId: String,
        chunkIds: List<Long>,
    ): List<KnowledgeBaseChunkWithDocumentRow>

    @Query(
        """
        SELECT
            c.id AS chunkId,
            c.document_id AS documentId,
            c.assistant_id AS assistantId,
            c.generation AS generation,
            c.chunk_order AS chunkOrder,
            c.content AS content,
            c.token_estimate AS tokenEstimate,
            c.embedding_json AS embeddingJson,
            c.updated_at AS chunkUpdatedAt,
            d.display_name AS documentName,
            d.mime_type AS mimeType
        FROM knowledge_base_chunk c
        INNER JOIN knowledge_base_document d ON d.id = c.document_id
        WHERE c.assistant_id = :assistantId
            AND c.document_id = :documentId
            AND c.chunk_order IN (:chunkOrders)
            AND c.generation = d.published_generation
        ORDER BY c.chunk_order ASC
        """
    )
    suspend fun getChunksByDocumentAndOrders(
        assistantId: String,
        documentId: Long,
        chunkOrders: List<Int>,
    ): List<KnowledgeBaseChunkWithDocumentRow>

    @Query(
        """
        SELECT
            id AS chunkId,
            document_id AS documentId,
            assistant_id AS assistantId,
            generation AS generation,
            content AS content
        FROM knowledge_base_chunk
        WHERE document_id = :documentId AND generation = :generation
        ORDER BY chunk_order ASC
        """
    )
    suspend fun getFtsRowsOfDocumentGeneration(
        documentId: Long,
        generation: Int,
    ): List<KnowledgeBaseChunkFtsRow>
}
