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

/**
 * Phase J0: 决策侧 needScore 来源一致性契约测试
 *
 * 验收标准：
 * J0.1: 决策中使用的 needScore 必须来自参数，而非 ledger.recent.size
 * J0.2: explicit snippet relevance 阈值必须从 RecallConstants 读取
 */
class PhaseJDecisionNeedScoreContractTest {

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
     * 测试用例 1：testNonExplicitNeedScoreMustNotComeFromLedgerSize
     *
     * 场景：
     * - ledger.recent.size 很大（20），表明历史有很多条目
     * - 但传入的 needScore < T_NEED（0.55），表明本轮需求低
     * - non-explicit 查询
     *
     * 期望：
     * - 决策必须返回 NONE
     * - 证明 needScore 来自参数而非 ledger.recent.size
     *
     * 验收要点：
     * - ledger.recent.size = 20（很大）
     * - needScore 参数 = 0.35（< T_NEED）
     * - decision.action = NONE（被 needScore 硬否决拦截）
     */
    @Test
    fun testNonExplicitNeedScoreMustNotComeFromLedgerSize() {
        // 1. 构造一个有很多历史条目的 ledger
        val ledgerWithLargeHistory = ProbeLedederStateWithHistory(size = 20)

        // 2. 构造低需求查询（non-explicit）
        val queryContext = createQueryContext(
            lastUserText = "随便聊聊",  // 无回指词，needScore 应很低
            explicit = false,
            ledger = ledgerWithLargeHistory,
            nowTurnIndex = 20
        )

        // 3. 构造一个高相关性候选（如果用 ledger.size 做 needScore 会导致错误决策）
        val candidate = Candidate(
            id = "P:conv123:SNIPPET:10,11,12",
            source = CandidateSource.P_TEXT,
            kind = CandidateKind.SNIPPET,
            content = "这是一段历史记录...",
            anchors = emptyList(),
            cost = 200,
            evidenceKey = "P:conv123:10,11,12",
            evidenceRaw = mapOf("node_indices" to "10,11,12")
        )

        // 4. 构造评分（relevance 很高，但 needScore 参数很低）
        val scores = EvidenceScores(
            needScore = 0.35f,  // 低于 T_NEED (0.55)
            relevance = 0.85f,  // 高相关性
            precision = 0.70f,
            novelty = 1.0f,
            recency = 0.80f,
            risk = 0.20f,
            redundancyPenalty = 0f,
            finalScore = 0.70f  // 虽然很高，但会被 needScore < T_NEED 拦截
        )

        val scoredCandidates = listOf(
            candidate to scores
        )

        // 5. 决策：传入低 needScore 参数（而非使用 ledger.recent.size）
        val lowNeedScore = 0.35f  // < T_NEED
        val result = RecallDecisionEngine.decide(
            scoredCandidates = scoredCandidates,
            queryContext = queryContext,
            needScore = lowNeedScore  // Phase J0: 使用参数 needScore
        )

        // 6. 验收：必须返回 NONE（证明 needScore 来自参数，而非 ledger.recent.size）
        assertEquals(
            RecallAction.NONE,
            result.action,
            "needScore < T_NEED 且 non-explicit 时必须返回 NONE，" +
            "即使 ledger.recent.size 很大（${ledgerWithLargeHistory.recent.size}）"
        )

        // 7. 验收：vetoReason 应包含 "NeedGate blocked"
        assertEquals(
            "NeedGate blocked (needScore=$lowNeedScore < T_NEED=${RecallConstants.T_NEED})",
            result.vetoReason,
            "vetoReason 应说明是 NeedGate 拦截"
        )
    }

