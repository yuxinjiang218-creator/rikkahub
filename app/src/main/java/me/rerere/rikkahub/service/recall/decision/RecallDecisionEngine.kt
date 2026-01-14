package me.rerere.rikkahub.service.recall.decision

import me.rerere.rikkahub.service.recall.model.Candidate
import me.rerere.rikkahub.service.recall.model.CandidateKind
import me.rerere.rikkahub.service.recall.model.EntryStatus
import me.rerere.rikkahub.service.recall.model.EvidenceScores
import me.rerere.rikkahub.service.recall.model.LedgerEntry
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import me.rerere.rikkahub.service.recall.model.QueryContext
import me.rerere.rikkahub.service.recall.model.RecallAction

/**
 * 召回决策引擎（RecallDecisionEngine）
 *
 * 阈值（写死）：
 * - T_PROBE = 0.75
 * - T_FILL = 0.88
 * - RISK_BLOCK = 0.60
 * - T_NEED = 0.55（已在 NeedGate 中定义）
 *
 * 硬性否决：
 * - NeedScore < T_NEED 且非显式 => NONE
 * - novelty==0 且非显式 => NONE
 * - redundancyPenalty==1 => NONE
 * - risk > RISK_BLOCK 且非显式 => NONE
 *
 * 动作映射：
 * 1. **explicit=true**：
 *    - 存在 FULL 候选且 relevance>=0.75 => FULL_VERBATIM
 *    - 存在 SNIPPET 候选且 relevance>=0.55 => PROBE_VERBATIM_SNIPPET
 *    - 否则 NONE
 *
 * 2. **explicit=false**：
 *    - bestCandidate.Final >= T_FILL => FACT_HINT（若 kind==HINT）或 PROBE_VERBATIM_SNIPPET（否则）
 *    - T_PROBE <= best.Final < T_FILL => PROBE_VERBATIM_SNIPPET
 *    - 否则 NONE
 */
object RecallDecisionEngine {
    /** 试探阈值 */
    private const val T_PROBE = 0.75f

    /** 填充阈值 */
    private const val T_FILL = 0.88f

    /** 风险阻断阈值 */
    private const val RISK_BLOCK = 0.60f

    /** 需求阈值（应与 NeedGate.T_NEED 一致） */
    private const val T_NEED = 0.55f

    /** 显式召回的最小相关性阈值（FULL） */
    private const val EXPLICIT_FULL_MIN_RELEVANCE = 0.75f

    /** 显式召回的最小相关性阈值（SNIPPET） */
    private const val EXPLICIT_SNIPPET_MIN_RELEVANCE = 0.55f

    /**
     * 决策入口
     *
     * @param scoredCandidates 评分后的候选列表（候选 -> 评分）
     * @param queryContext 查询上下文
     * @return 决策结果（动作 + 选中的候选）
     */
    fun decide(
        scoredCandidates: List<Pair<Candidate, EvidenceScores>>,
        queryContext: QueryContext
    ): DecisionResult {
        val isExplicit = queryContext.explicitSignal.explicit
        val needScore = queryContext.ledger.recent.size.toFloat()  // 简化：用账本大小代替

        // 硬性否决检查
        val vetoReason = checkHardVeto(scoredCandidates, queryContext, isExplicit, needScore)
        if (vetoReason != null) {
            return DecisionResult(
                action = RecallAction.NONE,
                selectedCandidate = null,
                vetoReason = vetoReason
            )
        }

        // 过滤冷却中的候选
        val validCandidates = scoredCandidates.filter { (candidate, scores) ->
            scores.redundancyPenalty == 0f  // 不在冷却中
        }

        if (validCandidates.isEmpty()) {
            return DecisionResult(
                action = RecallAction.NONE,
                selectedCandidate = null,
                vetoReason = "All candidates in cooldown"
            )
        }

        // 选择最高分候选
        val (bestCandidate, bestScores) = validCandidates.maxByOrNull { it.second.finalScore }!!

        // 根据显式/非显式决定动作
        val action = if (isExplicit) {
            decideExplicit(bestCandidate, bestScores, validCandidates)
        } else {
            decideNonExplicit(bestCandidate, bestScores)
        }

        return DecisionResult(
            action = action,
            selectedCandidate = bestCandidate,
            vetoReason = null
        )
    }

