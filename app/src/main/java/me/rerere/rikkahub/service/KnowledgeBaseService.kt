package me.rerere.rikkahub.service

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getEmbeddingModel
import me.rerere.rikkahub.data.db.fts.KnowledgeBaseFtsManager
import me.rerere.rikkahub.data.document.DocumentTextExtractor
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.knowledgebase.filterRankedKnowledgeBaseChunks
import me.rerere.rikkahub.data.knowledgebase.IncrementalKnowledgeBaseChunker
import me.rerere.rikkahub.data.knowledgebase.KnowledgeBaseCandidateChunk
import me.rerere.rikkahub.data.knowledgebase.KnowledgeBaseChunkDraft
import me.rerere.rikkahub.data.knowledgebase.rankKnowledgeBaseChunks
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.KnowledgeBaseChunk
import me.rerere.rikkahub.data.model.KnowledgeBaseChunkReadResult
import me.rerere.rikkahub.data.model.KnowledgeBaseDocument
import me.rerere.rikkahub.data.model.KnowledgeBaseDocumentStatus
import me.rerere.rikkahub.data.model.KnowledgeBaseDocumentSummary
import me.rerere.rikkahub.data.model.KnowledgeBaseIndexState
import me.rerere.rikkahub.data.model.KnowledgeBaseReadChunk
import me.rerere.rikkahub.data.model.KnowledgeBaseResultQuality
import me.rerere.rikkahub.data.model.KnowledgeBaseSearchChunk
import me.rerere.rikkahub.data.model.KnowledgeBaseSearchResult
import me.rerere.rikkahub.data.repository.KnowledgeBaseRepository
import java.io.File
import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

private const val KB_TOP_K = 6
private const val KB_MAX_RETURN_TOKENS = 2_400
private const val KB_FTS_CANDIDATE_LIMIT = 200
private const val KB_MIN_CANDIDATE_COUNT = 12
private const val KB_EMBEDDING_BATCH_SIZE = 8
private const val KB_EMBEDDING_RETRY_COUNT = 3
private const val KB_STALE_INDEX_MINUTES = 10L

