package me.rerere.rikkahub.service.recall.planner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.recall.gate.NeedGate
import me.rerere.rikkahub.service.recall.model.QueryContext

private const val TAG = "LlmRecallPlanner"

/**
 * LLM 召回规划器（Phase L1：主要增益点）
 *
 * 调用规划模型输出严格 JSON，决策是否召回并生成检索查询语句。
 * 失败时回退到 HeuristicRecallPlanner。
 */
class LlmRecallPlanner(
    private val providerManager: ProviderManager,
    private val planningModel: Model,
    private val planningProvider: ProviderSetting,
    private val heuristicPlanner: HeuristicRecallPlanner = HeuristicRecallPlanner(),
    private val logger: PlannerLogger = DefaultPlannerLogger(TAG)  // Phase L 收尾：可注入 logger
) : RecallPlanner {

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun plan(queryContext: QueryContext): PlannerResult = withContext(Dispatchers.Default) {
        try {
            val prompt = buildPlanningPrompt(queryContext)
            val messages = listOf(
                UIMessage.system(SYSTEM_PROMPT),
                UIMessage.user(prompt)
            )

            val params = TextGenerationParams(
                model = planningModel,
                temperature = 0.3f,  // 低温度保证稳定输出
                maxTokens = 512
            )

            val providerImpl = providerManager.getProviderByType(planningProvider)
            val response = providerImpl.generateText(
                providerSetting = planningProvider,
                messages = messages,
                params = params
            )

            // 从 MessageChunk 中提取文本内容
            val content = response.choices
                .firstNotNullOfOrNull { choice ->
                    choice.delta?.parts?.firstNotNullOfOrNull { part ->
                        (part as? UIMessagePart.Text)?.text
                    }
                }
                ?: throw IllegalArgumentException("Empty response from LLM")

            parseLlmResponse(content, queryContext)
        } catch (e: Exception) {
            logger.warn("LLM planning failed, falling back to heuristic", e)
            heuristicPlanner.plan(queryContext)
        }
    }

    /**
     * 构建 LLM 规划 prompt
     */
    private fun buildPlanningPrompt(context: QueryContext): String {
        return buildString {
            appendLine("## 当前用户输入")
            appendLine(context.lastUserText)
            appendLine()

            if (context.windowTexts.isNotEmpty()) {
                appendLine("## 对话窗口（最近几轮）")
                append(context.windowTexts.joinToString("\n"))
                appendLine()
            }

            if (!context.runningSummary.isNullOrBlank()) {
                appendLine("## 对话摘要")
                appendLine(context.runningSummary)
                appendLine()
            }

            // 传递 NeedGate 的启发式分数作为参考
            val needScore = NeedGate.computeNeedScoreHeuristic(context)
            appendLine("## 启发式召回分数")
            appendLine("当前 needScore: ${String.format("%.2f", needScore)} (阈值: ${NeedGate.getThreshold()})")
            appendLine()

            appendLine("请根据上述信息，输出 JSON 规划结果。")
        }
    }

    /**
     * 解析 LLM 返回的 JSON，具有容错能力
     */
    private fun parseLlmResponse(content: String, queryContext: QueryContext): PlannerResult {
        return try {
            // 尝试提取 JSON（去除可能的代码块标记）
            val jsonContent = extractJson(content)

            val jsonElement = jsonParser.parseToJsonElement(jsonContent)
            val jsonObject = jsonElement.jsonObject
            val data = jsonObject["data"]?.jsonObject ?: jsonObject

            // 解析 shouldRecall (boolean)
            val shouldRecall = try {
                val boolStr = data["shouldRecall"]?.jsonPrimitive?.content
                boolStr?.toBooleanStrict() ?: true
            } catch (e: Exception) {
                true
            }

            val pQueriesList = data["pQueries"]?.jsonArray ?: data["searchQueries"]?.jsonArray
            val aQueriesList = data["aQueries"]?.jsonArray ?: pQueriesList  // 默认使用相同查询
            val reason = data["reason"]?.jsonPrimitive?.content ?: "LLM planning"

            // 解析 confidence，如果不存在或无法解析，使用默认值
            val confidence = try {
                data["confidence"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0.7f
            } catch (e: Exception) {
                0.7f
            }

            val pQueries = pQueriesList?.map {
                it.jsonPrimitive?.content ?: ""
            }?.filter { it.isNotBlank() } ?: emptyList()
            val aQueries = aQueriesList?.map {
                it.jsonPrimitive?.content ?: ""
            }?.filter { it.isNotBlank() } ?: emptyList()

            // 如果没有生成任何查询，回退到原始查询
            val finalPQueries = if (pQueries.isEmpty()) listOf(queryContext.lastUserText) else pQueries
            val finalAQueries = if (aQueries.isEmpty()) listOf(queryContext.lastUserText) else aQueries

            PlannerResult(
                shouldRecall = shouldRecall,
                pQueries = finalPQueries,
                aQueries = finalAQueries,
                reason = reason,
                confidence = confidence.coerceIn(0f, 1f)
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse LLM response, using fallback", e)
            PlannerResult.fallback(
                lastUserText = queryContext.lastUserText,
                reason = "LLM response parsing failed, using fallback"
            )
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 内容
     */
    private fun extractJson(content: String): String {
        var trimmed = content.trim()

        // 去除可能的代码块标记
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7)
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3)
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length - 3)
        }

        // 查找第一个 { 和最后一个 }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')

        if (start != -1 && end != -1 && end > start) {
            return trimmed.substring(start, end + 1)
        }

        return trimmed
    }

    companion object {
        /**
         * 系统 prompt：要求严格 JSON 输出
         */
        private val SYSTEM_PROMPT = """
            你是一个召回规划专家。你的任务是判断是否需要从对话历史中检索相关信息，并生成检索查询语句。

            ## 输出格式
            只输出 JSON，不要包含任何其他文字。JSON 格式如下：
            ```json
            {
              "data": {
                "shouldRecall": true,
                "pQueries": ["查询1", "查询2"],
                "aQueries": ["查询1", "查询2"],
                "reason": "规划原因",
                "confidence": 0.8
              }
            }
            ```

            ## 字段说明
            - shouldRecall: 是否需要召回（true/false）
            - pQueries: 逐字召回源的查询列表（2-5条），用于精确匹配搜索
            - aQueries: 归档召回源的查询列表（2-5条），可以与 pQueries 相同
            - reason: 规划原因的简短说明
            - confidence: 置信度（0.0-1.0）

            ## 查询生成原则
            1. 不要编造事实，只基于对话窗口和摘要
            2. 查询要尽量简短，像搜索关键词
            3. 包含同义改写（例如："神经网络训练" → "深度学习模型训练"）
            4. 补全指代（例如："它怎么用" → "深度学习怎么用"）
            5. 抽取核心概念（例如："如何实现" → "实现方法 技术方案"）
            6. 中文优先，必要时可中英混合
            7. 生成 2-5 条多样化的查询

            ## 召回判断标准
            - 用户在追问之前的讨论内容 → shouldRecall = true
            - 用户使用指代词（它、这个、刚才说的）→ shouldRecall = true
            - 用户请求与对话窗口/摘要高度相关 → shouldRecall = true
            - 完全新的话题 → shouldRecall = false
            - 用户明确表示开始新对话 → shouldRecall = false

            ## 重要
            - 只输出 JSON，不要有任何额外的解释或说明
            - 如果不确定，倾向于召回（shouldRecall = true）
        """.trimIndent()
    }
}

/**
 * LLM 规划响应的数据结构（仅用于参考，实际解析使用容错方式）
 */
@Serializable
internal data class LlmPlanningResponse(
    val data: LlmPlanningData? = null
)

@Serializable
internal data class LlmPlanningData(
    val shouldRecall: Boolean? = null,
    val pQueries: List<String>? = null,
    val aQueries: List<String>? = null,
    val reason: String? = null,
    val confidence: Float? = null
)
