package me.rerere.rikkahub.data.db.index.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.index.entity.IndexKnowledgeBaseChunkEntity

data class IndexKnowledgeBaseChunkScopeRow(
    val id: Long,
    val documentId: Long,
    val generation: Int,
)

@Dao
interface IndexKnowledgeBaseChunkDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<IndexKnowledgeBaseChunkEntity>): List<Long>

    @Query("DELETE FROM knowledge_base_chunk WHERE document_id = :documentId")
    suspend fun deleteChunksOfDocument(documentId: Long)

    @Query("DELETE FROM knowledge_base_chunk WHERE assistant_id = :assistantId")
    suspend fun deleteChunksOfAssistant(assistantId: String)

    @Query("DELETE FROM knowledge_base_chunk WHERE document_id = :documentId AND generation = :generation")
    suspend fun deleteChunksOfDocumentGeneration(documentId: Long, generation: Int)

    @Query("SELECT COUNT(*) FROM knowledge_base_chunk WHERE document_id = :documentId AND generation = :generation")
    suspend fun countChunksOfDocumentGeneration(documentId: Long, generation: Int): Int

    @Query("SELECT * FROM knowledge_base_chunk WHERE assistant_id = :assistantId ORDER BY updated_at DESC, chunk_order ASC")
    suspend fun getChunksOfAssistant(assistantId: String): List<IndexKnowledgeBaseChunkEntity>

    @Query(
        """
        SELECT * FROM knowledge_base_chunk
        WHERE assistant_id = :assistantId
            AND document_id IN (:documentIds)
        ORDER BY updated_at DESC, chunk_order ASC
        """
    )
    suspend fun getChunksOfAssistantDocuments(
        assistantId: String,
        documentIds: List<Long>,
    ): List<IndexKnowledgeBaseChunkEntity>

    @Query(
        """
        SELECT id, document_id AS documentId, generation
        FROM knowledge_base_chunk
        WHERE assistant_id = :assistantId
        """
    )
    suspend fun getChunkScopeOfAssistant(
        assistantId: String,
    ): List<IndexKnowledgeBaseChunkScopeRow>

    @Query(
        """
        SELECT id, document_id AS documentId, generation
        FROM knowledge_base_chunk
        WHERE assistant_id = :assistantId
            AND document_id IN (:documentIds)
        """
    )
    suspend fun getChunkScopeOfAssistantDocuments(
        assistantId: String,
        documentIds: List<Long>,
    ): List<IndexKnowledgeBaseChunkScopeRow>

    @Query(
        """
        SELECT * FROM knowledge_base_chunk
        WHERE id IN (:chunkIds)
            AND assistant_id = :assistantId
        """
    )
    suspend fun getChunksByIds(
        assistantId: String,
        chunkIds: List<Long>,
    ): List<IndexKnowledgeBaseChunkEntity>

    @Query(
        """
        SELECT * FROM knowledge_base_chunk
        WHERE assistant_id = :assistantId
            AND document_id = :documentId
            AND chunk_order IN (:chunkOrders)
        ORDER BY chunk_order ASC
        """
    )
    suspend fun getChunksByDocumentAndOrders(
        assistantId: String,
        documentId: Long,
        chunkOrders: List<Int>,
    ): List<IndexKnowledgeBaseChunkEntity>

    @Query(
        """
        SELECT * FROM knowledge_base_chunk
        WHERE document_id = :documentId
            AND generation = :generation
        ORDER BY chunk_order ASC
        """
    )
    suspend fun getChunksOfDocumentGeneration(
        documentId: Long,
        generation: Int,
    ): List<IndexKnowledgeBaseChunkEntity>
}
