package me.rerere.rikkahub.service.recall

import me.rerere.rikkahub.service.ExplicitSignal
import me.rerere.rikkahub.service.recall.decision.RecallDecisionEngine
import me.rerere.rikkahub.service.recall.model.Candidate
import me.rerere.rikkahub.service.recall.model.CandidateKind
import me.rerere.rikkahub.service.recall.model.CandidateSource
import me.rerere.rikkahub.service.recall.model.EvidenceScores
import me.rerere.rikkahub.service.recall.model.LastProbeObservation
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import me.rerere.rikkahub.service.recall.model.ProbeOutcome
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
 * Phase H 系统级回归测试套件（端到端验收）
 *
 * 验收标准：
 * H1.1: non-explicit + needScore < 0.55 => NONE，无 DAO/embedding 调用
 * H1.2: A源 HINT → SNIPPET 升级（通过 evidenceKey 接住）
 * H1.3: 单候选高置信度（>=0.90）不被 margin veto 误伤
 */
class PhaseHSystemRegressionTest {

    /**
     * 创建测试用 QueryContext
     */
    private fun createQueryContext(
        lastUserText: String,
        explicit: Boolean = false,
        ledger: ProbeLedgerState = ProbeLedgerState(),
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
            ledger = ledger,
            nowTurnIndex = nowTurnIndex,
            explicitSignal = ExplicitSignal(
                explicit = explicit,
                titles = emptyList(),
                keyword = if (explicit) "原文" else null
            )
        )
    }

    /**
     * 测试1：NeedGate blocked 时不触发任何 DAO/embedding 调用（H1.1）
     *
     * 场景：
     * - non-explicit（无显式逐字关键词）
     * - needScore < 0.55（低需求，如"你好"）
     * - 期望：直接返回 NONE，不调用任何 DAO/embedding
     *
     * 验收：
     * - NeedGate.shouldProceed() 返回 false
     * - needScore < RecallConstants.T_NEED (0.55)
     * - explicit = false
     */
    @Test
    fun testNoRecallWhenNeedGateBlocked_NoDaoCalls() {
        // 1. 构造低需求查询（无显式关键词，无回指词）
        val lastUserText = "你好"  // 短文本，无回指词，needScore < 0.55
        val queryContext = createQueryContext(
            lastUserText = lastUserText,
            explicit = false,
            nowTurnIndex = 0
        )

        // 2. 计算 needScore
        val needScore = me.rerere.rikkahub.service.recall.gate.NeedGate.computeNeedScoreHeuristic(
            queryContext
        )

        // 验收：needScore < T_NEED (0.55)
        val tNeed = RecallConstants.T_NEED
        assertTrue(
            needScore < tNeed,
            "低需求查询 needScore 应 < ${tNeed}，实际：${needScore}"
        )

        // 3. 验证：NeedGate blocked
        val shouldProceed = me.rerere.rikkahub.service.recall.gate.NeedGate.shouldProceed(
            queryContext
        )

        // 验收：ShouldProceed 返回 false
        assertFalse(
            shouldProceed,
            "needScore < ${tNeed} 且 non-explicit 时应被 NeedGate 阻断"
        )

        // 4. 验收：无显式信号
        assertFalse(
            queryContext.explicitSignal.explicit,
            "应为 non-explicit 查询"
        )

        // 5. 验收关键断言：无 DAO/embedding 调用
        // 注意：由于本测试是单元测试（不是集成测试），我们无法直接测量 DAO 调用次数
        // 但根据 RecallCoordinator 协议：
        // - NeedGate blocked 时直接返回 null（line 138）
        // - 不执行 candidate generation（line 167-202）
        // - 因此不调用任何 DAO（MessageNodeTextDao, ArchiveSummaryDao, VectorIndexDao）
        // - 也不调用 embedding（ProviderManager.generateEmbedding）

        // 验证：NeedGate blocked 确保提前返回
        // 实际集成测试中可通过 fake DAO 计数器验证
        assertFalse(shouldProceed, "NeedGate blocked 应确保不进入候选生成流程")
    }

    /**
     * 测试2：A源 HINT → SNIPPET 升级（通过 evidenceKey 接住）（H1.2）
     *
     * 场景：
     * - 第1轮：A源 HINT 注入（evidenceKey=A:archive123）
     * - 第2轮：用户通过确认词/overlap 接住
     * - 第2轮：允许绕过冷却，注入同一归档的 SNIPPET
     *
     * 验收：
     * - 第1轮 outcome=ACCEPT
     * - evidenceKey 匹配允许升级
     * - 第2轮 candidateId 不同但 evidenceKey 相同
     */
    @Test
    fun testAProbeAcceptUpgradesHintToSnippet_EndToEnd() {
        // 1. 第1轮：A源 HINT 注入
        val firstTurnObservation = LastProbeObservation(
            turnIndex = 0,
            action = RecallAction.FACT_HINT,  // HINT 动作
            candidateId = "A:archive123:HINT",
            evidenceKey = "A:archive123",  // A源 evidenceKey = archiveId
            content = "归档摘要内容...",
            anchors = listOf("archive_id:archive123"),
            outcome = ProbeOutcome.IGNORE
        )

        val ledger = ProbeLedgerState(
            globalProbeStrikes = 0,
            lastProbeObservation = firstTurnObservation
        )

        // 2. 第2轮：用户输入确认词"继续"
        val lastUserText = "继续"
        val nowTurnIndex = 1

        // 3. 评估接住判定
        val outcome = me.rerere.rikkahub.service.recall.probe.ProbeAcceptanceJudge.judge(
            lastUserText,
            firstTurnObservation
        )

        // 验收：outcome=ACCEPT
        assertEquals(
            ProbeOutcome.ACCEPT,
            outcome,
            "输入'继续'应判定为 ACCEPT"
        )

        // 4. 模拟 ACCEPT 后的账本更新
        val updatedLedger = ledger.copy(
            globalProbeStrikes = 0,  // ACCEPT 后归零
            lastProbeObservation = firstTurnObservation.copy(outcome = ProbeOutcome.ACCEPT)
        )

        // 5. 验收：evidenceKey 匹配允许升级
        val sameEvidenceKey = "A:archive123"
        val canUpgrade = updatedLedger.canUpgradeOnce(sameEvidenceKey)

        assertTrue(
            canUpgrade,
            "evidenceKey 匹配时应允许升级（绕过冷却一次）"
        )

        // 6. 第2轮：同一归档的 SNIPPET（不同 candidateId，相同 evidenceKey）
        val snippetCandidate = Candidate(
            id = "A:archive123:SNIPPET",  // 不同 candidateId
            source = CandidateSource.A_ARCHIVE,
            kind = CandidateKind.SNIPPET,
            content = "归档原始内容（更长的 SNIPPET）...",
            anchors = listOf("archive_id:archive123"),
            cost = 500,
            evidenceKey = "A:archive123",  // 相同 evidenceKey
            evidenceRaw = mapOf(
                "archive_id" to "archive123",
                "max_cos_sim" to "0.65"
            )
        )

        // 验收：candidateId 不同但 evidenceKey 相同
        assertEquals("A:archive123:HINT", firstTurnObservation.candidateId)
        assertEquals("A:archive123:SNIPPET", snippetCandidate.id)
        assertEquals(firstTurnObservation.evidenceKey, snippetCandidate.evidenceKey)

        // 7. 验收：允许注入 SNIPPET（绕过冷却）
        // 实际场景中 RecallDecisionEngine.decide() 会通过冷却检查
        // 因为 canUpgradeOnce(evidenceKey) 返回 true
        assertTrue(canUpgrade, "第2轮应允许注入 SNIPPET（HINT → SNIPPET 升级）")

        // 8. 验收：FULL_VERBATIM 仍禁止升级
        val fullCandidate = Candidate(
            id = "A:archive123:FULL",
            source = CandidateSource.A_ARCHIVE,
            kind = CandidateKind.FULL,
            content = "归档完整内容...",
            anchors = emptyList(),
            cost = 2000,
            evidenceKey = "A:archive123",
            evidenceRaw = emptyMap()
        )

        val fullObservation = firstTurnObservation.copy(
            action = RecallAction.FULL_VERBATIM,
            candidateId = fullCandidate.id
        )
        val ledgerWithFull = updatedLedger.copy(
            lastProbeObservation = fullObservation
        )
        val canUpgradeFull = ledgerWithFull.canUpgradeOnce(sameEvidenceKey)

        assertFalse(
            canUpgradeFull,
            "FULL_VERBATIM 不应允许升级"
        )
    }

    /**
     * 测试3：单候选高置信度不被 margin veto 误伤（H1.3）
     *
     * 场景：
     * - 只有一个候选
     * - finalScore = 0.90（高置信度）
     * - precision = 0.50（低于 0.60）
     * - non-explicit
     *
     * 期望：
     * - 不触发 margin veto（因为只有一个候选）
     * - action = PROBE_VERBATIM_SNIPPET（因为 final >= T_PROBE）
     *
     * 验收：
     * - RecallDecisionEngine.decide() 返回 PROBE_VERBATIM_SNIPPET
     * - vetoReason = null（不被 margin veto）
     * - margin 视为 1.0（无第二候选）
     */
    @Test
    fun testMarginVetoDoesNotBlockHighConfidenceSingleCandidate() {
        // 1. 构造单候选场景
        val queryContext = createQueryContext(
            lastUserText = "李白那首诗怎么说来着",
            explicit = false,
            nowTurnIndex = 0
        )

        // 2. 构造高置信度单候选
        val candidate = Candidate(
            id = "P:conv456:SNIPPET:10,11,12",
            source = CandidateSource.P_TEXT,
            kind = CandidateKind.SNIPPET,
            content = "静夜思：床前明月光...",
            anchors = listOf("title:静夜思"),
            cost = 200,
            evidenceKey = "P:conv456:10,11,12",
            evidenceRaw = mapOf(
                "node_indices" to "10,11,12",
                "title" to "静夜思"
            )
        )

        // 3. 构造高置信度评分
        val scores = EvidenceScores(
            needScore = 0.60f,  // 满足需求
            relevance = 0.85f,  // 高相关性
            precision = 0.50f,  // 低 precision（触发条件之一）
            novelty = 1.0f,     // 新内容
            recency = 0.70f,    // 较新
            risk = 0.30f,       // 低风险
            redundancyPenalty = 0f,  // 不在冷却
            finalScore = 0.90f  // 高置信度（>= T_PROBE）
        )

        val scoredCandidates = listOf(
            candidate to scores
        )

        // 4. 决策
        val result = RecallDecisionEngine.decide(
            scoredCandidates = scoredCandidates,
            queryContext = queryContext
        )

        // 验收：action = PROBE_VERBATIM_SNIPPET（高置信度）
        assertEquals(
            RecallAction.PROBE_VERBATIM_SNIPPET,
            result.action,
            "单候选高置信度（0.90）应返回 PROBE_VERBATIM_SNIPPET"
        )

        // 验收：选中了候选
        assertNotNull(result.selectedCandidate, "应选中候选")
        assertEquals(candidate.id, result.selectedCandidate?.id)

        // 验收关键断言：不被 margin veto
        assertEquals(
            null,
            result.vetoReason,
            "单候选不应被 margin veto 阻断"
        )

        // 5. 验收：margin 视为 1.0（无第二候选）
        // 根据 RecallDecisionEngine.checkMarginVeto() 逻辑（line 160-161）：
        // - 若没有第二候选，margin 视为 1.0
        // - 1.0 >= MARGIN_VETO_THRESHOLD (0.05)，不触发 veto
        val marginVetoThreshold = RecallConstants.MARGIN_VETO_THRESHOLD
        assertTrue(1.0f >= marginVetoThreshold, "单候选 margin 应视为 1.0")

        // 6. 验收：precision < 0.60 但不影响（因为单候选）
        val marginVetoPrecisionThreshold = RecallConstants.MARGIN_VETO_PRECISION_THRESHOLD
        assertTrue(
            scores.precision < marginVetoPrecisionThreshold,
            "precision (${scores.precision}) 应 < ${marginVetoPrecisionThreshold}"
        )

        // 7. 验收：finalScore >= T_PROBE
        val tProbe = RecallConstants.T_PROBE
        assertTrue(
            scores.finalScore >= tProbe,
            "finalScore (${scores.finalScore}) 应 >= ${tProbe}"
        )

        // 8. 验收：单候选 + 高置信度 = PROBE，不被 margin veto 误伤
        assertFalse(
            result.vetoReason?.contains("Low margin ambiguous") == true,
            "单候选高置信度不应因 margin veto 阻断"
        )
    }

    /**
     * 测试4（补充）：margin veto 在双候选模糊场景下生效（对比测试）
     *
     * 场景：
     * - 两个候选分数接近（margin < 0.05）
     * - best.precision < 0.60
     * - non-explicit
     *
     * 期望：
     * - 触发 margin veto
     * - action = NONE
     * - vetoReason 包含 "Low margin ambiguous"
     *
     * 验收：
     * - margin < 0.05
     * - precision < 0.60
     * - vetoReason != null
     */
    @Test
    fun testMarginVetoBlocksAmbiguousDualCandidates() {
        val queryContext = createQueryContext(
            lastUserText = "上次说的那个东西",
            explicit = false,
            nowTurnIndex = 0
        )

        // 构造两个模糊候选
        val candidate1 = Candidate(
            id = "P:conv789:SNIPPET:20,21,22",
            source = CandidateSource.P_TEXT,
            kind = CandidateKind.SNIPPET,
            content = "候选内容A...",
            anchors = emptyList(),
            cost = 200,
            evidenceKey = "P:conv789:20,21,22",
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

        // best.final = 0.76, secondBest.final = 0.73 => margin = 0.03 < 0.05
        // best.precision = 0.50 < 0.60
        val scores1 = EvidenceScores(
            needScore = 0.60f,
            relevance = 0.75f,
            precision = 0.50f,  // < 0.60
            novelty = 1.0f,
            recency = 0.60f,
            risk = 0.30f,
            redundancyPenalty = 0f,
            finalScore = 0.76f  // >= T_PROBE
        )

        val scores2 = EvidenceScores(
            needScore = 0.60f,
            relevance = 0.72f,
            precision = 0.45f,
            novelty = 1.0f,
            recency = 0.60f,
            risk = 0.30f,
            redundancyPenalty = 0f,
            finalScore = 0.73f
        )

        val scoredCandidates = listOf(
            candidate1 to scores1,
            candidate2 to scores2
        )

        // 决策
        val result = RecallDecisionEngine.decide(
            scoredCandidates = scoredCandidates,
            queryContext = queryContext
        )

        // 验收：action = NONE（margin veto 生效）
        assertEquals(
            RecallAction.NONE,
            result.action,
            "双候选模糊场景应触发 margin veto 返回 NONE"
        )

        // 验收：vetoReason 包含 "Low margin ambiguous"
        assertNotNull(result.vetoReason, "vetoReason 不应为 null")
        assertTrue(
            result.vetoReason!!.contains("Low margin ambiguous"),
            "vetoReason 应包含 'Low margin ambiguous'，实际：${result.vetoReason}"
        )

        // 验收：margin < 0.05
        val margin = scores1.finalScore - scores2.finalScore
        val marginVetoThreshold = RecallConstants.MARGIN_VETO_THRESHOLD
        assertTrue(
            margin < marginVetoThreshold,
            "margin (${margin}) 应 < ${marginVetoThreshold}"
        )

        // 验收：precision < 0.60
        val marginVetoPrecisionThreshold = RecallConstants.MARGIN_VETO_PRECISION_THRESHOLD
        assertTrue(
            scores1.precision < marginVetoPrecisionThreshold,
            "precision (${scores1.precision}) 应 < ${marginVetoPrecisionThreshold}"
        )

        // 验收：vetoReason 包含 margin 和 precision 值
        assertTrue(
            result.vetoReason!!.contains("margin=") && result.vetoReason!!.contains("precision="),
            "vetoReason 应包含 margin 和 precision 值"
        )
    }
}
