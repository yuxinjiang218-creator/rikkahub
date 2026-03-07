package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.CompressionEventEntity

@Dao
interface CompressionEventDAO {
    @Query("SELECT * FROM compression_event WHERE conversation_id = :conversationId ORDER BY created_at ASC, id ASC")
    suspend fun getEventsOfConversation(conversationId: String): List<CompressionEventEntity>

    @Insert
    suspend fun insert(event: CompressionEventEntity): Long

    @Query("DELETE FROM compression_event WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}
