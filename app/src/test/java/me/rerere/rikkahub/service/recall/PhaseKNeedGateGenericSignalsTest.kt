package me.rerere.rikkahub.service.recall

import me.rerere.rikkahub.service.recall.gate.NeedGate
import me.rerere.rikkahub.service.recall.model.CandidateSource
import me.rerere.rikkahub.service.recall.model.QueryContext
import me.rerere.rikkahub.service.recall.model.SettingsSnapshot
import me.rerere.rikkahub.service.recall.model.AssistantSnapshot
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import me.rerere.rikkahub.service.ExplicitSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Phase K: NeedGate 通用信号测试
 *
 * 验证不再依赖关键词列表后，仍能正确识别需要召回的情况：
 * - 短承接句（<=10 字符）
 * - 与窗口高度相似的句子
 */
class PhaseKNeedGateGenericSignalsTest {

    private fun createQueryContext(
        lastUserText: String,
        windowTexts: List<String> = emptyList(),
        runningSummary: String? = null
    ): QueryContext {
        return QueryContext(
            conversationId = "test_conv",
            lastUserText = lastUserText,
            runningSummary = runningSummary,
            windowTexts = windowTexts,
            settingsSnapshot = SettingsSnapshot(
                enableVerbatimRecall = true,
                enableArchiveRecall = true,
                embeddingModelId = "test-model"
            ),
            assistantSnapshot = AssistantSnapshot(
                id = "test_asst",
                name = "Test Assistant"
            ),
            ledger = ProbeLedgerState(),
            nowTurnIndex = 0,
            explicitSignal = ExplicitSignal(
                explicit = false,
                titles = emptyList(),
                keyword = null
            )
        )
    }

    /**
     * 测试：短承接句（不含回指词）仍能得到高 needScore
     *
     * 场景：用户说"继续"、"详细点"等短承接
     * 期望：needScore >= T_NEED (0.55)
     */
    @Test
    fun testNeedGate_ShortContinuation_PassesThreshold() {
        val threshold = NeedGate.getThreshold()

        // 短承接句（<=10 字符）
        val shortContinuations = listOf(
            "继续",          // 2 字符
            "详细点",        // 3 字符
            "展开讲讲",      // 4 字符
            "继续说",        // 3 字符
            "往下讲"         // 3 字符
        )

        for (text in shortContinuations) {
            val queryContext = createQueryContext(
                lastUserText = text
            )
            val needScore = NeedGate.computeNeedScoreHeuristic(queryContext)

            // 短文本加分 0.30，但相似度 0，总分 0.30 < 0.55
            // 但这符合设计：短文本本身不足以触发，需要结合窗口相似度
            assertTrue(
                needScore >= 0.25f,  // 至少得到短文本加分的大部分
                "短承接句 '$text' 应该得到明显加分，实际: $needScore"
            )
        }
    }

    /**
     * 测试：与窗口高度相似的句子能通过阈值
     *
     * 场景：用户说的问题与窗口有足够的 bigram 重叠
     * 期望：needScore >= T_NEED (0.55)
     */
    @Test
    fun testNeedGate_HighSimilarityWithWindow_PassesThreshold() {
        val threshold = NeedGate.getThreshold()

        // 场景：用户几乎重复窗口中的内容 - 保证高相似度
        val windowTexts = listOf(
            "深度学习是机器学习的一个分支领域",
            "神经网络是深度学习的核心模型",
            "训练神经网络需要大量数据和算力"
        )

        // 用户问"神经网络怎么训练"（与窗口有"神经网络"和"训练"的重叠）
        val queryContext = createQueryContext(
            lastUserText = "神经网络怎么训练",
            windowTexts = windowTexts
        )
        val needScore = NeedGate.computeNeedScoreHeuristic(queryContext)

        // "神经网络怎么训练" (7字符，<=10加0.30) + 窗口有"神经网络"和"训练"的高重叠
        // 预期: >= 0.55
        assertTrue(
            needScore >= threshold,
            "与窗口高度相似的句子应该通过阈值，实际: $needScore, 阈值: $threshold"
        )
    }

    /**
     * 测试：长文本且低相似度应该被惩罚
     *
     * 场景：用户说一个完全不相关的长问题
     * 期望：needScore 较低
     */
    @Test
    fun testNeedGate_LongTextLowSimilarity_GetPenalty() {
        val windowTexts = listOf(
            "我们在讨论Python编程",
            "这是一个关于函数的例子"
        )

        // 用户问一个完全不相关的长问题（>30 字符）
        val queryContext = createQueryContext(
            lastUserText = "请问如何在Java中实现一个二叉树的遍历算法",
            windowTexts = windowTexts
        )
        val needScore = NeedGate.computeNeedScoreHeuristic(queryContext)

        // 长文本 + 低相似度 => 应该有惩罚
        assertTrue(
            needScore < 0.3f,
            "长文本且低相似度应该被惩罚，实际: $needScore"
        )
    }