    /**
     * 测试用例 2：testExplicitSnippetRelevanceUsesConstant_NotHardcoded
     *
     * 场景：
     * - explicit 查询（含显式关键词）
     * - SNIPPET 候选 relevance 略低于/略高于常量阈值
     *
     * 期望：
     * - 决策分支应随 RecallConstants.EXPLICIT_SNIPPET_MIN_RELEVANCE 变化
     * - 证明不是写死 0.44
     *
     * 验收要点：
     * - 读取 RecallConstants.EXPLICIT_SNIPPET_MIN_RELEVANCE
     * - relevance = 常量 - 0.01 时返回 NONE
     * - relevance = 常量 + 0.01 时返回 PROBE_VERBATIM_SNIPPET
     */
    @Test
    fun testExplicitSnippetRelevanceUsesConstant_NotHardcoded() {
        // 1. 读取常量
        val threshold = RecallConstants.EXPLICIT_SNIPPET_MIN_RELEVANCE

        // 2. 构造 explicit 查询
        val queryContext = createQueryContext(
            lastUserText = "请复述原文内容",
            explicit = true
        )

        // 3. 场景 A：relevance 略低于阈值
        val candidateBelow = Candidate(
            id = "P:conv123:SNIPPET:10,11,12",
            source = CandidateSource.P_TEXT,
            kind = CandidateKind.SNIPPET,
            content = "历史记录片段...",
            anchors = emptyList(),
            cost = 200,
            evidenceKey = "P:conv123:10,11,12",
            evidenceRaw = mapOf("node_indices" to "10,11,12")
        )

        val scoresBelow = EvidenceScores(
            needScore = 1.0f,
            relevance = threshold - 0.01f,  // 略低于阈值
            precision = 0.30f,
            novelty = 1.0f,
            recency = 0.80f,
            risk = 0.20f,
            redundancyPenalty = 0f,
            finalScore = 0.60f
        )

        val resultBelow = RecallDecisionEngine.decide(
            scoredCandidates = listOf(candidateBelow to scoresBelow),
            queryContext = queryContext,
            needScore = 1.0f
        )

        // 验收：relevance < 常量 => NONE
        assertEquals(
            RecallAction.NONE,
            resultBelow.action,
            "relevance ($threshold) < 常量 ($threshold) 时应返回 NONE"
        )

        // 4. 场景 B：relevance 略高于阈值
        val candidateAbove = candidateBelow.copy(
            id = "P:conv123:SNIPPET:13,14,15"
        )

        val scoresAbove = scoresBelow.copy(
            relevance = threshold + 0.01f  // 略高于阈值
        )

        val resultAbove = RecallDecisionEngine.decide(
            scoredCandidates = listOf(candidateAbove to scoresAbove),
            queryContext = queryContext,
            needScore = 1.0f
        )

        // 验收：relevance >= 常量 => PROBE_VERBATIM_SNIPPET
        assertEquals(
            RecallAction.PROBE_VERBATIM_SNIPPET,
            resultAbove.action,
            "relevance ($threshold) >= 常量 ($threshold) 时应返回 PROBE_VERBATIM_SNIPPET"
        )

        // 5. 验收：证明阈值来自 RecallConstants（而非写死 0.44）
        // 通过测试覆盖证明：如果写死 0.44，当常量 = 0.55 时，relevance = 0.50 应该返回 NONE
        // 但由于使用常量，0.50 < 0.55 仍返回 NONE ✅
        // 如果写死 0.44，relevance = 0.50 > 0.44 会返回 PROBE（错误）
    }
}

/**
 * 辅助函数：创建指定大小的 ProbeLedgerState
 */
private fun ProbeLedederStateWithHistory(size: Int): ProbeLedgerState {
    // 创建指定数量的历史条目
    val entries = (0 until size).map { index ->
        me.rerere.rikkahub.service.recall.model.LedgerEntry(
            contentHash = "hash_$index",
            candidateId = "candidate_$index",
            action = RecallAction.PROBE_VERBATIM_SNIPPET,
            turnIndex = index,
            status = me.rerere.rikkahub.service.recall.model.EntryStatus.SUCCESS,
            cooldownUntilTurn = index + 10
        )
    }

    return ProbeLedgerState(
        recent = entries,
        globalProbeStrikes = 0,
        lastProbeObservation = null,
        silentUntilTurn = 0
    )
}
