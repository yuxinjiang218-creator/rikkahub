package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_13_14"

/**
 * 数据库迁移 13 → 14
 *
 * 新增功能：
 * 1. 归档摘要 (Archive Summaries)
 * 2. 向量索引 (Vector Index)
 *
 * 目的：
 * - 实现上下文压缩的归档机制
 * - 支持向量检索的自动回填
 */
val Migration_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 创建归档摘要表
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS archive_summary (
                id TEXT PRIMARY KEY NOT NULL,
                conversation_id TEXT NOT NULL,
                window_start_index INTEGER NOT NULL,
                window_end_index INTEGER NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                embedding_model_id TEXT,
                FOREIGN KEY(conversation_id) REFERENCES conversationentity(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        // 为归档摘要表创建索引
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_archive_summary_conversation_id
            ON archive_summary(conversation_id)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_archive_summary_created_at
            ON archive_summary(created_at)
            """.trimIndent()
        )

        // 创建向量索引表
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS vector_index (
                id TEXT PRIMARY KEY NOT NULL,
                archive_id TEXT NOT NULL,
                embedding_vector BLOB NOT NULL,
                embedding_model_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(archive_id) REFERENCES archive_summary(id) ON DELETE CASCADE,
                UNIQUE(archive_id)
            )
            """.trimIndent()
        )

        // 为向量索引表创建索引
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_vector_index_embedding_model_id
            ON vector_index(embedding_model_id)
            """.trimIndent()
        )
    }
}
