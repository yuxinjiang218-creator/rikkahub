package me.rerere.rikkahub.service

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.truncate
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.debug.DebugLogger
import me.rerere.rikkahub.debug.model.LogLevel
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.db.dao.ArchiveSummaryDao
import me.rerere.rikkahub.data.db.dao.VectorIndexDao
import me.rerere.rikkahub.data.db.entity.ArchiveSummaryEntity
import me.rerere.rikkahub.data.db.entity.VectorIndexEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.deleteChatFiles
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

data class ChatError(
    val id: Uuid = Uuid.random(),
    val error: Throwable,
    val timestamp: Long = System.currentTimeMillis()
)

private val inputTransformers by lazy {
    listOf(
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        PromptInjectionTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val archiveSummaryDao: ArchiveSummaryDao,
    private val vectorIndexDao: VectorIndexDao,
    private val verbatimRecallService: VerbatimRecallService,
    private val semanticRecallService: SemanticRecallService,
) {
    // 存储每个对话的状态
    private val conversations = ConcurrentHashMap<Uuid, MutableStateFlow<Conversation>>()

    // 记录哪些conversation有VM引用
    private val conversationReferences = ConcurrentHashMap<Uuid, Int>()

    // 存储每个对话的生成任务状态
    private val _generationJobs = MutableStateFlow<Map<Uuid, Job?>>(emptyMap())
    private val generationJobs: StateFlow<Map<Uuid, Job?>> = _generationJobs
        .asStateFlow()

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(error: Throwable) {
        if (error is CancellationException) return
        _errors.update { it + ChatError(error = error) }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
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
        _generationJobs.value.values.forEach { it?.cancel() }
    }

    // 添加引用
    fun addConversationReference(conversationId: Uuid) {
        conversationReferences[conversationId] = conversationReferences.getOrDefault(conversationId, 0) + 1
        Log.d(
            TAG,
            "Added reference for $conversationId (current references: ${conversationReferences[conversationId] ?: 0})"
        )
    }

    // 移除引用
    fun removeConversationReference(conversationId: Uuid) {
        conversationReferences[conversationId]?.let { count ->
            if (count > 1) {
                conversationReferences[conversationId] = count - 1
            } else {
                conversationReferences.remove(conversationId)
            }
        }
        Log.d(
            TAG,
            "Removed reference for $conversationId (current references: ${conversationReferences[conversationId] ?: 0})"
        )
        appScope.launch {
            delay(500)
            checkAllConversationsReferences()
        }
    }

    // 检查是否有引用
    private fun hasReference(conversationId: Uuid): Boolean {
        return conversationReferences.containsKey(conversationId) || _generationJobs.value.containsKey(
            conversationId
        )
    }

    // 检查所有conversation的引用情况（生成结束后调用）
    fun checkAllConversationsReferences() {
        conversations.keys.forEach { conversationId ->
            if (!hasReference(conversationId)) {
                cleanupConversation(conversationId)
            }
        }
    }

    // 获取对话的StateFlow
    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        val settings = settingsStore.settingsFlow.value
        return conversations.getOrPut(conversationId) {
            MutableStateFlow(
                Conversation.ofId(
                    id = conversationId,
                    assistantId = settings.getCurrentAssistant().id
                )
            )
        }
    }

    // 获取生成任务状态流
    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        return generationJobs.map { jobs -> jobs[conversationId] }
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return generationJobs
    }

    private fun setGenerationJob(conversationId: Uuid, job: Job?) {
        if (job == null) {
            removeGenerationJob(conversationId)
            return
        }
        _generationJobs.value = _generationJobs.value.toMutableMap().apply {
            this[conversationId] = job
        }.toMap() // 确保创建新的不可变Map实例
    }

    private fun getGenerationJob(conversationId: Uuid): Job? {
        return _generationJobs.value[conversationId]
    }

    private fun removeGenerationJob(conversationId: Uuid) {
        _generationJobs.value = _generationJobs.value.toMutableMap().apply {
            remove(conversationId)
        }.toMap() // 确保创建新的不可变Map实例
    }

    // 初始化对话
    suspend fun initializeConversation(conversationId: Uuid) {
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

    // 发送消息
    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        // 取消现有的生成任务
        getGenerationJob(conversationId)?.cancel()

        val job = appScope.launch {
            try {
                val currentConversation = getConversationFlow(conversationId).value

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = content,
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
                addError(e)
            }
        }
        setGenerationJob(conversationId, job)
        job.invokeOnCompletion {
            setGenerationJob(conversationId, null)
            // 取消生成任务后，检查是否有其他任务在进行
            appScope.launch {
                delay(500)
                checkAllConversationsReferences()
            }
        }
    }

    // 重新生成消息
    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        getGenerationJob(conversationId)?.cancel()

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
                addError(e)
            }
        }

        setGenerationJob(conversationId, job)
        job.invokeOnCompletion {
            setGenerationJob(conversationId, null)
            // 取消生成任务后，检查是否有其他任务在进行
            appScope.launch {
                delay(500)
                checkAllConversationsReferences()
            }
        }
    }

    // 处理消息补全
    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.getCurrentChatModel() ?: return

        runCatching {
            var conversation = getConversationFlow(conversationId).value

            // 执行上下文压缩（如果需要）
            conversation = performContextCompression(conversation, settings)

            // reset suggestions
            updateConversation(conversationId, conversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (settings.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(IllegalStateException(context.getString(R.string.tools_warning)))
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)

            // 构建消息列表
            val messages = conversation.currentMessages.let {
                if (messageRange != null) {
                    it.subList(messageRange.start, messageRange.endInclusive + 1)
                } else {
                    it
                }
            }

            // start generating
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = messages,
                assistant = settings.getCurrentAssistant(),
                memories = memoryRepository.getMemoriesOfAssistant(settings.assistantId.toString()),
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (settings.enableWebSearch) {
                        addAll(createSearchTool(settings))
                    }
                    addAll(localTools.getTools(settings.getCurrentAssistant().localTools))
                    mcpManager.getAllAvailableTools().forEach { tool ->
                        add(
                            Tool(
                                name = "mcp__" + tool.name,
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                execute = {
                                    mcpManager.callTool(tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                },
                truncateIndex = conversation.truncateIndex,
                conversationSummary = conversation.conversationSummary,
                conversationId = conversationId,
                lastArchiveRecallIds = conversation.lastArchiveRecallIds,
                recallLedgerJson = conversation.recallLedgerJson,
                onRecallLedgerUpdate = { newLedgerJson ->
                    // vNext 召回系统账本更新
                    val currentConversation = getConversationFlow(conversationId).value
                    if (currentConversation.recallLedgerJson != newLedgerJson) {
                        updateConversation(
                            conversationId,
                            currentConversation.copy(recallLedgerJson = newLedgerJson)
                        )
                    }
                },
                onSemanticRecall = { newRecallIds ->
                    // SEMANTIC 召回成功后更新 lastArchiveRecallIds（已废弃，保留兼容性）
                    val currentConversation = getConversationFlow(conversationId).value
                    if (currentConversation.lastArchiveRecallIds != newRecallIds) {
                        updateConversation(
                            conversationId,
                            currentConversation.copy(lastArchiveRecallIds = newRecallIds)
                        )
                    }
                },
            ).onCompletion {
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
                            .updateCurrentMessages(chunk.messages)
                        updateConversation(conversationId, updatedConversation)
                    }
                }
            }
        }.onFailure {
            it.printStackTrace()
            addError(it)
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)

            addConversationReference(conversationId) // 添加引用
            appScope.launch {
                coroutineScope {
                    launch { generateTitle(conversationId, finalConversation) }
                    launch { generateSuggestion(conversationId, finalConversation) }
                }
            }.invokeOnCompletion {
                removeConversationReference(conversationId) // 移除引用
            }
        }
    }

    // 创建搜索工具
    private fun createSearchTool(settings: Settings): Set<Tool> {
        return buildSet {
            add(
                Tool(
                    name = "search_web",
                    description = "search web for latest information",
                    parameters = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        service.parameters
                    },
                    execute = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        val result = service.search(
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        )
                        val results =
                            JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject.let { json ->
                                val map = json.toMutableMap()
                                map["items"] =
                                    JsonArray(map["items"]!!.jsonArray.mapIndexed { index, item ->
                                        JsonObject(item.jsonObject.toMutableMap().apply {
                                            put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                            put("index", JsonPrimitive(index + 1))
                                        })
                                    })
                                JsonObject(map)
                            }
                        results
                    }, systemPrompt = { model, messages ->
                        if (model.tools.isNotEmpty()) return@Tool ""
                        val hasToolCall =
                            messages.any { it.getToolCalls().any { toolCall -> toolCall.toolName == "search_web" } }
                        val prompt = StringBuilder()
                        prompt.append(
                            """
                    ## tool: search_web

                    ### usage
                    - You can use the search_web tool to search the internet for the latest news or to confirm some facts.
                    - You can perform multiple search if needed
                    - Generate keywords based on the user's question
                    - Today is {{cur_date}}
                    """.trimIndent()
                        )
                        if (hasToolCall) {
                            prompt.append(
                                """
                        ### result example
                        ```json
                        {
                            "items": [
                                {
                                    "id": "random id in 6 characters",
                                    "title": "Title",
                                    "url": "https://example.com",
                                    "text": "Some relevant snippets"
                                }
                            ]
                        }
                        ```

                        ### citation
                        After using the search tool, when replying to users, you need to add a reference format to the referenced search terms in the content.
                        When citing facts or data from search results, you need to add a citation marker after the sentence: `[citation,domain](id of the search result)`.

                        For example:
                        ```
                        The capital of France is Paris. [citation,example.com](id of the search result)

                        The population of Paris is about 2.1 million. [citation,example.com](id of the search result) [citation,example2.com](id of the search result)
                        ```

                        If no search results are cited, you do not need to add a citation marker.
                        """.trimIndent()
                            )
                        }
                        prompt.toString()
                    }
                )
            )

            val options = settings.searchServices.getOrElse(
                index = settings.searchServiceSelected,
                defaultValue = { SearchServiceOptions.DEFAULT })
            val service = SearchService.getService(options)
            if (service.scrapingParameters != null) {
                add(
                    Tool(
                        name = "scrape_web",
                        description = "scrape web for content",
                        parameters = {
                            val options = settings.searchServices.getOrElse(
                                index = settings.searchServiceSelected,
                                defaultValue = { SearchServiceOptions.DEFAULT })
                            val service = SearchService.getService(options)
                            service.scrapingParameters
                        },
                        execute = {
                            val options = settings.searchServices.getOrElse(
                                index = settings.searchServiceSelected,
                                defaultValue = { SearchServiceOptions.DEFAULT })
                            val service = SearchService.getService(options)
                            val result = service.scrape(
                                params = it.jsonObject,
                                commonOptions = settings.searchCommonOptions,
                                serviceOptions = options,
                            )
                            JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject
                        },
                        systemPrompt = { model, messages ->
                            return@Tool """
                            ## tool: scrape_web

                            ### usage
                            - You can use the scrape_web tool to scrape url for detailed content.
                            - You can perform multiple scrape if needed.
                            - For common problems, try not to use this tool unless the user requests it.
                        """.trimIndent()
                        }
                    ))
            }
        }
    }

    // 执行上下文压缩（重构版：S 覆盖 + A 生成 + V 生成）
    private suspend fun performContextCompression(
        conversation: Conversation,
        settings: Settings
    ): Conversation {
        val debugLogger = DebugLogger.getInstance(context)
        val assistant = settings.getCurrentAssistant()

        // 检查是否启用压缩
        if (!assistant.enableCompression) {
            debugLogger.log(LogLevel.DEBUG, "ChatService", "Compression disabled")
            return conversation
        }

        val contextSize = assistant.contextMessageSize
        val messageCount = conversation.messageNodes.size

        // 如果消息数未超过上下文窗口，无需压缩
        if (messageCount <= contextSize) return conversation

        // 计算需要压缩的消息范围
        val lastCompressedIndex = conversation.conversationSummaryUntil
        val targetIndex = messageCount - contextSize

        // 幂等性检查：如果目标压缩位置小于等于已压缩位置，无需再次压缩
        if (targetIndex <= lastCompressedIndex) {
            debugLogger.log(LogLevel.INFO, "ChatService", "Compression skipped (idempotent)")
            return conversation
        }

        // 1. 提取压缩窗口 Wₖ
        val startIndex = if (lastCompressedIndex < 0) 0 else lastCompressedIndex + 1
        val endIndex = targetIndex

        if (startIndex >= endIndex) return conversation

        val windowMessages = conversation.messageNodes.slice(startIndex until endIndex)
        if (windowMessages.isEmpty()) return conversation

        debugLogger.log(
            LogLevel.INFO,
            "ChatService",
            "Compression triggered",
            mapOf(
                "messageCount" to messageCount,
                "contextSize" to contextSize,
                "windowSize" to (endIndex - startIndex),
                "startIndex" to startIndex,
                "endIndex" to endIndex
            )
        )

        // 确定压缩模型
        val compressionModelId = assistant.compressionModelId ?: settings.compressionModelId
        val compressionModel = settings.findModelById(compressionModelId)
        if (compressionModel == null) {
            Log.w(TAG, "Compression model not found: $compressionModelId")
            return conversation
        }

        val provider = compressionModel.findProvider(settings.providers)
        if (provider == null) {
            Log.w(TAG, "Compression provider not found for model: $compressionModelId")
            return conversation
        }

        val providerHandler = providerManager.getProviderByType(provider)

        return try {
            // 2. 生成归档摘要 Aₖ = archive(Wₖ)
            val archiveContent = generateArchiveSummary(windowMessages, providerHandler, compressionModel, provider)

            // 3. 更新运行摘要 Sₖ = update(Sₖ₋₁, Wₖ)
            val newRunningSummary = updateRunningSummary(
                previousSummary = conversation.conversationSummary,
                windowMessages = windowMessages,
                provider = providerHandler,
                compressionModel = compressionModel,
                providerSetting = provider
            )

            // 4. 原子化保存
            val archiveId = Uuid.random()
            val now = System.currentTimeMillis()

            // 保存归档摘要
            val archiveEntity = ArchiveSummaryEntity(
                id = archiveId.toString(),
                conversationId = conversation.id.toString(),
                windowStartIndex = startIndex,
                windowEndIndex = endIndex,
                content = archiveContent,
                createdAt = now,
                embeddingModelId = null // 稍后生成
            )
            archiveSummaryDao.insert(archiveEntity)

            // 生成并保存向量索引（如果配置了 embedding 模型）
            if (settings.embeddingModelId != null) {
                generateAndSaveVectorIndex(archiveId, archiveContent, settings)
            }

            // 更新会话的运行摘要和游标
            val updatedConversation = conversation.copy(
                conversationSummary = newRunningSummary,  // 覆盖而非追加
                conversationSummaryUntil = endIndex
            )

            // 保存到数据库
            saveConversation(conversation.id, updatedConversation)

            debugLogger.log(
                LogLevel.INFO,
                "ChatService",
                "Compression completed",
                mapOf(
                    "archiveId" to archiveId.toString(),
                    "startIndex" to startIndex,
                    "endIndex" to endIndex,
                    "archiveLength" to archiveContent.length,
                    "summaryLength" to newRunningSummary.length
                )
            )

            Log.i(TAG, "Context compression completed: compressed messages $startIndex to $endIndex")
            Log.i(TAG, "Archive summary created: A#$archiveId")

            updatedConversation
        } catch (e: Exception) {
            Log.e(TAG, "Context compression failed", e)
            addError(e)
            conversation
        }
    }

    // 生成归档摘要 Aₖ = archive(Wₖ)
    @Suppress("UNCHECKED_CAST")
    private suspend fun generateArchiveSummary(
        windowMessages: List<me.rerere.rikkahub.data.model.MessageNode>,
        provider: me.rerere.ai.provider.Provider<*>,
        compressionModel: me.rerere.ai.provider.Model,
        providerSetting: me.rerere.ai.provider.ProviderSetting
    ): String {
        val debugLogger = DebugLogger.getInstance(context)
        val prompt = buildString {
            appendLine("你是一个对话归档专家。请从以下对话片段中提取并归档关键信息。")
            appendLine()
            appendLine("归档内容必须严格限制为以下5类：")
            appendLine("1. 发生的事件事实")
            appendLine("2. 已达成的结论/决策")
            appendLine("3. 新增或变更的约束/偏好")
            appendLine("4. 未解决的问题")
            appendLine("5. 检索关键词")
            appendLine()
            appendLine("禁止项：")
            appendLine("- 推导过程")
            appendLine("- 教学步骤")
            appendLine("- 长原文复述")
            appendLine("- 情绪/气氛/修辞")
            appendLine()
            appendLine("长度要求：100-300字，上限500字")
            appendLine()
            appendLine("对话内容：")
            windowMessages.forEach { node ->
                val msg = node.currentMessage
                val role = when(msg.role) {
                    MessageRole.USER -> "User"
                    MessageRole.ASSISTANT -> "Assistant"
                    else -> "System"
                }
                appendLine("[$role] ${msg.toText().take(500)}")
                appendLine()
            }
            appendLine()
            appendLine("请输出归档内容：")
        }

        return try {
            @Suppress("UNCHECKED_CAST")
            val result = (provider as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>).generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.user(prompt = prompt)
                ),
                params = TextGenerationParams(
                    model = compressionModel,
                    temperature = 0.3f,
                    maxTokens = 800,
                    thinkingBudget = 0,
                ),
            )

            val archiveText = result.choices.firstOrNull()?.message?.toText()?.trim() ?: ""

            debugLogger.log(
                LogLevel.DEBUG,
                "ArchiveSummary",
                "Archive generated",
                mapOf(
                    "windowSize" to windowMessages.size,
                    "length" to archiveText.length
                )
            )

            archiveText
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate archive summary", e)
            ""
        }
    }

    // 更新运行摘要 Sₖ = update(Sₖ₋₁, Wₖ)
    @Suppress("UNCHECKED_CAST")
    private suspend fun updateRunningSummary(
        previousSummary: String,
        windowMessages: List<me.rerere.rikkahub.data.model.MessageNode>,
        provider: me.rerere.ai.provider.Provider<*>,
        compressionModel: me.rerere.ai.provider.Model,
        providerSetting: me.rerere.ai.provider.ProviderSetting
    ): String {
        val debugLogger = DebugLogger.getInstance(context)
        val prompt = buildString {
            if (previousSummary.isNotBlank()) {
                appendLine("当前会话摘要：")
                appendLine(previousSummary)
                appendLine()
            }
            appendLine("新增对话内容：")
            windowMessages.forEach { node ->
                val msg = node.currentMessage
                val role = when(msg.role) {
                    MessageRole.USER -> "User"
                    MessageRole.ASSISTANT -> "Assistant"
                    else -> "System"
                }
                appendLine("[$role] ${msg.toText().take(500)}")
                appendLine()
            }
            appendLine()
            appendLine("请基于当前摘要和新增对话，输出一份完整的、涵盖所有信息的更新后摘要。")
            appendLine("注意：输出应该是完整的摘要，而不是追加内容。")
        }

        return try {
            @Suppress("UNCHECKED_CAST")
            val result = (provider as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>).generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.user(prompt = prompt)
                ),
                params = TextGenerationParams(
                    model = compressionModel,
                    temperature = 0.3f,
                    maxTokens = 1000,
                    thinkingBudget = 0,
                ),
            )

            val newSummary = result.choices.firstOrNull()?.message?.toText()?.trim() ?: previousSummary

            debugLogger.log(
                LogLevel.DEBUG,
                "RunningSummary",
                "Summary updated",
                mapOf(
                    "previousLength" to previousSummary.length,
                    "newLength" to newSummary.length
                )
            )

            newSummary
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update running summary", e)
            previousSummary // 失败时保留旧摘要
        }
    }

    // 生成并保存向量索引
    private suspend fun generateAndSaveVectorIndex(
        archiveId: Uuid,
        archiveContent: String,
        settings: Settings
    ) {
        val debugLogger = DebugLogger.getInstance(context)
        try {
            val embeddingModelId = settings.embeddingModelId ?: return
            val embeddingModel = settings.findModelById(embeddingModelId)
                ?: return

            val provider = embeddingModel.findProvider(settings.providers)
                ?: return

            val providerHandler = providerManager.getProviderByType(provider)

            @Suppress("UNCHECKED_CAST")
            val embeddingVector = (providerHandler as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>).generateEmbedding(
                providerSetting = provider,
                text = archiveContent,
                params = me.rerere.ai.provider.EmbeddingGenerationParams(
                    model = embeddingModel
                )
            )

            val vectorIndexEntity = VectorIndexEntity(
                id = Uuid.random().toString(),
                archiveId = archiveId.toString(),
                embeddingVector = embeddingVector,
                embeddingModelId = embeddingModelId.toString(),
                createdAt = System.currentTimeMillis()
            )

            vectorIndexDao.insert(vectorIndexEntity)

            debugLogger.log(
                LogLevel.DEBUG,
                "VectorIndex",
                "Vector created",
                mapOf(
                    "archiveId" to archiveId.toString(),
                    "dimension" to embeddingVector.size,
                    "modelId" to embeddingModelId.toString()
                )
            )

            // 更新 archive_summary 的 embedding_model_id
            val archiveEntity = archiveSummaryDao.getById(archiveId.toString())
            if (archiveEntity != null) {
                val updatedArchive = archiveEntity.copy(
                    embeddingModelId = embeddingModelId.toString()
                )
                archiveSummaryDao.update(updatedArchive)
            }

            Log.i(TAG, "Vector index created for archive A#$archiveId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate vector index", e)
            // 向量生成失败不应影响归档功能
        }
    }

    // 构建检索查询（不使用 LLM）
    private fun buildRetrievalQuery(
        conversation: Conversation,
        lastUserMessage: me.rerere.rikkahub.data.model.MessageNode
    ): String {
        val queryBuilder = StringBuilder()

        // 1. 最后一条用户消息
        queryBuilder.appendLine(lastUserMessage.currentMessage.toText())

        // 2. 从 S 中抽取 1-3 行（简单规则：前3行）
        if (conversation.conversationSummary.isNotBlank()) {
            val summaryLines = conversation.conversationSummary.lines()
            val keyLines = summaryLines
                .take(3)
                .filter { it.isNotBlank() }
                .joinToString("\n")

            if (keyLines.isNotBlank()) {
                queryBuilder.appendLine()
                queryBuilder.appendLine(keyLines)
            }
        }

        return queryBuilder.toString()
    }

    // 计算余弦相似度
    private fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        require(vec1.size == vec2.size) { "Vectors must be same length" }

        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f

        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            norm1 += vec1[i] * vec1[i]
            norm2 += vec2[i] * vec2[i]
        }

        return if (norm1 == 0f || norm2 == 0f) 0f else dotProduct / (kotlin.math.sqrt(norm1) * kotlin.math.sqrt(norm2))
    }

    // 检索相关归档摘要
    suspend fun retrieveRelevantArchives(
        conversationId: Uuid,
        query: String,
        embeddingModelId: Uuid,
        topK: Int = 5
    ): List<ArchiveSummaryEntity> {
        try {
            // 获取所有归档摘要
            val allArchives = archiveSummaryDao.getListByConversationId(conversationId.toString())

            if (allArchives.isEmpty()) return emptyList()

            // 生成查询向量
            val settings = settingsStore.settingsFlow.value
            val embeddingModel = settings.findModelById(embeddingModelId)
                ?: return emptyList()

            val provider = embeddingModel.findProvider(settings.providers)
                ?: return emptyList()

            val providerHandler = providerManager.getProviderByType(provider)

            @Suppress("UNCHECKED_CAST")
            val queryEmbedding = (providerHandler as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>).generateEmbedding(
                providerSetting = provider,
                text = query,
                params = me.rerere.ai.provider.EmbeddingGenerationParams(
                    model = embeddingModel
                )
            )

            // 计算相似度并排序
            val archiveWithSimilarity = allArchives.mapNotNull { archive ->
                val vectorIndex = vectorIndexDao.getByArchiveId(archive.id)
                if (vectorIndex != null && vectorIndex.embeddingModelId == embeddingModelId.toString()) {
                    val similarity = cosineSimilarity(queryEmbedding, vectorIndex.embeddingVector)
                    archive to similarity
                } else null
            }

            return archiveWithSimilarity
                .sortedByDescending { it.second }
                .take(topK)
                .map { it.first }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve relevant archives", e)
            return emptyList<ArchiveSummaryEntity>()
        }
    }

    // 重建向量索引
    // 用于切换 embedding 模型或修复缺失的向量索引
    suspend fun rebuildVectorIndices(
        conversationId: Uuid? = null,  // null 表示重建所有会话
        embeddingModelId: Uuid,
        onProgress: (current: Int, total: Int, currentArchive: ArchiveSummaryEntity) -> Unit = { _, _, _ -> }
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val settings = settingsStore.settingsFlow.value
            val embeddingModel = settings.findModelById(embeddingModelId)
                ?: return@withContext Result.failure(IllegalArgumentException("Embedding model not found"))
            val provider = embeddingModel.findProvider(settings.providers)
                ?: return@withContext Result.failure(IllegalArgumentException("Provider not found"))
            val providerHandler = providerManager.getProviderByType(provider)

            // 获取需要重建的归档摘要
            val archivesToRebuild = if (conversationId != null) {
                // 指定会话：重建所有归档（无论是否有向量）
                archiveSummaryDao.getListByConversationId(conversationId.toString())
            } else {
                // 全局重建：只重建缺失向量或模型不匹配的归档
                val allArchives = archiveSummaryDao.getAll()
                allArchives.filter { archive ->
                    val vector = vectorIndexDao.getByArchiveId(archive.id)
                    vector == null || vector.embeddingModelId != embeddingModelId.toString()
                }
            }

            if (archivesToRebuild.isEmpty()) {
                return@withContext Result.success(0)
            }

            onProgress(0, archivesToRebuild.size, archivesToRebuild.first())

            // 逐个重建
            var successCount = 0
            archivesToRebuild.forEachIndexed { index, archive ->
                try {
                    // 生成新的 embedding
                    @Suppress("UNCHECKED_CAST")
                    val embeddingVector = (providerHandler as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>).generateEmbedding(
                        providerSetting = provider,
                        text = archive.content,
                        params = me.rerere.ai.provider.EmbeddingGenerationParams(
                            model = embeddingModel
                        )
                    )

                    // 删除旧的向量索引（如果存在）
                    val oldVector = vectorIndexDao.getByArchiveId(archive.id)
                    if (oldVector != null) {
                        vectorIndexDao.delete(oldVector)
                    }

                    // 创建新的向量索引
                    val newVectorIndex = VectorIndexEntity(
                        id = Uuid.random().toString(),
                        archiveId = archive.id,
                        embeddingVector = embeddingVector,
                        embeddingModelId = embeddingModelId.toString(),
                        createdAt = System.currentTimeMillis()
                    )
                    vectorIndexDao.insert(newVectorIndex)

                    // 更新归档摘要的 embedding_model_id
                    val updatedArchive = archive.copy(
                        embeddingModelId = embeddingModelId.toString()
                    )
                    archiveSummaryDao.update(updatedArchive)

                    successCount++
                    onProgress(index + 1, archivesToRebuild.size, archive)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to rebuild vector index for archive ${archive.id}", e)
                }
            }

            Result.success(successCount)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebuild vector indices", e)
            Result.failure(e)
        }
    }

    // 检查无效消息
    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效tool call
        messagesNodes = messagesNodes.mapIndexed { index, node ->
            val next = if (index < messagesNodes.size - 1) messagesNodes[index + 1] else null
            if (node.currentMessage.hasPart<UIMessagePart.ToolCall>()) {
                if (next?.currentMessage?.hasPart<UIMessagePart.ToolResult>() != true) {
                    return@mapIndexed node.copy(
                        messages = node.messages.filter { it.id != node.currentMessage.id },
                        selectIndex = node.selectIndex - 1
                    )
                }
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

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    // 生成标题
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
            val model =
                settings.findModelById(settings.titleModelId) ?: settings.getCurrentChatModel()
                ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages.truncate(conversation.truncateIndex)
                                .joinToString("\n\n") { it.summaryAsText() })
                    ),
                ),
                params = TextGenerationParams(
                    model = model, temperature = 0.3f, thinkingBudget = 0
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
        }
    }

    // 生成建议
    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.suggestionModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            updateConversation(
                conversationId,
                getConversationFlow(conversationId).value.copy(chatSuggestions = emptyList())
            )

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages.truncate(conversation.truncateIndex)
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText() }),
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    temperature = 1.0f,
                    thinkingBudget = 0,
                ),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            saveConversation(
                conversationId,
                getConversationFlow(conversationId).value.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    // 发送生成完成通知
    private fun sendGenerationDoneNotification(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        val notification =
            NotificationCompat.Builder(context, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_chat_done_title))
                .setContentText(conversation.currentMessages.lastOrNull()?.toText()?.take(50) ?: "")
                .setSmallIcon(R.drawable.small_icon)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(getPendingIntent(context, conversationId))

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(1, notification.build())
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

    // 更新对话
    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        checkFilesDelete(conversation, getConversationFlow(conversationId).value)
        conversations.getOrPut(conversationId) { MutableStateFlow(conversation) }.value =
            conversation
    }

    // 检查文件删除
    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            context.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    // 保存对话
    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.title.isBlank() && conversation.messageNodes.isEmpty()) return // 如果对话为空，则不保存

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (conversationRepo.getConversationById(conversation.id) == null) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // 翻译消息
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
                addError(e)
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

    // 清理对话相关资源
    fun cleanupConversation(conversationId: Uuid) {
        getGenerationJob(conversationId)?.cancel()
        removeGenerationJob(conversationId)
        conversations.remove(conversationId)

        Log.i(
            TAG,
            "cleanupConversation: removed $conversationId (current references: ${conversationReferences.size}, generation jobs: ${_generationJobs.value.size})"
        )
    }
}
