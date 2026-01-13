package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_12_13"

val Migration_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 添加上下文压缩相关字段
        db.execSQL(
            """
            ALTER TABLE conversationentity ADD COLUMN conversation_summary TEXT DEFAULT '' NOT NULL
            """.trimIndent()
        )
        db.execSQL(
            """
            ALTER TABLE conversationentity ADD COLUMN conversation_summary_until INTEGER DEFAULT -1 NOT NULL
            """.trimIndent()
        )
    }
}
