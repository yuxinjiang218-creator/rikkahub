package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import me.rerere.rikkahub.data.db.entity.MessageNodeTextEntity

@Dao
interface MessageNodeTextDao {
    // FTS5 查询（使用 RawQuery 绕过 KSP 验证，因为 message_node_fts 是 Migration 中创建的虚拟表）
    @RawQuery
    suspend fun searchByFts(query: SupportSQLiteQuery): List<FtsSearchResult>

    // 根据 node_index 批量获取完整记录（用于拼接逐字文本）
    @Query("""
        SELECT * FROM message_node_text
        WHERE conversation_id = :conversationId
        AND node_index IN (:indices)
        ORDER BY node_index ASC
    """)
    suspend fun getByConversationIdAndIndices(
        conversationId: String,
        indices: List<Int>
    ): List<MessageNodeTextEntity>

    // 获取会话的最大 node_index（用于 expandWindow 上下界过滤）
    @Query("SELECT MAX(node_index) FROM message_node_text WHERE conversation_id = :conversationId")
    suspend fun getMaxNodeIndex(conversationId: String): Int?

    // 插入/更新单个记录
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(entity: MessageNodeTextEntity)

    // 删除会话的所有记录
    @Query("DELETE FROM message_node_text WHERE conversation_id = :conversationId")
    suspend fun deleteByConversationId(conversationId: String)
}

// FTS 查询结果数据类
data class FtsSearchResult(
    val node_id: String,
    val node_index: Int,
    val score: Double  // BM25 分数
)
