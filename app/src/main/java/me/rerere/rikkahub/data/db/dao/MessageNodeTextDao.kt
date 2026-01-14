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
    // FTS4 查询（返回 matchinfo 用于 Kotlin 侧排序）
    // 使用 RawQuery 绕过 KSP 验证，因为 message_node_fts 是 Migration 中创建的虚拟表
    @RawQuery
    suspend fun searchByFts(query: SupportSQLiteQuery): List<FtsSearchResult>

    // 倒排索引兜底查询（FTS4 不可用时使用）
    // 使用 RawQuery 绕过 KSP 验证，因为 message_token_index 是 Migration 中创建的表
    @RawQuery
    suspend fun searchByInvertedIndex(query: SupportSQLiteQuery): List<InvertedIndexResult>

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

    // 删除会话的 FTS 数据
    // 使用 RawQuery 绕过 KSP 验证，因为 message_node_fts 是 Migration 中创建的虚拟表
    @RawQuery
    suspend fun deleteFtsByConversationId(query: SupportSQLiteQuery): Int

    // 删除会话的倒排索引
    // 使用 RawQuery 绕过 KSP 验证
    @RawQuery
    suspend fun deleteInvertedIndexByConversationId(query: SupportSQLiteQuery): Int
}

// FTS4 查询结果数据类（包含 matchinfo 用于排序）
data class FtsSearchResult(
    val node_id: String,
    val node_index: Int,
    val mi: ByteArray  // matchinfo，用于 Kotlin 侧排序
)

// 倒排索引查询结果数据类
data class InvertedIndexResult(
    val node_index: Int,
    val hit: Int  // 命中词数
)
