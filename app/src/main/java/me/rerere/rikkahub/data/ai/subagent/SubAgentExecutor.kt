package me.rerere.rikkahub.data.ai.subagent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.SubAgentToolSet
import me.rerere.rikkahub.data.model.WorkflowPhase
import kotlin.uuid.Uuid

private const val TAG = "SubAgentExecutor"

/**
 * 子代理执行器
 * 负责执行子代理任务，管理工具暴露和结果返回
 */
class SubAgentExecutor(
    private val generationHandler: GenerationHandler,
    private val context: Context,
) {
    /**
     * 执行子代理任务（流式返回进度）
     *
     * @param subAgent 子代理配置
     * @param task 任务描述
     * @param context 相关上下文
     * @param files 相关文件路径列表
     * @param sandboxId 沙箱ID（用于文件隔离）
     * @param settings 当前设置
     * @param parentModel 主对话使用的模型（子代理默认继承此模型）
     * @param parentWorkflowPhase 父代理的Workflow阶段（用于权限控制）
     * @param containerEnabled 容器是否可用
     * @param localTools 本地工具实例（用于创建子代理工具）
     * @param mcpTools 可用的MCP工具
     * @return 流式的子代理执行进度
     */
    fun execute(
        subAgent: SubAgent,
        task: String,
        context: String = "",
        files: List<String> = emptyList(),
        sandboxId: Uuid,
        settings: Settings,
        parentModel: me.rerere.ai.provider.Model,
        parentWorkflowPhase: WorkflowPhase? = null,
        containerEnabled: Boolean,
        localTools: LocalTools,
        mcpTools: List<Tool> = emptyList(),
    ): Flow<SubAgentProgress> = flow {
        Log.d(TAG, "Executing sub-agent ${subAgent.name} for task: ${task.take(50)}...")

        val startTime = System.currentTimeMillis()

        try {
            val systemPrompt = buildSubAgentSystemPrompt(subAgent, files)
            val messages = buildSubAgentMessages(systemPrompt, task, context)
            val model = subAgent.modelId?.let {
                settings.findModelById(it)
            } ?: parentModel

            val tools = buildSubAgentTools(
                toolSet = subAgent.allowedTools,
                sandboxId = sandboxId,
                containerEnabled = containerEnabled,
                localTools = localTools,
                mcpTools = mcpTools,
                settings = settings,
                parentWorkflowPhase = parentWorkflowPhase
            )

            val assistant = Assistant(
                id = Uuid.random(),
                name = subAgent.name,
                systemPrompt = systemPrompt,
                temperature = subAgent.temperature,
                maxTokens = subAgent.maxTokens,
                contextMessageSize = 0,
            )

            var resultText = ""
            var finalMessages: List<UIMessage> = emptyList()
            var stepIndex = 0
            var totalToolCalls = 0
            val toolCallsInCurrentStep = mutableListOf<String>()

            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = messages,
                assistant = assistant,
                tools = tools,
                maxSteps = 50,
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        finalMessages = chunk.messages
                        val lastMessage = chunk.messages.lastOrNull()

                        val currentStep = chunk.messages.size
                        if (currentStep > stepIndex) {
                            stepIndex = currentStep
                            toolCallsInCurrentStep.clear()
                        }

                        lastMessage?.parts
                            ?.filterIsInstance<UIMessagePart.Tool>()
                            ?.forEach { toolCall ->
                                if (!toolCallsInCurrentStep.contains(toolCall.toolName)) {
                                    toolCallsInCurrentStep.add(toolCall.toolName)
                                    totalToolCalls++
                                    emit(
                                        SubAgentProgress.ToolCallUpdate(
                                            toolName = toolCall.toolName,
                                            status = "executing",
                                            result = null
                                        )
                                    )
                                }
                            }

                        val textContent = lastMessage?.toText() ?: ""
                        if (textContent.isNotEmpty() && textContent != resultText) {
                            val newContent = textContent.removePrefix(resultText)
                            resultText = textContent
                            emit(
                                SubAgentProgress.TextChunk(
                                    content = newContent,
                                    isThinking = lastMessage?.parts?.any { it is UIMessagePart.Reasoning } ?: false
                                )
                            )
                        }

                        emit(
                            SubAgentProgress.StepUpdate(
                                stepIndex = stepIndex,
                                totalSteps = 50,
                                currentMessage = textContent.take(100),
                                toolCalls = toolCallsInCurrentStep.toList(),
                                totalToolCalls = totalToolCalls
                            )
                        )
                    }
                }
            }

            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Sub-agent ${subAgent.name} completed in ${duration}ms")

            emit(
                SubAgentProgress.Complete(
                    SubAgentResult(
                        success = true,
                        result = resultText,
                        duration = duration,
                        messages = finalMessages
                    )
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sub-agent ${subAgent.name} failed", e)
            val duration = System.currentTimeMillis() - startTime
            emit(SubAgentProgress.Error(e.message ?: "Unknown error"))
            emit(
                SubAgentProgress.Complete(
                    SubAgentResult(
                        success = false,
                        result = "",
                        error = e.message ?: "Unknown error",
                        duration = duration
                    )
                )
            )
        }
    }

    private fun buildSubAgentSystemPrompt(subAgent: SubAgent, files: List<String>): String {
        return buildString {
            appendLine(subAgent.systemPrompt)
            if (files.isNotEmpty()) {
                appendLine()
                appendLine("=== 相关文件 ===")
                files.forEach { file ->
                    appendLine("- $file")
                }
                appendLine("你可以使用当前已暴露的文件工具读取这些文件。")
            }
        }
    }

    private fun buildSubAgentMessages(
        systemPrompt: String,
        task: String,
        context: String
    ): List<UIMessage> {
        val userMessage = buildString {
            appendLine("=== 任务 ===")
            appendLine(task)
            if (context.isNotEmpty()) {
                appendLine()
                appendLine("=== 上下文 ===")
                appendLine(context)
            }
            appendLine()
            appendLine("请完成上述任务并返回清晰的结果。不要反问用户，直接执行任务。")
        }

        return listOf(
            UIMessage(
                role = MessageRole.SYSTEM,
                parts = listOf(UIMessagePart.Text(systemPrompt))
            ),
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(userMessage))
            )
        )
    }

    private fun buildSubAgentTools(
        toolSet: SubAgentToolSet,
        sandboxId: Uuid,
        containerEnabled: Boolean,
        localTools: LocalTools,
        mcpTools: List<Tool>,
        settings: Settings,
        parentWorkflowPhase: WorkflowPhase? = null
    ): List<Tool> {
        val tools = mutableListOf<Tool>()
        val isReadonlyPhase = parentWorkflowPhase == WorkflowPhase.PLAN || parentWorkflowPhase == WorkflowPhase.REVIEW

        if (toolSet.enableWebSearch) {
            tools.addAll(createSearchTools(context, settings))
        }

        if (toolSet.enableSandboxShellReadonly) {
            tools.add(localTools.createSandboxShellReadonlyTool(sandboxId))
        }

        if (!isReadonlyPhase &&
            (toolSet.enableContainer ||
                toolSet.enableSandboxPython ||
                toolSet.enableSandboxShell ||
                toolSet.enableSandboxData ||
                toolSet.enableSandboxDev) &&
            containerEnabled
        ) {
            tools.add(localTools.createContainerShellTool(sandboxId))
        }

        if (toolSet.allowedMcpServers.isNotEmpty()) {
            val allowedServerIds = toolSet.allowedMcpServers
            val serverToolNames = settings.mcpServers
                .filter { serverConfig -> serverConfig.id in allowedServerIds }
                .flatMap { serverConfig -> serverConfig.commonOptions.tools }
                .map { tool -> "mcp__${tool.name}" }
                .toSet()

            mcpTools.filter { tool -> tool.name in serverToolNames }
                .let { tools.addAll(it) }
        }

        return tools
    }
}

sealed class SubAgentProgress {
    data class StepUpdate(
        val stepIndex: Int,
        val totalSteps: Int? = null,
        val currentMessage: String = "",
        val toolCalls: List<String> = emptyList(),
        val totalToolCalls: Int = 0,
    ) : SubAgentProgress()

    data class ToolCallUpdate(
        val toolName: String,
        val status: String,
        val result: String? = null,
    ) : SubAgentProgress()

    data class TextChunk(
        val content: String,
        val isThinking: Boolean = false,
    ) : SubAgentProgress()

    data class Complete(
        val result: SubAgentResult,
    ) : SubAgentProgress()

    data class Error(
        val error: String,
    ) : SubAgentProgress()
}

data class SubAgentResult(
    val success: Boolean,
    val result: String,
    val error: String? = null,
    val duration: Long,
    val messages: List<UIMessage> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("success", success)
        put("result", result)
        error?.let { put("error", it) }
        put("duration_ms", duration)
    }
}
