package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 知识库功能数据库迁移 v17 -> v18
 * 创建 3 张表：
 * - knowledge_document: 文档元数据
 * - knowledge_chunk: 文本块
 * - knowledge_vector: 向量数据
 */
val Migration_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 创建 knowledge_document 表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS knowledge_document (
                id TEXT PRIMARY KEY NOT NULL,
                assistantId TEXT NOT NULL,
                fileName TEXT NOT NULL,
                mime TEXT NOT NULL,
                localPath TEXT NOT NULL,
                sizeBytes INTEGER NOT NULL,
                contentHash TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                status TEXT NOT NULL,
                errorMessage TEXT,
                embeddingModelId TEXT
            )
        """.trimIndent())

        // 创建索引
        db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_document_assistantId ON knowledge_document(assistantId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_knowledge_document_contentHash_assistantId ON knowledge_document(contentHash, assistantId)")

        // 创建 knowledge_chunk 表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS knowledge_chunk (
                id TEXT PRIMARY KEY NOT NULL,
                documentId TEXT NOT NULL,
                assistantId TEXT NOT NULL,
                chunkIndex INTEGER NOT NULL,
                text TEXT NOT NULL,
                charCount INTEGER NOT NULL,
                FOREIGN KEY(documentId) REFERENCES knowledge_document(id) ON DELETE CASCADE
            )
        """.trimIndent())

        // 创建索引
        db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_chunk_documentId ON knowledge_chunk(documentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_chunk_assistantId ON knowledge_chunk(assistantId)")

        // 创建 knowledge_vector 表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS knowledge_vector (
                id TEXT PRIMARY KEY NOT NULL,
                chunkId TEXT NOT NULL,
                assistantId TEXT NOT NULL,
                embeddingModelId TEXT NOT NULL,
                embeddingVector TEXT NOT NULL,
                vectorNorm REAL NOT NULL,
                FOREIGN KEY(chunkId) REFERENCES knowledge_chunk(id) ON DELETE CASCADE
            )
        """.trimIndent())

        // 创建索引
        db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_vector_chunkId ON knowledge_vector(chunkId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_vector_assistantId ON knowledge_vector(assistantId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_vector_embeddingModelId ON knowledge_vector(embeddingModelId)")
    }
}
