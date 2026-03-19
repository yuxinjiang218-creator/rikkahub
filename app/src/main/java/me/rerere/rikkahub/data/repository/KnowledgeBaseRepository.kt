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
import me.rerere.rikkahub.data.model.KnowledgeBaseChunk
import me.rerere.rikkahub.data.model.KnowledgeBaseDocumentSummary
import me.rerere.rikkahub.data.model.KnowledgeBaseDocument
import me.rerere.rikkahub.data.model.KnowledgeBaseDocumentStatus
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
    private val chunkDAO: KnowledgeBaseChunkDAO,
    private val database: AppDatabase,
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
        chunkDAO.insertAll(
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
    }

    suspend fun countChunksOfGeneration(documentId: Long, generation: Int): Int {
        return chunkDAO.countChunksOfDocumentGeneration(documentId, generation)
    }

    suspend fun discardGeneration(documentId: Long, generation: Int) {
        chunkDAO.deleteChunksOfDocumentGeneration(documentId, generation)
    }

    suspend fun publishGeneration(
        documentId: Long,
        generation: Int,
        chunkCount: Int,
        now: Instant,
    ): Pair<Int, KnowledgeBaseDocument?> {
        return database.withTransaction {
            val current = documentDAO.getDocument(documentId)?.toModel()
            if (current == null) {
                chunkDAO.deleteChunksOfDocumentGeneration(documentId, generation)
                return@withTransaction 0 to null
            }
            val previousGeneration = current.publishedGeneration
            val updated = current.copy(
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
            documentDAO.update(updated.toEntity())
            if (previousGeneration > 0 && previousGeneration != generation) {
                chunkDAO.deleteChunksOfDocumentGeneration(documentId, previousGeneration)
            }
            previousGeneration to updated
        }
    }

    suspend fun recoverStaleIndexing(heartbeatBefore: Instant): Int {
        val stale = documentDAO.getStaleIndexingDocuments(
            status = KnowledgeBaseDocumentStatus.INDEXING.name,
            heartbeatBefore = heartbeatBefore.toEpochMilli(),
        )
        if (stale.isEmpty()) return 0
        val now = Instant.now()
        database.withTransaction {
            stale.forEach { entity ->
                val model = entity.toModel() ?: return@forEach
                if (model.buildingGeneration > 0 && model.buildingGeneration != model.publishedGeneration) {
                    chunkDAO.deleteChunksOfDocumentGeneration(model.id, model.buildingGeneration)
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
        }
        return stale.size
    }

    suspend fun getReadyChunksOfAssistant(
        assistantId: Uuid,
        documentIds: List<Long> = emptyList(),
    ): List<KnowledgeBaseChunkWithDocument> {
        val rows = if (documentIds.isEmpty()) {
            chunkDAO.getReadyChunksOfAssistant(
                assistantId = assistantId.toString(),
            )
        } else {
            chunkDAO.getReadyChunksOfAssistantDocuments(
                assistantId = assistantId.toString(),
                documentIds = documentIds,
            )
        }
        return rows.mapNotNull { it.toModel() }
    }

    suspend fun getChunksByIds(
        assistantId: Uuid,
        chunkIds: List<Long>,
    ): List<KnowledgeBaseChunkWithDocument> {
        if (chunkIds.isEmpty()) return emptyList()
        return chunkDAO.getChunksByIds(
            assistantId = assistantId.toString(),
            chunkIds = chunkIds,
        ).mapNotNull { it.toModel() }
    }

    suspend fun getChunksByDocumentAndOrders(
        assistantId: Uuid,
        documentId: Long,
        chunkOrders: List<Int>,
    ): List<KnowledgeBaseChunkWithDocument> {
        if (chunkOrders.isEmpty()) return emptyList()
        return chunkDAO.getChunksByDocumentAndOrders(
            assistantId = assistantId.toString(),
            documentId = documentId,
            chunkOrders = chunkOrders,
        ).mapNotNull { it.toModel() }
    }

    suspend fun getFtsRowsOfDocumentGeneration(
        documentId: Long,
        generation: Int,
    ): List<KnowledgeBaseChunkFtsRow> {
        return chunkDAO.getFtsRowsOfDocumentGeneration(documentId, generation)
    }

    suspend fun deleteDocument(documentId: Long) {
        database.withTransaction {
            chunkDAO.deleteChunksOfDocument(documentId)
            documentDAO.deleteDocument(documentId)
        }
    }

    suspend fun deleteDocumentsOfAssistant(assistantId: Uuid) {
        database.withTransaction {
            chunkDAO.deleteChunksOfAssistant(assistantId.toString())
            documentDAO.deleteDocumentsOfAssistant(assistantId.toString())
        }
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
}
