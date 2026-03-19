package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

val Migration_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(21, 22)
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS knowledge_base_document (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    assistant_id TEXT NOT NULL,
                    relative_path TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    mime_type TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    chunk_count INTEGER NOT NULL,
                    last_indexed_at INTEGER,
                    last_error TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS index_knowledge_base_document_relative_path
                ON knowledge_base_document(relative_path)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_knowledge_base_document_assistant_id
                ON knowledge_base_document(assistant_id)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_knowledge_base_document_status
                ON knowledge_base_document(status)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_knowledge_base_document_assistant_id_status
                ON knowledge_base_document(assistant_id, status)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS knowledge_base_chunk (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    document_id INTEGER NOT NULL,
                    assistant_id TEXT NOT NULL,
                    chunk_order INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    token_estimate INTEGER NOT NULL,
                    embedding_json TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY(document_id) REFERENCES knowledge_base_document(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_knowledge_base_chunk_document_id
                ON knowledge_base_chunk(document_id)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_knowledge_base_chunk_assistant_id
                ON knowledge_base_chunk(assistant_id)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_knowledge_base_chunk_assistant_id_document_id
                ON knowledge_base_chunk(assistant_id, document_id)
                """.trimIndent()
            )
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
