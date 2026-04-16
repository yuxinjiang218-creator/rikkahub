package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.ConversationMessageSearchResult
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.CompressionRegenerationTarget
import me.rerere.rikkahub.service.CompressionUiState
import me.rerere.rikkahub.service.LedgerGenerationUiState
import me.rerere.rikkahub.service.StreamingTailState
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

private data class ChatInputMetaState(
    val workflowEnabled: Boolean,
    val currentChatModel: Model?,
    val enableWebSearch: Boolean,
)

private data class ChatInputRuntimeState(
    val loading: Boolean,
    val compressionUiState: CompressionUiState?,
    val showLedgerGenerationDialog: Boolean,
)

private const val PREVIEW_SEARCH_LIMIT = 50

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    val stableConversation: StateFlow<Conversation> = chatService.getConversationStableFlow(_conversationId)
    val stableMessageNodes: StateFlow<List<MessageNode>> = chatService.getMessageNodesFlow(_conversationId)
    val streamingTail: StateFlow<StreamingTailState?> = chatService.getStreamingTailFlow(_conversationId)
    val streamingUiTick: StateFlow<Long> = chatService.getStreamingUiTickFlow(_conversationId)
    private val _messageWindowState = MutableStateFlow(ChatMessageWindowState())
    val messageWindowState: StateFlow<ChatMessageWindowState> = _messageWindowState.asStateFlow()
    private val _previewSearchResults = MutableStateFlow<List<ConversationPreviewSearchResult>>(emptyList())
    val previewSearchResults: StateFlow<List<ConversationPreviewSearchResult>> = _previewSearchResults.asStateFlow()
    private var previewSearchJob: Job? = null
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已完成初始滚动

    // 聊天输入状态，保存在 ViewModel 中以避免 TransactionTooLargeException
    val inputState = ChatInputState()

    // 异步任务（从 ChatService 获取，响应式）
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }

        viewModelScope.launch {
            stableMessageNodes.collect { nodes ->
                _messageWindowState.update { current ->
                    syncChatMessageWindowWithNodes(current, nodes)
                }
            }
        }

        // 记住对话 ID，方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索
    val enableWebSearch = settings.map {
        it.enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 聊天列表（使用 Paging 分页加载）
    val conversations: Flow<PagingData<ConversationListItem>> =
        settings.map { it.assistantId }.distinctUntilChanged()
            .flatMapLatest { assistantId ->
                conversationRepo.getConversationsOfAssistantPaging(assistantId)
            }
            .map { pagingData ->
                pagingData
                    .map { ConversationListItem.Item(it) }
                    .insertSeparators { before, after ->
                        when {
                            // 列表开头：检查第一项是否置顶
                            before == null && after is ConversationListItem.Item -> {
                                if (after.conversation.isPinned) {
                                    ConversationListItem.PinnedHeader
                                } else {
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                }
                            }

                            // 中间项：检查置顶状态和日期是否变化
                            before is ConversationListItem.Item && after is ConversationListItem.Item -> {
                                // 从置顶切换到非置顶时，显示日期头部
                                if (before.conversation.isPinned && !after.conversation.isPinned) {
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                }
                                // 对于非置顶项，检查日期是否变化
                                else if (!after.conversation.isPinned) {
                                    val beforeDate = before.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()

                                    if (beforeDate != afterDate) {
                                        ConversationListItem.DateHeader(
                                            date = afterDate,
                                            label = getDateLabel(afterDate)
                                        )
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                            }

                            else -> null
                        }
                    }
            }
            .cachedIn(viewModelScope)

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors
    val compressionUiState: StateFlow<CompressionUiState?> =
        chatService.getCompressionUiStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val ledgerGenerationUiState: StateFlow<LedgerGenerationUiState?> =
        chatService.getLedgerGenerationUiStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val compressionScrollEvents: SharedFlow<Pair<Uuid, Long>> = chatService.compressionScrollEvents
    private val workflowEnabledState: StateFlow<Boolean> = combine(stableConversation, settings) { conversation, settings ->
        val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
        assistant.localTools.contains(LocalToolOption.WorkflowControl)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val chatChromeState: StateFlow<ChatChromeUiState> = combine(
        stableConversation,
        settings,
        workflowEnabledState,
    ) { conversation, settings, workflowEnabled ->
        buildChatChromeUiState(
            conversation = conversation,
            settings = settings,
            workflowEnabled = workflowEnabled,
            defaultAssistantLabel = context.getString(R.string.assistant_page_default_assistant),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatChromeUiState())
    private val chatInputMetaState: StateFlow<ChatInputMetaState> = combine(
        workflowEnabledState,
        currentChatModel,
        enableWebSearch,
    ) { workflowEnabled, currentChatModel, enableWebSearch ->
        ChatInputMetaState(
            workflowEnabled = workflowEnabled,
            currentChatModel = currentChatModel,
            enableWebSearch = enableWebSearch,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ChatInputMetaState(
            workflowEnabled = false,
            currentChatModel = null,
            enableWebSearch = false,
        )
    )
    private val chatInputRuntimeState: StateFlow<ChatInputRuntimeState> = combine(
        conversationJob,
        compressionUiState,
        ledgerGenerationUiState,
    ) { loadingJob, compressionUiState, ledgerGenerationUiState ->
        ChatInputRuntimeState(
            loading = loadingJob != null,
            compressionUiState = compressionUiState,
            showLedgerGenerationDialog = ledgerGenerationUiState != null,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ChatInputRuntimeState(
            loading = false,
            compressionUiState = null,
            showLedgerGenerationDialog = false,
        )
    )
    val chatInputUiState: StateFlow<ChatInputUiState> = combine(
        stableConversation,
        chatInputMetaState,
        chatInputRuntimeState,
    ) { conversation, metaState, runtimeState ->
        buildChatInputUiState(
            conversation = conversation,
            workflowEnabled = metaState.workflowEnabled,
            currentChatModel = metaState.currentChatModel,
            loading = runtimeState.loading,
            enableWebSearch = metaState.enableWebSearch,
            compressionUiState = runtimeState.compressionUiState,
            showLedgerGenerationDialog = runtimeState.showLedgerGenerationDialog,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatInputUiState())
    val chatTimelineUiState: StateFlow<ChatTimelineUiState> = combine(
        stableConversation,
        settings,
        messageWindowState,
        previewSearchResults,
    ) { conversation, settings, messageWindowState, previewSearchResults ->
        buildChatTimelineUiState(
            conversation = conversation,
            settings = settings,
            windowState = messageWindowState,
            previewSearchResults = previewSearchResults,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatTimelineUiState())
    val chatStreamingTailUiState: StateFlow<ChatStreamingTailUiState> = combine(
        stableConversation,
        settings,
        messageWindowState,
        streamingTail,
    ) { conversation, settings, messageWindowState, streamingTail ->
        buildChatStreamingTailUiState(
            conversation = conversation,
            settings = settings,
            windowState = messageWindowState,
            streamingTail = streamingTail,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatStreamingTailUiState())

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    suspend fun loadOlderMessages() {
        val current = _messageWindowState.value
        if (current.isLoadingOlder || !current.hasOlder) return

        val newStartIndex = (current.loadedStartIndex - CHAT_OLDER_LOAD_BATCH_SIZE).coerceAtLeast(0)
        val newLimit = current.loadedStableNodes.size + (current.loadedStartIndex - newStartIndex)
        recordUiDiagnostic(
            category = "older-load-start",
            detail = "currentStart=${current.loadedStartIndex} requestStart=$newStartIndex limit=$newLimit loaded=${current.loadedStableNodes.size}",
            phase = "start",
        )
        _messageWindowState.update { it.copy(isLoadingOlder = true) }
        try {
            val loadResult = conversationRepo.loadConversationNodesRange(
                conversationId = _conversationId,
                startIndex = newStartIndex,
                limit = newLimit,
            )
            if (newLimit > 0 && loadResult.nodes.isEmpty()) {
                error("Older message load returned no nodes for requestStart=$newStartIndex limit=$newLimit")
            }
            _messageWindowState.value = current.copy(
                totalStableCount = stableMessageNodes.value.size,
                loadedStartIndex = newStartIndex,
                loadedStableNodes = loadResult.nodes,
                hasOlder = newStartIndex > 0,
                isLoadingOlder = false,
                initialized = true,
            )
            recordUiDiagnostic(
                category = "older-load-success",
                detail = "requestStart=$newStartIndex limit=$newLimit returned=${loadResult.nodes.size} fallback=${loadResult.fallbackUsed} skipped=${loadResult.skippedNodeCount}",
                phase = "success",
            )
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            recordUiDiagnostic(
                category = "older-load-failure",
                detail = "requestStart=$newStartIndex limit=$newLimit error=${error::class.simpleName ?: "unknown"}",
                phase = "failure",
            )
            chatService.addError(
                error = error,
                conversationId = _conversationId,
                title = "加载旧消息失败",
            )
        } finally {
            _messageWindowState.update { state ->
                if (state.isLoadingOlder) {
                    state.copy(isLoadingOlder = false)
                } else {
                    state
                }
            }
        }
    }

    suspend fun ensureMessageIndexVisible(globalIndex: Int): Int {
        val totalCount = stableMessageNodes.value.size
        if (totalCount <= 0) return 0
        val normalizedIndex = globalIndex.coerceIn(0, totalCount - 1)
        val current = _messageWindowState.value
        val currentEnd = current.loadedStartIndex + current.loadedStableNodes.size
        if (normalizedIndex in current.loadedStartIndex until currentEnd) {
            return normalizedIndex - current.loadedStartIndex
        }

        val targetWindowSize = maxOf(
            CHAT_INITIAL_WINDOW_SIZE,
            minOf(totalCount, current.loadedStableNodes.size.coerceAtLeast(CHAT_INITIAL_WINDOW_SIZE))
        )
        val startIndex = computeFocusedWindowStart(
            totalCount = totalCount,
            targetIndex = normalizedIndex,
            windowSize = targetWindowSize,
        )
        val loadResult = conversationRepo.loadConversationNodesRange(
            conversationId = _conversationId,
            startIndex = startIndex,
            limit = targetWindowSize,
        )
        val loadedNodes = loadResult.nodes
        _messageWindowState.value = current.copy(
            totalStableCount = totalCount,
            loadedStartIndex = startIndex,
            loadedStableNodes = loadedNodes,
            hasOlder = startIndex > 0,
            isLoadingOlder = false,
            initialized = true,
        )
        return normalizedIndex - startIndex
    }

    suspend fun ensureNodeVisible(nodeId: Uuid): Int? {
        val globalIndex = conversationRepo.findNodeIndex(_conversationId, nodeId) ?: return null
        return ensureMessageIndexVisible(globalIndex)
    }

    fun searchPreviewMessages(query: String) {
        previewSearchJob?.cancel()
        if (query.isBlank()) {
            _previewSearchResults.value = emptyList()
            return
        }

        previewSearchJob = viewModelScope.launch {
            val results = conversationRepo.searchConversationMessages(
                conversationId = _conversationId,
                query = query,
                limit = PREVIEW_SEARCH_LIMIT,
            )
            _previewSearchResults.value = results.map(ConversationMessageSearchResult::toPreviewSearchResult)
        }
    }

    fun recordUiDiagnostic(category: String, detail: String, phase: String? = null) {
        chatService.recordUiDiagnostic(
            category = category,
            conversationId = _conversationId,
            detail = detail,
            phase = phase,
        )
    }

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP 管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings) {
        viewModelScope.launch {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    // 检查是否需要删除旧用户头像
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    // Update checker
    val updateState =
        updateChecker.checkUpdate().stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成；为 false 时只把消息加入消息列表
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        chatService.sendMessage(_conversationId, content, answer)
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleMessageTruncate() {
        viewModelScope.launch {

            // 清空截断后的标题和建议
            val newConversation = conversation.value.copy(

                title = "",
                chatSuggestions = emptyList(), // 清空建议
            )
            chatService.saveConversation(conversationId = _conversationId, conversation = newConversation)
        }
    }

    fun handleCompressContext(
        additionalPrompt: String,
        keepRecentMessages: Int,
        autoCompressEnabled: Boolean,
        autoCompressTriggerTokens: Int,
        generateMemoryLedger: Boolean,
    ): Job {
        return viewModelScope.launch {
            settingsStore.update {
                it.copy(
                    autoCompressEnabled = autoCompressEnabled,
                    autoCompressTriggerTokens = autoCompressTriggerTokens,
                    manualCompressKeepRecentMessages = keepRecentMessages,
                    manualCompressGenerateMemoryLedger = generateMemoryLedger,
                )
            }
            chatService.compressConversation(
                _conversationId,
                conversation.value,
                additionalPrompt,
                keepRecentMessages,
                generateMemoryLedger,
            ).onFailure {
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("Please stop generation before deleting messages"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun editLatestDialogueSummary(summaryText: String) {
        viewModelScope.launch {
            chatService.editLatestDialogueSummary(_conversationId, summaryText).onFailure {
                chatService.addError(
                    it,
                    conversationId = _conversationId,
                    title = context.getString(R.string.error_title_operation)
                )
            }
        }
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }
    }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            val updatedConversation = conversationFull.copy(assistantId = targetAssistantId)
            if (conversation.id == _conversationId) {
                chatService.saveConversation(_conversationId, updatedConversation)
                settingsStore.updateAssistant(targetAssistantId)
            } else {
                conversationRepo.updateConversation(updatedConversation)
            }
        }
    }

    fun generateMemoryIndex(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateMemoryIndex(conversation.id).onFailure {
                chatService.addError(
                    it,
                    conversationId = conversation.id,
                    title = context.getString(R.string.error_title_generate_memory_index)
                )
            }
        }
    }

    fun regenerateLatestCompression(
        target: CompressionRegenerationTarget = CompressionRegenerationTarget.DialogueSummary,
    ) {
        viewModelScope.launch {
            chatService.regenerateLatestCompression(_conversationId, target).onFailure {
                chatService.addError(
                    it,
                    conversationId = _conversationId,
                    title = context.getString(R.string.error_title_compress_conversation)
                )
            }
        }
    }

    fun cancelCompressionWork() {
        chatService.cancelCompressionWork(_conversationId)
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun updateMessageNode(newNode: MessageNode) {
        chatService.updateConversationState(_conversationId) { currentConversation ->
            currentConversation.copy(
                messageNodes = currentConversation.messageNodes.map { existingNode ->
                    if (existingNode.id == newNode.id) {
                        newNode
                    } else {
                        existingNode
                    }
                }
            )
        }
        saveConversationAsync()
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

    fun updateWorkflowPhase(phase: me.rerere.rikkahub.data.model.WorkflowPhase) {
        viewModelScope.launch {
            val currentConversation = conversation.value
            val currentState = currentConversation.workflowState
            if (currentState != null) {
                val newState = currentState.copy(phase = phase)
                val updatedConversation = currentConversation.copy(workflowState = newState)
                chatService.saveConversation(_conversationId, updatedConversation)
            }
        }
    }

    fun initializeWorkflowState() {
        viewModelScope.launch {
            val currentConversation = conversation.value
            if (currentConversation.workflowState == null) {
                val newWorkflowState = me.rerere.rikkahub.data.model.WorkflowState()
                val updatedConversation = currentConversation.copy(workflowState = newWorkflowState)
                chatService.saveConversation(_conversationId, updatedConversation)
            }
        }
    }

    fun disableWorkflowState() {
        viewModelScope.launch {
            val currentConversation = conversation.value
            if (currentConversation.workflowState != null) {
                val updatedConversation = currentConversation.copy(workflowState = null)
                chatService.saveConversation(_conversationId, updatedConversation)
            }
        }
    }

    private fun getDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)

        return when (date) {
            today -> context.getString(R.string.chat_page_today)
            yesterday -> context.getString(R.string.chat_page_yesterday)
            else -> date.toLocalString(date.year != today.year)
        }
    }
}

private fun ConversationMessageSearchResult.toPreviewSearchResult(): ConversationPreviewSearchResult {
    return ConversationPreviewSearchResult(
        nodeId = nodeId,
        messageId = messageId,
        globalIndex = globalIndex,
        message = message,
        snippet = snippet,
    )
}
