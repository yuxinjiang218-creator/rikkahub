package me.rerere.rikkahub.data.db.index

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase

data class VectorInsertRecord(
    val chunkId: Long,
    val embeddingJson: String,
)

class IndexVectorTableManager(
    private val database: IndexDatabase,
    private val vectorBackendVerifier: VectorBackendVerifier,
) {
    companion object {
        private const val TAG = "IndexVectorTableManager"
        private const val SQLITE_BIND_LIMIT_HEADROOM = 900
    }

    private val db: SupportSQLiteDatabase
        get() = database.openHelper.writableDatabase

    suspend fun ensureReady() {
        createKnowledgeBaseFtsTable(db)
        initializeExistingVectorTables(db)
    }

    suspend fun insertKnowledgeBaseVectors(
        dimension: Int,
        records: List<VectorInsertRecord>,
    ) {
        if (records.isEmpty() || dimension <= 0) return
        val tableName = buildKnowledgeBaseVectorTableName(dimension)
        ensureVectorTable(tableName, dimension, "knowledge_base_chunk")
        insertVectorRows(tableName, records)
    }

    suspend fun insertMemoryVectors(
        dimension: Int,
        records: List<VectorInsertRecord>,
    ) {
        if (records.isEmpty() || dimension <= 0) return
        val tableName = buildMemoryVectorTableName(dimension)
        ensureVectorTable(tableName, dimension, "memory_index_chunk")
        insertVectorRows(tableName, records)
    }

    suspend fun searchKnowledgeBaseDistances(
        candidateIds: List<Long>,
        queryEmbeddingJson: String,
        dimension: Int,
        limit: Int,
    ): Map<Long, Double> {
        if (candidateIds.isEmpty() || queryEmbeddingJson.isBlank() || dimension <= 0 || limit <= 0) {
            return emptyMap()
        }
        val tableName = buildKnowledgeBaseVectorTableName(dimension)
        if (!hasTable(tableName)) {
            throw VectorSearchExecutionException(
                operation = "knowledge_base",
                tableName = tableName,
                dimension = dimension,
                candidateCount = candidateIds.size,
                message = "Knowledge base vector table is missing"
            )
        }
        ensureVectorTable(tableName, dimension, "knowledge_base_chunk")
        return searchDistancesByRowIds(
            tableName = tableName,
            candidateIds = candidateIds,
            queryEmbeddingJson = queryEmbeddingJson,
            limit = limit,
            operation = "knowledge_base"
        )
    }

    suspend fun searchMemoryDistances(
        candidateIds: List<Long>,
        queryEmbeddingJson: String,
        dimension: Int,
        limit: Int,
    ): Map<Long, Double> {
        if (candidateIds.isEmpty() || queryEmbeddingJson.isBlank() || dimension <= 0 || limit <= 0) {
            return emptyMap()
        }
        val tableName = buildMemoryVectorTableName(dimension)
        if (!hasTable(tableName)) {
            throw VectorSearchExecutionException(
                operation = "memory",
                tableName = tableName,
                dimension = dimension,
                candidateCount = candidateIds.size,
                message = "Memory vector table is missing"
            )
        }
        ensureVectorTable(tableName, dimension, "memory_index_chunk")
        return searchDistancesByRowIds(
            tableName = tableName,
            candidateIds = candidateIds,
            queryEmbeddingJson = queryEmbeddingJson,
            limit = limit,
            operation = "memory"
        )
    }

    suspend fun clearAllVectorTables() {
        val cursor = db.query(
            """
            SELECT name
            FROM sqlite_master
            WHERE type = 'table'
                AND (name LIKE '${KB_VECTOR_TABLE_PREFIX}%' OR name LIKE '${MEMORY_VECTOR_TABLE_PREFIX}%')
            """.trimIndent()
        )
        val tableNames = cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(it.getString(0))
                }
            }
        }
        tableNames.forEach { tableName ->
            db.execSQL("DROP TABLE IF EXISTS `$tableName`")
        }
    }

    suspend fun deleteMemoryVectors(chunkIdsByDimension: Map<Int, List<Long>>) {
        chunkIdsByDimension.forEach { (dimension, chunkIds) ->
            if (dimension <= 0 || chunkIds.isEmpty()) return@forEach
            val tableName = buildMemoryVectorTableName(dimension)
            if (!hasTable(tableName)) return@forEach
            deleteVectorRows(tableName, chunkIds.distinct())
        }
    }

    private fun hasTable(tableName: String): Boolean {
        db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName)
        ).use {
            return it.moveToFirst() && it.getInt(0) > 0
        }
    }

    private fun ensureVectorTable(
        tableName: String,
        dimension: Int,
        parentTable: String,
    ) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `$tableName` (
                `chunk_id` INTEGER PRIMARY KEY NOT NULL,
                `embedding` BLOB NOT NULL,
                FOREIGN KEY(`chunk_id`) REFERENCES `$parentTable`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        initializeVectorTable(db, tableName, dimension)
    }

    private fun insertVectorRows(
        tableName: String,
        records: List<VectorInsertRecord>,
    ) {
        val statement = db.compileStatement(
            "INSERT OR REPLACE INTO `$tableName` (`chunk_id`, `embedding`) VALUES (?, vector_as_f32(?))"
        )
        records.forEach { record ->
            statement.clearBindings()
            statement.bindLong(1, record.chunkId)
            statement.bindString(2, record.embeddingJson)
            statement.executeInsert()
        }
    }

    private fun queryDistanceMap(
        sql: String,
        args: List<Any>,
    ): Map<Long, Double> {
        val result = linkedMapOf<Long, Double>()
        db.query(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getLong(0)] = cursor.getDouble(1)
            }
        }
        return result
    }

    private suspend fun searchDistancesByRowIds(
        tableName: String,
        candidateIds: List<Long>,
        queryEmbeddingJson: String,
        limit: Int,
        operation: String,
    ): Map<Long, Double> {
        val uniqueIds = candidateIds.distinct()
        if (uniqueIds.isEmpty()) return emptyMap()
        vectorBackendVerifier.assertHealthy(
            operation = operation,
            tableName = tableName,
            dimension = extractDimension(tableName),
            candidateCount = uniqueIds.size,
        )

        return try {
            uniqueIds
                .chunked(SQLITE_BIND_LIMIT_HEADROOM)
                .asSequence()
                .flatMap { batch ->
                    queryDistanceMap(
                        sql = buildRowIdDistanceQuery(tableName, batch),
                        args = buildList {
                            add(queryEmbeddingJson)
                            addAll(batch)
                            add(limit)
                        }
                    ).asSequence()
                }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, distances) -> distances.minOrNull() ?: Double.POSITIVE_INFINITY }
                .entries
                .sortedBy { it.value }
                .take(limit)
                .associate { it.key to it.value }
                .also { result ->
                    Log.i(
                        TAG,
                        "sqlite-vector $operation search succeeded for $tableName with ${result.size} hits from ${uniqueIds.size} candidates"
                    )
                }
        } catch (error: Throwable) {
            Log.e(TAG, "sqlite-vector $operation distance query failed for $tableName", error)
            throw VectorSearchExecutionException(
                operation = operation,
                tableName = tableName,
                dimension = extractDimension(tableName),
                candidateCount = uniqueIds.size,
                message = error.message.orEmpty().ifBlank { "sqlite-vector search failed" },
                cause = error
            )
        }
    }

    private fun deleteVectorRows(
        tableName: String,
        chunkIds: List<Long>,
    ) {
        if (chunkIds.isEmpty()) return
        chunkIds.chunked(SQLITE_BIND_LIMIT_HEADROOM).forEach { batch ->
            db.execSQL(
                "DELETE FROM `$tableName` WHERE chunk_id IN (${batch.joinToString(",") { "?" }})",
                batch.map { it as Any }.toTypedArray()
            )
        }
    }

    private fun buildRowIdDistanceQuery(
        tableName: String,
        candidateIds: List<Long>,
    ): String {
        return buildString {
            append(
                """
                SELECT v.rowid, v.distance
                FROM vector_full_scan('$tableName', 'embedding', vector_as_f32(?)) AS v
                WHERE v.rowid IN (
                """.trimIndent()
            )
            append(candidateIds.joinToString(",") { "?" })
            append(") ORDER BY v.distance LIMIT ?")
        }
    }

    private fun extractDimension(tableName: String): Int {
        return when {
            tableName.startsWith(KB_VECTOR_TABLE_PREFIX) ->
                tableName.removePrefix(KB_VECTOR_TABLE_PREFIX).toIntOrNull()
            tableName.startsWith(MEMORY_VECTOR_TABLE_PREFIX) ->
                tableName.removePrefix(MEMORY_VECTOR_TABLE_PREFIX).toIntOrNull()
            else -> null
        } ?: 0
    }
}
