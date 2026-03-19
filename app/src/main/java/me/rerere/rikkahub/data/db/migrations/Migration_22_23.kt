package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

val Migration_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(22, 23)
        try {
            db.execSQL("ALTER TABLE knowledge_base_document ADD COLUMN queued_at INTEGER")
            db.execSQL("ALTER TABLE knowledge_base_document ADD COLUMN published_generation INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE knowledge_base_document ADD COLUMN building_generation INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE knowledge_base_document ADD COLUMN progress_current INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE knowledge_base_document ADD COLUMN progress_total INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE knowledge_base_document ADD COLUMN progress_label TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE knowledge_base_document ADD COLUMN last_heartbeat_at INTEGER")
            db.execSQL(
                """
                UPDATE knowledge_base_document
                SET queued_at = created_at,
                    published_generation = CASE WHEN chunk_count > 0 THEN 1 ELSE 0 END,
                    building_generation = 0,
                    progress_current = 0,
                    progress_total = 0,
                    progress_label = '',
                    last_heartbeat_at = NULL
                """.trimIndent()
            )
            db.execSQL("ALTER TABLE knowledge_base_chunk ADD COLUMN generation INTEGER NOT NULL DEFAULT 1")
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_knowledge_base_document_status_queued_at
                ON knowledge_base_document(status, queued_at)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_knowledge_base_chunk_document_id_generation
                ON knowledge_base_chunk(document_id, generation)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_knowledge_base_chunk_assistant_id_generation
                ON knowledge_base_chunk(assistant_id, generation)
                """.trimIndent()
            )
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
