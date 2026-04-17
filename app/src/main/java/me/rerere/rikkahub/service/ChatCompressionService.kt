package me.rerere.rikkahub.service

import android.app.Application
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.findKeepStartIndexForVisibleMessages
import me.rerere.common.android.Logging
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_LEDGER_PATCH_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_LEDGER_PROMPT
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getEmbeddingModel
import me.rerere.rikkahub.data.memory.IndexedSourceMessage
import me.rerere.rikkahub.data.memory.buildMemoryIndexChunks
import me.rerere.rikkahub.data.memory.buildSourcePreviewChunks
import me.rerere.rikkahub.data.model.CompressionEvent
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.LedgerPatchDocument
import me.rerere.rikkahub.data.model.MemoryIndexChunk
import me.rerere.rikkahub.data.model.PendingLedgerBatch
import me.rerere.rikkahub.data.model.SourceDigestMessage
import me.rerere.rikkahub.data.model.applyLedgerPatchDocument
import me.rerere.rikkahub.data.model.buildLiveTailDigestJson
import me.rerere.rikkahub.data.model.compressionEventOrder
import me.rerere.rikkahub.data.model.latestCompressionEvent
import me.rerere.rikkahub.data.model.normalizeRollingSummaryJson
import me.rerere.rikkahub.data.model.parseLedgerPatchDocument
import me.rerere.rikkahub.data.model.parseRollingSummaryDocument
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryIndexRepository
import me.rerere.rikkahub.data.repository.PendingLedgerBatchRepository
import me.rerere.rikkahub.data.repository.SourcePreviewRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.uuid.Uuid

private const val TAG = "ChatCompressionService"
private const val CHAT_COMPRESSION_DIALOGUE_SUMMARY_MAX_OUTPUT_TOKENS = 65_536
private const val ROLLING_SUMMARY_MIN_OUTPUT_TOKENS = 1_200
private const val ROLLING_SUMMARY_TARGET_OUTPUT_TOKENS = 2_500
private const val ROLLING_SUMMARY_HARD_CAP_TOKENS = 30_000
private const val ROLLING_SUMMARY_MAX_CHRONOLOGY_ITEMS = 18
private const val ROLLING_SUMMARY_MAX_DETAIL_CAPSULES = 14

private data class ChatCompressionBudget(
    val incrementalInputTokens: Int,
    val minOutputTokens: Int,
    val targetOutputTokens: Int,
    val hardCapTokens: Int,
    val minChronologyItems: Int,
    val minDetailCapsules: Int,
)

