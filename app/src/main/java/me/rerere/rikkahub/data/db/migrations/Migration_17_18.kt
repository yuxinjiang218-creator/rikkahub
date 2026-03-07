package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val conversationColumns = db.getColumnNames("ConversationEntity").toMutableSet()

        // Upstream v17 databases can be missing workflow_state even though they share the same user_version.
        ensureConversationColumn(
            db = db,
            knownColumns = conversationColumns,
            name = "workflow_state",
            definition = "TEXT NOT NULL DEFAULT ''"
        )
        ensureConversationColumn(
            db = db,
            knownColumns = conversationColumns,
            name = "rolling_summary_json",
            definition = "TEXT NOT NULL DEFAULT ''"
        )
        ensureConversationColumn(
            db = db,
            knownColumns = conversationColumns,
            name = "rolling_summary_token_estimate",
            definition = "INTEGER NOT NULL DEFAULT 0"
        )
        ensureConversationColumn(
            db = db,
            knownColumns = conversationColumns,
            name = "last_compressed_message_index",
            definition = "INTEGER NOT NULL DEFAULT -1"
        )
        ensureConversationColumn(
            db = db,
            knownColumns = conversationColumns,
            name = "last_compressed_at",
            definition = "INTEGER NOT NULL DEFAULT 0"
        )

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

private fun ensureConversationColumn(
    db: SupportSQLiteDatabase,
    knownColumns: MutableSet<String>,
    name: String,
    definition: String,
) {
    if (knownColumns.contains(name)) return
    db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN $name $definition")
    knownColumns += name
}

private fun SupportSQLiteDatabase.getColumnNames(tableName: String): Set<String> {
    return query("PRAGMA table_info(`$tableName`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        buildSet {
            while (cursor.moveToNext()) {
                add(cursor.getString(nameIndex))
            }
        }
    }
}
