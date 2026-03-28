package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.KnowledgeBaseChunkDAO
import me.rerere.rikkahub.data.db.dao.KnowledgeBaseChunkFtsRow
import me.rerere.rikkahub.data.db.dao.KnowledgeBaseChunkWithDocumentRow
import me.rerere.rikkahub.data.db.dao.KnowledgeBaseDocumentDAO
import me.rerere.rikkahub.data.db.entity.KnowledgeBaseChunkEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeBaseDocumentEntity
import me.rerere.rikkahub.data.db.index.IndexDatabase
import me.rerere.rikkahub.data.db.index.IndexMigrationManager
import me.rerere.rikkahub.data.db.index.IndexVectorTableManager
import me.rerere.rikkahub.data.db.index.VectorInsertRecord
import me.rerere.rikkahub.data.db.index.dao.IndexKnowledgeBaseChunkDAO
import me.rerere.rikkahub.data.db.index.entity.IndexKnowledgeBaseChunkEntity
import me.rerere.rikkahub.data.model.KnowledgeBaseChunk
import me.rerere.rikkahub.data.model.KnowledgeBaseDocument
import me.rerere.rikkahub.data.model.KnowledgeBaseDocumentStatus
import me.rerere.rikkahub.data.model.KnowledgeBaseDocumentSummary
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

data class KnowledgeBaseChunkWithDocument(
    val chunk: KnowledgeBaseChunk,
    val documentName: String,
    val mimeType: String,
)

