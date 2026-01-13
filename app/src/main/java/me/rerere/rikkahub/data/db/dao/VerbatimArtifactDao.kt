package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.VerbatimArtifactEntity

@Dao
interface VerbatimArtifactDao {
    // 根据 conversation_id 和 title 查询（阶段一 title 匹配）
    @Query("""
        SELECT * FROM verbatim_artifact
        WHERE conversation_id = :conversationId
        AND title = :title
        ORDER BY updated_at DESC
        LIMIT 1
    """)
    suspend fun getByConversationIdAndTitle(
        conversationId: String,
        title: String
    ): List<VerbatimArtifactEntity>

    // 根据 conversation_id 查询所有 artifacts
    @Query("""
        SELECT * FROM verbatim_artifact
        WHERE conversation_id = :conversationId
        ORDER BY updated_at DESC
    """)
    suspend fun getByConversationId(conversationId: String): List<VerbatimArtifactEntity>

    // 插入/更新单个记录
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(entity: VerbatimArtifactEntity)

    // 删除会话的所有记录
    @Query("DELETE FROM verbatim_artifact WHERE conversation_id = :conversationId")
    suspend fun deleteByConversationId(conversationId: String)

    // 根据 id 删除
    @Query("DELETE FROM verbatim_artifact WHERE id = :id")
    suspend fun deleteById(id: String)
}
