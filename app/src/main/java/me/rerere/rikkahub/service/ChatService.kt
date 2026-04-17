package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.findKeepStartIndexForVisibleMessages
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.buildKnowledgeBaseGuidancePrompt
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_LEDGER_PATCH_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_LEDGER_PROMPT
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.buildListKnowledgeBaseDocumentsTool
import me.rerere.rikkahub.data.ai.tools.buildReadKnowledgeBaseChunksTool
import me.rerere.rikkahub.data.ai.tools.buildReadSourceTool
import me.rerere.rikkahub.data.ai.tools.buildRecallMemoryTool
import me.rerere.rikkahub.data.ai.tools.buildSearchKnowledgeBaseTool
import me.rerere.rikkahub.data.ai.tools.buildSearchSourceTool
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DeliveryOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.ToolImageReinjectionTransformer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getEmbeddingModel
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.memory.IndexedSourceMessage
import me.rerere.rikkahub.data.memory.buildMemoryIndexChunks
import me.rerere.rikkahub.data.memory.buildLiveTailSourceDigest
import me.rerere.rikkahub.data.memory.buildSourcePreviewChunks
import me.rerere.rikkahub.data.memory.isWeakSourceResult
import me.rerere.rikkahub.data.memory.parseSourceRef
import me.rerere.rikkahub.data.memory.rankMemoryChunks
import me.rerere.rikkahub.data.memory.rankMemoryChunksByVectorScores
import me.rerere.rikkahub.data.memory.rankSourcePreviewChunks
import me.rerere.rikkahub.data.memory.sourceRef
import me.rerere.rikkahub.data.model.applyLedgerPatchDocument
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.CompressionEvent
import me.rerere.rikkahub.data.model.ConversationCompressionState
import me.rerere.rikkahub.data.model.LedgerPatchDocument
import me.rerere.rikkahub.data.model.MemoryIndexChunk
import me.rerere.rikkahub.data.model.compressionEventOrder
import me.rerere.rikkahub.data.model.latestCompressionEvent
import me.rerere.rikkahub.data.model.parseLedgerPatchDocument
import me.rerere.rikkahub.data.model.PendingLedgerBatch
import me.rerere.rikkahub.data.model.ScheduledPromptTask
import me.rerere.rikkahub.data.model.ReadSourceResult
import me.rerere.rikkahub.data.model.RecallMemoryChunk
import me.rerere.rikkahub.data.model.RecallMemoryResult
import me.rerere.rikkahub.data.model.SearchSourceCandidate
import me.rerere.rikkahub.data.model.SearchSourceResult
import me.rerere.rikkahub.data.model.SourceDigestMessage
import me.rerere.rikkahub.data.model.SourcePreviewChunk
import me.rerere.rikkahub.data.model.buildLiveTailDigestJson
import me.rerere.rikkahub.data.model.normalizeRollingSummaryJson
import me.rerere.rikkahub.data.model.parseRollingSummaryDocument
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.withCompressionPayload
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryIndexRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.PendingLedgerBatchRepository
import me.rerere.rikkahub.data.repository.SourcePreviewRepository
import me.rerere.rikkahub.data.skills.SkillsRepository
import me.rerere.rikkahub.data.skills.buildSkillsCatalogPrompt
import me.rerere.rikkahub.sandbox.SandboxEngine
import me.rerere.rikkahub.ui.pages.debug.PerformanceDiagnosticsRecorder
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.uuid.Uuid

private const val TAG = "ChatService"
private const val STREAMING_COALESCE_WINDOW_MS = 24L
internal const val DIALOGUE_SUMMARY_MAX_OUTPUT_TOKENS = 65_536
private const val ROLLING_SUMMARY_MIN_OUTPUT_TOKENS = 1_200
private const val ROLLING_SUMMARY_TARGET_OUTPUT_TOKENS = 2_500
private const val ROLLING_SUMMARY_HARD_CAP_TOKENS = 30_000
private const val ROLLING_SUMMARY_MAX_CHRONOLOGY_ITEMS = 18
private const val ROLLING_SUMMARY_MAX_DETAIL_CAPSULES = 14
private const val RECALL_BM25_TOP_K = 50
private const val RECALL_VECTOR_RERANK_K = 30
private const val RECALL_MAX_RETURN_CHUNKS = 5
private const val RECALL_MAX_INJECT_TOKENS = 2_500
private const val SOURCE_PREVIEW_MAX_RESULTS = 3

internal fun shouldPreservePendingToolNode(tools: List<UIMessagePart.Tool>): Boolean {
    if (tools.isEmpty()) return false
    if (tools.all { it.isExecuted }) return true

    return tools.any { tool ->
        tool.approvalState is ToolApprovalState.Approved ||
            tool.approvalState is ToolApprovalState.Answered
    }
}

enum class ChatNoticeKind {
    ERROR,
    SUCCESS,
}

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val kind: ChatNoticeKind = ChatNoticeKind.ERROR,
)

data class ScheduledTaskExecutionResult(
    val replyPreview: String,
    val replyText: String,
    val modelId: Uuid?,
    val providerName: String,
)

enum class CompressionUiPhase {
    Compressing,
    Indexing,
}

data class CompressionUiState(
    val conversationId: Uuid,
    val trigger: String,
    val phase: CompressionUiPhase,
)

data class LedgerGenerationUiState(
    val conversationId: Uuid,
    val trigger: String,
)

enum class CompressionRegenerationTarget {
    DialogueSummary,
    MemoryLedger,
}

private data class CompressionBudget(
    val incrementalInputTokens: Int,
    val minOutputTokens: Int,
    val targetOutputTokens: Int,
    val hardCapTokens: Int,
    val minChronologyItems: Int,
    val minDetailCapsules: Int,
)

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        ToolImageReinjectionTransformer,
    )
}

