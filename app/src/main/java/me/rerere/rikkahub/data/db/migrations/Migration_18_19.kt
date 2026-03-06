package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversationentity ADD COLUMN last_index_status TEXT NOT NULL DEFAULT 'idle'")
        db.execSQL("ALTER TABLE conversationentity ADD COLUMN last_indexed_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversationentity ADD COLUMN last_index_error TEXT NOT NULL DEFAULT ''")

        db.execSQL("ALTER TABLE memory_index_chunk ADD COLUMN metadata_json TEXT NOT NULL DEFAULT '{}'")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS source_preview_chunk (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                assistant_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                message_id TEXT NOT NULL,
                role TEXT NOT NULL,
                chunk_order INTEGER NOT NULL,
                prefix_text TEXT NOT NULL,
                search_text TEXT NOT NULL,
                block_type TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES ConversationEntity(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_source_preview_chunk_assistant_id ON source_preview_chunk(assistant_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_source_preview_chunk_conversation_id ON source_preview_chunk(conversation_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_source_preview_chunk_message_id ON source_preview_chunk(message_id)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_source_preview_chunk_assistant_id_conversation_id " +
                "ON source_preview_chunk(assistant_id, conversation_id)"
        )
    }
}
