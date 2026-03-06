package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversationentity ADD COLUMN rolling_summary_json TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE conversationentity ADD COLUMN rolling_summary_token_estimate INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversationentity ADD COLUMN last_compressed_message_index INTEGER NOT NULL DEFAULT -1")
        db.execSQL("ALTER TABLE conversationentity ADD COLUMN last_compressed_at INTEGER NOT NULL DEFAULT 0")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS compression_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                conversation_id TEXT NOT NULL,
                boundary_index INTEGER NOT NULL,
                summary_snapshot TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES ConversationEntity(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_compression_event_conversation_id ON compression_event(conversation_id)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_compression_event_conversation_id_created_at ON compression_event(conversation_id, created_at)"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_index_chunk (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                assistant_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                section_key TEXT NOT NULL,
                chunk_order INTEGER NOT NULL,
                content TEXT NOT NULL,
                token_estimate INTEGER NOT NULL,
                embedding_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES ConversationEntity(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_index_chunk_assistant_id ON memory_index_chunk(assistant_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_index_chunk_conversation_id ON memory_index_chunk(conversation_id)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_memory_index_chunk_assistant_id_conversation_id ON memory_index_chunk(assistant_id, conversation_id)"
        )
    }
}
