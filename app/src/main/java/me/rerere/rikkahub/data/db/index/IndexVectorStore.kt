package me.rerere.rikkahub.data.db.index

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class IndexVectorStore(
    private val context: Context,
    private val databaseName: String = INDEX_DB_NAME,
) {
    companion object {
        private const val TAG = "IndexVectorStore"
        private const val SQLITE_BIND_LIMIT_HEADROOM = 900
    }

    private val operationMutex = Mutex()

    @Volatile
    private var database: SQLiteDatabase? = null

    val databasePath: String
        get() = context.getDatabasePath(databaseName).absolutePath

    internal fun buildStageError(
        stage: String,
        tableName: String,
        detail: String,
        cause: Throwable? = null,
    ): IllegalStateException {
        val causeText = cause?.message?.take(240).orEmpty()
        val baseMessage = if (causeText.isBlank()) {
            "sqlite-vector stage failed: $stage [db=$databasePath, table=$tableName, detail=$detail]"
        } else {
            "sqlite-vector stage failed: $stage: $causeText [db=$databasePath, table=$tableName, detail=$detail]"
        }
        return IllegalStateException(
            baseMessage,
            cause
        )
    }

    suspend fun ensureReady() {
        withPinnedConnection("ensure_ready", writeTransaction = false) { }
    }

    suspend fun insertKnowledgeBaseVectors(
        dimension: Int,
        records: List<VectorInsertRecord>,
    ) {
        if (records.isEmpty() || dimension <= 0) return
        withPinnedConnection("insert_knowledge_base_vectors_d$dimension", writeTransaction = true) { db ->
            val tableName = buildKnowledgeBaseVectorTableName(dimension)
            ensureVectorTable(db, tableName, dimension, "knowledge_base_chunk")
            insertVectorRows(db, tableName, records)
        }
    }

    suspend fun insertMemoryVectors(
        dimension: Int,
        records: List<VectorInsertRecord>,
    ) {
        if (records.isEmpty() || dimension <= 0) return
        withPinnedConnection("insert_memory_vectors_d$dimension", writeTransaction = true) { db ->
            val tableName = buildMemoryVectorTableName(dimension)
            ensureVectorTable(db, tableName, dimension, "memory_index_chunk")
            insertVectorRows(db, tableName, records)
        }
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
        return searchDistances(
            operation = "knowledge_base",
            tableName = buildKnowledgeBaseVectorTableName(dimension),
            parentTable = "knowledge_base_chunk",
            candidateIds = candidateIds,
            queryEmbeddingJson = queryEmbeddingJson,
            dimension = dimension,
            limit = limit,
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
        return searchDistances(
            operation = "memory",
            tableName = buildMemoryVectorTableName(dimension),
            parentTable = "memory_index_chunk",
            candidateIds = candidateIds,
            queryEmbeddingJson = queryEmbeddingJson,
            dimension = dimension,
            limit = limit,
        )
    }

    suspend fun clearAllVectorTables() {
        withPinnedConnection("clear_all_vector_tables", writeTransaction = true) { db ->
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
    }

    suspend fun deleteMemoryVectors(chunkIdsByDimension: Map<Int, List<Long>>) {
        deleteVectors(
            operation = "delete_memory_vectors",
            tableNameBuilder = ::buildMemoryVectorTableName,
            chunkIdsByDimension = chunkIdsByDimension,
        )
    }

    suspend fun deleteKnowledgeBaseVectors(chunkIdsByDimension: Map<Int, List<Long>>) {
        deleteVectors(
            operation = "delete_knowledge_base_vectors",
            tableNameBuilder = ::buildKnowledgeBaseVectorTableName,
            chunkIdsByDimension = chunkIdsByDimension,
        )
    }

    internal suspend fun <T> withPinnedConnection(
        operation: String,
        writeTransaction: Boolean = false,
        block: (SupportSQLiteDatabase) -> T,
    ): T = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val db = runCatching { getOrOpenDatabase() }.getOrElse { error ->
                throw buildStageError(
                    stage = "open_database",
                    tableName = "__raw_connection__",
                    detail = "operation=$operation",
                    cause = error,
                )
            }
            if (writeTransaction) {
                runCatching { db.beginTransaction() }.getOrElse { error ->
                    throw buildStageError(
                        stage = "begin_transaction",
                        tableName = "__raw_connection__",
                        detail = "operation=$operation,write=true",
                        cause = error,
                    )
                }
            }
            try {
                val result = block(db)
                if (writeTransaction) {
                    db.setTransactionSuccessful()
                }
                result
            } catch (error: Throwable) {
                Log.e(TAG, "Index vector operation failed: op=$operation path=$databasePath", error)
                throw error
            } finally {
                if (writeTransaction && db.inTransaction()) {
                    db.endTransaction()
                }
            }
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            database?.close()
            database = null
        }
    }

    private suspend fun searchDistances(
        operation: String,
        tableName: String,
        parentTable: String,
        candidateIds: List<Long>,
        queryEmbeddingJson: String,
        dimension: Int,
        limit: Int,
    ): Map<Long, Double> {
        val uniqueIds = candidateIds.distinct()
        if (uniqueIds.isEmpty()) return emptyMap()
        return withPinnedConnection("${operation}_search_d$dimension", writeTransaction = false) { db ->
            if (!hasTable(db, tableName)) {
                throw VectorSearchExecutionException(
                    operation = operation,
                    tableName = tableName,
                    dimension = dimension,
                    candidateCount = uniqueIds.size,
                    message = "Vector table is missing"
                )
            }
            ensureVectorTable(db, tableName, dimension, parentTable)
            val totalVectorRows = countRows(db, tableName)
            if (totalVectorRows == 0) {
                return@withPinnedConnection emptyMap()
            }
            val candidateIdSet = uniqueIds.toHashSet()
            val result = queryDistanceMapWithFallback(
                db = db,
                tableName = tableName,
                queryEmbeddingJson = queryEmbeddingJson,
                totalVectorRows = totalVectorRows,
                candidateIdSet = candidateIdSet,
                limit = limit,
            )
            Log.i(
                TAG,
                "sqlite-vector $operation search succeeded for $tableName with ${result.size} hits from ${uniqueIds.size} candidates"
            )
            result
        }
    }

    private suspend fun deleteVectors(
        operation: String,
        tableNameBuilder: (Int) -> String,
        chunkIdsByDimension: Map<Int, List<Long>>,
    ) {
        if (chunkIdsByDimension.isEmpty()) return
        withPinnedConnection(operation, writeTransaction = true) { db ->
            chunkIdsByDimension.forEach { (dimension, chunkIds) ->
                if (dimension <= 0 || chunkIds.isEmpty()) return@forEach
                val tableName = tableNameBuilder(dimension)
                if (!hasTable(db, tableName)) return@forEach
                deleteVectorRows(db, tableName, chunkIds.distinct())
            }
        }
    }

    private fun getOrOpenDatabase(): SQLiteDatabase {
        database?.takeIf { it.isOpen }?.let { return it }
        val configuration = SQLiteDatabaseConfiguration(
            databasePath,
            SQLiteDatabase.OPEN_READWRITE or
                SQLiteDatabase.CREATE_IF_NECESSARY
        ).apply {
            foreignKeyConstraintsEnabled = true
            customExtensions.add(
                SQLiteCustomExtension(
                    context.applicationInfo.nativeLibraryDir + "/libsimple",
                    null
                )
            )
            customExtensions.add(
                SQLiteCustomExtension(
                    context.applicationInfo.nativeLibraryDir + "/vector",
                    null
                )
            )
        }
        return SQLiteDatabase.openDatabase(configuration, null, null).also { opened ->
            applyOpenPragma(opened, stage = "set_busy_timeout", sql = "PRAGMA busy_timeout=5000")
            applyOpenPragma(opened, stage = "set_journal_mode", sql = "PRAGMA journal_mode=DELETE")
            applyOpenPragma(opened, stage = "set_synchronous", sql = "PRAGMA synchronous=NORMAL")
            Log.i(TAG, "Opened raw sqlite-vector database at $databasePath with WAL disabled")
            database = opened
        }
    }

    private fun applyOpenPragma(
        db: SQLiteDatabase,
        stage: String,
        sql: String,
    ) {
        runCatching {
            db.query(sql).use { }
        }.getOrElse { error ->
            throw buildStageError(
                stage = stage,
                tableName = "__raw_connection__",
                detail = sql,
                cause = error,
            )
        }
    }

    private fun hasTable(
        db: SupportSQLiteDatabase,
        tableName: String,
    ): Boolean {
        db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName)
        ).use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) > 0
        }
    }

    private fun ensureVectorTable(
        db: SupportSQLiteDatabase,
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
        runCatching {
            initializeVectorTable(db, tableName, dimension)
        }.getOrElse { error ->
            throw buildStageError(
                stage = "vector_init",
                tableName = tableName,
                detail = "dimension=$dimension,parent=$parentTable",
                cause = error,
            )
        }
    }

    private fun insertVectorRows(
        db: SupportSQLiteDatabase,
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

    private fun deleteVectorRows(
        db: SupportSQLiteDatabase,
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

    private fun queryDistanceMapWithFallback(
        db: SupportSQLiteDatabase,
        tableName: String,
        queryEmbeddingJson: String,
        totalVectorRows: Int,
        candidateIdSet: Set<Long>,
        limit: Int,
    ): Map<Long, Double> {
        val topKAttempt = runCatching {
            queryDistanceMap(
                db = db,
                sql = buildTopKDistanceQuery(tableName),
                args = listOf(queryEmbeddingJson, totalVectorRows),
                tableName = tableName,
                detail = "mode=top_k,k=$totalVectorRows"
            )
        }
        topKAttempt.getOrNull()?.let { distances ->
            return distances.asSequence()
                .filter { (rowId, _) -> candidateIdSet.contains(rowId) }
                .sortedBy { it.value }
                .take(limit)
                .associate { it.key to it.value }
        }

        val streamingAttempt = runCatching {
            queryDistanceMap(
                db = db,
                sql = buildStreamingDistanceQuery(tableName),
                args = listOf(queryEmbeddingJson),
                tableName = tableName,
                detail = "mode=streaming"
            )
        }
        streamingAttempt.getOrNull()?.let { distances ->
            return distances.asSequence()
                .filter { (rowId, _) -> candidateIdSet.contains(rowId) }
                .sortedBy { it.value }
                .take(limit)
                .associate { it.key to it.value }
        }

        val topKError = topKAttempt.exceptionOrNull()
        val streamingError = streamingAttempt.exceptionOrNull()
        throw buildStageError(
            stage = "vector_full_scan",
            tableName = tableName,
            detail = "top_k_failed=${topKError?.message.orEmpty().take(180)}; streaming_failed=${streamingError?.message.orEmpty().take(180)}",
            cause = streamingError ?: topKError,
        )
    }

    private fun queryDistanceMap(
        db: SupportSQLiteDatabase,
        sql: String,
        args: List<Any>,
        tableName: String,
        detail: String,
    ): Map<Long, Double> {
        val result = linkedMapOf<Long, Double>()
        runCatching {
            db.query(sql, args.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    result[cursor.getLong(0)] = cursor.getDouble(1)
                }
            }
        }.getOrElse { error ->
            throw buildStageError(
                stage = "vector_full_scan",
                tableName = tableName,
                detail = detail,
                cause = error,
            )
        }
        return result
    }

    private fun buildTopKDistanceQuery(tableName: String): String {
        return """
            SELECT rowid, distance
            FROM vector_full_scan('$tableName', 'embedding', vector_as_f32(?), ?)
        """.trimIndent()
    }

    private fun buildStreamingDistanceQuery(tableName: String): String {
        return """
            SELECT rowid, distance
            FROM vector_full_scan('$tableName', 'embedding', vector_as_f32(?))
        """.trimIndent()
    }

    private fun countRows(
        db: SupportSQLiteDatabase,
        tableName: String,
    ): Int {
        db.query("SELECT COUNT(*) FROM `$tableName`").use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }
}
