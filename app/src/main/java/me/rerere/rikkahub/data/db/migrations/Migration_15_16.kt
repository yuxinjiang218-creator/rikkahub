package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移 15 → 16
 *
 * 新增功能：
 * - 确保倒排索引表 (message_token_index) 存在
 *
 * 目的：
 * - 为已经升级到版本 15 的用户补充 message_token_index 表
 * - 确保倒排索引兜底方案可用
 */
val Migration_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 创建倒排索引兜底表（如果不存在）
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS message_token_index (
                token TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                node_index INTEGER NOT NULL,
                node_id TEXT NOT NULL,
                PRIMARY KEY (token, conversation_id, node_index)
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_token_conv ON message_token_index(conversation_id, token)")

        // 迁移现有数据到倒排索引（从 message_node_text 重新构建）
        db.beginTransaction()
        try {
            // 查询所有 message_node_text 记录
            val cursor = db.query("SELECT node_id, conversation_id, node_index, search_text FROM message_node_text")

            while (cursor.moveToNext()) {
                val nodeId = cursor.getString(0)
                val conversationId = cursor.getString(1)
                val nodeIndex = cursor.getInt(2)
                val searchText = cursor.getString(3)

                // 分词并插入倒排索引
                val tokens = searchText.split(" ")
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(64)

                for (token in tokens) {
                    db.execSQL(
                        """INSERT OR REPLACE INTO message_token_index
                        (token, conversation_id, node_index, node_id)
                        VALUES (?, ?, ?, ?)""",
                        arrayOf(token, conversationId, nodeIndex, nodeId)
                    )
                }
            }

            cursor.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
