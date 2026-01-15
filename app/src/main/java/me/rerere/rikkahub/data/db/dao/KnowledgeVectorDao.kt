package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.KnowledgeVectorEntity

/**
 * 知识库向量 DAO
 */
@Dao
interface KnowledgeVectorDao {
    @Query("SELECT * FROM knowledge_vector WHERE id = :id")
    suspend fun getById(id: String): KnowledgeVectorEntity?

    @Query("SELECT * FROM knowledge_vector WHERE chunkId = :chunkId")
    suspend fun getByChunkId(chunkId: String): KnowledgeVectorEntity?

    /**
     * 获取指定 assistant 和 embedding 模型的所有向量
     * 限制返回数量，避免内存溢出
     */
    @Query("SELECT * FROM knowledge_vector WHERE assistantId = :assistantId AND embeddingModelId = :embeddingModelId LIMIT :limit")
    suspend fun getByAssistantIdAndModel(
        assistantId: String,
        embeddingModelId: String,
        limit: Int = 5000
    ): List<KnowledgeVectorEntity>

    @Query("SELECT COUNT(*) FROM knowledge_vector WHERE assistantId = :assistantId AND embeddingModelId = :embeddingModelId")
    suspend fun countByAssistantIdAndModel(assistantId: String, embeddingModelId: String): Int

    @Query("DELETE FROM knowledge_vector WHERE chunkId = :chunkId")
    suspend fun deleteByChunkId(chunkId: String)

    @Query("DELETE FROM knowledge_vector WHERE assistantId = :assistantId")
    suspend fun deleteByAssistantId(assistantId: String)

    @Query("DELETE FROM knowledge_vector WHERE chunkId IN (SELECT id FROM knowledge_chunk WHERE documentId = :documentId)")
    suspend fun deleteByDocumentId(documentId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vector: KnowledgeVectorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(vectors: List<KnowledgeVectorEntity>)
}
