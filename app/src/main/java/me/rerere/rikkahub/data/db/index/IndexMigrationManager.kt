package me.rerere.rikkahub.data.db.index

import android.util.Log
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.index.dao.IndexKnowledgeBaseChunkDAO
import me.rerere.rikkahub.data.db.index.dao.IndexMemoryIndexChunkDAO
import me.rerere.rikkahub.data.db.index.dao.IndexMigrationStateDAO
import me.rerere.rikkahub.data.db.index.dao.IndexPendingLedgerBatchDAO
import me.rerere.rikkahub.data.db.index.dao.IndexSourcePreviewChunkDAO
import me.rerere.rikkahub.data.db.index.entity.IndexKnowledgeBaseChunkEntity
import me.rerere.rikkahub.data.db.index.entity.IndexMemoryIndexChunkEntity
import me.rerere.rikkahub.data.db.index.entity.IndexMigrationStateEntity
import me.rerere.rikkahub.data.db.index.entity.IndexPendingLedgerBatchEntity
import me.rerere.rikkahub.data.db.index.entity.IndexSourcePreviewChunkEntity
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "IndexMigrationManager"

class IndexMigrationManager(
    private val appDatabase: AppDatabase,
    private val indexDatabase: IndexDatabase,
    private val migrationStateDAO: IndexMigrationStateDAO,
    private val knowledgeBaseChunkDAO: IndexKnowledgeBaseChunkDAO,
    private val memoryIndexChunkDAO: IndexMemoryIndexChunkDAO,
    private val sourcePreviewChunkDAO: IndexSourcePreviewChunkDAO,
    private val pendingLedgerBatchDAO: IndexPendingLedgerBatchDAO,
    private val vectorTableManager: IndexVectorTableManager,
) {
    private val migrationMutex = Mutex()

    @Volatile
    private var cutoverComplete: Boolean = false

    init {
        cutoverComplete = loadCachedCutoverFlag()
    }

    fun shouldUseIndexBackend(): Boolean = cutoverComplete

    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        migrationMutex.withLock {
            val state = migrationStateDAO.getState() ?: IndexMigrationStateEntity()
            cutoverComplete = state.cutoverComplete
            if (state.cutoverComplete) {
                pruneOrphanedConversationScopedData()
                if (!state.legacyPruned) {
                    pruneLegacyTables(state)
                }
                return@withLock
            }

            runCatching {
                vectorTableManager.ensureReady()
                resetIndexDatabase()
                val legacyCounts = captureLegacyCounts()
                copyKnowledgeBaseChunks()
                copyMemoryIndexChunks()
                copySourcePreviewChunks()
                copyPendingLedgerBatches()
                rebuildKnowledgeBaseFts()
                verifyCounts(legacyCounts)
                val migratedState = state.copy(
                    schemaVersion = INDEX_SCHEMA_VERSION,
                    backendVersion = INDEX_BACKEND_VERSION,
                    cutoverComplete = true,
                    legacyPruned = false,
                    lastMigratedAt = System.currentTimeMillis(),
                    lastError = "",
                )
                migrationStateDAO.upsert(migratedState)
                cutoverComplete = true
                pruneLegacyTables(migratedState)
            }.onFailure { error ->
                Log.e(TAG, "Index migration failed", error)
                cutoverComplete = false
                migrationStateDAO.upsert(
                    state.copy(
                        schemaVersion = INDEX_SCHEMA_VERSION,
                        backendVersion = INDEX_BACKEND_VERSION,
                        cutoverComplete = false,
                        legacyPruned = false,
                        lastError = error.message.orEmpty().take(500),
                    )
                )
            }
        }
    }

    suspend fun deleteConversationScopedData(conversationId: String) = withContext(Dispatchers.IO) {
        migrationMutex.withLock {
            deleteConversationScopedDataLocked(conversationId)
        }
    }

    private fun loadCachedCutoverFlag(): Boolean {
        return runCatching {
            val db = indexDatabase.openHelper.writableDatabase
            db.query(
                "SELECT cutover_complete FROM $INDEX_MIGRATION_STATE_TABLE WHERE id = 1 LIMIT 1"
            ).use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) != 0
            }
        }.getOrDefault(false)
    }

    private suspend fun resetIndexDatabase() {
        indexDatabase.clearAllTables()
        vectorTableManager.clearAllVectorTables()
        val db = indexDatabase.openHelper.writableDatabase
        db.execSQL("DELETE FROM $INDEX_KB_FTS_TABLE")
    }

    private fun captureLegacyCounts(): Map<String, Int> {
        val db = appDatabase.openHelper.writableDatabase
        return mapOf(
            "knowledge_base_chunk" to countRows(db, "knowledge_base_chunk"),
            "memory_index_chunk" to countRows(db, "memory_index_chunk"),
            "source_preview_chunk" to countRows(db, "source_preview_chunk"),
            "pending_ledger_batch" to countRows(db, "pending_ledger_batch"),
        )
    }

    private suspend fun copyKnowledgeBaseChunks() {
        val cursor = appDatabase.openHelper.writableDatabase.query(
            """
            SELECT id, document_id, assistant_id, generation, chunk_order, content, token_estimate, embedding_json, updated_at
            FROM knowledge_base_chunk
            ORDER BY id
            """.trimIndent()
        )
        cursor.use {
            val entities = mutableListOf<IndexKnowledgeBaseChunkEntity>()
            val groupedVectors = linkedMapOf<Int, MutableList<VectorInsertRecord>>()
            while (it.moveToNext()) {
                val chunkId = it.getLong(0)
                val embeddingJson = it.getString(7)
                val dimension = JsonInstant.decodeFromString<List<Float>>(embeddingJson).size
                entities += IndexKnowledgeBaseChunkEntity(
                    id = chunkId,
                    documentId = it.getLong(1),
                    assistantId = it.getString(2),
                    generation = it.getInt(3),
                    chunkOrder = it.getInt(4),
                    content = it.getString(5),
                    tokenEstimate = it.getInt(6),
                    embeddingDimension = dimension,
                    updatedAt = it.getLong(8),
                )
                groupedVectors.getOrPut(dimension) { mutableListOf() }.add(
                    VectorInsertRecord(
                        chunkId = chunkId,
                        embeddingJson = embeddingJson,
                    )
                )
                if (entities.size >= 128) {
                    flushKnowledgeBaseBatch(entities, groupedVectors)
                }
            }
            flushKnowledgeBaseBatch(entities, groupedVectors)
        }
    }

    private suspend fun flushKnowledgeBaseBatch(
        entities: MutableList<IndexKnowledgeBaseChunkEntity>,
        groupedVectors: MutableMap<Int, MutableList<VectorInsertRecord>>,
    ) {
        if (entities.isEmpty()) return
        knowledgeBaseChunkDAO.insertAll(entities.toList())
        groupedVectors.forEach { (dimension, records) ->
            vectorTableManager.insertKnowledgeBaseVectors(dimension, records.toList())
        }
        entities.clear()
        groupedVectors.clear()
    }

    private suspend fun copyMemoryIndexChunks() {
        val cursor = appDatabase.openHelper.writableDatabase.query(
            """
            SELECT id, assistant_id, conversation_id, section_key, chunk_order, content, token_estimate, embedding_json, metadata_json, updated_at
            FROM memory_index_chunk
            ORDER BY id
            """.trimIndent()
        )
        cursor.use {
            val entities = mutableListOf<IndexMemoryIndexChunkEntity>()
            val groupedVectors = linkedMapOf<Int, MutableList<VectorInsertRecord>>()
            while (it.moveToNext()) {
                val chunkId = it.getLong(0)
                val embeddingJson = it.getString(7)
                val dimension = JsonInstant.decodeFromString<List<Float>>(embeddingJson).size
                entities += IndexMemoryIndexChunkEntity(
                    id = chunkId,
                    assistantId = it.getString(1),
                    conversationId = it.getString(2),
                    sectionKey = it.getString(3),
                    chunkOrder = it.getInt(4),
                    content = it.getString(5),
                    tokenEstimate = it.getInt(6),
                    embeddingDimension = dimension,
                    metadataJson = it.getString(8),
                    updatedAt = it.getLong(9),
                )
                groupedVectors.getOrPut(dimension) { mutableListOf() }.add(
                    VectorInsertRecord(
                        chunkId = chunkId,
                        embeddingJson = embeddingJson,
                    )
                )
                if (entities.size >= 128) {
                    flushMemoryBatch(entities, groupedVectors)
                }
            }
            flushMemoryBatch(entities, groupedVectors)
        }
    }

    private suspend fun flushMemoryBatch(
        entities: MutableList<IndexMemoryIndexChunkEntity>,
        groupedVectors: MutableMap<Int, MutableList<VectorInsertRecord>>,
    ) {
        if (entities.isEmpty()) return
        memoryIndexChunkDAO.insertAll(entities.toList())
        groupedVectors.forEach { (dimension, records) ->
            vectorTableManager.insertMemoryVectors(dimension, records.toList())
        }
        entities.clear()
        groupedVectors.clear()
    }

    private suspend fun copySourcePreviewChunks() {
        val cursor = appDatabase.openHelper.writableDatabase.query(
            """
            SELECT id, assistant_id, conversation_id, message_id, role, chunk_order, prefix_text, search_text, block_type, updated_at
            FROM source_preview_chunk
            ORDER BY id
            """.trimIndent()
        )
        cursor.use {
            val batch = mutableListOf<IndexSourcePreviewChunkEntity>()
            while (it.moveToNext()) {
                batch += IndexSourcePreviewChunkEntity(
                    id = it.getLong(0),
                    assistantId = it.getString(1),
                    conversationId = it.getString(2),
                    messageId = it.getString(3),
                    role = it.getString(4),
                    chunkOrder = it.getInt(5),
                    prefixText = it.getString(6),
                    searchText = it.getString(7),
                    blockType = it.getString(8),
                    updatedAt = it.getLong(9),
                )
                if (batch.size >= 256) {
                    sourcePreviewChunkDAO.insertAll(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) {
                sourcePreviewChunkDAO.insertAll(batch.toList())
            }
        }
    }

    private suspend fun copyPendingLedgerBatches() {
        val cursor = appDatabase.openHelper.writableDatabase.query(
            """
            SELECT id, conversation_id, event_id, start_index, end_index, incremental_messages, status, attempt_count, last_error, created_at, updated_at
            FROM pending_ledger_batch
            ORDER BY id
            """.trimIndent()
        )
        cursor.use {
            while (it.moveToNext()) {
                pendingLedgerBatchDAO.insert(
                    IndexPendingLedgerBatchEntity(
                        id = it.getLong(0),
                        conversationId = it.getString(1),
                        eventId = it.getLong(2),
                        startIndex = it.getInt(3),
                        endIndex = it.getInt(4),
                        incrementalMessages = it.getString(5),
                        status = it.getString(6),
                        attemptCount = it.getInt(7),
                        lastError = it.getString(8),
                        createdAt = it.getLong(9),
                        updatedAt = it.getLong(10),
                    )
                )
            }
        }
    }

    private fun rebuildKnowledgeBaseFts() {
        val legacyDb = appDatabase.openHelper.writableDatabase
        val indexDb = indexDatabase.openHelper.writableDatabase
        indexDb.execSQL("DELETE FROM $INDEX_KB_FTS_TABLE")
        legacyDb.query(
            """
            SELECT id, published_generation
            FROM knowledge_base_document
            WHERE published_generation > 0
            """.trimIndent()
        ).use { documentCursor ->
            while (documentCursor.moveToNext()) {
                val documentId = documentCursor.getLong(0)
                val generation = documentCursor.getInt(1)
                indexDb.query(
                    """
                    SELECT id, assistant_id, document_id, content
                    FROM knowledge_base_chunk
                    WHERE document_id = ? AND generation = ?
                    ORDER BY chunk_order ASC
                    """.trimIndent(),
                    arrayOf<Any>(documentId, generation)
                ).use { chunkCursor ->
                    while (chunkCursor.moveToNext()) {
                        indexDb.execSQL(
                            "INSERT INTO $INDEX_KB_FTS_TABLE(content, assistant_id, document_id, chunk_id) VALUES (?, ?, ?, ?)",
                            arrayOf(
                                chunkCursor.getString(3),
                                chunkCursor.getString(1),
                                chunkCursor.getLong(2),
                                chunkCursor.getLong(0),
                            )
                        )
                    }
                }
            }
        }
    }

    private fun verifyCounts(legacyCounts: Map<String, Int>) {
        val indexDb = indexDatabase.openHelper.writableDatabase
        val currentCounts = mapOf(
            "knowledge_base_chunk" to countRows(indexDb, "knowledge_base_chunk"),
            "memory_index_chunk" to countRows(indexDb, "memory_index_chunk"),
            "source_preview_chunk" to countRows(indexDb, "source_preview_chunk"),
            "pending_ledger_batch" to countRows(indexDb, "pending_ledger_batch"),
        )
        currentCounts.forEach { (table, count) ->
            val expected = legacyCounts[table] ?: 0
            check(count == expected) { "Index migration count mismatch for $table: expected=$expected actual=$count" }
        }
    }

    private suspend fun pruneLegacyTables(state: IndexMigrationStateEntity) {
        runCatching {
            val legacyDb = appDatabase.openHelper.writableDatabase
            legacyDb.execSQL("DELETE FROM knowledge_base_chunk")
            legacyDb.execSQL("DELETE FROM memory_index_chunk")
            legacyDb.execSQL("DELETE FROM source_preview_chunk")
            legacyDb.execSQL("DELETE FROM pending_ledger_batch")
            legacyDb.execSQL("DELETE FROM knowledge_base_chunk_fts")
            legacyDb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
            legacyDb.execSQL("VACUUM")
            migrationStateDAO.upsert(
                state.copy(
                    legacyPruned = true,
                    lastError = "",
                )
            )
        }.onFailure { error ->
            Log.e(TAG, "Legacy table prune failed", error)
            migrationStateDAO.upsert(
                state.copy(
                    legacyPruned = false,
                    lastError = error.message.orEmpty().take(500),
                )
            )
        }
    }

    private suspend fun pruneOrphanedConversationScopedData() {
        runCatching {
            val liveConversationIds = appDatabase.openHelper.writableDatabase.query(
                "SELECT id FROM conversationentity"
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0))
                    }
                }
            }
            val orphanConversationIds = indexDatabase.openHelper.writableDatabase.query(
                """
                SELECT conversation_id FROM memory_index_chunk
                UNION
                SELECT conversation_id FROM source_preview_chunk
                UNION
                SELECT conversation_id FROM pending_ledger_batch
                """.trimIndent()
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val conversationId = cursor.getString(0)
                        if (!liveConversationIds.contains(conversationId)) {
                            add(conversationId)
                        }
                    }
                }
            }
            if (orphanConversationIds.isEmpty()) return

            Log.w(
                TAG,
                "Pruning ${orphanConversationIds.size} orphaned conversation-scoped index payloads"
            )
            orphanConversationIds.forEach { conversationId ->
                deleteConversationScopedDataLocked(conversationId)
            }
            indexDatabase.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
            indexDatabase.openHelper.writableDatabase.execSQL("VACUUM")
        }.onFailure { error ->
            Log.e(TAG, "Orphaned conversation-scoped data prune failed", error)
        }
    }

    private suspend fun deleteConversationScopedDataLocked(conversationId: String) {
        appDatabase.withTransaction {
            val legacyDb = appDatabase.openHelper.writableDatabase
            deleteLegacyConversationScopedData(legacyDb, conversationId)
        }
        indexDatabase.withTransaction {
            deleteIndexConversationScopedData(conversationId)
        }
    }

    private fun deleteLegacyConversationScopedData(
        db: SupportSQLiteDatabase,
        conversationId: String,
    ) {
        db.execSQL(
            "DELETE FROM memory_index_chunk WHERE conversation_id = ?",
            arrayOf(conversationId)
        )
        db.execSQL(
            "DELETE FROM source_preview_chunk WHERE conversation_id = ?",
            arrayOf(conversationId)
        )
        db.execSQL(
            "DELETE FROM pending_ledger_batch WHERE conversation_id = ?",
            arrayOf(conversationId)
        )
    }

    private suspend fun deleteIndexConversationScopedData(conversationId: String) {
        val indexDb = indexDatabase.openHelper.writableDatabase
        val memoryChunkIdsByDimension = linkedMapOf<Int, MutableList<Long>>()
        indexDb.query(
            """
            SELECT id, embedding_dimension
            FROM memory_index_chunk
            WHERE conversation_id = ?
            """.trimIndent(),
            arrayOf(conversationId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val chunkId = cursor.getLong(0)
                val dimension = cursor.getInt(1)
                memoryChunkIdsByDimension.getOrPut(dimension) { mutableListOf() }.add(chunkId)
            }
        }

        indexDb.execSQL(
            "DELETE FROM memory_index_chunk WHERE conversation_id = ?",
            arrayOf(conversationId)
        )
        indexDb.execSQL(
            "DELETE FROM source_preview_chunk WHERE conversation_id = ?",
            arrayOf(conversationId)
        )
        indexDb.execSQL(
            "DELETE FROM pending_ledger_batch WHERE conversation_id = ?",
            arrayOf(conversationId)
        )
        vectorTableManager.deleteMemoryVectors(memoryChunkIdsByDimension)
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
