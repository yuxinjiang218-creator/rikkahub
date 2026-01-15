package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.KnowledgeChunkEntity

/**
 * 知识库文本块 DAO
 */
@Dao
interface KnowledgeChunkDao {
    @Query("SELECT * FROM knowledge_chunk WHERE id = :id")
    suspend fun getById(id: String): KnowledgeChunkEntity?

    @Query("SELECT * FROM knowledge_chunk WHERE documentId = :documentId ORDER BY chunkIndex ASC")
    suspend fun getByDocumentId(documentId: String): List<KnowledgeChunkEntity>

    @Query("SELECT * FROM knowledge_chunk WHERE documentId = :documentId ORDER BY chunkIndex ASC")
    fun getByDocumentIdFlow(documentId: String): Flow<List<KnowledgeChunkEntity>>

    @Query("SELECT * FROM knowledge_chunk WHERE assistantId = :assistantId ORDER BY documentId, chunkIndex ASC")
    suspend fun getByAssistantId(assistantId: String): List<KnowledgeChunkEntity>

    @Query("SELECT COUNT(*) FROM knowledge_chunk WHERE documentId = :documentId")
    suspend fun countByDocumentId(documentId: String): Int

    @Query("DELETE FROM knowledge_chunk WHERE documentId = :documentId")
    suspend fun deleteByDocumentId(documentId: String)

    @Query("DELETE FROM knowledge_chunk WHERE assistantId = :assistantId")
    suspend fun deleteByAssistantId(assistantId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunk: KnowledgeChunkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<KnowledgeChunkEntity>)
}