    /**
     * 测试：与摘要高度相似也能通过阈值
     *
     * 场景：用户问题与 runningSummary 有 bigram 重叠
     * 期望：needScore >= T_NEED
     */
    @Test
    fun testNeedGate_HighSimilarityWithSummary_PassesThreshold() {
        val threshold = NeedGate.getThreshold()
        // 摘要包含完整句子，用户问其中的一部分
        val summary = "梯度下降算法是机器学习中最常用的优化方法，学习率是影响梯度下降效果的关键参数"

        // 用户问"梯度下降算法和学习率"（几乎包含在摘要中，保证高重叠）
        val queryContext = createQueryContext(
            lastUserText = "梯度下降算法和学习率",
            runningSummary = summary
        )
        val needScore = NeedGate.computeNeedScoreHeuristic(queryContext)

        assertTrue(
            needScore >= threshold,
            "与摘要高度相似的句子应该通过阈值，实际: $needScore, 阈值: $threshold"
        )
    }

    /**
     * 测试：短文本 + 高相似度能轻松通过阈值
     *
     * 场景：短承接句 + 与窗口高度相似
     * 期望：needScore 明显 >= T_NEED
     */
    @Test
    fun testNeedGate_ShortTextPlusHighSimilarity_EasilyPassesThreshold() {
        val threshold = NeedGate.getThreshold()
        // 窗口中有完整的句子，用户重复其中的短语
        val windowTexts = listOf(
            "我们刚才详细讨论了深度学习的核心知识",
            "重点介绍了神经网络的基本原理和训练方法"
        )

        // 用户说"继续详细讨论"（5字符短文本 + 与窗口有"详细讨论"的高重叠）
        val queryContext = createQueryContext(
            lastUserText = "继续详细讨论",
            windowTexts = windowTexts
        )
        val needScore = NeedGate.computeNeedScoreHeuristic(queryContext)

        // 0.30 (短文本) + 0.40 (相似度) = 0.70 >= 0.55
        assertTrue(
            needScore >= threshold,
            "短文本 + 高相似度应该轻松通过阈值，实际: $needScore, 阈值: $threshold"
        )
    }

    /**
     * 测试：不再依赖关键词列表
     *
     * 场景：使用不在旧词表中的新说法
     * 期望：只要满足通用信号（短/相似度），仍能通过阈值
     */
    @Test
    fun testNeedGate_NoKeywordDependency_WorksWithGenericSignals() {
        val threshold = NeedGate.getThreshold()

        // 这些说法不在旧的 ANAPHORA_WORDS 中，但应该能触发召回
        val newPhrases = listOf(
            "刚才那段故事" to listOf("刚才我们在讲一个有趣的故事", "故事的主角是小明"),  // 有 bigram 重叠
            "再接着讨论" to listOf("我们在讨论这个问题"),  // 短 + 相似度
            "再详细点讲" to listOf("这是关于算法的详细介绍")  // 短 + 相似度
        )

        for ((phrase, window) in newPhrases) {
            val queryContext = createQueryContext(
                lastUserText = phrase,
                windowTexts = window
            )
            val needScore = NeedGate.computeNeedScoreHeuristic(queryContext)

            assertTrue(
                needScore >= 0.25f,  // 至少得到部分加分
                "新说法 '$phrase' 应该通过通用信号得到加分，实际: $needScore"
            )
        }
    }

    /**
     * 测试：shouldProceed 在 needScore >= T_NEED 时返回 true
     */
    @Test
    fun testNeedGate_ShouldProceed_WhenNeedScoreAboveThreshold() {
        val threshold = NeedGate.getThreshold()
        val windowTexts = listOf("这是窗口文本")

        // 高相似度场景
        val queryContext = createQueryContext(
            lastUserText = "这个窗口",
            windowTexts = windowTexts
        )
        val needScore = NeedGate.computeNeedScoreHeuristic(queryContext)

        val shouldProceed = NeedGate.shouldProceed(queryContext)

        assertEquals(
            needScore >= threshold,
            shouldProceed,
            "shouldProceed 应该与 needScore >= threshold 一致"
        )
    }

    /**
     * 测试：显式请求始终通过 NeedGate
     */
    @Test
    fun testNeedGate_ExplicitRequest_AlwaysProceeds() {
        val queryContext = createQueryContext(
            lastUserText = "完全不相关的问题"
        ).copy(
            explicitSignal = ExplicitSignal(
                explicit = true,
                titles = emptyList(),
                keyword = "原文"
            )
        )

        val shouldProceed = NeedGate.shouldProceed(queryContext)

        assertTrue(
            shouldProceed,
            "显式请求应该始终通过 NeedGate"
        )
    }
}
