package me.rerere.rikkahub.service.recall

import me.rerere.rikkahub.service.ExplicitSignal
import me.rerere.rikkahub.service.recall.gate.NeedGate
import me.rerere.rikkahub.service.recall.model.QueryContext
import me.rerere.rikkahub.service.recall.model.SettingsSnapshot
import me.rerere.rikkahub.service.recall.model.AssistantSnapshot
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase J3: NeedGate 对"这首诗/这段代码/这个方案"不过度拦截测试
 *
 * 验收标准：
 * J3.1: "这首诗"应通过（needScore >= 0.55）
 * J3.2: "那个"仍不通过
 * J3.3: "另外/换个"仍倾向拦截
 */
class PhaseJ3NeedGateObjectWordsTest {

    /**
     * 创建测试用 QueryContext
     */
    private fun createQueryContext(lastUserText: String): QueryContext {
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
                explicit = false,
                titles = emptyList(),
                keyword = null
            )
        )
    }

    /**
     * 测试用例 1：testNeedGate_ThisPoemPassesThreshold
     *
     * 场景："这首诗"包含回指词 + 对象词
     * - 回指词："这首" => +0.35
     * - 对象词："诗" => +0.25（组合触发）
     * - 总计：0.35 + 0.25 = 0.60 >= 0.55 => 通过
     *
     * 验收要点：
     * - needScore >= T_NEED (0.55)
     * - shouldProceed = true
     */
    @Test
    fun testNeedGate_ThisPoemPassesThreshold() {
        val queryContext = createQueryContext(
            lastUserText = "这首诗"
        )

        val needScore = NeedGate.computeNeedScoreHeuristic(queryContext)
        val shouldProceed = NeedGate.shouldProceed(queryContext)
        val threshold = NeedGate.getThreshold()

        // 验收：needScore >= T_NEED
        assertTrue(
            needScore >= threshold,
            "'这首诗' needScore ($needScore) 应 >= T_NEED ($threshold)"
        )

        // 验收：shouldProceed = true
        assertTrue(
            shouldProceed,
            "'这首诗' 应通过 NeedGate"
        )

        // 验收：needScore 应该是 0.35 + 0.25 = 0.60
        assertTrue(
            needScore >= 0.55f,
            "'这首诗' needScore ($needScore) 应 >= T_NEED (0.55)"
        )
    }

    /**
     * 测试用例 2：testNeedGate_ThisCodePassesThreshold
     *
     * 场景："这段代码"包含回指词 + 对象词
     * - 回指词："这段" => +0.35
     * - 对象词："代码" => +0.25（组合触发）
     * - 总计：0.35 + 0.25 = 0.60 >= 0.55 => 通过
     *
     * 验收要点：
     * - needScore >= T_NEED
     * - shouldProceed = true
     */
    @Test
    fun testNeedGate_ThisCodePassesThreshold() {
        val queryContext = createQueryContext(
            lastUserText = "这段代码"
        )

        val needScore = NeedGate.computeNeedScoreHeuristic(queryContext)
        val shouldProceed = NeedGate.shouldProceed(queryContext)

        // 验收：needScore >= T_NEED
        assertTrue(
            needScore >= NeedGate.getThreshold(),
            "'这段代码' needScore ($needScore) 应 >= T_NEED"
        )

        // 验收：shouldProceed = true
        assertTrue(
            shouldProceed,
            "'这段代码' 应通过 NeedGate"
        )
    }

    /**
     * 测试用例 3：testNeedGate_ThisPlanPassesThreshold
     *
     * 场景："这个方案"包含回指词 + 对象词
     * - 回指词："这个" => +0.35
     * - 对象词："方案" => +0.25（组合触发）
     * - 总计：0.35 + 0.25 = 0.60 >= 0.55 => 通过
     *
     * 验收要点：
     * - needScore >= T_NEED
     * - shouldProceed = true
     */
    @Test
    fun testNeedGate_ThisPlanPassesThreshold() {
        val queryContext = createQueryContext(
            lastUserText = "这个方案"
        )

        val needScore = NeedGate.computeNeedScoreHeuristic(queryContext)
        val shouldProceed = NeedGate.shouldProceed(queryContext)

        // 验收：needScore >= T_NEED
        assertTrue(
            needScore >= NeedGate.getThreshold(),
            "'这个方案' needScore ($needScore) 应 >= T_NEED"
        )

        // 验收：shouldProceed = true
        assertTrue(
            shouldProceed,
            "'这个方案' 应通过 NeedGate"
        )
    }

    /**
     * 测试用例 4：testNeedGate_GenericThatDoesNotPass
     *
     * 场景："那个"只包含回指词，没有对象词
     * - 回指词："那个" => +0.35
     * - 短文本（2 chars <= 8）=> +0.15
     * - 对象词：无
     * - 总计：0.35 + 0.15 = 0.50 < 0.55 => 不通过
     *
     * 验收要点：
     * - needScore < T_NEED
     * - shouldProceed = false
     */
    @Test
    fun testNeedGate_GenericThatDoesNotPass() {
        val queryContext = createQueryContext(
            lastUserText = "那个"
        )

        val needScore = NeedGate.computeNeedScoreHeuristic(queryContext)
        val shouldProceed = NeedGate.shouldProceed(queryContext)

        // 验收：needScore < T_NEED
        assertTrue(
            needScore < NeedGate.getThreshold(),
            "'那个' needScore ($needScore) 应 < T_NEED"
        )

        // 验收：shouldProceed = false
        assertFalse(
            shouldProceed,
            "'那个' 不应通过 NeedGate"
        )

        // 验收：needScore = 0.35 + 0.15 = 0.50（短文本回指词）
        assertEquals(
            0.50f,
            needScore,
            0.001f,
            "'那个' needScore 应为 0.50"
        )
    }

    /**
     * 测试用例 5：testNeedGate_NewTopicStillSuppresses
     *
     * 场景："另外/换个"包含新话题词
     * - 新话题词：-0.30
     * - 总计：负数或很低 => 被拦截
     *
     * 验收要点：
     * - needScore 应较低
     * - shouldProceed = false
     */
    @Test
    fun testNeedGate_NewTopicStillSuppresses() {
        val testCases = listOf("另外", "换个", "换个话题")

        for (testCase in testCases) {
            val queryContext = createQueryContext(lastUserText = testCase)

            val needScore = NeedGate.computeNeedScoreHeuristic(queryContext)
            val shouldProceed = NeedGate.shouldProceed(queryContext)

            // 验收：needScore 应很低（< T_NEED）
            assertTrue(
                needScore < NeedGate.getThreshold(),
                "'$testCase' needScore ($needScore) 应 < T_NEED"
            )

            // 验收：shouldProceed = false
            assertFalse(
                shouldProceed,
                "'$testCase' 不应通过 NeedGate"
            )
        }
    }

    /**
     * 测试用例 6：testNeedGateway_AnaphoraWithoutObjectDoesNotPass
     *
     * 场景：只有回指词，没有对象词
     * - 回指词："这段" => +0.35
     * - 对象词：无
     * - 总计：0.35 < 0.55 => 不通过
     *
     * 验收要点：
     * - 证明必须组合触发才加分
     */
    @Test
    fun testNeedGateway_AnaphoraWithoutObjectDoesNotPass() {
        val queryContext = createQueryContext(
            lastUserText = "这段内容"
        )

        val needScore = NeedGate.computeNeedScoreHeuristic(queryContext)
        val shouldProceed = NeedGate.shouldProceed(queryContext)

        // 验收：needScore < T_NEED（因为没有对象词）
        assertTrue(
            needScore < NeedGate.getThreshold(),
            "'这段内容' needScore ($needScore) 应 < T_NEED（缺少对象词）"
        )

        // 验收：shouldProceed = false
        assertFalse(
            shouldProceed,
            "'这段内容' 不应通过 NeedGate（缺少对象词）"
        )
    }

    /**
     * 测试用例 7：testNeedGateway_AllObjectWordsCovered
     *
     * 场景：验证所有对象词都能触发组合加分
     *
     * 验收要点：
     * - 所有对象词 + 回指词组合 => needScore >= 0.55
     */
    @Test
    fun testNeedGateway_AllObjectWordsCovered() {
        val objectWords = listOf("诗", "代码", "方案", "步骤", "公式", "参数", "阈值", "设置", "配置", "实现", "逻辑")
        val anaphoraWords = listOf("这首", "这个", "那首", "该")

        for (objWord in objectWords) {
            for (anaphoraWord in anaphoraWords) {
                val query = "$anaphoraWord$objWord"
                val queryContext = createQueryContext(lastUserText = query)

                val needScore = NeedGate.computeNeedScoreHeuristic(queryContext)

                // 验收：needScore >= T_NEED (0.60 = 0.35 + 0.25)
                assertTrue(
                    needScore >= NeedGate.getThreshold(),
                    "'$query' needScore ($needScore) 应 >= T_NEED (回指词+对象词组合触发)"
                )
            }
        }
    }
}
