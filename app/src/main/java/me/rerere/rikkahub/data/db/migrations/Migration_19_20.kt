package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE compression_event ADD COLUMN compress_start_index INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE compression_event ADD COLUMN compress_end_index INTEGER NOT NULL DEFAULT -1")
        db.execSQL("ALTER TABLE compression_event ADD COLUMN keep_recent_messages INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE compression_event ADD COLUMN trigger TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE compression_event ADD COLUMN additional_prompt TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE compression_event ADD COLUMN base_summary_json TEXT NOT NULL DEFAULT ''")
    }
}
