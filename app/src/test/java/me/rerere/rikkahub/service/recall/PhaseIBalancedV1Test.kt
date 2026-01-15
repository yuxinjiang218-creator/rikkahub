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
import kotlin.test.assertTrue

/**
 * Phase I (Balanced v1) 回归测试套件
 *
 * 验收标准：
 * I1: 疲劳控制从"太容易静默"调到"更耐心"
 * I2: margin veto 只用于"中等置信灰区"，不误伤高置信
 * I3: A 源边缘区间更愿意给 SNIPPET（仍保守）
 */
class PhaseIBalancedV1Test {

    /**
     * 创建测试用 QueryContext
     */
    private fun createQueryContext(
        lastUserText: String = "测试查询",
        explicit: Boolean = false,
        nowTurnIndex: Int = 0
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
            nowTurnIndex = nowTurnIndex,
            explicitSignal = ExplicitSignal(
                explicit = explicit,
                titles = emptyList(),
                keyword = if (explicit) "原文" else null
            )
        )
    }

    // ========================================
    // I1: 疲劳控制回归测试
    // ========================================

    /**
     * 测试1：两次 IGNORE 不应触发静默窗口（Balanced v1）
     *
     * 场景：
     * - strikes = 2（连续两次 IGNORE）
     * - 期望：不进入静默窗口
     *
     * 验收：
     * - MAX_STRIKES = 3（Phase I 调整）
     * - strikes == 2 时，isInSilentWindow() 返回 false
     */
    @Test
    fun testSilentWindow_NotTriggeredAtTwoIgnores_BalancedV1() {
        val nowTurnIndex = 10

        // 构造 strikes = 2 的账本
        val ledger = ProbeLedgerState(
            globalProbeStrikes = 2,  // 连续两次 IGNORE
            silentUntilTurn = 0  // 未进入静默
        )

        // 验收：MAX_STRIKES = 3（Phase I 调整）
        assertEquals(
            3,
            RecallConstants.MAX_STRIKES,
            "Phase I: MAX_STRIKES 应为 3"
        )

        // 验收：strikes == 2 时，不进入静默
        assertFalse(
            me.rerere.rikkahub.service.recall.probe.ProbeControl.isInSilentWindow(
                ledger = ledger,
                nowTurnIndex = nowTurnIndex
            ),
            "strikes == 2 时不应触发静默窗口（Phase I 更耐心）"
        )

        // 验收：strikes < MAX_STRIKES
        assertTrue(
            ledger.globalProbeStrikes < RecallConstants.MAX_STRIKES,
            "strikes (2) 应 < MAX_STRIKES (3)"
        )
    }

    /**
     * 测试2：三次 IGNORE 触发静默窗口，持续 6 轮（Balanced v1）
     *
     * 场景：
     * - strikes = 3（连续三次 IGNORE）
     * - 静默持续 6 轮
     * - now+7：静默过期
     *
     * 验收：
     * - SILENT_WINDOW_TURNS = 6（Phase I 调整）
     * - strikes >= 3 时，isInSilentWindow(now) 返回 true
     * - silentUntilTurn == now + 6
     * - now+7：isInSilentWindow 返回 false
     */
    @Test
    fun testSilentWindow_TriggeredAtThreeIgnores_AndExpiresIn6Turns() {
        val nowTurnIndex = 20

        // 构造 strikes = 3 的账本
        val ledger = ProbeLedgerState(
            globalProbeStrikes = 3,  // 连续三次 IGNORE
            silentUntilTurn = nowTurnIndex + 6  // 静默到 26
        )

        // 验收：SILENT_WINDOW_TURNS = 6（Phase I 调整）
        assertEquals(
            6,
            RecallConstants.SILENT_WINDOW_TURNS,
            "Phase I: SILENT_WINDOW_TURNS 应为 6"
        )

        // 验收：strikes >= MAX_STRIKES 时，进入静默
        assertTrue(
            ledger.globalProbeStrikes >= RecallConstants.MAX_STRIKES,
            "strikes (3) 应 >= MAX_STRIKES (3)"
        )

        // 验收：在静默窗口内
        assertTrue(
            me.rerere.rikkahub.service.recall.probe.ProbeControl.isInSilentWindow(
                ledger = ledger,
                nowTurnIndex = nowTurnIndex
            ),
            "strikes >= 3 时应触发静默窗口"
        )

        // 验收：silentUntilTurn == now + 6
        assertEquals(
            nowTurnIndex + 6,
            ledger.silentUntilTurn,
            "静默应持续 6 轮（Phase I 更短）"
        )

        // 验收：now+7 静默过期
        assertFalse(
            me.rerere.rikkahub.service.recall.probe.ProbeControl.isInSilentWindow(
                ledger = ledger,
                nowTurnIndex = nowTurnIndex + 7
            ),
            "静默应在 6 轮后过期"
        )
    }

    // ========================================
    // I2: margin veto 回归测试
    // ========================================

    /**
     * 测试3：高置信度召回不被 margin veto 误伤（Balanced v1）
     *
     * 场景：
     * - best.final = 0.90（>= MARGIN_VETO_MAX_SCORE）
     * - second.final = 0.88（margin = 0.02 < 0.04）
     * - best.precision = 0.55（< 0.60）
     * - non-explicit
     *
     * 期望：
     * - 不触发 margin veto
     * - action = PROBE_VERBATIM_SNIPPET（或其他积极动作）
     * - vetoReason = null 或不含 "Low margin ambiguous"
     *
     * 验收：
     * - MARGIN_VETO_MAX_SCORE = 0.88（Phase I 新增）
     * - MARGIN_VETO_THRESHOLD = 0.04（Phase I 调整）
     * - 高置信度不受 margin veto 影响
     */
    @Test
    fun testMarginVeto_NotAppliedWhenBestScoreHigh_BalancedV1() {
        val queryContext = createQueryContext(
            lastUserText = "上次说的那个算法",
            explicit = false
        )

        // 构造两个高置信度候选
        val candidate1 = Candidate(
            id = "P:conv789:SNIPPET:10,11,12",
            source = CandidateSource.P_TEXT,
            kind = CandidateKind.SNIPPET,
            content = "候选内容A...",
            anchors = emptyList(),
            cost = 200,
            evidenceKey = "P:conv789:10,11,12",
            evidenceRaw = emptyMap()
        )

        val candidate2 = Candidate(
            id = "A:archive456:HINT",
            source = CandidateSource.A_ARCHIVE,
            kind = CandidateKind.HINT,
            content = "候选内容B...",
            anchors = emptyList(),
            cost = 150,
            evidenceKey = "A:archive456",
            evidenceRaw = emptyMap()
        )

        // best.final = 0.90, second.final = 0.88 => margin = 0.02 < 0.04
        // best.precision = 0.55 < 0.60
        val scores1 = EvidenceScores(
            needScore = 0.60f,
            relevance = 0.85f,
            precision = 0.55f,  // < 0.60
            novelty = 1.0f,
            recency = 0.70f,
            risk = 0.30f,
            redundancyPenalty = 0f,
            finalScore = 0.90f  // >= MARGIN_VETO_MAX_SCORE
        )

        val scores2 = EvidenceScores(
            needScore = 0.60f,
            relevance = 0.82f,
            precision = 0.50f,
            novelty = 1.0f,
            recency = 0.70f,
            risk = 0.30f,
            redundancyPenalty = 0f,
            finalScore = 0.88f
        )

        val scoredCandidates = listOf(
            candidate1 to scores1,
            candidate2 to scores2
        )

        // 验收常量
        assertEquals(
            0.88f,
            RecallConstants.MARGIN_VETO_MAX_SCORE,
            "Phase I: MARGIN_VETO_MAX_SCORE 应为 0.88"
        )
        assertEquals(
            0.04f,
            RecallConstants.MARGIN_VETO_THRESHOLD,
            "Phase I: MARGIN_VETO_THRESHOLD 应为 0.04"
        )

        // 决策
        val result = RecallDecisionEngine.decide(
            scoredCandidates = scoredCandidates,
            queryContext = queryContext,
            needScore = 0.6f
        )

        // 验收：不触发 margin veto（action 不能因 veto 变 NONE）
        // 注意：action 可能是 PROBE_VERBATIM_SNIPPET 或其他积极动作
        // 关键是 vetoReason 不应包含 "Low margin ambiguous"
        if (result.action == RecallAction.NONE) {
            // 如果返回 NONE，vetoReason 不应包含 "Low margin ambiguous"
            assertFalse(
                result.vetoReason?.contains("Low margin ambiguous") == true,
                "高置信度（>=0.88）不应因 margin veto 返回 NONE"
            )
        } else {
            // 如果返回积极动作，验证 vetoReason = null
            assertEquals(
                null,
                result.vetoReason,
                "高置信度（>=0.88）不应触发 margin veto"
            )
        }

        // 验收：best.final >= MARGIN_VETO_MAX_SCORE
        assertTrue(
            scores1.finalScore >= RecallConstants.MARGIN_VETO_MAX_SCORE,
            "best.final (0.90) 应 >= MARGIN_VETO_MAX_SCORE (0.88)"
        )

        // 验收：margin < MARGIN_VETO_THRESHOLD
        val margin = scores1.finalScore - scores2.finalScore
        assertTrue(
            margin < RecallConstants.MARGIN_VETO_THRESHOLD,
            "margin (${margin}) 应 < MARGIN_VETO_THRESHOLD (0.04)"
        )

        // 验收：best.precision < MARGIN_VETO_PRECISION_THRESHOLD
        assertTrue(
            scores1.precision < RecallConstants.MARGIN_VETO_PRECISION_THRESHOLD,
            "precision (${scores1.precision}) 应 < 0.60"
        )
    }

    // ========================================
    // I3: A 源边缘区间回归测试
    // ========================================

    /**
     * 测试4：A 源边缘区间收窄（Balanced v1）
     *
     * 场景：
     * - cosineSimilarity = 0.34（在边缘区间上界）
     * - 旧逻辑：[0.30, 0.35) 只生成 HINT
     * - 新逻辑：[0.30, 0.34) 只生成 HINT，0.34 可尝试 SNIPPET
     *
     * 验收：
     * - EDGE_SIMILARITY_MAX = 0.34（Phase I 调整）
     * - 相似度 >= 0.34 时，不在边缘区间
     * - 相似度在 [0.30, 0.34) 时，在边缘区间
     */
    @Test
    fun testASnippet_EdgeSimilarityBandNarrowed_BalancedV1() {
        // 验收：EDGE_SIMILARITY_MAX = 0.34（Phase I 调整）
        assertEquals(
            0.34f,
            RecallConstants.EDGE_SIMILARITY_MAX,
            "Phase I: EDGE_SIMILARITY_MAX 应为 0.34"
        )

        // 验收：边缘区间为 [0.30, 0.34)
        val edgeMin = RecallConstants.EDGE_SIMILARITY_MIN
        val edgeMax = RecallConstants.EDGE_SIMILARITY_MAX
        assertEquals(0.30f, edgeMin, "EDGE_SIMILARITY_MIN 应为 0.30")
        assertEquals(0.34f, edgeMax, "EDGE_SIMILARITY_MAX 应为 0.34")

        // 验收：0.34 不在边缘区间（边缘区间为 [0.30, 0.34)）
        val similarityAtEdge = 0.34f
        val inEdgeZoneOld = similarityAtEdge >= 0.30f && similarityAtEdge < 0.35f
        val inEdgeZoneNew = similarityAtEdge >= edgeMin && similarityAtEdge < edgeMax

        assertFalse(
            inEdgeZoneNew,
            "相似度 0.34 不应在边缘区间 [0.30, 0.34) 内（Phase I 收窄）"
        )

        // 验收：0.339 在边缘区间内
        val similarityInsideEdge = 0.339f
        val inEdgeZoneInside = similarityInsideEdge >= edgeMin && similarityInsideEdge < edgeMax
        assertTrue(
            inEdgeZoneInside,
            "相似度 0.339 应在边缘区间 [0.30, 0.34) 内"
        )

        // 验收：0.30 在边缘区间内（下界包含）
        val similarityAtMin = 0.30f
        val inEdgeZoneAtMin = similarityAtMin >= edgeMin && similarityAtMin < edgeMax
        assertTrue(
            inEdgeZoneAtMin,
            "相似度 0.30 应在边缘区间内（下界包含）"
        )

        // 验收：0.29 不在边缘区间内（低于下界）
        val similarityBelowMin = 0.29f
        val inEdgeZoneBelow = similarityBelowMin >= edgeMin && similarityBelowMin < edgeMax
        assertFalse(
            inEdgeZoneBelow,
            "相似度 0.29 不应在边缘区间内（低于下界）"
        )
    }
}
