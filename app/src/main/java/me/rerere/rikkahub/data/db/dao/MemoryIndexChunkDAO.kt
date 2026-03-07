package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.MemoryIndexChunkEntity

@Dao
interface MemoryIndexChunkDAO {
    @Query("SELECT * FROM memory_index_chunk WHERE assistant_id = :assistantId ORDER BY updated_at DESC, chunk_order ASC")
    suspend fun getChunksOfAssistant(assistantId: String): List<MemoryIndexChunkEntity>

    @Query("DELETE FROM memory_index_chunk WHERE conversation_id = :conversationId")
    suspend fun deleteChunksOfConversation(conversationId: String)

    @Insert
    suspend fun insertAll(chunks: List<MemoryIndexChunkEntity>)
}
