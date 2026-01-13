package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ArchiveSummaryEntity

@Dao
interface ArchiveSummaryDao {
    @Query("SELECT * FROM archive_summary WHERE id = :id")
    suspend fun getById(id: String): ArchiveSummaryEntity?

    @Query("SELECT * FROM archive_summary WHERE id = :id")
    fun getFlowById(id: String): Flow<ArchiveSummaryEntity?>

    @Query("SELECT * FROM archive_summary WHERE conversation_id = :conversationId ORDER BY window_start_index ASC")
    fun getByConversationId(conversationId: String): Flow<List<ArchiveSummaryEntity>>

    @Query("SELECT * FROM archive_summary WHERE conversation_id = :conversationId ORDER BY window_start_index ASC")
    suspend fun getListByConversationId(conversationId: String): List<ArchiveSummaryEntity>

    @Query("SELECT * FROM archive_summary WHERE conversation_id = :conversationId AND window_start_index >= :startIndex AND window_end_index <= :endIndex ORDER BY window_start_index ASC")
    suspend fun getByWindowRange(
        conversationId: String,
        startIndex: Int,
        endIndex: Int
    ): List<ArchiveSummaryEntity>

    @Query("SELECT COUNT(*) FROM archive_summary WHERE conversation_id = :conversationId")
    suspend fun countByConversationId(conversationId: String): Int

    @Query("SELECT * FROM archive_summary")
    suspend fun getAll(): List<ArchiveSummaryEntity>

    @Insert
    suspend fun insert(archiveSummary: ArchiveSummaryEntity)

    @Insert
    suspend fun insertAll(archiveSummaries: List<ArchiveSummaryEntity>)

    @Update
    suspend fun update(archiveSummary: ArchiveSummaryEntity)

    @Delete
    suspend fun delete(archiveSummary: ArchiveSummaryEntity)

    @Query("DELETE FROM archive_summary WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM archive_summary WHERE conversation_id = :conversationId")
    suspend fun deleteByConversationId(conversationId: String)

    @Query("DELETE FROM archive_summary")
    suspend fun deleteAll()

    @Query("SELECT * FROM archive_summary WHERE embedding_model_id IS NULL")
    suspend fun getMissingEmbeddings(): List<ArchiveSummaryEntity>

    @Query("SELECT * FROM archive_summary WHERE embedding_model_id = :modelId")
    suspend fun getByEmbeddingModelId(modelId: String): List<ArchiveSummaryEntity>
}
