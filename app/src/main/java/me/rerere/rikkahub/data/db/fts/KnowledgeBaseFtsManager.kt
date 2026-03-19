package me.rerere.rikkahub.data.db.fts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.KnowledgeBaseRepository

private const val KB_FTS_TABLE = "knowledge_base_chunk_fts"

class KnowledgeBaseFtsManager(
    private val database: AppDatabase,
    private val knowledgeBaseRepository: KnowledgeBaseRepository,
) {
    private val db get() = database.openHelper.writableDatabase

    suspend fun deleteDocument(documentId: Long) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM $KB_FTS_TABLE WHERE document_id = ?", arrayOf(documentId))
    }

    suspend fun rebuildDocument(documentId: Long, generation: Int) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM $KB_FTS_TABLE WHERE document_id = ?", arrayOf(documentId))
        knowledgeBaseRepository.getFtsRowsOfDocumentGeneration(documentId, generation).forEach { row ->
            db.execSQL(
                "INSERT INTO $KB_FTS_TABLE(content, assistant_id, document_id, chunk_id) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(row.content, row.assistantId, row.documentId, row.chunkId)
            )
        }
    }

    suspend fun ensureReady() = withContext(Dispatchers.IO) {
        val cursor = db.query("SELECT COUNT(*) FROM $KB_FTS_TABLE")
        val rowCount = cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        if (rowCount > 0) return@withContext

        knowledgeBaseRepository.getPublishedDocuments().forEach { document ->
            if (document.publishedGeneration > 0) {
                rebuildDocument(document.id, document.publishedGeneration)
            }
        }
    }

    suspend fun searchChunkIds(
        assistantId: String,
        query: String,
        limit: Int,
        documentIds: List<Long> = emptyList(),
    ): List<Long> = withContext(Dispatchers.IO) {
        val matchQuery = buildMatchQuery(query)
        if (matchQuery.isBlank()) return@withContext emptyList()
        val results = mutableListOf<Long>()
        val args = mutableListOf<Any>(assistantId)
        val sql = buildString {
            append(
                """
                SELECT chunk_id
                FROM $KB_FTS_TABLE
                WHERE assistant_id = ?
                """.trimIndent()
            )
            if (documentIds.isNotEmpty()) {
                append(" AND document_id IN (")
                append(documentIds.joinToString(",") { "?" })
                append(')')
                args.addAll(documentIds)
            }
            append(" AND content MATCH ?")
            append(" ORDER BY rank")
            append(" LIMIT ?")
        }
        args.add(matchQuery)
        args.add(limit.toString())
        val cursor = db.query(sql, args.toTypedArray())
        cursor.use {
            while (it.moveToNext()) {
                results += it.getLong(0)
            }
        }
        results
    }

    private fun buildMatchQuery(query: String): String {
        val tokens = Regex("[\\p{L}\\p{N}_-]+")
            .findAll(query)
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" OR ") { token ->
            "\"${token.replace("\"", "\"\"")}\""
        }
    }
}
