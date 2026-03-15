package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.buildReadSourceTool
import me.rerere.rikkahub.data.ai.tools.buildRecallMemoryTool
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
import me.rerere.rikkahub.data.memory.rankSourcePreviewChunks
import me.rerere.rikkahub.data.memory.sourceRef
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MemoryIndexChunk
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
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryIndexRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.SourcePreviewRepository
import me.rerere.rikkahub.data.skills.SkillsRepository
import me.rerere.rikkahub.data.skills.buildSkillsCatalogPrompt
import me.rerere.rikkahub.sandbox.SandboxEngine
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
    )
}

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
    private val sourcePreviewRepository: SourcePreviewRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val skillsRepository: SkillsRepository,
) {
    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    private val _compressionUiStates = MutableStateFlow<Map<Uuid, CompressionUiState>>(emptyMap())
    private val _compressionScrollEvents = MutableSharedFlow<Pair<Uuid, Long>>(extraBufferCapacity = 8)
    val compressionScrollEvents: SharedFlow<Pair<Uuid, Long>> = _compressionScrollEvents.asSharedFlow()

    fun addError(error: Throwable, conversationId: Uuid? = null, title: String? = null) {
        if (error is CancellationException) return
        _errors.update { it + ChatError(title = title, error = error, conversationId = conversationId) }
    }

    fun addSuccessNotice(message: String, conversationId: Uuid? = null, title: String? = null) {
        _errors.update {
            it + ChatError(
                title = title,
                error = IllegalStateException(message),
                conversationId = conversationId,
                kind = ChatNoticeKind.SUCCESS,
            )
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
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
        val skillPrompt = buildSkillsCatalogPrompt(
            assistant = effectiveAssistant,
            model = model,
            catalog = skillsRepository.state.value,
        )
        val assistantForGeneration = if (skillPrompt.isNullOrBlank()) {
            effectiveAssistant
        } else {
            effectiveAssistant.copy(
                systemPrompt = listOf(
                    effectiveAssistant.systemPrompt.trim(),
                    skillPrompt.trim()
                ).filter { it.isNotBlank() }.joinToString("\n\n")
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
                addAll(
                    localTools.getTools(
                        options = assistantForGeneration.localTools,
                        sandboxId = sandboxId,
                        enabledSkills = assistantForGeneration.enabledSkills,
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
                is GenerationChunk.Messages -> {
                    generatedMessages = chunk.messages
                }
            }
        }

        val finalMessage = generatedMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: throw IllegalStateException("Scheduled task did not generate an assistant reply")
        val replyText = finalMessage.toText()?.trim().orEmpty()
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
        return _compressionUiStates.map { it[conversationId] }
    }

    private fun updateCompressionUiState(conversationId: Uuid, state: CompressionUiState?) {
        _compressionUiStates.update { current ->
            if (state == null) current - conversationId else current + (conversationId to state)
        }
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
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
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

        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()
        val processedContent = preprocessUserInputParts(content)

        val job = appScope.launch {
            try {
                val currentConversation = session.state.value

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
        session.setJob(job)
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
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value

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

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value
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

        session.setJob(job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.getCurrentChatModel() ?: return
        var promptCharsForCalibration = 0

        runCatching {
            var conversation = getConversationFlow(conversationId).value
            val assistant = settings.getCurrentAssistant()

            // reset suggestions
            updateConversation(conversationId, conversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                val toolRequired = settings.enableWebSearch ||
                    assistant.enableMemory ||
                    assistant.enableRecentChatsReference ||
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
                            trigger = "auto-threshold"
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
            val rollingSummaryJsonForGeneration: String
            if (messageRange != null) {
                generationWriteBackStartIndex = messageRange.start
                messagesForGeneration = conversation.currentMessages
                    .subList(messageRange.start, messageRange.endInclusive + 1)
                rollingSummaryJsonForGeneration = ""
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
                rollingSummaryJsonForGeneration = conversation.compressionState.rollingSummaryJson
            }
            promptCharsForCalibration = estimatePromptCharCount(
                messages = messagesForGeneration,
                rollingSummaryJson = rollingSummaryJsonForGeneration
            )

            if (assistant.localTools.contains(LocalToolOption.Container)) {
                skillsRepository.refresh()
            }
            val skillPrompt = buildSkillsCatalogPrompt(
                assistant = assistant,
                model = model,
                catalog = skillsRepository.state.value,
            )
            val assistantForGeneration = if (skillPrompt.isNullOrBlank()) {
                assistant
            } else {
                assistant.copy(
                    systemPrompt = listOf(
                        assistant.systemPrompt.trim(),
                        skillPrompt.trim(),
                    ).filter { it.isNotBlank() }.joinToString("\n\n")
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
                rollingSummaryJson = rollingSummaryJsonForGeneration,
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
                    addAll(
                        localTools.getTools(
                            options = assistantForGeneration.localTools,
                            sandboxId = conversationId,
                            enabledSkills = assistantForGeneration.enabledSkills,
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

                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // Show notification if app is not in foreground
                if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) {
                    sendGenerationDoneNotification(conversationId)
                }
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(
                                messages = chunk.messages,
                                startIndex = generationWriteBackStartIndex
                            )
                        updateConversation(conversationId, updatedConversation)

                        // 如果应用不在前台，发送 Live Update 通知
                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                            sendLiveUpdateNotification(conversationId, chunk.messages)
                        }
                    }
                }
            }
        }.onFailure {
            // 取消 Live Update 通知
            cancelLiveUpdateNotification(conversationId)

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)
            calibrateTokenEstimator(
                promptChars = promptCharsForCalibration,
                actualPromptTokens = finalConversation.currentMessages.lastOrNull()?.usage?.promptTokens ?: 0
            )

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
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
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText() })
                    ),
                ),
                params = TextGenerationParams(
                    model = model,
                    thinkingBudget = 0,
                ),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.choices[0].message?.toText()?.trim() ?: "")
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generate_title))
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.suggestionModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText() }),
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    thinkingBudget = 0,
                ),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
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
    ): Result<Unit> = runCatching {
        compressConversationInternal(
            conversationId = conversationId,
            conversation = conversation,
            additionalPrompt = additionalPrompt,
            keepRecentMessages = keepRecentMessages,
            trigger = "manual"
        )
    }

    suspend fun generateMemoryIndex(conversationId: Uuid): Result<Int> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val conversation = conversationRepo.getConversationById(conversationId)
            ?: throw IllegalStateException("Conversation not found")
        try {
            val indexedCount = rebuildConversationIndexes(
                conversationId = conversationId,
                conversation = conversation,
                settings = settings,
            )
            addSuccessNotice(
                message = context.getString(R.string.memory_index_updated),
                conversationId = conversationId,
                title = context.getString(R.string.memory_index_updated_title)
            )
            indexedCount
        } catch (error: Throwable) {
            saveConversation(
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
    }

    suspend fun regenerateLatestCompression(conversationId: Uuid): Result<Unit> = runCatching {
        val conversation = conversationRepo.getConversationById(conversationId)
            ?: throw IllegalStateException("Conversation not found")
        val latestEvent = conversation.compressionEvents.maxByOrNull { it.createdAt }
            ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_no_latest_summary))
        val rebuiltConversation = conversation.copy(
            compressionState = conversation.compressionState.copy(
                rollingSummaryJson = latestEvent.baseSummaryJson.ifBlank { "{}" },
                rollingSummaryTokenEstimate = estimateTokenCount(
                    latestEvent.baseSummaryJson.ifBlank { "{}" },
                    settingsStore.settingsFlow.first().tokenEstimatorCharsPerToken
                ),
                lastCompressedMessageIndex = (latestEvent.compressStartIndex - 1).coerceAtLeast(-1),
                updatedAt = Instant.now()
            ),
            compressionEvents = conversation.compressionEvents.filterNot { it.id == latestEvent.id }
        )
        compressConversationInternal(
            conversationId = conversationId,
            conversation = rebuiltConversation,
            additionalPrompt = latestEvent.additionalPrompt,
            keepRecentMessages = latestEvent.keepRecentMessages,
            trigger = "regenerate",
            baseRollingSummaryJsonOverride = latestEvent.baseSummaryJson.ifBlank { "{}" },
            compressStartIndexOverride = latestEvent.compressStartIndex,
            compressEndIndexOverride = latestEvent.compressEndIndex
        )
    }

    private suspend fun compressConversationInternal(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        keepRecentMessages: Int,
        trigger: String,
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

        val showAutoProgress = trigger == "auto-threshold"
        val showIndexSuccessNotice =
            trigger == "manual" || trigger == "auto-threshold" || trigger == "regenerate"
        if (showAutoProgress) {
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

            val currentRollingSummary = baseRollingSummaryJsonOverride
                ?: conversation.compressionState.rollingSummaryJson
                .ifBlank { "{}" }
            val compressionBudget = calculateCompressionBudget(
                incrementalMessages = incrementalMessages,
                charsPerToken = settings.tokenEstimatorCharsPerToken
            )
            val additionalContext = buildString {
                if (additionalPrompt.isNotBlank()) {
                    append("Additional instructions from user: ")
                    append(additionalPrompt)
                    appendLine()
                }
                append("Compression trigger: ")
                append(trigger)
                appendLine()
                append("Keep recent visible messages outside compression: ")
                append(normalizedKeepRecent)
            }

            fun buildPrompt(extraContext: String = additionalContext): String {
                return settings.compressPrompt.applyPlaceholders(
                    "rolling_summary_json" to currentRollingSummary,
                    "incremental_messages" to incrementalMessages,
                    "incremental_input_tokens" to compressionBudget.incrementalInputTokens.toString(),
                    "min_output_tokens" to compressionBudget.minOutputTokens.toString(),
                    "target_output_tokens" to compressionBudget.targetOutputTokens.toString(),
                    "hard_cap_tokens" to compressionBudget.hardCapTokens.toString(),
                    "min_chronology_items" to compressionBudget.minChronologyItems.toString(),
                    "min_detail_capsules" to compressionBudget.minDetailCapsules.toString(),
                    "additional_context" to extraContext,
                    "locale" to Locale.getDefault().displayName
                )
            }

            suspend fun runCompress(prompt: String): String {
                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = listOf(UIMessage.user(prompt)),
                    params = TextGenerationParams(model = model),
                )
                val summary = result.choices[0].message?.toText()?.trim().orEmpty()
                if (summary.isBlank()) {
                    throw IllegalStateException("Failed to generate compressed summary")
                }
                return normalizeRollingSummaryJson(
                    rawSummary = summary,
                    summaryTurn = compressEndIndex + 1,
                    updatedAt = Instant.now()
                )
            }

            var nextRollingSummaryJson = runCompress(buildPrompt())
            var summaryTokenEstimate = estimateTokenCount(
                text = nextRollingSummaryJson,
                charsPerToken = settings.tokenEstimatorCharsPerToken
            )

            if (summaryTokenEstimate > compressionBudget.hardCapTokens) {
                nextRollingSummaryJson = runCompress(
                    buildPrompt(
                        extraContext = additionalContext +
                            "\nForce convergence: keep required chronology/detail capsule minimums and stay within hard cap."
                    )
                )
                summaryTokenEstimate = estimateTokenCount(
                    text = nextRollingSummaryJson,
                    charsPerToken = settings.tokenEstimatorCharsPerToken
                )
            }

            val boundaryIndex = (compressEndIndex + 1).coerceIn(0, conversation.messageNodes.size)
            val event = conversationRepo.addCompressionEvent(
                conversationId = conversationId,
                boundaryIndex = boundaryIndex,
                summarySnapshot = buildSummarySnapshot(nextRollingSummaryJson),
                compressStartIndex = startIndex,
                compressEndIndex = compressEndIndex,
                keepRecentMessages = normalizedKeepRecent,
                trigger = trigger,
                additionalPrompt = additionalPrompt,
                baseSummaryJson = currentRollingSummary,
            )

            val updatedConversation = conversation.copy(
                compressionState = conversation.compressionState.copy(
                    rollingSummaryJson = nextRollingSummaryJson,
                    rollingSummaryTokenEstimate = summaryTokenEstimate,
                    lastCompressedMessageIndex = compressEndIndex,
                    updatedAt = Instant.now()
                ),
                compressionEvents = (conversation.compressionEvents + event).sortedBy { it.createdAt },
                chatSuggestions = emptyList(),
            )
            saveConversation(conversationId, updatedConversation)
            _compressionScrollEvents.tryEmit(conversationId to event.id)
            try {
                if (showAutoProgress) {
                    updateCompressionUiState(
                        conversationId,
                        CompressionUiState(
                            conversationId = conversationId,
                            trigger = trigger,
                            phase = CompressionUiPhase.Indexing
                        )
                    )
                }
                rebuildConversationIndexes(
                    conversationId = conversationId,
                    conversation = updatedConversation,
                    settings = settings,
                )
                if (showIndexSuccessNotice) {
                    addSuccessNotice(
                        message = context.getString(R.string.memory_index_updated),
                        conversationId = conversationId,
                        title = context.getString(R.string.memory_index_updated_title)
                    )
                }
            } catch (error: Throwable) {
                saveConversation(
                    conversationId,
                    updatedConversation.copy(
                        memoryIndexState = updatedConversation.memoryIndexState.copy(
                            lastIndexStatus = "failed",
                            lastIndexError = error.message.orEmpty()
                        )
                    )
                )
                addError(
                    error = error,
                    conversationId = conversationId,
                    title = context.getString(R.string.error_title_generate_memory_index)
                )
            }
            return getConversationFlow(conversationId).value
        } finally {
            if (showAutoProgress) {
                updateCompressionUiState(conversationId, null)
            }
        }
    }

    private fun buildSummarySnapshot(summaryJson: String): String {
        return parseRollingSummaryDocument(summaryJson)
            .toSummarySnapshot()
            .toJson()
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
        saveConversation(conversationId, refreshed)
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
        val ranked = rankMemoryChunks(
            query = normalizedQuery,
            chunks = retrievalChunks,
            documentEmbeddings = indexedChunks.map { it.chunk.embedding },
            queryEmbedding = queryEmbedding,
            channel = channel,
            role = role,
            bm25TopK = RECALL_BM25_TOP_K,
            vectorTopK = RECALL_VECTOR_RERANK_K
        ).map { score ->
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
        val maxIndex = conversation.messageNodes.lastIndex
        val normalizedCompressedIndex = conversation.compressionState.lastCompressedMessageIndex
            .coerceAtLeast(-1)
            .coerceAtMost(maxIndex)
        val normalizedEvents = conversation.compressionEvents.map { event ->
            event.copy(boundaryIndex = event.boundaryIndex.coerceIn(0, conversation.messageNodes.size))
        }
        return conversation.copy(
            compressionState = conversation.compressionState.copy(
                lastCompressedMessageIndex = normalizedCompressedIndex
            ),
            compressionEvents = normalizedEvents,
        )
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
            rollingSummaryJson = conversation.compressionState.rollingSummaryJson
        )
        val ratio = charsPerToken.coerceIn(2.0f, 8.0f).toDouble()
        return (estimatedChars / ratio).toInt().coerceAtLeast(1)
    }

    private fun estimatePromptCharCount(
        messages: List<UIMessage>,
        rollingSummaryJson: String,
    ): Int {
        val messageChars = messages.sumOf { message ->
            message.toCompressionText().length
        }
        val summaryChars = if (rollingSummaryJson.isBlank()) {
            0
        } else {
            parseRollingSummaryDocument(rollingSummaryJson).toCurrentViewProjection().length
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

    private fun sendGenerationDoneNotification(conversationId: Uuid) {
        // 先取消 Live Update 通知
        cancelLiveUpdateNotification(conversationId)

        val conversation = getConversationFlow(conversationId).value
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = context.getString(R.string.notification_chat_done_title)
            content = conversation.currentMessages.lastOrNull()?.toText()?.take(50) ?: ""
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 10000
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        messages: List<UIMessage>
    ) {
        val lastMessage = messages.lastOrNull() ?: return
        val parts = lastMessage.parts

        // 确定当前状态
        val (chipText, statusText, contentText) = determineNotificationContent(parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId(conversationId)
        ) {
            title = context.getString(R.string.notification_live_update_title)
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
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
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
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
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = normalizeCompressionState(conversation.copy())
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
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

        val currentConversation = getConversationFlow(conversationId).value
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversationId = Uuid.random()
        val forkConversation = Conversation(
            id = forkConversationId,
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
        )

        if (!SandboxEngine.copySandbox(context, conversationId.toString(), forkConversationId.toString())) {
            Log.w(TAG, "forkConversationAtMessage: failed to copy sandbox from $conversationId to $forkConversationId")
        }

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

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
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
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

    // 停止当前会话生成任务（不清理会话缓存）
    fun stopGeneration(conversationId: Uuid) {
        sessions[conversationId]?.getJob()?.cancel()
    }
}