    /**
     * 硬性否决检查
     */
    private fun checkHardVeto(
        scoredCandidates: List<Pair<Candidate, EvidenceScores>>,
        queryContext: QueryContext,
        isExplicit: Boolean,
        needScore: Float
    ): String? {
        // 1. NeedScore < T_NEED 且非显式
        if (!isExplicit && needScore < T_NEED) {
            return "NeedGate blocked (needScore=$needScore < T_NEED=$T_NEED)"
        }

        // 2. novelty==0 且非显式
        if (!isExplicit) {
            val noNovelty = scoredCandidates.any { (_, scores) -> scores.novelty == 0f }
            if (noNovelty) {
                return "No novelty (all candidates already in context)"
            }
        }

        // 3. redundancyPenalty==1
        val allInCooldown = scoredCandidates.all { (_, scores) -> scores.redundancyPenalty == 1f }
        if (allInCooldown && scoredCandidates.isNotEmpty()) {
            return "All candidates in cooldown"
        }

        // 4. risk > RISK_BLOCK 且非显式
        if (!isExplicit) {
            val highRisk = scoredCandidates.any { (_, scores) -> scores.risk > RISK_BLOCK }
            if (highRisk) {
                return "High risk (risk > RISK_BLOCK=$RISK_BLOCK)"
            }
        }

        return null  // 通过硬性否决
    }

    /**
     * 显式请求决策
     */
    private fun decideExplicit(
        candidate: Candidate,
        scores: EvidenceScores,
        allCandidates: List<Pair<Candidate, EvidenceScores>>
    ): RecallAction {
        // 1. 优先选择 FULL 候选（如果 relevance>=0.75）
        if (candidate.kind == CandidateKind.FULL && scores.relevance >= EXPLICIT_FULL_MIN_RELEVANCE) {
            return RecallAction.FULL_VERBATIM
        }

        // 2. 检查是否有任何 FULL 候选满足条件
        val fullCandidate = allCandidates.firstOrNull { (c, s) ->
            c.kind == CandidateKind.FULL && s.relevance >= EXPLICIT_FULL_MIN_RELEVANCE
        }
        if (fullCandidate != null) {
            return RecallAction.FULL_VERBATIM
        }

        // 3. SNIPPET 候选（relevance>=0.55）
        if (candidate.kind == CandidateKind.SNIPPET && scores.relevance >= EXPLICIT_SNIPPET_MIN_RELEVANCE) {
            return RecallAction.PROBE_VERBATIM_SNIPPET
        }

        // 4. 检查是否有任何 SNIPPET 候选满足条件
        val snippetCandidate = allCandidates.firstOrNull { (c, s) ->
            c.kind == CandidateKind.SNIPPET && s.relevance >= EXPLICIT_SNIPPET_MIN_RELEVANCE
        }
        if (snippetCandidate != null) {
            return RecallAction.PROBE_VERBATIM_SNIPPET
        }

        // 否则 NONE
        return RecallAction.NONE
    }

    /**
     * 非显式请求决策
     */
    private fun decideNonExplicit(
        candidate: Candidate,
        scores: EvidenceScores
    ): RecallAction {
        val finalScore = scores.finalScore

        return when {
            finalScore >= T_FILL -> {
                // 高分：优先返回 HINT，否则 SNIPPET
                if (candidate.kind == CandidateKind.HINT) {
                    RecallAction.FACT_HINT
                } else {
                    RecallAction.PROBE_VERBATIM_SNIPPET
                }
            }
            finalScore >= T_PROBE -> {
                // 中等分：PROBE
                RecallAction.PROBE_VERBATIM_SNIPPET
            }
            else -> {
                // 低分：NONE
                RecallAction.NONE
            }
        }
    }

    /**
     * 创建账本条目
     */
    fun createLedgerEntry(
        candidate: Candidate,
        action: RecallAction,
        queryContext: QueryContext
    ): LedgerEntry {
        val contentHash = candidate.content.take(100).hashCode().toString()  // 简化 hash
        val status = if (action == RecallAction.NONE) {
            EntryStatus.SKIPPED_BUDGET  // 简化：假设所有 NONE 都是预算跳过
        } else {
            EntryStatus.SUCCESS
        }

        val cooldownTurns = when (action) {
            RecallAction.PROBE_VERBATIM_SNIPPET,
            RecallAction.FACT_HINT,
            RecallAction.FULL_VERBATIM -> 10
            RecallAction.NONE -> 0
        }

        // 方案A：冷却10轮（now..now+9），保持判定 <= 不动
        // 例如：now=0 时写入，cooldownUntilTurn=9，turnIndex=0..9 命中冷却，turnIndex=10 放行
        val cooldownUntilTurn = if (cooldownTurns > 0) {
            queryContext.nowTurnIndex + cooldownTurns - 1  // 10轮：0..9
        } else {
            queryContext.nowTurnIndex
        }

        return LedgerEntry(
            contentHash = contentHash,
            candidateId = candidate.id,
            action = action,
            turnIndex = queryContext.nowTurnIndex,
            status = status,
            cooldownUntilTurn = cooldownUntilTurn
        )
    }

    /**
     * 决策结果
     */
    data class DecisionResult(
        val action: RecallAction,
        val selectedCandidate: Candidate?,
        val vetoReason: String?
    )
}