class KnowledgeBaseService(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val filesManager: FilesManager,
    private val knowledgeBaseRepository: KnowledgeBaseRepository,
    private val knowledgeBaseFtsManager: KnowledgeBaseFtsManager,
) {
    private val processingMutex = Mutex()
    private val _indexState = MutableStateFlow(KnowledgeBaseIndexState())
    val indexState: StateFlow<KnowledgeBaseIndexState> = _indexState.asStateFlow()

    fun observeDocuments(assistantId: Uuid): Flow<List<KnowledgeBaseDocument>> {
        return knowledgeBaseRepository.observeDocumentsOfAssistant(assistantId)
    }

    suspend fun hasDocuments(assistantId: Uuid): Boolean {
        return knowledgeBaseRepository.hasDocuments(assistantId)
    }

    suspend fun hasSearchableDocuments(assistantId: Uuid): Boolean {
        return knowledgeBaseRepository.hasSearchableDocuments(assistantId)
    }

    suspend fun listKnowledgeBaseDocuments(assistantId: Uuid): List<KnowledgeBaseDocumentSummary> {
        return knowledgeBaseRepository.getSearchableDocumentsOfAssistant(assistantId)
    }

    suspend fun readKnowledgeBaseChunks(
        assistantId: Uuid,
        documentId: Long,
        chunkOrders: List<Int>,
    ): KnowledgeBaseChunkReadResult {
        val normalizedOrders = chunkOrders
            .map { it.coerceAtLeast(0) }
            .distinct()
            .sorted()
        if (normalizedOrders.isEmpty()) {
            return KnowledgeBaseChunkReadResult(
                documentId = documentId,
                documentName = "",
                mimeType = "",
                returnedCount = 0,
                missingChunkOrders = emptyList(),
                chunks = emptyList(),
            )
        }

        val chunks = knowledgeBaseRepository.getChunksByDocumentAndOrders(
            assistantId = assistantId,
            documentId = documentId,
            chunkOrders = normalizedOrders,
        )
        val documentName = chunks.firstOrNull()?.documentName.orEmpty()
        val mimeType = chunks.firstOrNull()?.mimeType.orEmpty()
        val foundOrders = chunks.map { it.chunk.chunkOrder }.toSet()
        return KnowledgeBaseChunkReadResult(
            documentId = documentId,
            documentName = documentName,
            mimeType = mimeType,
            returnedCount = chunks.size,
            missingChunkOrders = normalizedOrders.filterNot(foundOrders::contains),
            chunks = chunks.map { item ->
                KnowledgeBaseReadChunk(
                    chunkId = item.chunk.id,
                    documentId = item.chunk.documentId,
                    assistantId = item.chunk.assistantId,
                    documentName = item.documentName,
                    mimeType = item.mimeType,
                    chunkOrder = item.chunk.chunkOrder,
                    content = item.chunk.content,
                    tokenEstimate = item.chunk.tokenEstimate,
                    updatedAt = item.chunk.updatedAt,
                )
            }
        )
    }

    suspend fun isSearchToolAvailable(
        assistant: Assistant,
        model: Model,
        settings: Settings,
    ): Boolean {
        if (!assistant.enableKnowledgeBaseTool) return false
        if (!model.abilities.contains(ModelAbility.TOOL)) return false
        if (settings.getEmbeddingModel() == null) return false
        return hasSearchableDocuments(assistant.id)
    }

    suspend fun importDocuments(assistantId: Uuid, uris: List<Uri>): Int {
        val settings = settingsStore.settingsFlowRaw.first()
        check(settings.getEmbeddingModel() != null) {
            context.getString(R.string.knowledge_base_embedding_required)
        }

        var imported = 0
        uris.forEach { uri ->
            val displayName = filesManager.getFileNameFromUri(uri) ?: "file"
            val mimeType = filesManager.resolveMimeType(uri, displayName)
            if (!DocumentTextExtractor.isKnowledgeBaseSupported(displayName, mimeType)) return@forEach

            val entity = filesManager.saveUploadFromUri(
                uri = uri,
                displayName = displayName,
                mimeType = mimeType,
                folder = FileFolders.KNOWLEDGE_BASE
            )
            val now = Instant.now()
            knowledgeBaseRepository.createDocument(
                KnowledgeBaseDocument(
                    assistantId = assistantId,
                    relativePath = entity.relativePath,
                    displayName = entity.displayName,
                    mimeType = entity.mimeType,
                    sizeBytes = entity.sizeBytes,
                    status = KnowledgeBaseDocumentStatus.QUEUED,
                    queuedAt = now,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            imported += 1
        }

        refreshIndexState()
        if (imported > 0) {
            startForegroundIndexing()
        }
        return imported
    }

    suspend fun reindexDocument(documentId: Long) {
        val document = knowledgeBaseRepository.getDocument(documentId) ?: return
        if (document.status == KnowledgeBaseDocumentStatus.QUEUED ||
            document.status == KnowledgeBaseDocumentStatus.INDEXING
        ) {
            startForegroundIndexing()
            return
        }
        queueDocument(document)
        refreshIndexState()
        startForegroundIndexing()
    }

    suspend fun reindexAllDocuments(assistantId: Uuid): Int {
        var queuedCount = 0
        knowledgeBaseRepository.getDocumentsOfAssistant(assistantId).forEach { document ->
            if (document.status == KnowledgeBaseDocumentStatus.QUEUED ||
                document.status == KnowledgeBaseDocumentStatus.INDEXING
            ) {
                return@forEach
            }
            queueDocument(document)
            queuedCount += 1
        }
        refreshIndexState()
        if (queuedCount > 0) {
            startForegroundIndexing()
        }
        return queuedCount
    }

    suspend fun deleteDocument(documentId: Long): Boolean {
        val document = knowledgeBaseRepository.getDocument(documentId) ?: return false
        KnowledgeBaseIndexForegroundService.cancelDocument(context, documentId)
        knowledgeBaseFtsManager.deleteDocument(documentId)
        filesManager.deleteByRelativePath(document.relativePath, deleteFromDisk = true)
        knowledgeBaseRepository.deleteDocument(documentId)
        refreshIndexState()
        return true
    }

    suspend fun deleteDocumentsOfAssistant(assistantId: Uuid) {
        knowledgeBaseRepository.getDocumentsOfAssistant(assistantId).forEach { document ->
            KnowledgeBaseIndexForegroundService.cancelDocument(context, document.id)
            knowledgeBaseFtsManager.deleteDocument(document.id)
            filesManager.deleteByRelativePath(document.relativePath, deleteFromDisk = true)
        }
        knowledgeBaseRepository.deleteDocumentsOfAssistant(assistantId)
        refreshIndexState()
    }

    suspend fun recoverInterruptedIndexing() {
        knowledgeBaseRepository.recoverStaleIndexing(
            heartbeatBefore = Instant.now().minusSeconds(KB_STALE_INDEX_MINUTES * 60)
        )
        refreshIndexState()
    }

    suspend fun ensureFtsReady() {
        knowledgeBaseFtsManager.ensureReady()
    }

    suspend fun resumePendingWorkIfNeeded() {
        recoverInterruptedIndexing()
        ensureFtsReady()
        if (knowledgeBaseRepository.countQueuedDocuments() > 0) {
            startForegroundIndexing()
        }
    }

    suspend fun runIndexQueueLoop() {
        if (!processingMutex.tryLock()) {
            refreshIndexState()
            return
        }
        try {
            recoverInterruptedIndexing()
            while (true) {
                coroutineContext.ensureActive()
                val next = knowledgeBaseRepository.getNextQueuedDocument() ?: break
                indexDocument(next.id)
            }
        } finally {
            refreshIndexState()
            processingMutex.unlock()
        }
    }

    suspend fun searchKnowledgeBase(
        assistantId: Uuid,
        query: String,
        documentIds: List<Long> = emptyList(),
    ): KnowledgeBaseSearchResult {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return KnowledgeBaseSearchResult(
                query = query,
                quality = KnowledgeBaseResultQuality.EMPTY,
                returnedCount = 0,
                chunks = emptyList()
            )
        }

        ensureFtsReady()
        val candidateIds = knowledgeBaseFtsManager.searchChunkIds(
            assistantId = assistantId.toString(),
            query = normalizedQuery,
            limit = KB_FTS_CANDIDATE_LIMIT,
            documentIds = documentIds.distinct(),
        )
        val candidateChunks = buildCandidateChunkPool(
            assistantId = assistantId,
            candidateIds = candidateIds,
            documentIds = documentIds,
        )
        if (candidateChunks.isEmpty()) {
            return KnowledgeBaseSearchResult(
                query = query,
                quality = KnowledgeBaseResultQuality.EMPTY,
                returnedCount = 0,
                chunks = emptyList()
            )
        }

        val settings = settingsStore.settingsFlowRaw.first()
        val embeddingModel = settings.getEmbeddingModel()
            ?: return KnowledgeBaseSearchResult(
                query = query,
                quality = KnowledgeBaseResultQuality.EMPTY,
                returnedCount = 0,
                chunks = emptyList()
            )
        val provider = embeddingModel.findProvider(settings.providers)
            ?: return KnowledgeBaseSearchResult(
                query = query,
                quality = KnowledgeBaseResultQuality.EMPTY,
                returnedCount = 0,
                chunks = emptyList()
            )
        val providerHandler = providerManager.getProviderByType(provider)
        val queryEmbedding = providerHandler.generateEmbedding(
            providerSetting = provider,
            params = EmbeddingGenerationParams(
                model = embeddingModel,
                input = listOf(normalizedQuery)
            )
        ).embeddings.firstOrNull()
            ?: return KnowledgeBaseSearchResult(
                query = query,
                quality = KnowledgeBaseResultQuality.EMPTY,
                returnedCount = 0,
                chunks = emptyList()
            )

        val ranked = rankKnowledgeBaseChunks(
            query = normalizedQuery,
            chunks = candidateChunks.map { candidate ->
                KnowledgeBaseCandidateChunk(
                    content = candidate.chunk.content,
                    embedding = candidate.chunk.embedding
                )
            },
            queryEmbedding = queryEmbedding,
        ).map { score -> score to candidateChunks[score.index] }
        val filtered = filterRankedKnowledgeBaseChunks(ranked.map { it.first })
        if (filtered.quality == KnowledgeBaseResultQuality.EMPTY || filtered.chunks.isEmpty()) {
            return KnowledgeBaseSearchResult(
                query = query,
                quality = KnowledgeBaseResultQuality.EMPTY,
                returnedCount = 0,
                chunks = emptyList()
            )
        }

        val filteredIndexes = filtered.chunks.mapTo(linkedSetOf()) { it.index }

        val selected = buildList {
            var usedTokens = 0
            ranked.forEach { (score, chunkWithDocument) ->
                if (score.index !in filteredIndexes) return@forEach
                if (size >= KB_TOP_K) return@forEach
                val nextTokens = chunkWithDocument.chunk.tokenEstimate.coerceAtLeast(1)
                if (usedTokens + nextTokens > KB_MAX_RETURN_TOKENS) return@forEach
                add(
                    KnowledgeBaseSearchChunk(
                        chunkId = chunkWithDocument.chunk.id,
                        documentId = chunkWithDocument.chunk.documentId,
                        assistantId = chunkWithDocument.chunk.assistantId,
                        documentName = chunkWithDocument.documentName,
                        mimeType = chunkWithDocument.mimeType,
                        chunkOrder = chunkWithDocument.chunk.chunkOrder,
                        content = chunkWithDocument.chunk.content,
                        score = score.finalScore,
                        tokenEstimate = chunkWithDocument.chunk.tokenEstimate,
                        updatedAt = chunkWithDocument.chunk.updatedAt,
                    )
                )
                usedTokens += nextTokens
            }
        }

        return KnowledgeBaseSearchResult(
            query = query,
            quality = if (selected.isEmpty()) KnowledgeBaseResultQuality.EMPTY else filtered.quality,
            returnedCount = selected.size,
            chunks = selected,
        )
    }

    private suspend fun buildCandidateChunkPool(
        assistantId: Uuid,
        candidateIds: List<Long>,
        documentIds: List<Long>,
    ): List<me.rerere.rikkahub.data.repository.KnowledgeBaseChunkWithDocument> {
        val baseCandidates = knowledgeBaseRepository.getChunksByIds(assistantId, candidateIds)
        if (documentIds.isEmpty()) {
            return baseCandidates
        }
        if (baseCandidates.size >= KB_MIN_CANDIDATE_COUNT) {
            return baseCandidates
        }
        val fallbackCandidates = knowledgeBaseRepository.getReadyChunksOfAssistant(
            assistantId = assistantId,
            documentIds = documentIds.distinct(),
        )
        if (fallbackCandidates.isEmpty()) {
            return baseCandidates
        }
        return (baseCandidates + fallbackCandidates)
            .associateBy { it.chunk.id }
            .values
            .toList()
    }

    suspend fun refreshIndexState() {
        val indexingDocument = knowledgeBaseRepository.getIndexingDocument()
        val queuedCount = knowledgeBaseRepository.countQueuedDocuments()
        _indexState.value = if (indexingDocument != null) {
            KnowledgeBaseIndexState(
                isRunning = true,
                currentDocumentId = indexingDocument.id,
                currentDocumentName = indexingDocument.displayName,
                queuedCount = queuedCount,
                progressCurrent = indexingDocument.progressCurrent,
                progressTotal = indexingDocument.progressTotal,
                progressLabel = indexingDocument.progressLabel,
            )
        } else {
            KnowledgeBaseIndexState(
                queuedCount = queuedCount,
            )
        }
    }

    private suspend fun queueDocument(document: KnowledgeBaseDocument) {
        val now = Instant.now()
        knowledgeBaseRepository.updateDocument(
            document.copy(
                status = KnowledgeBaseDocumentStatus.QUEUED,
                queuedAt = now,
                buildingGeneration = 0,
                progressCurrent = 0,
                progressTotal = 0,
                progressLabel = "",
                lastHeartbeatAt = null,
                lastError = "",
                updatedAt = now,
            )
        )
    }

    private suspend fun indexDocument(documentId: Long) = withContext(Dispatchers.IO) {
        val existing = knowledgeBaseRepository.getDocument(documentId) ?: return@withContext
        val generation = (existing.publishedGeneration + 1).coerceAtLeast(1)
        var workingDocument = existing.copy(
            status = KnowledgeBaseDocumentStatus.INDEXING,
            buildingGeneration = generation,
            progressCurrent = 0,
            progressTotal = 0,
            progressLabel = "准备中",
            lastHeartbeatAt = Instant.now(),
            lastError = "",
            updatedAt = Instant.now(),
        )
        knowledgeBaseRepository.updateDocument(workingDocument)
        refreshIndexState()

        try {
            val file = File(context.filesDir, existing.relativePath)
            val settings = settingsStore.settingsFlowRaw.first()
            val embeddingModel = settings.getEmbeddingModel()
                ?: error(context.getString(R.string.knowledge_base_embedding_required))
            val provider = embeddingModel.findProvider(settings.providers)
                ?: error("Embedding provider not found")
            val providerHandler = providerManager.getProviderByType(provider)
            val chunker = IncrementalKnowledgeBaseChunker(
                charsPerToken = settings.tokenEstimatorCharsPerToken
            )
            val pendingDrafts = mutableListOf<KnowledgeBaseChunkDraft>()

            suspend fun flushBatch(batchSize: Int) {
                if (batchSize <= 0 || pendingDrafts.isEmpty()) return
                coroutineContext.ensureActive()
                val batch = pendingDrafts.take(batchSize).toList()
                val embeddings = generateEmbeddingsWithRetry(
                    providerHandler = providerHandler,
                    provider = provider,
                    embeddingModel = embeddingModel,
                    inputs = batch.map { it.content }
                )
                val now = Instant.now()
                val chunks = batch.mapIndexed { index, draft ->
                    KnowledgeBaseChunk(
                        documentId = existing.id,
                        assistantId = existing.assistantId,
                        generation = generation,
                        chunkOrder = draft.chunkOrder,
                        content = draft.content,
                        tokenEstimate = draft.tokenEstimate,
                        embedding = embeddings[index],
                        updatedAt = now,
                    )
                }
                knowledgeBaseRepository.insertChunkBatch(chunks)
                pendingDrafts.subList(0, batch.size).clear()
                workingDocument = workingDocument.copy(
                    lastHeartbeatAt = now,
                    updatedAt = now,
                )
                knowledgeBaseRepository.updateDocument(workingDocument)
                refreshIndexState()
            }

            DocumentTextExtractor.streamTextSuspend(
                file = file,
                fileName = existing.displayName,
                mimeType = existing.mimeType,
            ) { block ->
                val now = Instant.now()
                workingDocument = workingDocument.copy(
                    progressCurrent = block.progressCurrent,
                    progressTotal = block.progressTotal,
                    progressLabel = block.progressLabel,
                    lastHeartbeatAt = now,
                    updatedAt = now,
                )
                knowledgeBaseRepository.updateDocument(workingDocument)
                refreshIndexState()

                val emitted = chunker.appendText(block.text)
                if (emitted.isNotEmpty()) {
                    pendingDrafts += emitted
                    while (pendingDrafts.size >= KB_EMBEDDING_BATCH_SIZE) {
                        flushBatch(KB_EMBEDDING_BATCH_SIZE)
                    }
                }
                true
            }

            pendingDrafts += chunker.finish()
            flushBatch(pendingDrafts.size)

            val chunkCount = knowledgeBaseRepository.countChunksOfGeneration(existing.id, generation)
            require(chunkCount > 0) { "Knowledge base document is empty after chunking" }

            val publishNow = Instant.now()
            val (_, publishedDocument) = knowledgeBaseRepository.publishGeneration(
                documentId = existing.id,
                generation = generation,
                chunkCount = chunkCount,
                now = publishNow,
            )
            if (publishedDocument != null) {
                knowledgeBaseFtsManager.rebuildDocument(existing.id, generation)
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) {
                throw error
            }
            knowledgeBaseRepository.discardGeneration(existing.id, generation)
            knowledgeBaseRepository.getDocument(existing.id)?.let { current ->
                knowledgeBaseRepository.updateDocument(
                    current.copy(
                        status = KnowledgeBaseDocumentStatus.FAILED,
                        buildingGeneration = 0,
                        progressCurrent = 0,
                        progressTotal = 0,
                        progressLabel = "",
                        lastHeartbeatAt = Instant.now(),
                        lastError = error.message.orEmpty().take(500),
                        updatedAt = Instant.now(),
                    )
                )
            }
        } finally {
            refreshIndexState()
        }
    }

    private suspend fun <T : ProviderSetting> generateEmbeddingsWithRetry(
        providerHandler: Provider<T>,
        provider: T,
        embeddingModel: Model,
        inputs: List<String>,
    ): List<List<Float>> {
        var lastError: Throwable? = null
        repeat(KB_EMBEDDING_RETRY_COUNT) { attempt ->
            try {
                val embeddings = providerHandler.generateEmbedding(
                    providerSetting = provider,
                    params = EmbeddingGenerationParams(
                        model = embeddingModel,
                        input = inputs
                    )
                ).embeddings
                require(embeddings.size == inputs.size) { "Embedding result size mismatch" }
                return embeddings
            } catch (error: Throwable) {
                lastError = error
                if (attempt < KB_EMBEDDING_RETRY_COUNT - 1) {
                    delay((attempt + 1) * 750L)
                }
            }
        }
        throw lastError ?: IllegalStateException("Embedding generation failed")
    }

    private fun startForegroundIndexing() {
        KnowledgeBaseIndexForegroundService.start(context)
    }
}
