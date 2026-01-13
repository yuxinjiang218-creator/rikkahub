package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.ai.ui.truncate
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_ARCHIVE_RECALL_LENGTH = 2000  // [ARCHIVE_RECALL] 硬性字符上限

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val aiLoggingManager: AILoggingManager,
    private val archiveSummaryDao: me.rerere.rikkahub.data.db.dao.ArchiveSummaryDao,
    private val vectorIndexDao: me.rerere.rikkahub.data.db.dao.VectorIndexDao,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        truncateIndex: Int = -1,
        maxSteps: Int = 256,
        conversationSummary: String = "",  // 对话摘要
        conversationId: Uuid? = null,     // 会话 ID，用于归档回填
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (assistant?.enableMemory == true) {
                    buildMemoryTools(
                        onCreation = { content ->
                            memoryRepo.addMemory(assistant.id.toString(), content)
                        },
                        onUpdate = { id, content ->
                            memoryRepo.updateContent(id, content)
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(id)
                        }
                    ).let(this::addAll)
                }
                addAll(tools)
            }

            generateInternal(
                assistant = assistant,
                settings = settings,
                messages = messages,
                conversationId = conversationId,
                onUpdateMessages = {
                    messages = it.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                    emit(
                        GenerationChunk.Messages(
                            messages.visualTransforms(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                assistant = assistant,
                                settings = settings
                            )
                        )
                    )
                },
                transformers = inputTransformers,
                model = model,
                providerImpl = providerImpl,
                provider = provider,
                tools = toolsInternal,
                memories = memories ?: emptyList(),
                truncateIndex = truncateIndex,
                stream = assistant.streamOutput,
                conversationSummary = conversationSummary,
            )
            messages = messages.visualTransforms(
                transformers = outputTransformers,
                context = context,
                model = model,
                assistant = assistant,
                settings = settings
            )
            messages = messages.onGenerationFinish(
                transformers = outputTransformers,
                context = context,
                model = model,
                assistant = assistant,
                settings = settings
            )
            messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                finishedAt = Clock.System.now()
                    .toLocalDateTime(TimeZone.currentSystemDefault())
            )
            emit(GenerationChunk.Messages(messages))

            val toolCalls = messages.last().getToolCalls()
            if (toolCalls.isEmpty()) {
                // no tool calls, break
                break
            }
            // handle tool calls
            val results = arrayListOf<UIMessagePart.ToolResult>()
            toolCalls.forEach { toolCall ->
                runCatching {
                    val tool = toolsInternal.find { tool -> tool.name == toolCall.toolName }
                        ?: error("Tool ${toolCall.toolName} not found")
                    val args = json.parseToJsonElement(toolCall.arguments.ifBlank { "{}" })
                    Log.i(TAG, "generateText: executing tool ${tool.name} with args: $args")
                    val result = tool.execute(args)
                    results += UIMessagePart.ToolResult(
                        toolName = toolCall.toolName,
                        toolCallId = toolCall.toolCallId,
                        content = result,
                        arguments = args,
                        metadata = toolCall.metadata
                    )
                }.onFailure {
                    it.printStackTrace()
                    results += UIMessagePart.ToolResult(
                        toolName = toolCall.toolName,
                        toolCallId = toolCall.toolCallId,
                        metadata = toolCall.metadata,
                        content = buildJsonObject {
                            put(
                                "error",
                                JsonPrimitive(buildString {
                                    append("[${it.javaClass.name}] ${it.message}")
                                    append("\n${it.stackTraceToString()}")
                                })
                            )
                        },
                        arguments = runCatching {
                            json.parseToJsonElement(toolCall.arguments)
                        }.getOrElse { JsonObject(emptyMap()) }
                    )
                }
            }
            messages = messages + UIMessage(
                role = MessageRole.TOOL,
                parts = results
            )
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }

    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        conversationId: Uuid?,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        truncateIndex: Int,
        stream: Boolean,
        conversationSummary: String,
    ) {
        val internalMessages = buildList {
            val system = buildString {
                // 如果助手有系统提示，则添加到消息中
                if (assistant.systemPrompt.isNotBlank()) {
                    append(assistant.systemPrompt)
                }

                // [S: Running Summary] 对话摘要（上下文压缩）
                if (conversationSummary.isNotBlank()) {
                    appendLine()
                    appendLine()
                    append("## 对话历史摘要")
                    appendLine()
                    append(conversationSummary)
                }

                // [ARCHIVE_RECALL] 自动归档回填
                if (assistant.enableArchiveRecall && conversationId != null && settings.embeddingModelId != null) {
                    val lastUserMessage = messages.lastOrNull { it.role == me.rerere.ai.core.MessageRole.USER }
                    if (lastUserMessage != null) {
                        val query = buildString {
                            appendLine(lastUserMessage.toText())
                            if (conversationSummary.isNotBlank()) {
                                val summaryLines = conversationSummary.lines()
                                val keyLines = summaryLines.take(3).filter { it.isNotBlank() }
                                    .joinToString("\n")
                                if (keyLines.isNotBlank()) {
                                    appendLine()
                                    appendLine(keyLines)
                                }
                            }
                        }

                        val relevantArchives = retrieveRelevantArchives(
                            settings = settings,
                            conversationId = conversationId,
                            query = query,
                            embeddingModelId = settings.embeddingModelId,
                            topK = 5
                        )

                        if (relevantArchives.isNotEmpty()) {
                            appendLine()
                            appendLine()
                            append("[ARCHIVE_RECALL]")
                            var currentLength = 0
                            relevantArchives.forEach { archive ->
                                // 对单条 content 先截断，避免单条过长
                                val content = archive.content.take(500)
                                val entry = "- A#${archive.id} $content"
                                val entryLength = entry.length + 1  // +1 for newline

                                // 累加计数，达到上限后提前停止
                                if (currentLength + entryLength > MAX_ARCHIVE_RECALL_LENGTH) {
                                    return@forEach
                                }

                                appendLine()
                                append(entry)
                                currentLength += entryLength
                            }
                            appendLine()
                            append("[/ARCHIVE_RECALL]")
                        }
                    }
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(model = model, memories = memories))
                }
                if (assistant.enableRecentChatsReference) {
                    appendLine()
                    append(buildRecentChatsPrompt(assistant))
                }

                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
            }
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
            addAll(messages.truncate(truncateIndex).limitContext(assistant.contextMessageSize))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings
        )

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            thinkingBudget = assistant.thinkingBudget,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        if (stream) {
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = messages,
                    providerSetting = provider,
                    stream = true
                )
            )
            providerImpl.streamText(
                providerSetting = provider,
                messages = internalMessages,
                params = params
            ).collect {
                messages = messages.handleMessageChunk(chunk = it, model = model)
                it.usage?.let { usage ->
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            message.copy(usage = message.usage.merge(usage))
                        } else {
                            message
                        }
                    }
                }
                onUpdateMessages(messages)
            }
        } else {
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = messages,
                    providerSetting = provider,
                    stream = false
                )
            )
            val chunk = providerImpl.generateText(
                providerSetting = provider,
                messages = internalMessages,
                params = params,
            )
            messages = messages.handleMessageChunk(chunk = chunk, model = model)
            chunk.usage?.let { usage ->
                messages = messages.mapIndexed { index, message ->
                    if (index == messages.lastIndex) {
                        message.copy(
                            usage = message.usage.merge(usage)
                        )
                    } else {
                        message
                    }
                }
            }
            onUpdateMessages(messages)
        }
    }

    private fun buildMemoryTools(
        onCreation: suspend (String) -> AssistantMemory,
        onUpdate: suspend (Int, String) -> AssistantMemory,
        onDelete: suspend (Int) -> Unit
    ) = listOf(
        Tool(
            name = "create_memory",
            description = "create a memory record",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "The content of the memory record")
                        })
                    },
                    required = listOf("content")
                )
            },
            execute = {
                val params = it.jsonObject
                val content =
                    params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content))
            }
        ),
        Tool(
            name = "edit_memory",
            description = "update a memory record",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "integer")
                            put("description", "The id of the memory record")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "The content of the memory record")
                        })
                    },
                    required = listOf("id", "content"),
                )
            },
            execute = {
                val params = it.jsonObject
                val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                val content =
                    params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                json.encodeToJsonElement(
                    AssistantMemory.serializer(), onUpdate(id, content)
                )
            }
        ),
        Tool(
            name = "delete_memory",
            description = "delete a memory record",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "integer")
                            put("description", "The id of the memory record")
                        })
                    },
                    required = listOf("id")
                )
            },
            execute = {
                val params = it.jsonObject
                val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                onDelete(id)
                JsonPrimitive(true)
            }
        )
    )

    private fun buildMemoryPrompt(model: Model, memories: List<AssistantMemory>) =
        buildString {
            append("## Memories")
            append("These are memories that you can reference in the future conversations.")
            append("\n<memories>\n")
            memories.forEach { memory ->
                append("<record>\n")
                append("<id>${memory.id}</id>")
                append("<content>${memory.content}</content>")
                append("</record>\n")
            }
            append("</memories>\n")

            if (model.abilities.contains(ModelAbility.TOOL)) {
                append(
                    """
                        ## Memory Tool
                        你是一个无状态的大模型，你**无法存储记忆**，因此为了记住信息，你需要使用**记忆工具**。
                        记忆工具允许你(助手)存储多条信息(record)以便在跨对话聊天中记住信息。
                        你可以使用`create_memory`, `edit_memory`和`delete_memory`工具创建，更新或删除记忆。
                        - 如果记忆内没有相关信息，你需要调用`create_memory`工具来创建一个记忆记录。
                        - 如果已经有相关记录，请调用`edit_memory`工具来更新一个记忆记录。
                        - 如果一个记忆过时或者无用了，请调用`delete_memory`工具来删除一个记忆记录。
                        这些记忆会自动包含在未来的对话上下文中，在<memories>标签内。
                        请勿在记忆中存储敏感信息，敏感信息包括：用户的民族、宗教信仰、性取向、政治观点及党派归属、性生活、犯罪记录等。
                        在与用户聊天过程中，你可以像一个私人秘书一样**主动的**记录用户相关的信息到记忆里，包括但不限于：
                        - 用户昵称/姓名
                        - 年龄/性别/兴趣爱好
                        - 计划事项等
                        - 聊天风格偏好
                        - 工作相关
                        - 首次聊天时间
                        - ...
                        请主动调用工具记录，而不是需要用户要求。
                        记忆如果包含日期信息，请包含在内，请使用绝对时间格式，并且当前时间是 {cur_datetime}。
                        无需告知用户你已更改记忆记录，也不要在对话中直接显示记忆内容，除非用户主动要求。
                        相似或相关的记忆应合并为一条记录，而不要重复记录，过时记录应删除。
                        你可以在和用户闲聊的时候暗示用户你能记住东西。
                    """.trimIndent()
                )
            }
        }

    private suspend fun buildRecentChatsPrompt(assistant: Assistant): String {
        val recentConversations = conversationRepo.getRecentConversations(
            assistantId = assistant.id,
            limit = 10,
        )
        if (recentConversations.isNotEmpty()) {
            return buildString {
                append("## 最近的对话\n")
                append("这是用户最近的一些对话，你可以参考这些对话了解用户偏好:\n")
                append("\n<recent_chats>\n")
                recentConversations.forEach { conversation ->
                    append("<conversation>\n")
                    append("  <title>${conversation.title}</title>")
                    append("</conversation>\n")
                }
                append("</recent_chats>\n")
            }
        }
        return ""
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)

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

    // 检索相关归档摘要（基于向量相似度）
    private suspend fun retrieveRelevantArchives(
        settings: Settings,
        conversationId: Uuid,
        query: String,
        embeddingModelId: Uuid,
        topK: Int = 5
    ): List<me.rerere.rikkahub.data.db.entity.ArchiveSummaryEntity> {
        // 1. 获取该会话的所有归档摘要
        val allArchives = archiveSummaryDao.getListByConversationId(conversationId.toString())
        if (allArchives.isEmpty()) return emptyList()

        // 2. 获取 embedding 模型和 provider
        val embeddingModel = settings.findModelById(embeddingModelId)
            ?: return emptyList()
        val provider = embeddingModel.findProvider(settings.providers)
            ?: return emptyList()
        val providerImpl = providerManager.getProviderByType(provider)

        // 3. 生成查询向量
        @Suppress("UNCHECKED_CAST")
        val queryEmbedding = (providerImpl as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>).generateEmbedding(
            providerSetting = provider,
            text = query,
            params = me.rerere.ai.provider.EmbeddingGenerationParams(model = embeddingModel)
        )

        // 4. 计算相似度并排序
        val archiveWithSimilarity = allArchives.mapNotNull { archive ->
            val vectorIndex = vectorIndexDao.getByArchiveId(archive.id)
            if (vectorIndex != null && vectorIndex.embeddingModelId == embeddingModelId.toString()) {
                val similarity = cosineSimilarity(queryEmbedding, vectorIndex.embeddingVector)
                archive to similarity
            } else {
                null
            }
        }

        // 5. 返回 top-k
        return archiveWithSimilarity
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }
}