private data class PendingStreamingUpdate(
    val conversation: Conversation,
    val source: String,
    val detail: String,
    val sizeHint: String,
)

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
        DeliveryOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val memoryIndexRepository: MemoryIndexRepository,
    private val pendingLedgerBatchRepository: PendingLedgerBatchRepository,
    private val sourcePreviewRepository: SourcePreviewRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val skillsRepository: SkillsRepository,
    private val knowledgeBaseService: KnowledgeBaseService,
    private val runtimeService: ConversationRuntimeService,
    private val persistenceService: ConversationPersistenceService,
    private val artifactService: ConversationArtifactService,
    private val derivedWorkService: ConversationDerivedWorkService,
    private val diagnosticsRecorder: PerformanceDiagnosticsRecorder,
    private val noticeService: ChatNoticeService,
    private val mutationService: ChatMutationService,
    private val compressionService: ChatCompressionService,
) {
    val errors: StateFlow<List<ChatError>> = noticeService.errors
    private val pendingStreamingUpdates = ConcurrentHashMap<Uuid, PendingStreamingUpdate>()
    private val streamingFlushJobs = ConcurrentHashMap<Uuid, Job>()
    // Legacy compaction internals still compile against this buffer while live behavior routes through ChatCompressionService.
    private val _compressionScrollEvents = MutableSharedFlow<Pair<Uuid, Long>>(extraBufferCapacity = 8)
    val compressionScrollEvents: SharedFlow<Pair<Uuid, Long>> = compressionService.compressionScrollEvents

    fun addError(error: Throwable, conversationId: Uuid? = null, title: String? = null) {
        noticeService.addError(error, conversationId, title)
    }

    fun addSuccessNotice(message: String, conversationId: Uuid? = null, title: String? = null) {
        noticeService.addSuccessNotice(message, conversationId, title)
    }

    fun dismissError(id: Uuid) {
        noticeService.dismissError(id)
    }

    fun clearAllErrors() {
        noticeService.clearAllErrors()
    }

    suspend fun executeScheduledTask(task: ScheduledPromptTask): Result<ScheduledTaskExecutionResult> = runCatching {
        if (task.prompt.isBlank()) {
            throw BadRequestException("Scheduled prompt cannot be blank")
        }

        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(task.assistantId)
            ?: throw NotFoundException("Assistant not found: ${task.assistantId}")
        val maxSearchIndex = (settings.searchServices.size - 1).coerceAtLeast(0)
        val effectiveSearchServiceIndex = task.overrideSearchServiceIndex?.let { index ->
            if (settings.searchServices.isEmpty()) {
                settings.searchServiceSelected
            } else {
                index.coerceIn(0, maxSearchIndex)
            }
        } ?: settings.searchServiceSelected.coerceIn(0, maxSearchIndex)
        val effectiveSettings = settings.copy(
            enableWebSearch = task.overrideEnableWebSearch ?: settings.enableWebSearch,
            searchServiceSelected = effectiveSearchServiceIndex
        )
        val effectiveAssistant = assistant.copy(
            chatModelId = task.overrideModelId ?: assistant.chatModelId,
            localTools = task.overrideLocalTools ?: assistant.localTools,
            mcpServers = task.overrideMcpServers ?: assistant.mcpServers
        )
        val model = effectiveAssistant.chatModelId?.let { effectiveSettings.findModelById(it) }
            ?: effectiveSettings.getCurrentChatModel()
            ?: throw IllegalStateException("No model configured for scheduled task")
        val provider = model.findProvider(effectiveSettings.providers)
            ?: throw IllegalStateException("Provider not found for model: ${model.id}")

        val promptText = task.prompt.replaceRegexes(
            assistant = effectiveAssistant,
            scope = AssistantAffectScope.USER,
            visual = false
        )
        val messages = buildList {
            addAll(effectiveAssistant.presetMessages)
            add(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(promptText))
                )
            )
        }

        val sandboxId = task.id
        if (effectiveAssistant.localTools.contains(LocalToolOption.Container)) {
            skillsRepository.refresh()
        }
        val kbToolAvailable = knowledgeBaseService.isSearchToolAvailable(
            assistant = effectiveAssistant,
            model = model,
            settings = effectiveSettings,
        )
        val knowledgeBasePrompt = if (kbToolAvailable) {
            buildKnowledgeBaseGuidancePrompt()
        } else {
            ""
        }
        val skillPrompt = buildSkillsCatalogPrompt(
            assistant = effectiveAssistant,
            model = model,
            catalog = skillsRepository.state.value,
        )?.trim().orEmpty()
        val baseSystemPrompt = effectiveAssistant.systemPrompt.trim()
        val mergedSystemPrompt = listOf(
            baseSystemPrompt,
            skillPrompt,
            knowledgeBasePrompt.trim(),
        ).filter { it.isNotBlank() }.joinToString("\n\n")
        val assistantForGeneration = if (mergedSystemPrompt == baseSystemPrompt) {
            effectiveAssistant
        } else {
            effectiveAssistant.copy(
                systemPrompt = mergedSystemPrompt
            )
        }

        val availableMcpTools = mcpManager.getAvailableToolsForServers(assistantForGeneration.mcpServers)
        val mcpWrappedTools = availableMcpTools.map { tool ->
            Tool(
                name = "mcp__${tool.name}",
                description = tool.description ?: "",
                parameters = { tool.inputSchema },
                needsApproval = tool.needsApproval,
                execute = {
                    mcpManager.callToolFromServers(
                        serverIds = assistantForGeneration.mcpServers,
                        toolName = tool.name,
                        args = it.jsonObject
                    )
                },
            )
        }

        var generatedMessages = messages
        generationHandler.generateText(
            settings = effectiveSettings,
            model = model,
            messages = messages,
            assistant = assistantForGeneration,
            memories = if (assistantForGeneration.useGlobalMemory) {
                memoryRepository.getGlobalMemories()
            } else {
                memoryRepository.getMemoriesOfAssistant(assistantForGeneration.id.toString())
            },
            inputTransformers = buildList {
                addAll(inputTransformers)
                add(templateTransformer)
            },
            outputTransformers = outputTransformers,
            tools = buildList {
                if (effectiveSettings.enableWebSearch) {
                    addAll(createSearchTools(context, effectiveSettings))
                }
                if (assistantForGeneration.enableRecentChatsReference) {
                    add(
                        buildRecallMemoryTool(json = JsonInstant) { query, channel, role ->
                            recallMemory(
                                assistantId = assistantForGeneration.id,
                                query = query,
                                channel = channel,
                                role = role
                            )
                        }
                    )
                    add(
                        buildSearchSourceTool(json = JsonInstant) { query, role, candidateConversationIds ->
                            searchSource(
                                assistantId = assistantForGeneration.id,
                                query = query,
                                role = role,
                                candidateConversationIds = candidateConversationIds
                            )
                        }
                    )
                    add(
                        buildReadSourceTool(json = JsonInstant) { sourceRef ->
                            readSource(
                                assistantId = assistantForGeneration.id,
                                sourceRef = sourceRef
                            )
                        }
                    )
                }
                if (kbToolAvailable) {
                    add(
                        buildListKnowledgeBaseDocumentsTool(json = JsonInstant) {
                            knowledgeBaseService.listKnowledgeBaseDocuments(assistantForGeneration.id)
                        }
                    )
                    add(
                        buildSearchKnowledgeBaseTool(json = JsonInstant) { query, documentIds ->
                            knowledgeBaseService.searchKnowledgeBase(
                                assistantId = assistantForGeneration.id,
                                query = query,
                                documentIds = documentIds,
                            )
                        }
                    )
                    add(
                        buildReadKnowledgeBaseChunksTool(json = JsonInstant) { documentId, chunkOrders ->
                            knowledgeBaseService.readKnowledgeBaseChunks(
                                assistantId = assistantForGeneration.id,
                                documentId = documentId,
                                chunkOrders = chunkOrders,
                            )
                        }
                    )
                }
                addAll(
                    localTools.getTools(
                        options = assistantForGeneration.localTools,
                        sandboxId = sandboxId,
                        settings = effectiveSettings,
                        parentModel = model,
                        subAgents = me.rerere.rikkahub.data.model.SubAgentTemplates.All,
                        mcpTools = mcpWrappedTools,
                    )
                )
                addAll(mcpWrappedTools)
            },
        ).collect { chunk ->
            when (chunk) {
                is GenerationChunk.TailMessage -> Unit
                is GenerationChunk.Messages -> {
                    generatedMessages = chunk.messages
                }
            }
        }

        val finalMessage = generatedMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: throw IllegalStateException("Scheduled task did not generate an assistant reply")
        val replyText = finalMessage.toText().trim()
        if (replyText.isBlank()) {
            throw IllegalStateException("Scheduled task generated an empty reply")
        }

        ScheduledTaskExecutionResult(
            replyPreview = replyText.take(200),
            replyText = replyText.take(20_000),
            modelId = model.id,
            providerName = provider.name
        )
    }

    fun getCompressionUiStateFlow(conversationId: Uuid): Flow<CompressionUiState?> {
        return compressionService.getCompressionUiStateFlow(conversationId)
    }

    fun getLedgerGenerationUiStateFlow(conversationId: Uuid): Flow<LedgerGenerationUiState?> {
        return compressionService.getLedgerGenerationUiStateFlow(conversationId)
    }

    private fun updateCompressionUiState(conversationId: Uuid, state: CompressionUiState?) {
        // Legacy bridge for compaction code that is no longer on the hot path.
    }

    private fun updateLedgerGenerationUiState(conversationId: Uuid, state: LedgerGenerationUiState?) {
        // Legacy bridge for compaction code that is no longer on the hot path.
    }

    private fun updateCompressionWorkerJob(conversationId: Uuid, job: Job?) {
        // Legacy bridge for compaction code that is no longer on the hot path.
    }

    fun cancelCompressionWork(conversationId: Uuid) {
        compressionService.cancelCompressionWork(conversationId)
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    // 前台状态管理
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    init {
        // 添加生命周期观察者
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun cleanup() = runCatching {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        artifactService.cleanup()
        runtimeService.cleanup()
    }

    // ---- Session 管理 ----

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        runtimeService.addConversationReference(conversationId)
    }

    fun removeConversationReference(conversationId: Uuid) {
        runtimeService.removeConversationReference(conversationId)
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = runtimeService.launchWithConversationReference(conversationId, block)

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return runtimeService.getConversationFlow(conversationId)
    }

    fun getConversationStableFlow(conversationId: Uuid): StateFlow<Conversation> {
        return runtimeService.getConversationStableFlow(conversationId)
    }

    fun getMessageNodesFlow(conversationId: Uuid): StateFlow<List<me.rerere.rikkahub.data.model.MessageNode>> {
        return runtimeService.getMessageNodesFlow(conversationId)
    }

    fun getStreamingTailFlow(conversationId: Uuid): StateFlow<StreamingTailState?> {
        return runtimeService.getStreamingTailFlow(conversationId)
    }

    fun getStreamingUiTickFlow(conversationId: Uuid): StateFlow<Long> {
        return runtimeService.getStreamingUiTickFlow(conversationId)
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        return runtimeService.getGenerationJobStateFlow(conversationId)
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return runtimeService.getConversationJobs()
    }

    fun getConversationJobsSnapshot(): Map<Uuid, Job?> = runtimeService.getConversationJobsSnapshot()

    fun getConversationJobSnapshot(conversationId: Uuid): Job? = runtimeService.getConversationJobsSnapshot()[conversationId]

    fun getCompressionUiStatesSnapshot(): Map<Uuid, CompressionUiState> = compressionService.getCompressionUiStatesSnapshot()

    fun getLedgerGenerationUiStatesSnapshot(): Map<Uuid, LedgerGenerationUiState> =
        compressionService.getLedgerGenerationUiStatesSnapshot()

    fun getCompressionWorkerJobsSnapshot(): Map<Uuid, Job?> = compressionService.getCompressionWorkerJobsSnapshot()

    fun getCurrentConversationSnapshotOrNull(conversationId: Uuid): Conversation? =
        runtimeService.getCurrentConversationOrNull(conversationId)

    fun recordUiDiagnostic(
        category: String,
        conversationId: Uuid,
        detail: String,
        phase: String? = null,
    ) {
        noticeService.recordUiDiagnostic(category, conversationId, detail, phase)
    }

    private fun getPendingOrCurrentConversation(conversationId: Uuid): Conversation {
        return pendingStreamingUpdates[conversationId]?.conversation ?: runtimeService.getCurrentConversation(conversationId)
    }

    private fun enqueueStreamingConversationUpdate(
        conversationId: Uuid,
        conversation: Conversation,
        source: String,
        detail: String,
        sizeHint: String,
        immediate: Boolean = false,
    ) {
        pendingStreamingUpdates[conversationId] = PendingStreamingUpdate(
            conversation = conversation,
            source = source,
            detail = detail,
            sizeHint = sizeHint,
        )
        if (immediate) {
            appScope.launch {
                flushPendingStreamingConversationNow(conversationId)
            }
            return
        }
        val existingJob = streamingFlushJobs[conversationId]
        if (existingJob?.isActive == true) return

        streamingFlushJobs[conversationId] = appScope.launch {
            kotlinx.coroutines.delay(STREAMING_COALESCE_WINDOW_MS)
            flushPendingStreamingConversation(conversationId)
        }
    }

    private suspend fun flushPendingStreamingConversation(conversationId: Uuid) {
        val pending = pendingStreamingUpdates.remove(conversationId) ?: run {
            streamingFlushJobs.remove(conversationId)
            return
        }
        streamingFlushJobs.remove(conversationId)

        val uiVersion = traceDiagnostic(
            category = "streaming-coalesce-flush",
            detail = pending.detail,
            conversationId = conversationId,
            phase = pending.source,
            sizeHint = pending.sizeHint,
        ) {
            runtimeService.updateStreamingConversation(
                conversationId = conversationId,
                conversation = pending.conversation,
                source = pending.source,
            )
        }
        diagnosticsRecorder.record(
            category = "ui-tail-version",
            detail = "version=$uiVersion source=${pending.source}",
            conversationId = conversationId,
            phase = "tailVersion",
            sizeHint = pending.sizeHint,
        )
    }

    private suspend fun flushPendingStreamingConversationNow(conversationId: Uuid) {
        streamingFlushJobs.remove(conversationId)?.cancel()
        flushPendingStreamingConversation(conversationId)
    }

    private fun clearPendingStreamingConversation(conversationId: Uuid) {
        streamingFlushJobs.remove(conversationId)?.cancel()
        pendingStreamingUpdates.remove(conversationId)
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        val sessionConversation = getConversationFlow(conversationId).value
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            val mergedConversation = mergeMissingCompressionArtifacts(
                base = conversation,
                fallback = sessionConversation,
            )
            updateConversation(conversationId, mergedConversation)
            settingsStore.updateAssistant(mergedConversation.assistantId)
            warmCompressionArtifactsAsync(conversationId, mergedConversation)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        runtimeService.cancelGenerationJob(conversationId)
        val processedContent = preprocessUserInputParts(content)

        val job = appScope.launch {
            try {
                val currentConversation = getConversationFlow(conversationId).value

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        runtimeService.setGenerationJob(conversationId, job)
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>): List<UIMessagePart> {
        val assistant = settingsStore.settingsFlow.value.getCurrentAssistant()
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        runtimeService.cancelGenerationJob(conversationId)

        val job = appScope.launch {
            try {
                val conversation = getConversationFlow(conversationId).value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        runtimeService.setGenerationJob(conversationId, job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        runtimeService.cancelGenerationJob(conversationId)

        val job = appScope.launch {
            try {
                val conversation = getConversationFlow(conversationId).value
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                // Update the tool approval state
                val updatedNodes = conversation.messageNodes.map { node ->
                    node.copy(
                        messages = node.messages.map { msg ->
                            msg.copy(
                                parts = msg.parts.map { part ->
                                    when {
                                        part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                            part.copy(approvalState = newApprovalState)
                                        }

                                        else -> part
                                    }
                                }
                            )
                        }
                    )
                }
                val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                saveConversation(conversationId, updatedConversation)

                // Check if there are still pending tools
                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        runtimeService.setGenerationJob(conversationId, job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.getCurrentChatModel() ?: return
        var promptCharsForCalibration = 0

        val assistant = settings.getCurrentAssistant()
        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        runCatching {
            var conversation = getConversationFlow(conversationId).value
            val assistant = settings.getCurrentAssistant()
            val hasKnowledgeBaseDocuments = assistant.enableKnowledgeBaseTool &&
                knowledgeBaseService.hasDocuments(assistant.id)

            // reset suggestions
            updateConversation(conversationId, conversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                val toolRequired = settings.enableWebSearch ||
                    assistant.enableMemory ||
                    assistant.enableRecentChatsReference ||
                    hasKnowledgeBaseDocuments ||
                    assistant.enabledSkills.isNotEmpty() ||
                    assistant.localTools.isNotEmpty() ||
                    mcpManager.getAllAvailableTools().isNotEmpty()
                if (toolRequired) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            conversation = getConversationFlow(conversationId).value
            conversation = hydrateCompressionPayload(conversationId, conversation)

            // If container tool is enabled, import current user documents into sandbox first.
            if (assistant.localTools.contains(LocalToolOption.Container)) {
                importDocumentsToSandbox(conversation, conversationId.toString())
            }

            if (messageRange == null && settings.autoCompressEnabled) {
                val estimatedPromptTokens = estimatePromptTokenUsage(
                    conversation = conversation,
                    charsPerToken = settings.tokenEstimatorCharsPerToken
                )
                if (estimatedPromptTokens >= settings.autoCompressTriggerTokens) {
                    runCatching {
                        compressConversationInternal(
                            conversationId = conversationId,
                            conversation = conversation,
                            additionalPrompt = "",
                            keepRecentMessages = 6,
                            trigger = "auto-threshold",
                            generateMemoryLedger = true,
                        )
                    }.onFailure { error ->
                        addError(
                            error,
                            conversationId = conversationId,
                            title = context.getString(R.string.error_title_compress_conversation)
                        )
                    }
                    conversation = getConversationFlow(conversationId).value
                }
            }

            val messagesForGeneration: List<UIMessage>
            val generationWriteBackStartIndex: Int
            val dialogueSummaryTextForGeneration: String
            val legacyRollingSummaryJsonForGeneration: String
            if (messageRange != null) {
                generationWriteBackStartIndex = messageRange.start
                messagesForGeneration = conversation.currentMessages
                    .subList(messageRange.start, messageRange.endInclusive + 1)
                dialogueSummaryTextForGeneration = ""
                legacyRollingSummaryJsonForGeneration = ""
            } else {
                val compressedUntil = conversation.compressionState.lastCompressedMessageIndex
                    .coerceAtMost(conversation.currentMessages.lastIndex)
                val hasRollingSummary = conversation.compressionState.hasSummary && compressedUntil >= 0
                messagesForGeneration = if (hasRollingSummary) {
                    conversation.currentMessages.drop(compressedUntil + 1).ifEmpty {
                        conversation.currentMessages.takeLast(1)
                    }
                } else {
                    conversation.currentMessages
                }
                generationWriteBackStartIndex = if (hasRollingSummary) {
                    (conversation.currentMessages.lastIndex - messagesForGeneration.lastIndex).coerceAtLeast(0)
                } else {
                    0
                }
                dialogueSummaryTextForGeneration = conversation.compressionState.dialogueSummaryText
                legacyRollingSummaryJsonForGeneration = conversation.compressionState.rollingSummaryJson
            }
            promptCharsForCalibration = estimatePromptCharCount(
                messages = messagesForGeneration,
                dialogueSummaryText = dialogueSummaryTextForGeneration,
                legacyRollingSummaryJson = legacyRollingSummaryJsonForGeneration,
            )

            if (assistant.localTools.contains(LocalToolOption.Container)) {
                skillsRepository.refresh()
            }
            val kbToolAvailable = knowledgeBaseService.isSearchToolAvailable(
                assistant = assistant,
                model = model,
                settings = settings,
            )
            val knowledgeBasePrompt = if (kbToolAvailable) {
                buildKnowledgeBaseGuidancePrompt()
            } else {
                ""
            }
            val skillPrompt = buildSkillsCatalogPrompt(
                assistant = assistant,
                model = model,
                catalog = skillsRepository.state.value,
            )?.trim().orEmpty()
            val baseSystemPrompt = assistant.systemPrompt.trim()
            val mergedSystemPrompt = listOf(
                baseSystemPrompt,
                skillPrompt,
                knowledgeBasePrompt.trim(),
            ).filter { it.isNotBlank() }.joinToString("\n\n")
            val assistantForGeneration = if (mergedSystemPrompt == baseSystemPrompt) {
                assistant
            } else {
                assistant.copy(
                    systemPrompt = mergedSystemPrompt
                )
            }

            val availableMcpTools = mcpManager.getAllAvailableTools()
            val mcpWrappedTools = availableMcpTools.map { tool ->
                Tool(
                    name = "mcp__" + tool.name,
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = tool.needsApproval,
                    execute = {
                        listOf(
                            UIMessagePart.Text(
                                mcpManager.callTool(tool.name, it.jsonObject).toString()
                            )
                        )
                    },
                )
            }

            // start generating
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = messagesForGeneration,
                assistant = assistantForGeneration,
                memories = if (assistantForGeneration.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistantForGeneration.id.toString())
                },
                dialogueSummaryText = dialogueSummaryTextForGeneration,
                legacyRollingSummaryJson = legacyRollingSummaryJsonForGeneration,
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (settings.enableWebSearch) {
                        addAll(createSearchTools(context, settings))
                    }
                    if (assistantForGeneration.enableRecentChatsReference) {
                        add(
                            buildRecallMemoryTool(json = JsonInstant) { query, channel, role ->
                                recallMemory(
                                    assistantId = assistantForGeneration.id,
                                    query = query,
                                    channel = channel,
                                    role = role
                                )
                            }
                        )
                        add(
                            buildSearchSourceTool(json = JsonInstant) { query, role, candidateConversationIds ->
                                searchSource(
                                    assistantId = assistantForGeneration.id,
                                    query = query,
                                    role = role,
                                    candidateConversationIds = candidateConversationIds
                                )
                            }
                        )
                        add(
                            buildReadSourceTool(json = JsonInstant) { sourceRef ->
                                readSource(
                                    assistantId = assistantForGeneration.id,
                                    sourceRef = sourceRef
                                )
                            }
                        )
                    }
                    if (kbToolAvailable) {
                        add(
                            buildListKnowledgeBaseDocumentsTool(json = JsonInstant) {
                                knowledgeBaseService.listKnowledgeBaseDocuments(assistantForGeneration.id)
                            }
                        )
                        add(
                            buildSearchKnowledgeBaseTool(json = JsonInstant) { query, documentIds ->
                                knowledgeBaseService.searchKnowledgeBase(
                                    assistantId = assistantForGeneration.id,
                                    query = query,
                                    documentIds = documentIds,
                                )
                            }
                        )
                        add(
                            buildReadKnowledgeBaseChunksTool(json = JsonInstant) { documentId, chunkOrders ->
                                knowledgeBaseService.readKnowledgeBaseChunks(
                                    assistantId = assistantForGeneration.id,
                                    documentId = documentId,
                                    chunkOrders = chunkOrders,
                                )
                            }
                        )
                    }
                    addAll(
                        localTools.getTools(
                            options = assistantForGeneration.localTools,
                            sandboxId = conversationId,
                            workflowStateProvider = {
                                getConversationFlow(conversationId).value.workflowState
                            },
                            onWorkflowStateUpdate = { newWorkflowState ->
                                val currentConversation = getConversationFlow(conversationId).value
                                updateConversation(
                                    conversationId,
                                    currentConversation.copy(workflowState = newWorkflowState)
                                )
                            },
                            todoStateProvider = {
                                val currentConversation = getConversationFlow(conversationId).value
                                currentConversation.todoState ?: if (
                                    assistantForGeneration.localTools.contains(
                                        me.rerere.rikkahub.data.ai.tools.LocalToolOption.WorkflowTodo
                                    )
                                ) {
                                    val newTodoState = me.rerere.rikkahub.data.model.TodoState(isEnabled = true)
                                    updateConversation(
                                        conversationId,
                                        currentConversation.copy(todoState = newTodoState)
                                    )
                                    newTodoState
                                } else {
                                    null
                                }
                            },
                            onTodoStateUpdate = { newTodoState ->
                                val currentConversation = getConversationFlow(conversationId).value
                                updateConversation(
                                    conversationId,
                                    currentConversation.copy(todoState = newTodoState)
                                )
                            },
                            subAgents = me.rerere.rikkahub.data.model.SubAgentTemplates.All,
                            settings = settings,
                            parentModel = model,
                            parentWorkflowPhase = conversation.workflowState?.phase,
                            mcpTools = mcpWrappedTools,
                        )
                    )
                    addAll(mcpWrappedTools)
                },
            ).onCompletion {
                // 取消 Live Update 通知
                cancelLiveUpdateNotification(conversationId)
                flushPendingStreamingConversationNow(conversationId)

                // 可能被取消了，或者意外结束，兜底更新
                val currentConversation = runtimeService.getCurrentConversation(conversationId)
                val updatedConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // Show notification if app is not in foreground
                if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) {
                    sendGenerationDoneNotification(conversationId, senderName)
                }
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.TailMessage -> {
                        val absoluteIndex = generationWriteBackStartIndex + chunk.messageIndex
                        val chunkDetail = "messageIndex=$absoluteIndex localIndex=${chunk.messageIndex}"
                        val chunkSizeHint = buildChunkSizeHint(
                            messages = listOf(chunk.message),
                            startIndex = absoluteIndex,
                        )
                        diagnosticsRecorder.record(
                            category = "tail-received",
                            detail = chunkDetail,
                            conversationId = conversationId,
                            phase = "received",
                            sizeHint = chunkSizeHint,
                        )
                        val currentConversation = getPendingOrCurrentConversation(conversationId)
                        val updatedConversation = traceDiagnostic(
                            category = "tail-updateCurrentMessage",
                            detail = chunkDetail,
                            conversationId = conversationId,
                            phase = "updateCurrentMessage",
                            sizeHint = chunkSizeHint,
                        ) {
                            currentConversation.updateCurrentMessage(
                                message = chunk.message,
                                targetIndex = absoluteIndex,
                            )
                        }
                        traceDiagnostic(
                            category = "tail-runtimeUpdate",
                            detail = "messageNodes=${updatedConversation.messageNodes.size}",
                            conversationId = conversationId,
                            phase = "runtimeUpdate",
                            sizeHint = chunkSizeHint,
                        ) {
                            enqueueStreamingConversationUpdate(
                                conversationId = conversationId,
                                conversation = updatedConversation,
                                source = "tail",
                                detail = "messageNodes=${updatedConversation.messageNodes.size}",
                                sizeHint = chunkSizeHint,
                                immediate = true,
                            )
                        }
                        diagnosticsRecorder.record(
                            category = "tail",
                            detail = chunkDetail,
                            conversationId = conversationId,
                            phase = "complete",
                            sizeHint = chunkSizeHint,
                        )

                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                            traceDiagnostic(
                                category = "tail-liveNotification",
                                detail = "sender=$senderName",
                                conversationId = conversationId,
                                phase = "liveNotification",
                                sizeHint = chunkSizeHint,
                            ) {
                                sendLiveUpdateNotification(conversationId, listOf(chunk.message), senderName)
                            }
                        }
                    }

                    is GenerationChunk.Messages -> {
                        val chunkDetail = "messages=${chunk.messages.size} startIndex=$generationWriteBackStartIndex"
                        val chunkSizeHint = buildChunkSizeHint(
                            messages = chunk.messages,
                            startIndex = generationWriteBackStartIndex,
                        )
                        diagnosticsRecorder.record(
                            category = "chunk-received",
                            detail = chunkDetail,
                            conversationId = conversationId,
                            phase = "received",
                            sizeHint = chunkSizeHint,
                        )
                        val currentConversation = getPendingOrCurrentConversation(conversationId)
                        val updatedConversation = traceDiagnostic(
                            category = "chunk-updateCurrentMessages",
                            detail = chunkDetail,
                            conversationId = conversationId,
                            phase = "updateCurrentMessages",
                            sizeHint = chunkSizeHint,
                        ) {
                            currentConversation.updateCurrentMessages(
                                messages = chunk.messages,
                                startIndex = generationWriteBackStartIndex
                            )
                        }
                        traceDiagnostic(
                            category = "chunk-runtimeUpdate",
                            detail = "messageNodes=${updatedConversation.messageNodes.size}",
                            conversationId = conversationId,
                            phase = "runtimeUpdate",
                            sizeHint = chunkSizeHint,
                        ) {
                            enqueueStreamingConversationUpdate(
                                conversationId = conversationId,
                                conversation = updatedConversation,
                                source = "chunk",
                                detail = "messageNodes=${updatedConversation.messageNodes.size}",
                                sizeHint = chunkSizeHint,
                            )
                        }
                        diagnosticsRecorder.record(
                            category = "chunk",
                            detail = chunkDetail,
                            conversationId = conversationId,
                            phase = "complete",
                            sizeHint = chunkSizeHint,
                        )

                        // 如果应用不在前台，发送 Live Update 通知
                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                            traceDiagnostic(
                                category = "chunk-liveNotification",
                                detail = "sender=$senderName",
                                conversationId = conversationId,
                                phase = "liveNotification",
                                sizeHint = chunkSizeHint,
                            ) {
                                sendLiveUpdateNotification(conversationId, chunk.messages, senderName)
                            }
                        }
                    }
                }
            }
        }.onFailure {
            // 取消 Live Update 通知
            cancelLiveUpdateNotification(conversationId)
            clearPendingStreamingConversation(conversationId)

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            clearPendingStreamingConversation(conversationId)
            val finalConversation = runtimeService.getCurrentConversation(conversationId)
            traceDiagnostic(
                category = "generation-finish-save",
                detail = "messageNodes=${finalConversation.messageNodes.size}",
                conversationId = conversationId,
                phase = "finishSave",
                sizeHint = buildConversationSizeHint(finalConversation),
            ) {
                saveConversation(conversationId, finalConversation)
            }
            calibrateTokenEstimator(
                promptChars = promptCharsForCalibration,
                actualPromptTokens = finalConversation.currentMessages.lastOrNull()?.usage?.promptTokens ?: 0
            )

            derivedWorkService.trackTitleJob(
                conversationId,
                launchWithConversationReference(conversationId) {
                    generateTitle(conversationId, finalConversation)
                },
            )
            derivedWorkService.trackSuggestionJob(
                conversationId,
                launchWithConversationReference(conversationId) {
                    generateSuggestion(conversationId, finalConversation)
                },
            )
        }
    }

    // ---- 检查无效消息 ----

    private suspend fun importDocumentsToSandbox(
        conversation: Conversation,
        sandboxId: String
    ) = withContext(Dispatchers.IO) {
        val lastUserMessage = conversation.messageNodes
            .flatMap { it.messages }
            .filter { it.role == MessageRole.USER }
            .lastOrNull()

        lastUserMessage?.parts?.filterIsInstance<UIMessagePart.Document>()?.forEach { document ->
            try {
                val fileUri = android.net.Uri.parse(document.url)
                val targetPath = SandboxEngine.importFileToSandbox(
                    context,
                    sandboxId,
                    fileUri,
                    document.fileName
                )
                if (targetPath != null) {
                    Log.i(TAG, "Imported file to sandbox [$sandboxId]: $targetPath")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import file to sandbox: ${document.fileName}", e)
            }
        }
    }

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { index, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep tool nodes that are ready to resume after approval or ask_user answers.
                if (shouldPreservePendingToolNode(node.currentMessage.getTools())) {
                    return@mapIndexed node
                }

                // Remove message with pending non-approved tools
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(
            conversationId,
            normalizeCompressionState(conversation.copy(messageNodes = messagesNodes))
        )
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        runCatching {
            derivedWorkService.generateTitle(conversationId, conversation, force)
        }.onFailure {
            it.printStackTrace()
            addError(it, conversationId, title = derivedWorkService.titleErrorTitle())
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            derivedWorkService.generateSuggestion(conversationId, conversation)
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩与记忆索引 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        keepRecentMessages: Int = 6,
        generateMemoryLedger: Boolean = true,
    ): Result<Unit> = compressionService.compressConversation(
        conversationId = conversationId,
        conversation = conversation,
        additionalPrompt = additionalPrompt,
        keepRecentMessages = keepRecentMessages,
        generateMemoryLedger = generateMemoryLedger,
    )

    suspend fun generateMemoryIndex(conversationId: Uuid): Result<Int> =
        compressionService.generateMemoryIndex(conversationId)

    suspend fun regenerateLatestCompression(
        conversationId: Uuid,
        target: CompressionRegenerationTarget = CompressionRegenerationTarget.DialogueSummary,
    ): Result<Unit> = compressionService.regenerateLatestCompression(conversationId, target)

    suspend fun editLatestDialogueSummary(
        conversationId: Uuid,
        editedSummaryText: String,
    ): Result<Unit> = compressionService.editLatestDialogueSummary(conversationId, editedSummaryText)

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
                        // Keep summary budgeting hidden from the model. The wide cap here is
                        // only a safety fuse against accidental truncation inside the app.
                        maxTokens = DIALOGUE_SUMMARY_MAX_OUTPUT_TOKENS
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

            var nextDialogueSummary = runDialogueSummary(buildDialoguePrompt())
            var dialogueSummaryTokenEstimate = estimateTokenCount(
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
                return getConversationFlow(conversationId).value
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
            return getConversationFlow(conversationId).value
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
                addSuccessNotice(
                    message = context.getString(R.string.memory_index_updated),
                    conversationId = conversationId,
                    title = context.getString(R.string.memory_index_updated_title)
                )
            }
            getConversationFlow(conversationId).value
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
            addError(
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
                // Older buggy regenerations could leave behind pending-ledger rows whose
                // source compression event has already been replaced. Drop those stale rows
                // instead of letting them poison every later ledger rebuild attempt.
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
                addError(
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
            // Patch is only a fast path. If it fails, fall back to a full ledger rewrite
            // rather than forcing a lower-quality partial result into the persisted ledger.
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
        // Summary regeneration now creates a replacement event from the exact same base
        // summary + incremental range. Once the replacement exists, we can safely remove
        // the superseded event row and its old pending-ledger batch together.
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
            maxTokens = maxTokens,
            includeThoughtsInResponse = false,
        )
    }

    private fun calculateCompressionBudget(
        incrementalMessages: String,
        charsPerToken: Float,
    ): CompressionBudget {
        val incrementalInputTokens = estimateTokenCount(
            text = incrementalMessages,
            charsPerToken = charsPerToken
        )
        val minOutputTokens = ((incrementalInputTokens * 0.10).let(::ceil).toInt())
            .coerceIn(ROLLING_SUMMARY_MIN_OUTPUT_TOKENS, 12_000)
        val targetOutputTokens = ((incrementalInputTokens * 0.16).let(::ceil).toInt())
            .coerceIn(ROLLING_SUMMARY_TARGET_OUTPUT_TOKENS, 18_000)
        val minChronologyItems = ceil(incrementalInputTokens / 1_500.0)
            .toInt()
            .coerceIn(2, ROLLING_SUMMARY_MAX_CHRONOLOGY_ITEMS)
        val minDetailCapsules = ceil(incrementalInputTokens / 2_500.0)
            .toInt()
            .coerceIn(1, ROLLING_SUMMARY_MAX_DETAIL_CAPSULES)
        return CompressionBudget(
            incrementalInputTokens = incrementalInputTokens,
            minOutputTokens = minOutputTokens,
            targetOutputTokens = targetOutputTokens,
            hardCapTokens = ROLLING_SUMMARY_HARD_CAP_TOKENS,
            minChronologyItems = minChronologyItems,
            minDetailCapsules = minDetailCapsules,
        )
    }

    private fun estimateTokenCount(text: String, charsPerToken: Float): Int {
        val ratio = charsPerToken.coerceIn(2.0f, 8.0f).toDouble()
        val value = (text.length / ratio).toInt()
        return max(1, value)
    }

    private fun UIMessage.toSourceText(): String {
        return parts.joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> ""
            }
        }.trim()
    }

    private fun UIMessage.createdAtInstant(): Instant {
        return runCatching {
            java.time.LocalDateTime.parse(createdAt.toString())
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
        }.getOrElse { Instant.now() }
    }

    private fun UIMessage.toCompressionText(): String {
        val text = buildString {
            parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> appendLine(part.text)
                    is UIMessagePart.Reasoning -> appendLine("[reasoning] ${part.reasoning.take(1200)}")
                    is UIMessagePart.Tool -> {
                        appendLine(renderToolCompressionEnvelope(part))
                    }

                    else -> Unit
                }
            }
        }.trim()
        return "[${role.name}] $text".trim()
    }

    private fun renderToolCompressionEnvelope(part: UIMessagePart.Tool): String {
        val outputText = part.output.joinToString("\n") {
            when (it) {
                is UIMessagePart.Text -> it.text
                else -> it.toString()
            }
        }.trim()
        val keyFields = extractKeyFields(outputText)
        val identifiers = extractIdentifiers(outputText)
        val errors = extractErrors(outputText)
        val pathsOrUrls = extractPathsOrUrls(outputText)
        val normalizedOutput = when {
            outputText.isBlank() -> ""
            outputText.length <= 4_000 -> outputText
            else -> buildString {
                appendLine("output_summary:")
                keyFields.takeIf { it.isNotEmpty() }?.let {
                    appendLine(it.joinToString("\n") { field -> "- $field" })
                }
                appendLine("output_head:")
                appendLine(outputText.take(1_200))
                appendLine("output_tail:")
                append(outputText.takeLast(1_200))
            }.trim()
        }
        return buildString {
            appendLine("[tool] ${part.toolName}")
            appendLine("input: ${part.input.take(1_600)}")
            if (keyFields.isNotEmpty()) {
                appendLine("key_fields: ${keyFields.joinToString(" | ")}")
            }
            if (identifiers.isNotEmpty()) {
                appendLine("identifiers: ${identifiers.joinToString(", ")}")
            }
            if (errors.isNotEmpty()) {
                appendLine("errors: ${errors.joinToString(" | ")}")
            }
            if (pathsOrUrls.isNotEmpty()) {
                appendLine("paths_or_urls: ${pathsOrUrls.joinToString(", ")}")
            }
            if (normalizedOutput.isNotBlank()) {
                appendLine(normalizedOutput)
            }
        }.trim()
    }

    private fun extractKeyFields(text: String): List<String> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.contains(':') &&
                    line.any(Char::isLetterOrDigit) &&
                    line.length in 4..220
            }
            .distinct()
            .take(12)
            .toList()
    }

    private fun extractIdentifiers(text: String): List<String> {
        return Regex("[A-Za-z0-9_./:-]{3,}")
            .findAll(text)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)
            .toList()
    }

    private fun extractErrors(text: String): List<String> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.contains("error", ignoreCase = true) ||
                    line.contains("exception", ignoreCase = true) ||
                    line.contains("failed", ignoreCase = true)
            }
            .distinct()
            .take(8)
            .toList()
    }

    private fun extractPathsOrUrls(text: String): List<String> {
        val matches = mutableListOf<String>()
        Regex("""https?://\S+|[A-Za-z]:\\[^\s]+|/[\w./-]+""")
            .findAll(text)
            .forEach { matches += it.value }
        return matches.distinct().take(12)
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
    ): List<SourcePreviewChunk> {
        return buildSourcePreviewChunks(
            messages = collectIndexableSourceMessages(conversation)
        ).map { chunk ->
            SourcePreviewChunk(
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

    private suspend fun recallMemory(
        assistantId: Uuid,
        query: String,
        channel: String,
        role: String,
    ): RecallMemoryResult {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return RecallMemoryResult(
                query = query,
                channel = channel,
                role = role,
                returnedCount = 0,
                candidateConversationIds = emptyList(),
                chunks = emptyList()
            )
        }

        val indexedChunks = memoryIndexRepository.getChunksOfAssistant(assistantId)
        if (indexedChunks.isEmpty()) {
            return RecallMemoryResult(
                query = query,
                channel = channel,
                role = role,
                returnedCount = 0,
                candidateConversationIds = emptyList(),
                chunks = emptyList()
            )
        }

        val settings = settingsStore.settingsFlow.first()
        val embeddingModel = settings.getEmbeddingModel()
            ?: return RecallMemoryResult(
                query = query,
                channel = channel,
                role = role,
                returnedCount = 0,
                candidateConversationIds = emptyList(),
                chunks = emptyList()
            )
        val provider = embeddingModel.findProvider(settings.providers)
            ?: return RecallMemoryResult(
                query = query,
                channel = channel,
                role = role,
                returnedCount = 0,
                candidateConversationIds = emptyList(),
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
            ?: return RecallMemoryResult(
                query = query,
                channel = channel,
                role = role,
                returnedCount = 0,
                candidateConversationIds = emptyList(),
                chunks = emptyList()
            )

        val retrievalChunks = indexedChunks.map { indexed ->
            me.rerere.rikkahub.data.memory.MemorySummaryChunk(
                sectionKey = indexed.chunk.sectionKey,
                chunkOrder = indexed.chunk.chunkOrder,
                content = buildString {
                    if (indexed.conversationTitle.isNotBlank()) {
                        appendLine(indexed.conversationTitle)
                    }
                    append(indexed.chunk.content)
                },
                tokenEstimate = indexed.chunk.tokenEstimate,
                metadata = indexed.chunk.metadata
            )
        }
        val ranked = if (indexedChunks.any { it.chunk.embedding.isNotEmpty() }) {
            rankMemoryChunks(
                query = normalizedQuery,
                chunks = retrievalChunks,
                documentEmbeddings = indexedChunks.map { it.chunk.embedding },
                queryEmbedding = queryEmbedding,
                channel = channel,
                role = role,
                bm25TopK = RECALL_BM25_TOP_K,
                vectorTopK = RECALL_VECTOR_RERANK_K
            )
        } else {
            Log.i(
                TAG,
                "recallMemory: running vector search for assistant=$assistantId chunks=${indexedChunks.size} dimension=${queryEmbedding.size}"
            )
            val vectorDistances = memoryIndexRepository.searchVectorDistances(
                candidateChunkIds = indexedChunks.map { it.chunk.id },
                queryEmbedding = queryEmbedding,
                limit = RECALL_VECTOR_RERANK_K,
            )
            Log.i(
                TAG,
                "recallMemory: vector search returned ${vectorDistances.size} hits for assistant=$assistantId"
            )
            val vectorScoresByIndex = indexedChunks.mapIndexedNotNull { index, item ->
                vectorDistances[item.chunk.id]?.let { distance ->
                    index to (1.0 / (1.0 + distance.coerceAtLeast(0.0)))
                }
            }.toMap()
            rankMemoryChunksByVectorScores(
                query = normalizedQuery,
                chunks = retrievalChunks,
                vectorScoresByIndex = vectorScoresByIndex,
                channel = channel,
                role = role,
                bm25TopK = RECALL_BM25_TOP_K,
                vectorTopK = RECALL_VECTOR_RERANK_K
            )
        }.map { score ->
            val indexed = indexedChunks[score.docIndex]
            RecallMemoryChunk(
                chunkId = indexed.chunk.id,
                assistantId = indexed.chunk.assistantId,
                conversationId = indexed.chunk.conversationId,
                conversationTitle = indexed.conversationTitle,
                sectionKey = indexed.chunk.sectionKey,
                content = indexed.chunk.content,
                lane = indexed.chunk.metadata.lane,
                status = indexed.chunk.metadata.status,
                tags = indexed.chunk.metadata.tags,
                entityKeys = indexed.chunk.metadata.entityKeys,
                timeRef = indexed.chunk.metadata.timeRef,
                bm25Score = score.bm25Score,
                vectorScore = score.vectorScore,
                finalScore = score.finalScore,
                tokenEstimate = indexed.chunk.tokenEstimate,
                updatedAt = indexed.chunk.updatedAt
            )
        }

        val selected = buildList {
            var usedTokens = 0
            ranked.forEach { chunk ->
                if (size >= RECALL_MAX_RETURN_CHUNKS) return@forEach
                val nextTokens = chunk.tokenEstimate.coerceAtLeast(1)
                if (usedTokens + nextTokens > RECALL_MAX_INJECT_TOKENS) return@forEach
                add(chunk)
                usedTokens += nextTokens
            }
        }

        val candidateConversationIds = ranked
            .map { it.conversationId }
            .distinct()
            .take(6)

        return RecallMemoryResult(
            query = query,
            channel = channel,
            role = role,
            returnedCount = selected.size,
            candidateConversationIds = candidateConversationIds,
            chunks = selected
        )
    }

    private suspend fun searchSource(
        assistantId: Uuid,
        query: String,
        role: String,
        candidateConversationIds: List<String>,
    ): SearchSourceResult {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return SearchSourceResult(
                query = query,
                role = role,
                returnedCount = 0,
                usedFallbackScope = false,
                candidates = emptyList()
            )
        }

        val scopedConversationIds = candidateConversationIds.mapNotNull {
            runCatching { Uuid.parse(it) }.getOrNull()
        }
        val scopedChunks = if (scopedConversationIds.isNotEmpty()) {
            sourcePreviewRepository.getChunksOfConversations(assistantId, scopedConversationIds)
        } else {
            emptyList()
        }
        var ranked = rankSourcePreviewChunks(
            query = normalizedQuery,
            chunks = scopedChunks,
            role = role,
            candidateConversationIds = candidateConversationIds.toSet(),
            usedFallbackScope = false
        )
        var usedFallbackScope = false
        if (ranked.isEmpty() || isWeakSourceResult(ranked.firstOrNull())) {
            val allChunks = sourcePreviewRepository.getChunksOfAssistant(assistantId)
            ranked = rankSourcePreviewChunks(
                query = normalizedQuery,
                chunks = allChunks,
                role = role,
                candidateConversationIds = candidateConversationIds.toSet(),
                usedFallbackScope = true
            )
            usedFallbackScope = true
        }

        val allChunksByIndex = if (usedFallbackScope || scopedChunks.isEmpty()) {
            sourcePreviewRepository.getChunksOfAssistant(assistantId)
        } else {
            scopedChunks
        }
        val candidates = ranked
            .mapNotNull { score ->
                val chunk = allChunksByIndex.getOrNull(score.chunkIndex) ?: return@mapNotNull null
                SearchSourceCandidate(
                    sourceRef = sourceRef(
                        conversationId = chunk.conversationId.toString(),
                        messageId = chunk.messageId.toString()
                    ),
                    conversationId = chunk.conversationId,
                    messageId = chunk.messageId,
                    role = chunk.role,
                    prefix = chunk.prefixText,
                    hitSnippet = score.matchedSnippet,
                    score = score.score,
                    usedFallbackScope = score.usedFallbackScope
                )
            }
            .distinctBy { it.sourceRef }
            .take(SOURCE_PREVIEW_MAX_RESULTS)

        return SearchSourceResult(
            query = query,
            role = role,
            returnedCount = candidates.size,
            usedFallbackScope = usedFallbackScope,
            candidates = candidates
        )
    }

    private suspend fun readSource(
        assistantId: Uuid,
        sourceRef: String,
    ): ReadSourceResult {
        val parsed = parseSourceRef(sourceRef)
            ?: throw IllegalArgumentException("Invalid source_ref")
        val conversationId = Uuid.parse(parsed.conversationId)
        val messageId = Uuid.parse(parsed.messageId)
        val conversation = conversationRepo.getConversationById(conversationId)
            ?: throw IllegalStateException("Conversation not found")
        if (conversation.assistantId != assistantId) {
            throw IllegalStateException("Source does not belong to current assistant")
        }
        val message = conversation.messageNodes
            .flatMap { it.messages }
            .firstOrNull { it.id == messageId && (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) }
            ?: throw IllegalStateException("Message not found")
        return ReadSourceResult(
            sourceRef = sourceRef,
            conversationId = conversationId,
            messageId = messageId,
            role = message.role.name.lowercase(),
            createdAt = message.createdAtInstant(),
            content = message.toSourceText()
        )
    }

    private fun normalizeCompressionState(conversation: Conversation): Conversation {
        return persistenceService.normalizeCompressionState(conversation)
    }

    private fun mergeMissingCompressionArtifacts(
        base: Conversation,
        fallback: Conversation,
    ): Conversation {
        return artifactService.mergeMissingCompressionArtifacts(base, fallback)
    }

    private fun warmCompressionArtifactsAsync(conversationId: Uuid, conversation: Conversation) {
        artifactService.warmCompressionArtifactsAsync(conversationId, conversation)
    }

    private suspend fun hydrateCompressionPayload(
        conversationId: Uuid,
        conversation: Conversation,
    ): Conversation {
        return artifactService.hydrateCompressionPayload(conversationId, conversation)
    }

    private suspend fun getConversationWithCompressionArtifacts(conversationId: Uuid): Conversation? {
        return artifactService.getConversationWithCompressionArtifacts(conversationId)
    }

    private fun estimatePromptTokenUsage(
        conversation: Conversation,
        charsPerToken: Float,
    ): Int {
        val compressedUntil = conversation.compressionState.lastCompressedMessageIndex
            .coerceAtMost(conversation.currentMessages.lastIndex)
        val activeMessages = if (conversation.compressionState.hasSummary && compressedUntil >= 0) {
            conversation.currentMessages.drop(compressedUntil + 1)
        } else {
            conversation.currentMessages
        }
        val estimatedChars = estimatePromptCharCount(
            messages = activeMessages,
            dialogueSummaryText = conversation.compressionState.dialogueSummaryText,
            legacyRollingSummaryJson = conversation.compressionState.rollingSummaryJson,
        )
        val ratio = charsPerToken.coerceIn(2.0f, 8.0f).toDouble()
        return (estimatedChars / ratio).toInt().coerceAtLeast(1)
    }

    private fun estimatePromptCharCount(
        messages: List<UIMessage>,
        dialogueSummaryText: String,
        legacyRollingSummaryJson: String,
    ): Int {
        val messageChars = messages.sumOf { message ->
            message.toCompressionText().length
        }
        val summaryChars = when {
            dialogueSummaryText.isNotBlank() -> dialogueSummaryText.length
            legacyRollingSummaryJson.isBlank() -> 0
            else -> parseRollingSummaryDocument(legacyRollingSummaryJson).toCurrentViewProjection().length
        }
        return messageChars + summaryChars
    }

    private fun calibrateTokenEstimator(
        promptChars: Int,
        actualPromptTokens: Int,
    ) {
        if (promptChars <= 0 || actualPromptTokens <= 0) return
        val observed = promptChars.toFloat() / actualPromptTokens.toFloat()
        val old = settingsStore.settingsFlow.value.tokenEstimatorCharsPerToken
        val updated = (old * 0.8f + observed * 0.2f).coerceIn(2.0f, 8.0f)
        appScope.launch {
            settingsStore.update {
                it.copy(tokenEstimatorCharsPerToken = updated)
            }
        }
    }

    // ---- 通知 ----

    private fun sendGenerationDoneNotification(conversationId: Uuid, senderName: String) {
        noticeService.sendGenerationDoneNotification(conversationId, senderName)
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 10000
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String
    ) {
        noticeService.sendLiveUpdateNotification(conversationId, messages, senderName)
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        // 检查最近的 part 来确定状态
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            // 正在执行工具
            lastTool != null && !lastTool.isExecuted -> {
                val toolName = lastTool.toolName.removePrefix("mcp__")
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    lastTool.input.take(100)
                )
            }
            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }
            // 正在写回复
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }
            // 默认状态
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        noticeService.cancelLiveUpdateNotification(conversationId)
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        runtimeService.updateConversation(conversationId, conversation)
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        runtimeService.updateConversationState(conversationId, update)
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        persistenceService.saveConversation(conversationId, conversation)
        noticeService.recordConversationSave(conversationId, conversation, startedAt)
    }

    private suspend fun saveConversationMetadata(conversationId: Uuid, conversation: Conversation) {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        persistenceService.saveConversationMetadata(conversationId, conversation)
        noticeService.recordConversationMetadataSave(conversationId, conversation, startedAt)
    }

    private inline fun <T> traceDiagnostic(
        category: String,
        detail: String,
        conversationId: Uuid,
        phase: String? = null,
        sizeHint: String? = null,
        block: () -> T,
    ): T {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        return block().also {
            diagnosticsRecorder.record(
                category = category,
                detail = detail,
                conversationId = conversationId,
                costMs = elapsedMillisSince(startedAt),
                phase = phase,
                sizeHint = sizeHint,
            )
        }
    }

    private fun buildConversationSizeHint(conversation: Conversation): String {
        return noticeService.buildConversationSizeHint(conversation)
    }

    private fun buildChunkSizeHint(messages: List<UIMessage>, startIndex: Int): String {
        return noticeService.buildChunkSizeHint(messages, startIndex)
    }

    private fun estimateMessageChars(message: UIMessage?): Int {
        return message?.parts?.sumOf(::estimatePartChars) ?: 0
    }

    private fun estimatePartChars(part: UIMessagePart): Int = when (part) {
        is UIMessagePart.Text -> part.text.length
        is UIMessagePart.Document -> part.fileName.length + part.url.length
        is UIMessagePart.Image -> part.url.length
        is UIMessagePart.Tool -> part.output.sumOf(::estimatePartChars)
        else -> part.toString().length
    }

    private fun elapsedMillisSince(startedAtNs: Long): Long {
        return ((SystemClock.elapsedRealtimeNanos() - startedAtNs) / 1_000_000L).coerceAtLeast(0L)
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        mutationService.translateMessage(conversationId, message, targetLanguage)
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return
        val processedParts = preprocessUserInputParts(parts)
        mutationService.editMessage(conversationId, messageId, processedParts)
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation = mutationService.forkConversationAtMessage(conversationId, messageId)

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) = mutationService.selectMessageNode(conversationId, nodeId, selectIndex)

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) = mutationService.deleteMessage(conversationId, messageId, failIfMissing)

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) = mutationService.deleteMessage(conversationId, message)

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        mutationService.clearTranslationField(conversationId, messageId)
    }

    // 停止当前会话生成任务（不清理会话缓存）
    fun stopGeneration(conversationId: Uuid) {
        runtimeService.cancelGenerationJob(conversationId)
    }
}
