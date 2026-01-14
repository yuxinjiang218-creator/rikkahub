package me.rerere.rikkahub.service.recall

import me.rerere.rikkahub.service.ExplicitSignal
import me.rerere.rikkahub.service.recall.decision.RecallDecisionEngine
import me.rerere.rikkahub.service.recall.model.Candidate
import me.rerere.rikkahub.service.recall.model.CandidateKind
import me.rerere.rikkahub.service.recall.model.CandidateSource
import me.rerere.rikkahub.service.recall.model.EvidenceScores
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import me.rerere.rikkahub.service.recall.model.QueryContext
import me.rerere.rikkahub.service.recall.model.RecallAction
import me.rerere.rikkahub.service.recall.model.SettingsSnapshot
import me.rerere.rikkahub.service.recall.model.AssistantSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase G 契约测试：margin veto 灰区保守策略（G2）
 *
 * 验收标准：
 * 1. margin veto 仅在 non-explicit 时生效
 * 2. margin < 0.05 且 best.precision < 0.60 且 best.final >= T_PROBE => 强制 NONE
 * 3. explicit 场景不应用 margin veto
 */
class PhaseGMarginVetoTest {

    /**
     * 创建测试用 QueryContext
     */
    private fun createQueryContext(
        lastUserText: String = "测试查询",
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

    /**
     * 创建测试用 Candidate
     */
    private fun createCandidate(
        id: String,
        kind: CandidateKind = CandidateKind.SNIPPET
    ): Candidate {
        return Candidate(
            id = id,
            source = CandidateSource.P_TEXT,
            kind = kind,
            content = "测试内容",
            anchors = emptyList(),
            cost = 100,
            evidenceKey = "test_evidence",
            evidenceRaw = emptyMap()
        )
    }

    /**
     * 创建测试用 EvidenceScores
     */
    private fun createScores(
        finalScore: Float,
        precision: Float,
        relevance: Float = 0.7f
    ): EvidenceScores {
        return EvidenceScores(
            needScore = 0.6f,
            relevance = relevance,
            precision = precision,
            novelty = 1.0f,
            recency = 0.5f,
            risk = 0.3f,
            redundancyPenalty = 0f,
            finalScore = finalScore
        )
    }

    /**
     * 测试1：margin veto 阻断模糊召回（non-explicit）
     *
     * 场景：
     * - best.final = 0.76 >= T_PROBE (0.75)
     * - secondBest.final = 0.73
     * - margin = 0.03 < 0.05
     * - best.precision = 0.50 < 0.60
     * - explicit = false
     *
     * 期望：action = NONE（vetoReason 包含 "Low margin ambiguous"）
     */
    @Test
    fun testMarginVetoBlocksAmbiguousRecall() {
        val queryContext = createQueryContext(explicit = false)

        val candidate1 = createCandidate("candidate_1")
        val candidate2 = createCandidate("candidate_2")

        // best.final = 0.76, secondBest.final = 0.73 => margin = 0.03 < 0.05
        // best.precision = 0.50 < 0.60
        val scores1 = createScores(finalScore = 0.76f, precision = 0.50f)
        val scores2 = createScores(finalScore = 0.73f, precision = 0.45f)

        val scoredCandidates = listOf(
            candidate1 to scores1,
            candidate2 to scores2
        )

        val result = RecallDecisionEngine.decide(scoredCandidates, queryContext)

        // 验证：action = NONE（margin veto 生效）
        assertEquals(RecallAction.NONE, result.action, "margin veto 应阻断模糊召回")
        assertEquals(null, result.selectedCandidate, "不应选中任何候选")

        // 验证：vetoReason 包含 "Low margin ambiguous"
        assertNotNull(result.vetoReason, "vetoReason 不应为 null")
        assertTrue(
            result.vetoReason!!.contains("Low margin ambiguous"),
            "vetoReason 应包含 'Low margin ambiguous'，实际：${result.vetoReason}"
        )

        // 验证：vetoReason 包含 margin 和 precision 值
        assertTrue(
            result.vetoReason!!.contains("margin=") && result.vetoReason!!.contains("precision="),
            "vetoReason 应包含 margin 和 precision 值"
        )
    }

    /**
     * 测试2：margin veto 不应用于 explicit
     *
     * 场景：
     * - best.final = 0.76 >= T_PROBE (0.75)
     * - secondBest.final = 0.73
     * - margin = 0.03 < 0.05
     * - best.precision = 0.50 < 0.60
     * - explicit = true
     *
     * 期望：action != NONE（不因 margin veto 返回 NONE，仍按 explicit 逻辑）
     */
    @Test
    fun testMarginVetoNotAppliedToExplicit() {
        val queryContext = createQueryContext(explicit = true)

        val candidate1 = createCandidate("candidate_1")
        val candidate2 = createCandidate("candidate_2")

        // 同样的分数结构，但 explicit=true
        val scores1 = createScores(finalScore = 0.76f, precision = 0.50f)
        val scores2 = createScores(finalScore = 0.73f, precision = 0.45f)

        val scoredCandidates = listOf(
            candidate1 to scores1,
            candidate2 to scores2
        )

        val result = RecallDecisionEngine.decide(scoredCandidates, queryContext)

        // 验证：action != NONE（margin veto 不应用于 explicit）
        // explicit 场景按 explicit 规则：SNIPPET + relevance>=0.55 => PROBE_VERBATIM_SNIPPET
        // 但这里 relevance=0.7 >=0.55，应返回 PROBE_VERBATIM_SNIPPET
        assertEquals(
            RecallAction.PROBE_VERBATIM_SNIPPET,
            result.action,
            "explicit 场景不应因 margin veto 返回 NONE"
        )

        // 验证：vetoReason = null（未被 veto）
        assertEquals(null, result.vetoReason, "explicit 场景 vetoReason 应为 null")

        // 验证：选中了候选
        assertEquals(candidate1, result.selectedCandidate, "应选中 candidate_1")
    }

    /**
     * 测试3：margin 足够大时不触发 veto
     *
     * 场景：
     * - best.final = 0.80
     * - secondBest.final = 0.70
     * - margin = 0.10 >= 0.05
     * - best.precision = 0.50 < 0.60
     * - explicit = false
     *
     * 期望：action = PROBE_VERBATIM_SNIPPET（不触发 veto）
     */
    @Test
    fun testMarginVetoNotTriggeredWhenMarginLarge() {
        val queryContext = createQueryContext(explicit = false)

        val candidate1 = createCandidate("candidate_1")
        val candidate2 = createCandidate("candidate_2")

        // margin = 0.10 >= 0.05，不应触发 veto
        val scores1 = createScores(finalScore = 0.80f, precision = 0.50f)
        val scores2 = createScores(finalScore = 0.70f, precision = 0.45f)

        val scoredCandidates = listOf(
            candidate1 to scores1,
            candidate2 to scores2
        )

        val result = RecallDecisionEngine.decide(scoredCandidates, queryContext)

        // 验证：action = PROBE_VERBATIM_SNIPPET（margin 足够大，不触发 veto）
        assertEquals(
            RecallAction.PROBE_VERBATIM_SNIPPET,
            result.action,
            "margin 足够大时不应触发 veto"
        )

        // 验证：vetoReason = null（未被 veto）
        assertEquals(null, result.vetoReason, "vetoReason 应为 null")
    }

    /**
     * 测试4：precision 足够高时不触发 veto
     *
     * 场景：
     * - best.final = 0.76
     * - secondBest.final = 0.73
     * - margin = 0.03 < 0.05
     * - best.precision = 0.70 >= 0.60
     * - explicit = false
     *
     * 期望：action = PROBE_VERBATIM_SNIPPET（不触发 veto）
     */
    @Test
    fun testMarginVetoNotTriggeredWhenPrecisionHigh() {
        val queryContext = createQueryContext(explicit = false)

        val candidate1 = createCandidate("candidate_1")
        val candidate2 = createCandidate("candidate_2")

        // margin < 0.05，但 precision = 0.70 >= 0.60，不应触发 veto
        val scores1 = createScores(finalScore = 0.76f, precision = 0.70f)
        val scores2 = createScores(finalScore = 0.73f, precision = 0.65f)

        val scoredCandidates = listOf(
            candidate1 to scores1,
            candidate2 to scores2
        )

        val result = RecallDecisionEngine.decide(scoredCandidates, queryContext)

        // 验证：action = PROBE_VERBATIM_SNIPPET（precision 足够高，不触发 veto）
        assertEquals(
            RecallAction.PROBE_VERBATIM_SNIPPET,
            result.action,
            "precision 足够高时不应触发 veto"
        )

        // 验证：vetoReason = null（未被 veto）
        assertEquals(null, result.vetoReason, "vetoReason 应为 null")
    }

    /**
     * 测试5：best.final < T_PROBE 时不触发 veto
     *
     * 场景：
     * - best.final = 0.70 < T_PROBE (0.75)
     * - secondBest.final = 0.68
     * - margin = 0.02 < 0.05
     * - best.precision = 0.50 < 0.60
     * - explicit = false
     *
     * 期望：action = NONE（但不是 margin veto，是分数不够）
     */
    @Test
    fun testMarginVetoNotTriggeredWhenScoreBelowThreshold() {
        val queryContext = createQueryContext(explicit = false)

        val candidate1 = createCandidate("candidate_1")
        val candidate2 = createCandidate("candidate_2")

        // best.final = 0.70 < T_PROBE，本来就不会召回
        val scores1 = createScores(finalScore = 0.70f, precision = 0.50f)
        val scores2 = createScores(finalScore = 0.68f, precision = 0.45f)

        val scoredCandidates = listOf(
            candidate1 to scores1,
            candidate2 to scores2
        )

        val result = RecallDecisionEngine.decide(scoredCandidates, queryContext)

        // 验证：action = NONE（分数不够）
        assertEquals(RecallAction.NONE, result.action, "分数不够时应返回 NONE")

        // 验证：vetoReason 不包含 "Low margin ambiguous"（不是 margin veto）
        if (result.vetoReason != null) {
            assertFalse(
                result.vetoReason!!.contains("Low margin ambiguous"),
                "分数不够时不应因 margin veto 阻断，实际：${result.vetoReason}"
            )
        }
    }

    /**
     * 测试6：只有一个候选时不触发 veto
     *
     * 场景：
     * - 只有一个候选
     * - best.final = 0.76 >= T_PROBE
     * - best.precision = 0.50 < 0.60
     * - explicit = false
     *
     * 期望：action = PROBE_VERBATIM_SNIPPET（margin 视为 1.0，不触发 veto）
     */
    @Test
    fun testMarginVetoNotTriggeredWhenSingleCandidate() {
        val queryContext = createQueryContext(explicit = false)

        val candidate1 = createCandidate("candidate_1")

        // 只有一个候选，margin 视为 1.0
        val scores1 = createScores(finalScore = 0.76f, precision = 0.50f)

        val scoredCandidates = listOf(
            candidate1 to scores1
        )

        val result = RecallDecisionEngine.decide(scoredCandidates, queryContext)

        // 验证：action = PROBE_VERBATIM_SNIPPET（只有一个候选，不触发 veto）
        assertEquals(
            RecallAction.PROBE_VERBATIM_SNIPPET,
            result.action,
            "只有一个候选时不应触发 veto"
        )

        // 验证：vetoReason = null（未被 veto）
        assertEquals(null, result.vetoReason, "vetoReason 应为 null")
    }
}
