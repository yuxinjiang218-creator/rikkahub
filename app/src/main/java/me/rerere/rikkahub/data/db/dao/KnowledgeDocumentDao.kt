package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.KnowledgeDocumentEntity

/**
 * 知识库文档 DAO
 */
@Dao
interface KnowledgeDocumentDao {
    @Query("SELECT * FROM knowledge_document WHERE id = :id")
    suspend fun getById(id: String): KnowledgeDocumentEntity?

    @Query("SELECT * FROM knowledge_document WHERE assistantId = :assistantId ORDER BY createdAt DESC")
    fun getByAssistantId(assistantId: String): Flow<List<KnowledgeDocumentEntity>>

    @Query("SELECT * FROM knowledge_document WHERE assistantId = :assistantId ORDER BY createdAt DESC")
    suspend fun getByAssistantIdSync(assistantId: String): List<KnowledgeDocumentEntity>

    @Query("SELECT * FROM knowledge_document WHERE assistantId = :assistantId AND contentHash = :contentHash LIMIT 1")
    suspend fun getByAssistantIdAndContentHash(assistantId: String, contentHash: String): KnowledgeDocumentEntity?

    @Query("SELECT COUNT(*) FROM knowledge_document WHERE assistantId = :assistantId AND status = 'READY'")
    suspend fun countReadyByAssistantId(assistantId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: KnowledgeDocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(documents: List<KnowledgeDocumentEntity>)

    @Update
    suspend fun update(document: KnowledgeDocumentEntity)

    @Delete
    suspend fun delete(document: KnowledgeDocumentEntity)

    @Query("DELETE FROM knowledge_document WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM knowledge_document WHERE assistantId = :assistantId")
    suspend fun deleteByAssistantId(assistantId: String)

    @Transaction
    @Query("DELETE FROM knowledge_document WHERE id = :id")
    suspend fun deleteWithCascade(id: String)
}
