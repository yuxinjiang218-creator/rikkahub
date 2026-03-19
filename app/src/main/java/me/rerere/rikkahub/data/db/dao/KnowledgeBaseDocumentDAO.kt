package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.KnowledgeBaseDocumentEntity

@Dao
interface KnowledgeBaseDocumentDAO {
    @Query(
        "SELECT * FROM knowledge_base_document WHERE assistant_id = :assistantId " +
            "ORDER BY updated_at DESC, created_at DESC"
    )
    fun observeDocumentsOfAssistant(assistantId: String): Flow<List<KnowledgeBaseDocumentEntity>>

    @Query(
        "SELECT * FROM knowledge_base_document WHERE assistant_id = :assistantId " +
            "ORDER BY updated_at DESC, created_at DESC"
    )
    suspend fun getDocumentsOfAssistant(assistantId: String): List<KnowledgeBaseDocumentEntity>

    @Query(
        "SELECT * FROM knowledge_base_document WHERE published_generation > 0 " +
            "ORDER BY updated_at DESC, created_at DESC"
    )
    suspend fun getPublishedDocuments(): List<KnowledgeBaseDocumentEntity>

    @Query(
        "SELECT * FROM knowledge_base_document WHERE assistant_id = :assistantId AND published_generation > 0 " +
            "ORDER BY chunk_count DESC, updated_at DESC, created_at DESC"
    )
    suspend fun getSearchableDocumentsOfAssistant(assistantId: String): List<KnowledgeBaseDocumentEntity>

    @Query("SELECT * FROM knowledge_base_document WHERE id = :documentId LIMIT 1")
    suspend fun getDocument(documentId: Long): KnowledgeBaseDocumentEntity?

    @Query("SELECT COUNT(*) FROM knowledge_base_document WHERE assistant_id = :assistantId")
    suspend fun countDocumentsOfAssistant(assistantId: String): Int

    @Query(
        "SELECT COUNT(*) FROM knowledge_base_document WHERE assistant_id = :assistantId AND status = :status"
    )
    suspend fun countDocumentsOfAssistantByStatus(assistantId: String, status: String): Int

    @Query(
        "SELECT COUNT(*) FROM knowledge_base_document WHERE assistant_id = :assistantId AND published_generation > 0"
    )
    suspend fun countSearchableDocumentsOfAssistant(assistantId: String): Int

    @Query("SELECT COUNT(*) FROM knowledge_base_document WHERE status = :status")
    suspend fun countDocumentsByStatus(status: String): Int

    @Query(
        "SELECT * FROM knowledge_base_document WHERE status = :status " +
            "ORDER BY queued_at ASC, id ASC LIMIT 1"
    )
    suspend fun getNextDocumentByStatus(status: String): KnowledgeBaseDocumentEntity?

    @Query(
        "SELECT * FROM knowledge_base_document WHERE status = :status " +
            "ORDER BY updated_at DESC, id DESC LIMIT 1"
    )
    suspend fun getLatestDocumentByStatus(status: String): KnowledgeBaseDocumentEntity?

    @Query(
        "SELECT * FROM knowledge_base_document WHERE status = :status AND " +
            "(last_heartbeat_at IS NULL OR last_heartbeat_at < :heartbeatBefore)"
    )
    suspend fun getStaleIndexingDocuments(
        status: String,
        heartbeatBefore: Long,
    ): List<KnowledgeBaseDocumentEntity>

    @Insert
    suspend fun insert(document: KnowledgeBaseDocumentEntity): Long

    @Update
    suspend fun update(document: KnowledgeBaseDocumentEntity)

    @Query("DELETE FROM knowledge_base_document WHERE id = :documentId")
    suspend fun deleteDocument(documentId: Long): Int

    @Query("DELETE FROM knowledge_base_document WHERE assistant_id = :assistantId")
    suspend fun deleteDocumentsOfAssistant(assistantId: String): Int
}