class KnowledgeBaseRepository(
    private val documentDAO: KnowledgeBaseDocumentDAO,
    private val legacyChunkDAO: KnowledgeBaseChunkDAO,
    private val appDatabase: AppDatabase,
    private val indexChunkDAO: IndexKnowledgeBaseChunkDAO,
    private val indexDatabase: IndexDatabase,
    private val indexMigrationManager: IndexMigrationManager,
    private val vectorTableManager: IndexVectorTableManager,
) {
    fun observeDocumentsOfAssistant(assistantId: Uuid): Flow<List<KnowledgeBaseDocument>> {
        return documentDAO.observeDocumentsOfAssistant(assistantId.toString())
            .map { entities -> entities.mapNotNull { it.toModel() } }
    }

    suspend fun getDocumentsOfAssistant(assistantId: Uuid): List<KnowledgeBaseDocument> {
        return documentDAO.getDocumentsOfAssistant(assistantId.toString())
            .mapNotNull { it.toModel() }
    }

    suspend fun getPublishedDocuments(): List<KnowledgeBaseDocument> {
        return documentDAO.getPublishedDocuments().mapNotNull { it.toModel() }
    }

    suspend fun getSearchableDocumentsOfAssistant(assistantId: Uuid): List<KnowledgeBaseDocumentSummary> {
        return documentDAO.getSearchableDocumentsOfAssistant(assistantId.toString())
            .mapNotNull { entity ->
                entity.toModel()?.takeIf { it.isSearchable }?.let { document ->
                    KnowledgeBaseDocumentSummary(
                        documentId = document.id,
                        documentName = document.displayName,
                        mimeType = document.mimeType,
                        chunkCount = document.chunkCount,
                    )
                }
            }
    }

    suspend fun getDocument(documentId: Long): KnowledgeBaseDocument? {
        return documentDAO.getDocument(documentId)?.toModel()
    }

    suspend fun getNextQueuedDocument(): KnowledgeBaseDocument? {
        return documentDAO.getNextDocumentByStatus(KnowledgeBaseDocumentStatus.QUEUED.name)?.toModel()
    }

    suspend fun getIndexingDocument(): KnowledgeBaseDocument? {
        return documentDAO.getLatestDocumentByStatus(KnowledgeBaseDocumentStatus.INDEXING.name)?.toModel()
    }

    suspend fun createDocument(document: KnowledgeBaseDocument): KnowledgeBaseDocument {
        val entity = document.toEntity()
        val id = documentDAO.insert(entity)
        return document.copy(id = id)
    }

    suspend fun updateDocument(document: KnowledgeBaseDocument) {
        documentDAO.update(document.toEntity())
    }

    suspend fun hasDocuments(assistantId: Uuid): Boolean {
        return documentDAO.countDocumentsOfAssistant(assistantId.toString()) > 0
    }

    suspend fun hasSearchableDocuments(assistantId: Uuid): Boolean {
        return documentDAO.countSearchableDocumentsOfAssistant(assistantId.toString()) > 0
    }

    suspend fun countQueuedDocuments(): Int {
        return documentDAO.countDocumentsByStatus(KnowledgeBaseDocumentStatus.QUEUED.name)
    }

    suspend fun insertChunkBatch(chunks: List<KnowledgeBaseChunk>) {
        if (chunks.isEmpty()) return
        if (!indexMigrationManager.shouldUseIndexBackend()) {
            legacyChunkDAO.insertAll(
                chunks.map { chunk ->
                    KnowledgeBaseChunkEntity(
                        documentId = chunk.documentId,
                        assistantId = chunk.assistantId.toString(),
                        generation = chunk.generation,
                        chunkOrder = chunk.chunkOrder,
                        content = chunk.content,
                        tokenEstimate = chunk.tokenEstimate,
                        embeddingJson = JsonInstant.encodeToString(chunk.embedding),
                        updatedAt = chunk.updatedAt.toEpochMilli(),
                    )
                }
            )
            return
        }

        indexDatabase.withTransaction {
            val rows = indexChunkDAO.insertAll(
                chunks.map { chunk ->
                    IndexKnowledgeBaseChunkEntity(
                        documentId = chunk.documentId,
                        assistantId = chunk.assistantId.toString(),
                        generation = chunk.generation,
                        chunkOrder = chunk.chunkOrder,
                        content = chunk.content,
                        tokenEstimate = chunk.tokenEstimate,
                        embeddingDimension = chunk.embedding.size,
                        updatedAt = chunk.updatedAt.toEpochMilli(),
                    )
                }
            )
            chunks.zip(rows).groupBy({ it.first.embedding.size }, { pair ->
                VectorInsertRecord(
                    chunkId = pair.second,
                    embeddingJson = JsonInstant.encodeToString(pair.first.embedding),
                )
            }).forEach { (dimension, records) ->
                vectorTableManager.insertKnowledgeBaseVectors(dimension, records)
            }
        }
    }

    suspend fun countChunksOfGeneration(documentId: Long, generation: Int): Int {
        return if (indexMigrationManager.shouldUseIndexBackend()) {
            indexChunkDAO.countChunksOfDocumentGeneration(documentId, generation)
        } else {
            legacyChunkDAO.countChunksOfDocumentGeneration(documentId, generation)
        }
    }

    suspend fun discardGeneration(documentId: Long, generation: Int) {
        if (indexMigrationManager.shouldUseIndexBackend()) {
            indexChunkDAO.deleteChunksOfDocumentGeneration(documentId, generation)
        } else {
            legacyChunkDAO.deleteChunksOfDocumentGeneration(documentId, generation)
        }
    }

    suspend fun publishGeneration(
        documentId: Long,
        generation: Int,
        chunkCount: Int,
        now: Instant,
    ): Pair<Int, KnowledgeBaseDocument?> {
        if (!indexMigrationManager.shouldUseIndexBackend()) {
            return appDatabase.withTransaction {
                val current = documentDAO.getDocument(documentId)?.toModel()
                if (current == null) {
                    legacyChunkDAO.deleteChunksOfDocumentGeneration(documentId, generation)
                    return@withTransaction 0 to null
                }
                val previousGeneration = current.publishedGeneration
                val updated = buildPublishedDocument(current, generation, chunkCount, now)
                documentDAO.update(updated.toEntity())
                if (previousGeneration > 0 && previousGeneration != generation) {
                    legacyChunkDAO.deleteChunksOfDocumentGeneration(documentId, previousGeneration)
                }
                previousGeneration to updated
            }
        }

        val current = documentDAO.getDocument(documentId)?.toModel()
        if (current == null) {
            indexChunkDAO.deleteChunksOfDocumentGeneration(documentId, generation)
            return 0 to null
        }
        val previousGeneration = current.publishedGeneration
        val updated = buildPublishedDocument(current, generation, chunkCount, now)
        documentDAO.update(updated.toEntity())
        if (previousGeneration > 0 && previousGeneration != generation) {
            indexChunkDAO.deleteChunksOfDocumentGeneration(documentId, previousGeneration)
        }
        return previousGeneration to updated
    }

    suspend fun recoverStaleIndexing(heartbeatBefore: Instant): Int {
        val stale = documentDAO.getStaleIndexingDocuments(
            status = KnowledgeBaseDocumentStatus.INDEXING.name,
            heartbeatBefore = heartbeatBefore.toEpochMilli(),
        )
        if (stale.isEmpty()) return 0
        val now = Instant.now()
        stale.forEach { entity ->
            val model = entity.toModel() ?: return@forEach
            if (model.buildingGeneration > 0 && model.buildingGeneration != model.publishedGeneration) {
                if (indexMigrationManager.shouldUseIndexBackend()) {
                    indexChunkDAO.deleteChunksOfDocumentGeneration(model.id, model.buildingGeneration)
                } else {
                    legacyChunkDAO.deleteChunksOfDocumentGeneration(model.id, model.buildingGeneration)
                }
            }
            documentDAO.update(
                model.copy(
                    status = KnowledgeBaseDocumentStatus.QUEUED,
                    buildingGeneration = 0,
                    progressCurrent = 0,
                    progressTotal = 0,
                    progressLabel = "",
                    queuedAt = now,
                    lastHeartbeatAt = null,
                    updatedAt = now,
                ).toEntity()
            )
        }
        return stale.size
    }

    suspend fun getReadyChunksOfAssistant(
        assistantId: Uuid,
        documentIds: List<Long> = emptyList(),
    ): List<KnowledgeBaseChunkWithDocument> {
        if (!indexMigrationManager.shouldUseIndexBackend()) {
            val rows = if (documentIds.isEmpty()) {
                legacyChunkDAO.getReadyChunksOfAssistant(assistantId = assistantId.toString())
            } else {
                legacyChunkDAO.getReadyChunksOfAssistantDocuments(
                    assistantId = assistantId.toString(),
                    documentIds = documentIds,
                )
            }
            return rows.mapNotNull { it.toModel() }
        }

        val documents = getCurrentDocumentMap(assistantId, documentIds)
        if (documents.isEmpty()) return emptyList()
        val rows = if (documentIds.isEmpty()) {
            indexChunkDAO.getChunksOfAssistant(assistantId.toString())
        } else {
            indexChunkDAO.getChunksOfAssistantDocuments(assistantId.toString(), documents.keys.toList())
        }
        return rows.mapNotNull { row ->
            val document = documents[row.documentId] ?: return@mapNotNull null
            if (row.generation != document.publishedGeneration) return@mapNotNull null
            row.toModel(document.displayName, document.mimeType)
        }
    }

    suspend fun getChunksByIds(
        assistantId: Uuid,
        chunkIds: List<Long>,
    ): List<KnowledgeBaseChunkWithDocument> {
        if (chunkIds.isEmpty()) return emptyList()
        if (!indexMigrationManager.shouldUseIndexBackend()) {
            return legacyChunkDAO.getChunksByIds(
                assistantId = assistantId.toString(),
                chunkIds = chunkIds,
            ).mapNotNull { it.toModel() }
        }

        val rows = indexChunkDAO.getChunksByIds(
            assistantId = assistantId.toString(),
            chunkIds = chunkIds,
        )
        if (rows.isEmpty()) return emptyList()
        val documents = getCurrentDocumentMap(
            assistantId = assistantId,
            documentIds = rows.map { it.documentId }.distinct()
        )
        return rows.mapNotNull { row ->
            val document = documents[row.documentId] ?: return@mapNotNull null
            if (row.generation != document.publishedGeneration) return@mapNotNull null
            row.toModel(document.displayName, document.mimeType)
        }
    }

    suspend fun getSearchScopeChunkIds(
        assistantId: Uuid,
        documentIds: List<Long> = emptyList(),
    ): List<Long> {
        if (!indexMigrationManager.shouldUseIndexBackend()) {
            val rows = if (documentIds.isEmpty()) {
                legacyChunkDAO.getReadyChunkScopeOfAssistant(assistantId.toString())
            } else {
                legacyChunkDAO.getReadyChunkScopeOfAssistantDocuments(
                    assistantId = assistantId.toString(),
                    documentIds = documentIds,
                )
            }
            return rows.map { it.chunkId }
        }

        val documents = getCurrentDocumentMap(assistantId, documentIds)
        if (documents.isEmpty()) return emptyList()
        val rows = if (documentIds.isEmpty()) {
            indexChunkDAO.getChunkScopeOfAssistant(assistantId.toString())
        } else {
            indexChunkDAO.getChunkScopeOfAssistantDocuments(
                assistantId = assistantId.toString(),
                documentIds = documents.keys.toList(),
            )
        }
        return rows.mapNotNull { row ->
            val document = documents[row.documentId] ?: return@mapNotNull null
            if (row.generation != document.publishedGeneration) return@mapNotNull null
            row.id
        }
    }

    suspend fun getChunksByDocumentAndOrders(
        assistantId: Uuid,
        documentId: Long,
        chunkOrders: List<Int>,
    ): List<KnowledgeBaseChunkWithDocument> {
        if (chunkOrders.isEmpty()) return emptyList()
        if (!indexMigrationManager.shouldUseIndexBackend()) {
            return legacyChunkDAO.getChunksByDocumentAndOrders(
                assistantId = assistantId.toString(),
                documentId = documentId,
                chunkOrders = chunkOrders,
            ).mapNotNull { it.toModel() }
        }

        val document = documentDAO.getDocument(documentId)?.toModel()
            ?.takeIf { it.assistantId == assistantId && it.isSearchable }
            ?: return emptyList()
        return indexChunkDAO.getChunksByDocumentAndOrders(
            assistantId = assistantId.toString(),
            documentId = documentId,
            chunkOrders = chunkOrders,
        ).mapNotNull { row ->
            if (row.generation != document.publishedGeneration) return@mapNotNull null
            row.toModel(document.displayName, document.mimeType)
        }
    }

    suspend fun getFtsRowsOfDocumentGeneration(
        documentId: Long,
        generation: Int,
    ): List<KnowledgeBaseChunkFtsRow> {
        if (!indexMigrationManager.shouldUseIndexBackend()) {
            return legacyChunkDAO.getFtsRowsOfDocumentGeneration(documentId, generation)
        }
        return indexChunkDAO.getChunksOfDocumentGeneration(documentId, generation).map { row ->
            KnowledgeBaseChunkFtsRow(
                chunkId = row.id,
                documentId = row.documentId,
                assistantId = row.assistantId,
                generation = row.generation,
                content = row.content,
            )
        }
    }

    suspend fun searchVectorDistances(
        candidateChunkIds: List<Long>,
        queryEmbedding: List<Float>,
        limit: Int,
    ): Map<Long, Double> {
        check(indexMigrationManager.shouldUseIndexBackend()) {
            "Knowledge base vector search requires the migrated index backend"
        }
        val dimension = queryEmbedding.size
        return vectorTableManager.searchKnowledgeBaseDistances(
            candidateIds = candidateChunkIds,
            queryEmbeddingJson = JsonInstant.encodeToString(queryEmbedding),
            dimension = dimension,
            limit = limit,
        )
    }

    suspend fun deleteDocument(documentId: Long) {
        if (!indexMigrationManager.shouldUseIndexBackend()) {
            appDatabase.withTransaction {
                legacyChunkDAO.deleteChunksOfDocument(documentId)
                documentDAO.deleteDocument(documentId)
            }
            return
        }
        indexChunkDAO.deleteChunksOfDocument(documentId)
        documentDAO.deleteDocument(documentId)
    }

    suspend fun deleteDocumentsOfAssistant(assistantId: Uuid) {
        if (!indexMigrationManager.shouldUseIndexBackend()) {
            appDatabase.withTransaction {
                legacyChunkDAO.deleteChunksOfAssistant(assistantId.toString())
                documentDAO.deleteDocumentsOfAssistant(assistantId.toString())
            }
            return
        }
        indexChunkDAO.deleteChunksOfAssistant(assistantId.toString())
        documentDAO.deleteDocumentsOfAssistant(assistantId.toString())
    }

    private suspend fun getCurrentDocumentMap(
        assistantId: Uuid,
        documentIds: List<Long>,
    ): Map<Long, KnowledgeBaseDocument> {
        return documentDAO.getDocumentsOfAssistant(assistantId.toString())
            .mapNotNull { it.toModel() }
            .filter { it.isSearchable && (documentIds.isEmpty() || documentIds.contains(it.id)) }
            .associateBy { it.id }
    }

    private fun buildPublishedDocument(
        current: KnowledgeBaseDocument,
        generation: Int,
        chunkCount: Int,
        now: Instant,
    ): KnowledgeBaseDocument {
        return current.copy(
            status = KnowledgeBaseDocumentStatus.READY,
            chunkCount = chunkCount,
            queuedAt = null,
            publishedGeneration = generation,
            buildingGeneration = 0,
            progressCurrent = current.progressTotal.takeIf { it > 0 } ?: current.progressCurrent,
            progressLabel = "",
            lastIndexedAt = now,
            lastHeartbeatAt = now,
            lastError = "",
            updatedAt = now,
        )
    }

    private fun KnowledgeBaseDocument.toEntity(): KnowledgeBaseDocumentEntity {
        return KnowledgeBaseDocumentEntity(
            id = id,
            assistantId = assistantId.toString(),
            relativePath = relativePath,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            status = status.name,
            chunkCount = chunkCount,
            queuedAt = queuedAt?.toEpochMilli(),
            publishedGeneration = publishedGeneration,
            buildingGeneration = buildingGeneration,
            progressCurrent = progressCurrent,
            progressTotal = progressTotal,
            progressLabel = progressLabel,
            lastIndexedAt = lastIndexedAt?.toEpochMilli(),
            lastHeartbeatAt = lastHeartbeatAt?.toEpochMilli(),
            lastError = lastError,
            createdAt = createdAt.toEpochMilli(),
            updatedAt = updatedAt.toEpochMilli(),
        )
    }

    private fun KnowledgeBaseDocumentEntity.toModel(): KnowledgeBaseDocument? {
        val assistantUuid = runCatching { Uuid.parse(assistantId) }.getOrNull() ?: return null
        val statusValue = runCatching { KnowledgeBaseDocumentStatus.valueOf(status) }
            .getOrDefault(KnowledgeBaseDocumentStatus.FAILED)
        return KnowledgeBaseDocument(
            id = id,
            assistantId = assistantUuid,
            relativePath = relativePath,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            status = statusValue,
            chunkCount = chunkCount,
            queuedAt = queuedAt?.let(Instant::ofEpochMilli),
            publishedGeneration = publishedGeneration,
            buildingGeneration = buildingGeneration,
            progressCurrent = progressCurrent,
            progressTotal = progressTotal,
            progressLabel = progressLabel,
            lastIndexedAt = lastIndexedAt?.let(Instant::ofEpochMilli),
            lastHeartbeatAt = lastHeartbeatAt?.let(Instant::ofEpochMilli),
            lastError = lastError,
            createdAt = Instant.ofEpochMilli(createdAt),
            updatedAt = Instant.ofEpochMilli(updatedAt),
        )
    }

    private fun KnowledgeBaseChunkWithDocumentRow.toModel(): KnowledgeBaseChunkWithDocument? {
        val assistantUuid = runCatching { Uuid.parse(assistantId) }.getOrNull() ?: return null
        val embedding = runCatching {
            JsonInstant.decodeFromString<List<Float>>(embeddingJson)
        }.getOrNull() ?: return null
        return KnowledgeBaseChunkWithDocument(
            chunk = KnowledgeBaseChunk(
                id = chunkId,
                documentId = documentId,
                assistantId = assistantUuid,
                generation = generation,
                chunkOrder = chunkOrder,
                content = content,
                tokenEstimate = tokenEstimate,
                embedding = embedding,
                updatedAt = Instant.ofEpochMilli(chunkUpdatedAt),
            ),
            documentName = documentName,
            mimeType = mimeType,
        )
    }

    private fun IndexKnowledgeBaseChunkEntity.toModel(
        documentName: String,
        mimeType: String,
    ): KnowledgeBaseChunkWithDocument? {
        val assistantUuid = runCatching { Uuid.parse(assistantId) }.getOrNull() ?: return null
        return KnowledgeBaseChunkWithDocument(
            chunk = KnowledgeBaseChunk(
                id = id,
                documentId = documentId,
                assistantId = assistantUuid,
                generation = generation,
                chunkOrder = chunkOrder,
                content = content,
                tokenEstimate = tokenEstimate,
                embedding = emptyList(),
                updatedAt = Instant.ofEpochMilli(updatedAt),
            ),
            documentName = documentName,
            mimeType = mimeType,
        )
    }
}