class ChatCompressionService(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryIndexRepository: MemoryIndexRepository,
    private val pendingLedgerBatchRepository: PendingLedgerBatchRepository,
    private val sourcePreviewRepository: SourcePreviewRepository,
    private val providerManager: ProviderManager,
    private val runtimeService: ConversationRuntimeService,
    private val persistenceService: ConversationPersistenceService,
    private val artifactService: ConversationArtifactService,
    private val noticeService: ChatNoticeService,
) {
    private val _compressionUiStates = MutableStateFlow<Map<Uuid, CompressionUiState>>(emptyMap())
    private val _ledgerGenerationUiStates = MutableStateFlow<Map<Uuid, LedgerGenerationUiState>>(emptyMap())
    private val _compressionWorkerJobs = MutableStateFlow<Map<Uuid, Job?>>(emptyMap())
    private val _compressionScrollEvents = MutableSharedFlow<Pair<Uuid, Long>>(extraBufferCapacity = 8)

    val compressionScrollEvents: SharedFlow<Pair<Uuid, Long>> = _compressionScrollEvents

    fun getCompressionUiStateFlow(conversationId: Uuid): Flow<CompressionUiState?> {
        return _compressionUiStates.map { it[conversationId] }
    }

    fun getLedgerGenerationUiStateFlow(conversationId: Uuid): Flow<LedgerGenerationUiState?> {
        return _ledgerGenerationUiStates.map { it[conversationId] }
    }

    fun getCompressionUiStatesSnapshot(): Map<Uuid, CompressionUiState> = _compressionUiStates.value

    fun getLedgerGenerationUiStatesSnapshot(): Map<Uuid, LedgerGenerationUiState> = _ledgerGenerationUiStates.value

    fun getCompressionWorkerJobsSnapshot(): Map<Uuid, Job?> = _compressionWorkerJobs.value

    fun cancelCompressionWork(conversationId: Uuid) {
        _compressionWorkerJobs.value[conversationId]?.cancel()
    }

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        keepRecentMessages: Int = 6,
        generateMemoryLedger: Boolean = true,
    ): Result<Unit> {
        noticeService.recordUiDiagnostic(
            category = "compression-start",
            detail = "keepRecent=$keepRecentMessages ledger=$generateMemoryLedger",
            conversationId = conversationId,
        )
        updateCompressionWorkerJob(conversationId, currentCoroutineContext()[Job])
        return runCatching<Unit> {
            val hydratedConversation = artifactService.hydrateCompressionPayload(conversationId, conversation)
            compressConversationInternal(
                conversationId = conversationId,
                conversation = hydratedConversation,
                additionalPrompt = additionalPrompt,
                keepRecentMessages = keepRecentMessages,
                trigger = "manual",
                generateMemoryLedger = generateMemoryLedger,
            )
            Unit
        }.also {
            updateCompressionWorkerJob(conversationId, null)
            noticeService.recordUiDiagnostic(
                category = "compression-finish",
                detail = "success=${it.isSuccess}",
                conversationId = conversationId,
            )
        }
    }

    suspend fun generateMemoryIndex(conversationId: Uuid): Result<Int> = runCatching {
        noticeService.recordUiDiagnostic(
            category = "memory-index-start",
            detail = "manual=true",
            conversationId = conversationId,
        )
        val settings = settingsStore.settingsFlow.first()
        val conversation = artifactService.getConversationWithCompressionArtifacts(conversationId)
            ?: throw IllegalStateException("Conversation not found")
        try {
            val indexedCount = rebuildConversationIndexes(
                conversationId = conversationId,
                conversation = conversation,
                settings = settings,
            )
            noticeService.addSuccessNotice(
                message = context.getString(R.string.memory_index_updated),
                conversationId = conversationId,
                title = context.getString(R.string.memory_index_updated_title)
            )
            indexedCount
        } catch (error: Throwable) {
            saveConversationMetadata(
                conversationId,
                conversation.copy(
                    memoryIndexState = conversation.memoryIndexState.copy(
                        lastIndexStatus = "failed",
                        lastIndexError = error.message.orEmpty()
                    )
                )
            )
            throw error
        }
    }.also {
        noticeService.recordUiDiagnostic(
            category = "memory-index-finish",
            detail = "success=${it.isSuccess}",
            conversationId = conversationId,
        )
    }

    suspend fun regenerateLatestCompression(
        conversationId: Uuid,
        target: CompressionRegenerationTarget = CompressionRegenerationTarget.DialogueSummary,
    ): Result<Unit> {
        updateCompressionWorkerJob(conversationId, currentCoroutineContext()[Job])
        return runCatching<Unit> {
            val conversation = artifactService.getConversationWithCompressionArtifacts(conversationId)
                ?: throw IllegalStateException("Conversation not found")
            val latestEvent = conversation.compressionEvents.latestCompressionEvent()
                ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_no_latest_summary))
            when (target) {
                CompressionRegenerationTarget.DialogueSummary -> {
                    val rebuiltConversation = conversation.copy(
                        compressionState = conversation.compressionState.copy(
                            dialogueSummaryText = latestEvent.baseDialogueSummaryText,
                            dialogueSummaryTokenEstimate = estimateTokenCount(
                                latestEvent.baseDialogueSummaryText,
                                settingsStore.settingsFlow.first().tokenEstimatorCharsPerToken
                            ),
                            dialogueSummaryUpdatedAt = Instant.now(),
                            lastCompressedMessageIndex = (latestEvent.compressStartIndex - 1).coerceAtLeast(-1),
                            memoryLedgerStatus = "stale",
                            memoryLedgerError = "",
                            updatedAt = Instant.now()
                        )
                    )
                    val regeneratedConversation = compressConversationInternal(
                        conversationId = conversationId,
                        conversation = rebuiltConversation,
                        additionalPrompt = latestEvent.additionalPrompt,
                        keepRecentMessages = latestEvent.keepRecentMessages,
                        trigger = "regenerate-dialogue-summary",
                        generateMemoryLedger = false,
                        baseDialogueSummaryTextOverride = latestEvent.baseDialogueSummaryText,
                        baseRollingSummaryJsonOverride = conversation.compressionState.rollingSummaryJson.ifBlank {
                            latestEvent.baseLedgerJson.ifBlank { latestEvent.baseSummaryJson.ifBlank { "{}" } }
                        },
                        compressStartIndexOverride = latestEvent.compressStartIndex,
                        compressEndIndexOverride = latestEvent.compressEndIndex
                    )
                    removeSupersededCompressionArtifacts(
                        conversationId = conversationId,
                        conversation = regeneratedConversation,
                        supersededEventId = latestEvent.id,
                    )
                }

                CompressionRegenerationTarget.MemoryLedger -> {
                    val baseLedgerJson = latestEvent.baseLedgerJson.ifBlank { latestEvent.baseSummaryJson.ifBlank { "{}" } }
                    val rebuiltConversation = conversation.copy(
                        compressionState = conversation.compressionState.copy(
                            rollingSummaryJson = baseLedgerJson,
                            rollingSummaryTokenEstimate = estimateTokenCount(
                                baseLedgerJson,
                                settingsStore.settingsFlow.first().tokenEstimatorCharsPerToken
                            ),
                            memoryLedgerStatus = "pending",
                            memoryLedgerError = "",
                            updatedAt = Instant.now()
                        )
                    )
                    saveConversationMetadata(conversationId, rebuiltConversation)
                    val incrementalMessages = rebuiltConversation.currentMessages
                        .subList(latestEvent.compressStartIndex, latestEvent.compressEndIndex + 1)
                        .joinToString("\n\n") { message -> message.toCompressionText() }
                    pendingLedgerBatchRepository.upsertPendingBatch(
                        conversationId = conversationId,
                        eventId = latestEvent.id,
                        startIndex = latestEvent.compressStartIndex,
                        endIndex = latestEvent.compressEndIndex,
                        incrementalMessages = incrementalMessages,
                    )
                    val settings = settingsStore.settingsFlow.first()
                    val model = settings.findModelById(settings.compressModelId)
                        ?: settings.getCurrentChatModel()
                        ?: throw IllegalStateException("No model available for compression")
                    val provider = model.findProvider(settings.providers)
                        ?: throw IllegalStateException("Compression provider not found")
                    val providerHandler = providerManager.getProviderByType(provider)

                    updateLedgerGenerationUiState(
                        conversationId,
                        LedgerGenerationUiState(conversationId = conversationId, trigger = "regenerate-memory-ledger")
                    )
                    try {
                        var updatedConversation = processPendingLedgerBatches(
                            conversationId = conversationId,
                            conversation = rebuiltConversation,
                            trigger = "regenerate-memory-ledger",
                            settings = settings,
                            provider = provider,
                            providerHandler = providerHandler,
                            model = model,
                        )
                        updateCompressionUiState(
                            conversationId,
                            CompressionUiState(
                                conversationId = conversationId,
                                trigger = "regenerate-memory-ledger",
                                phase = CompressionUiPhase.Indexing
                            )
                        )
                        updatedConversation = rebuildIndexesWithRecovery(
                            conversationId = conversationId,
                            conversation = updatedConversation,
                            settings = settings,
                            showSuccessNotice = true,
                        )
                    } finally {
                        updateLedgerGenerationUiState(conversationId, null)
                        updateCompressionUiState(conversationId, null)
                    }
                }
            }
            Unit
        }.also {
            updateCompressionWorkerJob(conversationId, null)
        }
    }

    suspend fun editLatestDialogueSummary(
        conversationId: Uuid,
        editedSummaryText: String,
    ): Result<Unit> {
        return runCatching {
            val normalizedSummary = normalizeCompressionPlainText(editedSummaryText)
            if (normalizedSummary.isBlank()) {
                throw IllegalStateException("Dialogue summary cannot be blank")
            }

            val conversation = artifactService.getConversationWithCompressionArtifacts(conversationId)
                ?: throw IllegalStateException("Conversation not found")
            val latestEvent = conversation.compressionEvents.latestCompressionEvent()
                ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_no_latest_summary))
            val now = Instant.now()
            val tokenEstimate = estimateTokenCount(
                text = normalizedSummary,
                charsPerToken = settingsStore.settingsFlow.first().tokenEstimatorCharsPerToken
            )
            val updatedEvent = latestEvent.copy(
                dialogueSummaryText = normalizedSummary,
                dialogueSummaryPreview = buildDialogueSummaryPreview(normalizedSummary),
            )
            conversationRepo.updateCompressionEvent(updatedEvent, conversationId)
            val updatedConversation = conversation.copy(
                compressionState = conversation.compressionState.copy(
                    dialogueSummaryText = normalizedSummary,
                    dialogueSummaryTokenEstimate = tokenEstimate,
                    dialogueSummaryUpdatedAt = now,
                    updatedAt = now,
                ),
                compressionEvents = conversation.compressionEvents
                    .map { event -> if (event.id == updatedEvent.id) updatedEvent else event }
                    .sortedWith(compressionEventOrder),
                chatSuggestions = emptyList(),
            )
            saveConversationMetadata(conversationId, updatedConversation)
        }
    }

    private suspend fun compressConversationInternal(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        keepRecentMessages: Int,
        trigger: String,
        generateMemoryLedger: Boolean,
        baseDialogueSummaryTextOverride: String? = null,
        baseRollingSummaryJsonOverride: String? = null,
        compressStartIndexOverride: Int? = null,
        compressEndIndexOverride: Int? = null,
    ): Conversation {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Compression provider not found")
        val providerHandler = providerManager.getProviderByType(provider)

        val normalizedKeepRecent = keepRecentMessages.coerceAtLeast(0)
        val keepStartIndex = conversation.currentMessages.findKeepStartIndexForVisibleMessages(normalizedKeepRecent)
            ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        val compressEndIndex = compressEndIndexOverride ?: (keepStartIndex - 1)
        if (compressEndIndex < 0) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }

        val startIndex = compressStartIndexOverride
            ?: (conversation.compressionState.lastCompressedMessageIndex + 1).coerceAtLeast(0)
        if (startIndex > compressEndIndex) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_no_new_messages))
        }

        val showCompressionProgress = trigger in setOf(
            "manual",
            "auto-threshold",
            "regenerate-dialogue-summary",
            "regenerate-memory-ledger",
        )
        val showIndexSuccessNotice =
            trigger == "manual" || trigger == "auto-threshold" ||
                trigger == "regenerate-dialogue-summary" || trigger == "regenerate-memory-ledger"
        if (showCompressionProgress) {
            updateCompressionUiState(
                conversationId,
                CompressionUiState(
                    conversationId = conversationId,
                    trigger = trigger,
                    phase = CompressionUiPhase.Compressing
                )
            )
        }

        try {
            val incrementalMessages = conversation.currentMessages
                .subList(startIndex, compressEndIndex + 1)
                .joinToString("\n\n") { message ->
                    message.toCompressionText()
                }

            val currentDialogueSummary = baseDialogueSummaryTextOverride
                ?: conversation.compressionState.dialogueSummaryText
            val currentRollingSummary = baseRollingSummaryJsonOverride
                ?: conversation.compressionState.rollingSummaryJson.ifBlank { "{}" }
            val dialogueAdditionalContext = buildString {
                if (additionalPrompt.isNotBlank()) {
                    append("Additional instructions from user: ")
                    append(additionalPrompt)
                    appendLine()
                }
                append("Summary maintenance trigger: ")
                append(trigger)
                appendLine()
                append("Keep recent visible messages outside compression: ")
                append(normalizedKeepRecent)
            }

            fun buildDialoguePrompt(extraContext: String = dialogueAdditionalContext): String {
                return settings.dialogueCompressPrompt.applyPlaceholders(
                    "dialogue_summary_text" to currentDialogueSummary,
                    "incremental_messages" to incrementalMessages,
                    "additional_context" to extraContext,
                    "locale" to Locale.getDefault().displayName
                )
            }

            suspend fun runDialogueSummary(prompt: String): String {
                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = listOf(UIMessage.user(prompt)),
                    params = compressionGenerationParams(
                        model = model,
                        maxTokens = CHAT_COMPRESSION_DIALOGUE_SUMMARY_MAX_OUTPUT_TOKENS
                    ),
                )
                val summary = normalizeCompressionPlainText(
                    result.choices.firstOrNull()?.message?.toText().orEmpty()
                )
                if (summary.isBlank()) {
                    throw IllegalStateException("Failed to generate dialogue summary")
                }
                return summary
            }

            val nextDialogueSummary = runDialogueSummary(buildDialoguePrompt())
            val dialogueSummaryTokenEstimate = estimateTokenCount(
                text = nextDialogueSummary,
                charsPerToken = settings.tokenEstimatorCharsPerToken
            )

            val boundaryIndex = (compressEndIndex + 1).coerceIn(0, conversation.messageNodes.size)
            val event = conversationRepo.addCompressionEvent(
                conversationId = conversationId,
                boundaryIndex = boundaryIndex,
                dialogueSummaryText = nextDialogueSummary,
                dialogueSummaryPreview = buildDialogueSummaryPreview(nextDialogueSummary),
                ledgerSnapshot = "",
                summarySnapshot = "",
                compressStartIndex = startIndex,
                compressEndIndex = compressEndIndex,
                keepRecentMessages = normalizedKeepRecent,
                trigger = trigger,
                additionalPrompt = additionalPrompt,
                baseDialogueSummaryText = currentDialogueSummary,
                baseLedgerJson = currentRollingSummary,
                baseSummaryJson = currentRollingSummary,
            )

            var updatedConversation = conversation.copy(
                compressionState = conversation.compressionState.copy(
                    dialogueSummaryText = nextDialogueSummary,
                    dialogueSummaryTokenEstimate = dialogueSummaryTokenEstimate,
                    dialogueSummaryUpdatedAt = Instant.now(),
                    memoryLedgerStatus = if (generateMemoryLedger) "pending" else "stale",
                    memoryLedgerError = "",
                    lastCompressedMessageIndex = compressEndIndex,
                    updatedAt = Instant.now()
                ),
                compressionEvents = (conversation.compressionEvents + event).sortedWith(compressionEventOrder),
                chatSuggestions = emptyList(),
            )
            saveConversationMetadata(conversationId, updatedConversation)
            _compressionScrollEvents.tryEmit(conversationId to event.id)

            pendingLedgerBatchRepository.upsertPendingBatch(
                conversationId = conversationId,
                eventId = event.id,
                startIndex = startIndex,
                endIndex = compressEndIndex,
                incrementalMessages = incrementalMessages,
            )

            if (!generateMemoryLedger) {
                return runtimeService.getConversationFlow(conversationId).value
            }

            if (showCompressionProgress) {
                updateCompressionUiState(conversationId, null)
            }
            updateLedgerGenerationUiState(
                conversationId,
                LedgerGenerationUiState(conversationId = conversationId, trigger = trigger)
            )
            try {
                updatedConversation = processPendingLedgerBatches(
                    conversationId = conversationId,
                    conversation = updatedConversation,
                    trigger = trigger,
                    settings = settings,
                    provider = provider,
                    providerHandler = providerHandler,
                    model = model,
                )
            } finally {
                updateLedgerGenerationUiState(conversationId, null)
            }

            if (showCompressionProgress) {
                updateCompressionUiState(
                    conversationId,
                    CompressionUiState(
                        conversationId = conversationId,
                        trigger = trigger,
                        phase = CompressionUiPhase.Indexing
                    )
                )
            }
            updatedConversation = rebuildIndexesWithRecovery(
                conversationId = conversationId,
                conversation = updatedConversation,
                settings = settings,
                showSuccessNotice = showIndexSuccessNotice,
            )
            return runtimeService.getConversationFlow(conversationId).value
        } finally {
            updateLedgerGenerationUiState(conversationId, null)
            if (showCompressionProgress) {
                updateCompressionUiState(conversationId, null)
            }
        }
    }

    private fun buildSummarySnapshot(summaryJson: String): String {
        return parseRollingSummaryDocument(summaryJson)
            .toSummarySnapshot()
            .toJson()
    }

    private suspend fun rebuildIndexesWithRecovery(
        conversationId: Uuid,
        conversation: Conversation,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        showSuccessNotice: Boolean,
    ): Conversation {
        val startedAt = System.currentTimeMillis()
        logLedgerStep(conversationId, "index", "memory index rebuild started")
        return try {
            rebuildConversationIndexes(
                conversationId = conversationId,
                conversation = conversation,
                settings = settings,
            )
            logLedgerStep(
                conversationId,
                "index",
                "memory index rebuild finished in ${System.currentTimeMillis() - startedAt}ms"
            )
            if (showSuccessNotice) {
                noticeService.addSuccessNotice(
                    message = context.getString(R.string.memory_index_updated),
                    conversationId = conversationId,
                    title = context.getString(R.string.memory_index_updated_title)
                )
            }
            runtimeService.getConversationFlow(conversationId).value
        } catch (error: Throwable) {
            val failedConversation = conversation.copy(
                memoryIndexState = conversation.memoryIndexState.copy(
                    lastIndexStatus = "failed",
                    lastIndexError = error.message.orEmpty()
                )
            )
            saveConversationMetadata(conversationId, failedConversation)
            logLedgerStep(
                conversationId,
                "index",
                "memory index rebuild failed after ${System.currentTimeMillis() - startedAt}ms",
                error
            )
            noticeService.addError(
                error = error,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_memory_index)
            )
            failedConversation
        }
    }

    private suspend fun processPendingLedgerBatches(
        conversationId: Uuid,
        conversation: Conversation,
        trigger: String,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        provider: me.rerere.ai.provider.ProviderSetting,
        providerHandler: me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>,
        model: me.rerere.ai.provider.Model,
    ): Conversation {
        var currentConversation = conversation
        val processableBatches = pendingLedgerBatchRepository.getProcessableOfConversation(conversationId)
        if (processableBatches.isEmpty()) {
            logLedgerStep(conversationId, trigger, "no pending ledger batches")
            return currentConversation
        }

        logLedgerStep(
            conversationId,
            trigger,
            "processing ${processableBatches.size} pending ledger batch(es)"
        )

        processableBatches.forEach { batch ->
            val batchStartedAt = System.currentTimeMillis()
            if (currentConversation.compressionEvents.none { it.id == batch.eventId }) {
                pendingLedgerBatchRepository.deleteByConversationAndEvent(conversationId, batch.eventId)
                logLedgerStep(
                    conversationId,
                    trigger,
                    "batch ${batch.id} dropped because source event ${batch.eventId} no longer exists"
                )
                return@forEach
            }
            logLedgerStep(
                conversationId,
                trigger,
                "batch ${batch.id} range=${batch.startIndex}..${batch.endIndex} attempt=${batch.attemptCount + 1} started"
            )
            val runningBatch = pendingLedgerBatchRepository.updateStatus(
                batch = batch,
                status = "running",
                attemptCount = batch.attemptCount + 1,
                lastError = "",
            )
            try {
                currentConversation = applyLedgerBatch(
                    conversationId = conversationId,
                    conversation = currentConversation,
                    batch = runningBatch,
                    trigger = trigger,
                    settings = settings,
                    provider = provider,
                    providerHandler = providerHandler,
                    model = model,
                )
                pendingLedgerBatchRepository.updateStatus(
                    batch = runningBatch,
                    status = "done",
                    attemptCount = runningBatch.attemptCount,
                    lastError = "",
                )
                logLedgerStep(
                    conversationId,
                    trigger,
                    "batch ${batch.id} finished in ${System.currentTimeMillis() - batchStartedAt}ms"
                )
            } catch (error: CancellationException) {
                pendingLedgerBatchRepository.updateStatus(
                    batch = runningBatch,
                    status = "pending",
                    attemptCount = batch.attemptCount,
                    lastError = "cancelled",
                )
                logLedgerStep(
                    conversationId,
                    trigger,
                    "batch ${batch.id} cancelled after ${System.currentTimeMillis() - batchStartedAt}ms",
                    error
                )
                throw error
            } catch (error: Throwable) {
                pendingLedgerBatchRepository.updateStatus(
                    batch = runningBatch,
                    status = "failed",
                    attemptCount = runningBatch.attemptCount,
                    lastError = error.message.orEmpty(),
                )
                logLedgerStep(
                    conversationId,
                    trigger,
                    "batch ${batch.id} failed after ${System.currentTimeMillis() - batchStartedAt}ms",
                    error
                )
                currentConversation = currentConversation.copy(
                    compressionState = currentConversation.compressionState.copy(
                        memoryLedgerStatus = "failed",
                        memoryLedgerError = error.message.orEmpty(),
                        updatedAt = Instant.now()
                    )
                )
                saveConversationMetadata(conversationId, currentConversation)
                noticeService.addError(
                    error = error,
                    conversationId = conversationId,
                    title = context.getString(R.string.error_title_compress_conversation)
                )
                return currentConversation
            }
        }
        return currentConversation
    }

    private suspend fun applyLedgerBatch(
        conversationId: Uuid,
        conversation: Conversation,
        batch: PendingLedgerBatch,
        trigger: String,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        provider: me.rerere.ai.provider.ProviderSetting,
        providerHandler: me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>,
        model: me.rerere.ai.provider.Model,
    ): Conversation {
        val event = conversation.compressionEvents.firstOrNull { it.id == batch.eventId }
            ?: throw IllegalStateException("Compression event ${batch.eventId} not found for pending ledger batch")
        val ledgerStartedAt = System.currentTimeMillis()
        val currentRollingSummary = conversation.compressionState.rollingSummaryJson.ifBlank {
            event.baseLedgerJson.ifBlank { event.baseSummaryJson.ifBlank { "{}" } }
        }
        val budget = calculateCompressionBudget(
            incrementalMessages = batch.incrementalMessages,
            charsPerToken = settings.tokenEstimatorCharsPerToken
        )
        val additionalContext = buildString {
            if (event.additionalPrompt.isNotBlank()) {
                append("Additional user instructions: ")
                append(event.additionalPrompt)
                appendLine()
            }
            append("Ledger maintenance trigger: ")
            append(trigger)
            appendLine()
            append("Pending ledger batch range: ")
            append(batch.startIndex)
            append("..")
            append(batch.endIndex)
        }

        suspend fun runPatchPrompt(): String {
            val patchStartedAt = System.currentTimeMillis()
            logLedgerStep(
                conversationId,
                trigger,
                "batch ${batch.id} patch request started inputChars=${batch.incrementalMessages.length}"
            )
            val prompt = DEFAULT_MEMORY_LEDGER_PATCH_PROMPT.applyPlaceholders(
                "rolling_summary_json" to currentRollingSummary,
                "incremental_messages" to batch.incrementalMessages,
                "additional_context" to additionalContext,
                "locale" to Locale.getDefault().displayName
            )
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = compressionGenerationParams(model = model),
            )
            val normalized = normalizeCompressionJsonText(result.choices.firstOrNull()?.message?.toText().orEmpty())
            logLedgerStep(
                conversationId,
                trigger,
                "batch ${batch.id} patch request finished in ${System.currentTimeMillis() - patchStartedAt}ms outputChars=${normalized.length}"
            )
            return normalized
        }

        suspend fun runLedgerRewrite(): String {
            val rewriteStartedAt = System.currentTimeMillis()
            logLedgerStep(
                conversationId,
                trigger,
                "batch ${batch.id} full ledger rewrite started inputChars=${batch.incrementalMessages.length}"
            )
            val prompt = DEFAULT_MEMORY_LEDGER_PROMPT.applyPlaceholders(
                "rolling_summary_json" to currentRollingSummary,
                "incremental_messages" to batch.incrementalMessages,
                "incremental_input_tokens" to budget.incrementalInputTokens.toString(),
                "min_output_tokens" to budget.minOutputTokens.toString(),
                "target_output_tokens" to budget.targetOutputTokens.toString(),
                "hard_cap_tokens" to budget.hardCapTokens.toString(),
                "min_chronology_items" to budget.minChronologyItems.toString(),
                "min_detail_capsules" to budget.minDetailCapsules.toString(),
                "additional_context" to additionalContext,
                "locale" to Locale.getDefault().displayName
            )
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = compressionGenerationParams(model = model),
            )
            val rawSummary = normalizeCompressionJsonText(result.choices.firstOrNull()?.message?.toText().orEmpty())
            if (rawSummary.isBlank()) {
                throw IllegalStateException("Failed to generate memory ledger")
            }
            val normalized = normalizeRollingSummaryJson(
                rawSummary = rawSummary,
                summaryTurn = batch.endIndex + 1,
                updatedAt = Instant.now()
            )
            logLedgerStep(
                conversationId,
                trigger,
                "batch ${batch.id} full ledger rewrite finished in ${System.currentTimeMillis() - rewriteStartedAt}ms outputChars=${normalized.length}"
            )
            return normalized
        }

        val nextRollingSummaryJson = runCatching {
            logLedgerStep(conversationId, trigger, "batch ${batch.id} attempting patch fast path")
            val patchDocument: LedgerPatchDocument = parseLedgerPatchDocument(runPatchPrompt())
            val baseDocument = parseRollingSummaryDocument(currentRollingSummary)
            applyLedgerPatchDocument(
                base = baseDocument,
                patch = patchDocument,
                fallbackTurn = batch.endIndex + 1,
                updatedAt = Instant.now()
            ).toJson()
        }.getOrElse {
            logLedgerStep(
                conversationId,
                trigger,
                "batch ${batch.id} patch fast path failed, falling back to full rewrite",
                it
            )
            runLedgerRewrite()
        }

        val ledgerSnapshot = buildSummarySnapshot(nextRollingSummaryJson)
        val updatedEvent = event.copy(
            ledgerSnapshot = ledgerSnapshot,
            summarySnapshot = ledgerSnapshot,
        )
        conversationRepo.updateCompressionEvent(updatedEvent, conversationId)

        val updatedConversation = conversation.copy(
            compressionState = conversation.compressionState.copy(
                rollingSummaryJson = nextRollingSummaryJson,
                rollingSummaryTokenEstimate = estimateTokenCount(
                    nextRollingSummaryJson,
                    settings.tokenEstimatorCharsPerToken
                ),
                memoryLedgerStatus = "ready",
                memoryLedgerError = "",
                updatedAt = Instant.now()
            ),
            compressionEvents = conversation.compressionEvents.map {
                if (it.id == updatedEvent.id) updatedEvent else it
            }.sortedWith(compressionEventOrder),
            chatSuggestions = emptyList(),
        )
        saveConversationMetadata(conversationId, updatedConversation)
        logLedgerStep(
            conversationId,
            trigger,
            "batch ${batch.id} persisted in ${System.currentTimeMillis() - ledgerStartedAt}ms snapshotChars=${ledgerSnapshot.length}"
        )
        return updatedConversation
    }

    private suspend fun rebuildConversationIndexes(
        conversationId: Uuid,
        conversation: Conversation,
        settings: me.rerere.rikkahub.data.datastore.Settings,
    ): Int {
        val embeddingModel = settings.getEmbeddingModel()
            ?: throw IllegalStateException(context.getString(R.string.memory_index_embedding_required))
        if (embeddingModel.type != ModelType.EMBEDDING) {
            throw IllegalStateException(context.getString(R.string.memory_index_embedding_required))
        }
        val provider = embeddingModel.findProvider(settings.providers)
            ?: throw IllegalStateException("Embedding provider not found")
        val providerHandler = providerManager.getProviderByType(provider)
        val rollingSummaryJson = conversation.compressionState.rollingSummaryJson
            .takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(context.getString(R.string.memory_index_missing_summary))

        val liveTailDigest = buildLiveTailDigestJson(
            messages = collectLiveTailDigestMessages(conversation),
            updatedAt = Instant.now(),
            charsPerToken = settings.tokenEstimatorCharsPerToken
        )
        val memoryChunks = buildMemoryIndexChunks(
            rollingSummaryJson = rollingSummaryJson,
            charsPerToken = settings.tokenEstimatorCharsPerToken,
            liveTailDigestJson = liveTailDigest.json
        )
        if (memoryChunks.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.memory_index_empty))
        }

        val chunkEmbeddings = mutableListOf<List<Float>>()
        memoryChunks.chunked(32).forEach { batch ->
            val embeddingResult = providerHandler.generateEmbedding(
                providerSetting = provider,
                params = EmbeddingGenerationParams(
                    model = embeddingModel,
                    input = batch.map { it.content },
                )
            )
            chunkEmbeddings += embeddingResult.embeddings
        }
        if (chunkEmbeddings.size != memoryChunks.size) {
            throw IllegalStateException("Embedding result size mismatch")
        }

        val now = Instant.now()
        val memoryRecords = memoryChunks.mapIndexed { index, chunk ->
            MemoryIndexChunk(
                assistantId = conversation.assistantId,
                conversationId = conversation.id,
                sectionKey = chunk.sectionKey,
                chunkOrder = chunk.chunkOrder,
                content = chunk.content,
                tokenEstimate = chunk.tokenEstimate,
                embedding = chunkEmbeddings[index],
                metadata = chunk.metadata,
                updatedAt = now,
            )
        }
        val sourcePreviewRecords = buildSourcePreviewIndexChunks(conversation, now)

        memoryIndexRepository.replaceConversationChunks(
            assistantId = conversation.assistantId,
            conversationId = conversation.id,
            chunks = memoryRecords
        )
        sourcePreviewRepository.replaceConversationChunks(
            conversationId = conversation.id,
            chunks = sourcePreviewRecords
        )

        val refreshed = conversation.copy(
            memoryIndexState = conversation.memoryIndexState.copy(
                lastIndexStatus = "success",
                lastIndexedAt = now,
                lastIndexError = ""
            )
        )
        saveConversationMetadata(conversationId, refreshed)
        return memoryRecords.size
    }

    private fun buildSourcePreviewIndexChunks(
        conversation: Conversation,
        updatedAt: Instant,
    ): List<me.rerere.rikkahub.data.model.SourcePreviewChunk> {
        return buildSourcePreviewChunks(
            messages = collectIndexableSourceMessages(conversation)
        ).map { chunk ->
            me.rerere.rikkahub.data.model.SourcePreviewChunk(
                assistantId = conversation.assistantId,
                conversationId = conversation.id,
                messageId = Uuid.parse(chunk.messageId),
                role = chunk.role,
                chunkOrder = chunk.chunkOrder,
                prefixText = chunk.prefixText,
                searchText = chunk.searchText,
                blockType = chunk.blockType,
                updatedAt = updatedAt,
            )
        }
    }

    private fun collectLiveTailDigestMessages(conversation: Conversation): List<SourceDigestMessage> {
        val startIndex = (conversation.compressionState.lastCompressedMessageIndex + 1)
            .coerceAtLeast(0)
            .coerceAtMost(conversation.currentMessages.size)
        return conversation.currentMessages
            .drop(startIndex)
            .filter { message ->
                (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT) &&
                    message.toSourceText().isNotBlank()
            }
            .map { message ->
                SourceDigestMessage(
                    messageId = message.id.toString(),
                    role = message.role.name.lowercase(),
                    text = message.toSourceText(),
                    createdAt = message.createdAtInstant()
                )
            }
    }

    private fun collectIndexableSourceMessages(conversation: Conversation): List<IndexedSourceMessage> {
        return conversation.messageNodes
            .flatMap { node -> node.messages }
            .asSequence()
            .filter { message -> message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT }
            .map { message ->
                IndexedSourceMessage(
                    messageId = message.id.toString(),
                    role = message.role.name.lowercase(),
                    text = message.toSourceText()
                )
            }
            .filter { it.text.isNotBlank() }
            .distinctBy { it.messageId }
            .toList()
    }

    private fun buildDialogueSummaryPreview(summaryText: String): String {
        return summaryText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("[") && !it.endsWith("]") }
            .take(3)
            .joinToString(" | ")
            .take(220)
    }

    private suspend fun removeSupersededCompressionArtifacts(
        conversationId: Uuid,
        conversation: Conversation,
        supersededEventId: Long,
    ): Conversation {
        pendingLedgerBatchRepository.deleteByConversationAndEvent(conversationId, supersededEventId)
        conversationRepo.deleteCompressionEvent(conversationId, supersededEventId)
        val cleanedConversation = conversation.copy(
            compressionEvents = conversation.compressionEvents
                .filterNot { it.id == supersededEventId }
                .sortedWith(compressionEventOrder),
        )
        saveConversationMetadata(conversationId, cleanedConversation)
        return cleanedConversation
    }

    private fun logLedgerStep(
        conversationId: Uuid,
        trigger: String,
        message: String,
        error: Throwable? = null,
    ) {
        val text = "[ledger][$conversationId][$trigger] $message"
        if (error == null) {
            Log.d(TAG, text)
            Logging.log(TAG, text)
        } else {
            Log.e(TAG, text, error)
            Logging.log(TAG, "$text :: ${error.stackTraceToString()}")
        }
    }

    private fun normalizeCompressionPlainText(rawText: String): String {
        val withoutThinking = rawText
            .replace(Regex("(?is)<think>.*?</think>"), "")
            .replace(Regex("(?im)^\\s*(reasoning|thoughts?|thinking|思考过程|推理过程)\\s*[:：]?\\s*$"), "")
            .trim()
        val unfenced = withoutThinking
            .removePrefix("```text")
            .removePrefix("```markdown")
            .removePrefix("```md")
            .removePrefix("```json")
            .removePrefix("```")
            .trim()
            .removeSuffix("```")
            .trim()
        return unfenced
            .lines()
            .dropWhile { line ->
                val trimmed = line.trim()
                trimmed.equals("text", ignoreCase = true) ||
                    trimmed.equals("markdown", ignoreCase = true) ||
                    trimmed.equals("md", ignoreCase = true) ||
                    trimmed.equals("json", ignoreCase = true)
            }
            .joinToString("\n")
            .trim()
    }

    private fun normalizeCompressionJsonText(rawText: String): String {
        val sanitized = normalizeCompressionPlainText(rawText)
        val firstBrace = sanitized.indexOfFirst { it == '{' }
        val lastBrace = sanitized.indexOfLast { it == '}' }
        return if (firstBrace >= 0 && lastBrace > firstBrace) {
            sanitized.substring(firstBrace, lastBrace + 1).trim()
        } else {
            sanitized
        }
    }

    private fun compressionGenerationParams(
        model: me.rerere.ai.provider.Model,
        maxTokens: Int? = null,
    ): TextGenerationParams {
        return TextGenerationParams(
            model = model,
            temperature = 0.2f,
            topP = 1.0f,
            maxTokens = maxTokens,
            thinkingBudget = 0,
        )
    }

    private fun calculateCompressionBudget(
        incrementalMessages: String,
        charsPerToken: Float,
    ): ChatCompressionBudget {
        val inputTokens = estimateTokenCount(incrementalMessages, charsPerToken).coerceAtLeast(1)
        val dynamicOutputBudget = max(
            ROLLING_SUMMARY_TARGET_OUTPUT_TOKENS,
            ceil(inputTokens * 0.35f).toInt()
        )
        val targetOutputTokens = dynamicOutputBudget.coerceAtMost(ROLLING_SUMMARY_HARD_CAP_TOKENS)
        val minOutputTokens = minOf(
            targetOutputTokens,
            max(ROLLING_SUMMARY_MIN_OUTPUT_TOKENS, ceil(targetOutputTokens * 0.55f).toInt())
        )
        val chronologyPressure = ceil(inputTokens / 450f).toInt().coerceAtLeast(1)
        val detailPressure = ceil(inputTokens / 650f).toInt().coerceAtLeast(1)
        return ChatCompressionBudget(
            incrementalInputTokens = inputTokens,
            minOutputTokens = minOutputTokens,
            targetOutputTokens = targetOutputTokens,
            hardCapTokens = ROLLING_SUMMARY_HARD_CAP_TOKENS,
            minChronologyItems = chronologyPressure.coerceAtMost(ROLLING_SUMMARY_MAX_CHRONOLOGY_ITEMS),
            minDetailCapsules = detailPressure.coerceAtMost(ROLLING_SUMMARY_MAX_DETAIL_CAPSULES),
        )
    }

    private fun estimateTokenCount(text: String, charsPerToken: Float): Int {
        val normalizedCharsPerToken = charsPerToken.takeIf { it > 0f } ?: 4f
        return ceil(text.length / normalizedCharsPerToken).toInt().coerceAtLeast(0)
    }

    private fun UIMessage.createdAtInstant(): Instant {
        return Instant.ofEpochMilli(
            this.createdAt
                .toInstant(TimeZone.currentSystemDefault())
                .toEpochMilliseconds()
        )
    }

    private fun UIMessage.toSourceText(): String {
        return parts.joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text.trim()
                is UIMessagePart.Tool -> renderToolCompressionEnvelope(part)
                else -> ""
            }
        }.trim()
    }

    private fun UIMessage.toCompressionText(): String {
        return buildString {
            append('[')
            append(role.name)
            append("] ")
            append(parts.joinToString("\n") { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text.trim()
                    is UIMessagePart.Tool -> renderToolCompressionEnvelope(part)
                    else -> ""
                }
            }.trim())
        }
    }

    private fun renderToolCompressionEnvelope(part: UIMessagePart.Tool): String {
        val summaryLines = buildList {
            add("tool=${part.toolName}")
            val outputText = part.output.joinToString("\n") { outputPart ->
                when (outputPart) {
                    is UIMessagePart.Text -> outputPart.text
                    else -> outputPart.toString()
                }
            }.trim()
            if (part.isExecuted) {
                add("status=executed")
            } else {
                add("status=pending")
            }
            if (part.input.isNotBlank()) {
                add("input=${part.input.take(400)}")
            }
            if (outputText.isNotBlank()) {
                val trimmedOutput = outputText.take(900)
                val keyFields = extractKeyFields(outputText)
                val identifiers = extractIdentifiers(outputText)
                val errors = extractErrors(outputText)
                val references = extractPathsOrUrls(outputText)
                add("output=$trimmedOutput")
                if (keyFields.isNotEmpty()) add("key_fields=${keyFields.joinToString("; ")}")
                if (identifiers.isNotEmpty()) add("identifiers=${identifiers.joinToString(", ")}")
                if (errors.isNotEmpty()) add("errors=${errors.joinToString("; ")}")
                if (references.isNotEmpty()) add("references=${references.joinToString(", ")}")
            }
        }
        return summaryLines.joinToString("\n")
    }

    private fun extractKeyFields(text: String): List<String> {
        return Regex("(?im)^(?:title|name|status|summary|result|message|description|path)\\s*[:=]\\s*(.+)$")
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .take(8)
            .toList()
    }

    private fun extractIdentifiers(text: String): List<String> {
        return Regex("(?i)\\b(?:id|uuid|conversation|message|task|job)[-_ ]?(?:id)?\\b\\s*[:=]\\s*([A-Za-z0-9._:-]+)")
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .take(8)
            .toList()
    }

    private fun extractErrors(text: String): List<String> {
        return Regex("(?im)^(?:error|exception|stderr|warning)\\s*[:=]?\\s*(.+)$")
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .take(6)
            .toList()
    }

    private fun extractPathsOrUrls(text: String): List<String> {
        return Regex("""((?:https?://|file:/|/)[^\s"'`<>]+)""")
            .findAll(text)
            .map { it.value.trim() }
            .distinct()
            .take(6)
            .toList()
    }

    private suspend fun saveConversationMetadata(conversationId: Uuid, conversation: Conversation) {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        persistenceService.saveConversationMetadata(conversationId, conversation)
        noticeService.recordConversationMetadataSave(conversationId, conversation, startedAt)
    }

    private fun updateCompressionUiState(conversationId: Uuid, state: CompressionUiState?) {
        _compressionUiStates.update { current ->
            if (state == null) current - conversationId else current + (conversationId to state)
        }
    }

    private fun updateLedgerGenerationUiState(conversationId: Uuid, state: LedgerGenerationUiState?) {
        _ledgerGenerationUiStates.update { current ->
            if (state == null) current - conversationId else current + (conversationId to state)
        }
    }

    private fun updateCompressionWorkerJob(conversationId: Uuid, job: Job?) {
        _compressionWorkerJobs.update { current ->
            if (job == null) current - conversationId else current + (conversationId to job)
        }
    }
}
