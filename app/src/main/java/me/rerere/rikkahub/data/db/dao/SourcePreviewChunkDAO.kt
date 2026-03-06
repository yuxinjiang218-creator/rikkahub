package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.SourcePreviewChunkEntity

@Dao
interface SourcePreviewChunkDAO {
    @Query("SELECT * FROM source_preview_chunk WHERE assistant_id = :assistantId ORDER BY updated_at DESC, chunk_order ASC")
    suspend fun getChunksOfAssistant(assistantId: String): List<SourcePreviewChunkEntity>

    @Query(
        "SELECT * FROM source_preview_chunk WHERE assistant_id = :assistantId AND conversation_id IN (:conversationIds) " +
            "ORDER BY updated_at DESC, chunk_order ASC"
    )
    suspend fun getChunksOfConversations(
        assistantId: String,
        conversationIds: List<String>,
    ): List<SourcePreviewChunkEntity>

    @Query("DELETE FROM source_preview_chunk WHERE conversation_id = :conversationId")
    suspend fun deleteChunksOfConversation(conversationId: String)

    @Insert
    suspend fun insertAll(chunks: List<SourcePreviewChunkEntity>)
}
