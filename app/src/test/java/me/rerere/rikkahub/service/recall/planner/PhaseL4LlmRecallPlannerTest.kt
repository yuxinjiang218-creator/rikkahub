package me.rerere.rikkahub.service.recall.planner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.ExplicitSignal
import me.rerere.rikkahub.service.recall.model.QueryContext
import me.rerere.rikkahub.service.recall.model.SettingsSnapshot
import me.rerere.rikkahub.service.recall.model.AssistantSnapshot
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid
import okhttp3.OkHttpClient

/**
 * Phase L4: LlmRecallPlanner 单元测试
 *
 * 覆盖：
 * 1. Valid JSON -> PlannerResult 正确解析
 * 2. Invalid output -> 回退 HeuristicRecallPlanner
 * 3. Prompt 里 windowTexts 的拼接格式（必须是 joinToString("\n")）
 */
class PhaseL4LlmRecallPlannerTest {

    /**
     * Fake Provider 用于测试，可控制返回内容
     */
    private class FakeOpenAIProvider(
        private val responseText: String,
        private val captureMessages: MutableList<List<UIMessage>>? = null
    ) : Provider<ProviderSetting.OpenAI> {

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> {
            return emptyList()
        }

        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk {
            // 捕获 messages 用于测试 3
            captureMessages?.add(messages)

            // 返回预设的响应
            return MessageChunk(
                id = "fake-id",
                model = "fake-model",
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = UIMessage.assistant(responseText),
                        message = null,
                        finishReason = "stop"
                    )
                )
            )
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ) = flowOf(
            MessageChunk(
                id = "fake-id",
                model = "fake-model",
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = UIMessage.assistant(responseText),
                        message = null,
                        finishReason = "stop"
                    )
                )
            )
        )

        // 其他方法不需要实现，测试不会调用
        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: me.rerere.ai.provider.ImageGenerationParams,
        ) = throw NotImplementedError()

        override suspend fun generateEmbedding(
            providerSetting: ProviderSetting.OpenAI,
            text: String,
            params: EmbeddingGenerationParams,
        ) = throw NotImplementedError()
    }

    /**
     * 创建测试用 QueryContext
     */
    private fun createQueryContext(
        lastUserText: String,
        explicit: Boolean = false,
        windowTexts: List<String> = emptyList()
    ): QueryContext {
        return QueryContext(
            conversationId = "test_conv",
            lastUserText = lastUserText,
            runningSummary = null,
            windowTexts = windowTexts,
            settingsSnapshot = SettingsSnapshot(
                enableVerbatimRecall = true,
                enableArchiveRecall = true,
                embeddingModelId = null
            ),
            assistantSnapshot = AssistantSnapshot(
                id = "test_assistant",
                name = "Test Assistant"
            ),
            ledger = ProbeLedgerState(),
            nowTurnIndex = 0,
            explicitSignal = ExplicitSignal(
                explicit = explicit,
                titles = emptyList(),
                keyword = null
            )
        )
    }

    /**
     * 测试 1：Valid JSON -> PlannerResult 正确解析
     *
     * 验证 LlmRecallPlanner 能正确解析 JSON 返回值
     */
    @Test
    fun testValidJson_ParsesCorrectly() = runBlocking(Dispatchers.Default) {
        // 构造有效的 JSON 响应
        val validJson = """
            {
              "data": {
                "shouldRecall": true,
                "pQueries": ["q1", "q2"],
                "aQueries": ["a1"],
                "reason": "ok",
                "confidence": 0.9
              }
            }
        """.trimIndent()

        // 创建 FakeProvider 并注册到 ProviderManager
        val capturedMessages = mutableListOf<List<UIMessage>>()
        val fakeProvider = FakeOpenAIProvider(validJson, capturedMessages)
        val providerManager = ProviderManager(OkHttpClient()).apply {
            registerProvider("openai", fakeProvider)
        }

        // 创建 LlmRecallPlanner
        val planningModel = Model(id = Uuid.random(), displayName = "test-model")
        val planningProvider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "Test OpenAI",
            apiKey = "test-key"
        )
        val planner = LlmRecallPlanner(
            providerManager = providerManager,
            planningModel = planningModel,
            planningProvider = planningProvider
        )

        // 执行规划
        val queryContext = createQueryContext(
            lastUserText = "这段代码是什么",
            windowTexts = listOf("那段代码是什么", "这段代码的功能")
        )
        val result = planner.plan(queryContext)

        // 断言：shouldRecall=true
        assertTrue(result.shouldRecall, "shouldRecall 应该为 true")

        // 断言：pQueries=2 条且内容一致
        assertEquals(2, result.pQueries.size, "pQueries 应该有 2 条")
        assertEquals("q1", result.pQueries[0], "pQueries[0] 应该是 q1")
        assertEquals("q2", result.pQueries[1], "pQueries[1] 应该是 q2")

        // 断言：aQueries=1 条且内容一致
        assertEquals(1, result.aQueries.size, "aQueries 应该有 1 条")
        assertEquals("a1", result.aQueries[0], "aQueries[0] 应该是 a1")

        // 断言：reason/confidence 一致
        assertEquals("ok", result.reason, "reason 应该是 ok")
        assertEquals(0.9f, result.confidence, 0.001f, "confidence 应该是 0.9")
    }

    /**
     * 测试 2：Invalid output -> 回退 HeuristicRecallPlanner
     *
     * 验证解析失败时回退到 HeuristicRecallPlanner（真正的 fallback 路径）
     * 使用 NoOpPlannerLogger 避免 Log 依赖问题
     */
    @Test
    fun testInvalidOutput_FallsBackToHeuristic() = runBlocking(Dispatchers.Default) {
        // 构造无效的响应（非 JSON）
        val invalidResponse = "NOT_JSON_AT_ALL"

        // 创建 FakeProvider
        val fakeProvider = FakeOpenAIProvider(invalidResponse)
        val providerManager = ProviderManager(OkHttpClient()).apply {
            registerProvider("openai", fakeProvider)
        }

        // 创建 LlmRecallPlanner（注入 NoOpPlannerLogger）
        val planningModel = Model(id = Uuid.random(), displayName = "test-model")
        val planningProvider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "Test OpenAI",
            apiKey = "test-key"
        )
        val planner = LlmRecallPlanner(
            providerManager = providerManager,
            planningModel = planningModel,
            planningProvider = planningProvider,
            logger = NoOpPlannerLogger()  // 注入 NoOpLogger，避免 Log 依赖
        )

        // 构造 QueryContext
        val lastUserText = "这段代码是什么"
        val queryContext = createQueryContext(
            lastUserText = lastUserText,
            explicit = false
        )

        // 执行规划
        val result = planner.plan(queryContext)

        // 断言：返回 fallback 结果（来自 HeuristicRecallPlanner）
        // HeuristicRecallPlanner 会根据 NeedGate 判断，这里断言基本的 fallback 行为
        assertTrue(
            result.pQueries.contains(lastUserText),
            "pQueries 应该包含原始查询（fallback 行为），实际: ${result.pQueries}"
        )
        assertTrue(
            result.aQueries.contains(lastUserText),
            "aQueries 应该包含原始查询（fallback 行为），实际: ${result.aQueries}"
        )

        // 断言：reason 包含 fallback/heuristic 的语义
        assertTrue(
            result.reason.contains("Heuristic", ignoreCase = true) ||
            result.reason.contains("fallback", ignoreCase = true),
            "reason 应该包含 heuristic 或 fallback 关键词，实际: ${result.reason}"
        )
    }

    /**
     * 测试 3：Prompt 拼接回归保护（windowTexts 必须用 \n 连接）
     *
     * 防止 windowTexts.joinToString("\n") 回退成逗号/空格等
     */
    @Test
    fun testPromptFormat_UsesNewlineSeparator() = runBlocking(Dispatchers.Default) {
        // 构造一个简单的合法 JSON 让测试能跑完
        val validJson = """
            {"shouldRecall":true,"pQueries":["test"],"aQueries":["test"],"reason":"test","confidence":0.8}
        """.trimIndent()

        // 捕获 messages 用于验证 prompt 格式
        val capturedMessages = mutableListOf<List<UIMessage>>()
        val fakeProvider = FakeOpenAIProvider(validJson, capturedMessages)
        val providerManager = ProviderManager(OkHttpClient()).apply {
            registerProvider("openai", fakeProvider)
        }

        // 创建 LlmRecallPlanner
        val planningModel = Model(id = Uuid.random(), displayName = "test-model")
        val planningProvider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "Test OpenAI",
            apiKey = "test-key"
        )
        val planner = LlmRecallPlanner(
            providerManager = providerManager,
            planningModel = planningModel,
            planningProvider = planningProvider
        )

        // 构造 QueryContext：windowTexts = listOf("lineA", "lineB")
        val queryContext = createQueryContext(
            lastUserText = "测试查询",
            windowTexts = listOf("lineA", "lineB")
        )

        // 执行规划
        planner.plan(queryContext)

        // 验证：capturedMessages 应该有一条记录
        assertEquals(1, capturedMessages.size, "应该捕获到一条 messages")

        val messages = capturedMessages[0]
        // 找到 user message（第二条，第一条是 system）
        val userMessage = messages[1]
        assertEquals(me.rerere.ai.core.MessageRole.USER, userMessage.role, "第二条应该是 user message")

        // 提取 user message 的文本内容
        val userText = userMessage.parts
            .filterIsInstance<UIMessagePart.Text>()
            .firstOrNull()
            ?.text

        assertNotNull(userText, "User message 应该包含文本内容")

        // 断言：必须包含 "lineA\nlineB"（严格要求 \n 相邻）
        assertTrue(
            userText.contains("lineA\nlineB"),
            "User prompt 应该包含 'lineA\\nlineB'，实际内容: $userText"
        )

        // 负断言：不包含 "lineA, lineB" 这种错误格式
        val hasCommaSeparator = userText.contains("lineA, lineB") ||
                               userText.contains("lineA,lineB")
        assertTrue(
            !hasCommaSeparator,
            "User prompt 不应该包含逗号分隔的 'lineA, lineB'，实际内容: $userText"
        )
    }
}
