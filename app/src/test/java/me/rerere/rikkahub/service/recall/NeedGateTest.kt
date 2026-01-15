package me.rerere.rikkahub.service.recall

import me.rerere.rikkahub.service.ExplicitSignal
import me.rerere.rikkahub.service.recall.gate.NeedGate
import me.rerere.rikkahub.service.recall.model.AssistantSnapshot
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import me.rerere.rikkahub.service.recall.model.QueryContext
import me.rerere.rikkahub.service.recall.model.SettingsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NeedGate 单元测试
 *
 * 验收标准：
 * 1. 回指词检测 => needScore >= 0.55
 * 2. 新话题检测 => needScore < 0.55
 * 3. 短文本 + 回指词 => needScore >= 0.55
 * 4. 显式信号 => shouldProceed = true（无论 needScore）
 */
class NeedGateTest {

    /**
     * 创建测试用 QueryContext
     */
    private fun createQueryContext(
        lastUserText: String,
        explicit: Boolean = false
    ): QueryContext {
        return QueryContext(
            conversationId = "test_conv",
            lastUserText = lastUserText,
            runningSummary = null,
            windowTexts = emptyList(),
            settingsSnapshot = SettingsSnapshot(
                enableVerbatimRecall = true,
                enableArchiveRecall = false,
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
                keyword = if (explicit) "原文" else null
            )
        )
    }

    @Test
    fun testAnaphoraDetection() {
        val context = createQueryContext("那个方案到底怎么样？")  // 10 个字符，>8
        val needScore = NeedGate.computeNeedScoreHeuristic(context)
        val shouldProceed = NeedGate.shouldProceed(context)

        // Phase J3: "那个" + "方案" => 0.35 + 0.25 = 0.60 >= 0.55 => 通过
        assertEquals(0.60f, needScore, 0.001f, "回指词+对象词得分应该是 0.60")
        assertTrue(shouldProceed, "回指词+对象词应该通过 NeedGate")
    }

    @Test
    fun testNewTopicDetection() {
        val context = createQueryContext("另外问个新问题")
        val needScore = NeedGate.computeNeedScoreHeuristic(context)
        val shouldProceed = NeedGate.shouldProceed(context)

        // 新话题 => needScore < 0.55
        assertTrue(needScore < 0.55f, "新话题不应该触发召回 (needScore=$needScore)")
        assertFalse(shouldProceed, "新话题不应该通过 NeedGate")
    }

    @Test
    fun testShortTextWithAnaphora() {
        val context = createQueryContext("继续")
        val needScore = NeedGate.computeNeedScoreHeuristic(context)
        val shouldProceed = NeedGate.shouldProceed(context)

        // 短文本 + 回指词 => needScore = 0.35 + 0.15 = 0.50 < 0.55
        assertEquals(0.50f, needScore, "短文本+回指词得分应该是 0.50")
        assertFalse(shouldProceed, "短文本+回指词不应该通过 NeedGate（需要显式信号或更高分数）")
    }

    @Test
    fun testExplicitSignal() {
        val context = createQueryContext("请给出原文", explicit = true)
        val shouldProceed = NeedGate.shouldProceed(context)

        // 显式信号 => 无论 needScore 都应该通过
        assertTrue(shouldProceed, "显式信号应该通过 NeedGate")
    }

    @Test
    fun testThreshold() {
        val context = createQueryContext("你好")
        val needScore = NeedGate.computeNeedScoreHeuristic(context)
        val threshold = NeedGate.getThreshold()

        // 验证阈值是 0.55
        assertEquals(0.55f, threshold, "阈值应该是 0.55")
        // 普通问候语不应该触发
        assertTrue(needScore < 0.55f, "普通问候语不应该触发召回 (needScore=$needScore)")
    }

    @Test
    fun testMultipleAnaphora() {
        val context = createQueryContext("你刚才说的那段代码，再详细解释一下")
        val needScore = NeedGate.computeNeedScoreHeuristic(context)
        val shouldProceed = NeedGate.shouldProceed(context)

        // Phase J3: "你刚才说的" + "代码" => 0.35 + 0.25 = 0.60 >= 0.55 => 通过
        assertEquals(0.60f, needScore, 0.001f, "回指词+对象词得分应该是 0.60")
        assertTrue(shouldProceed, "回指词+对象词应该通过 NeedGate")
    }

    @Test
    fun testNoScoreClamp() {
        val context1 = createQueryContext("另外 换个 新问题") // 多个新话题词
        val needScore1 = NeedGate.computeNeedScoreHeuristic(context1)

        // needScore 应该被 clamp 到 [0, 1]
        assertTrue(needScore1 >= 0f, "needScore 不应该 < 0 (needScore=$needScore1)")
        assertTrue(needScore1 <= 1f, "needScore 不应该 > 1 (needScore=$needScore1)")
    }
}
