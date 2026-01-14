package me.rerere.rikkahub.service.recall.scorer

import me.rerere.rikkahub.service.recall.model.Candidate
import me.rerere.rikkahub.service.recall.model.CandidateSource
import me.rerere.rikkahub.service.recall.model.EvidenceScores
import me.rerere.rikkahub.service.recall.model.QueryContext

/**
 * 证据评分器（EvidenceScorer）
 *
 * 可复现评分算法（写死）：
 * - relevance（相关性）：P源为 keyword hit rate + rank 归一化
 * - precision（精确度）：title 命中 => 1.0；命中显式短语 => 0.7；否则 0.3
 * - novelty（新颖性）：前 120 chars 在 windowTexts 中出现 => 0；否则 1
 * - recency（时新性）：P源 = nodeIndex / maxNodeIndex
 * - risk（风险）：clamp(1 - relevance + (explicit==false && precision<0.5 ? 0.15 : 0), 0, 1)
 * - redundancyPenalty（重复惩罚）：相同 contentHash 或 candidateId 在冷却内 => 1；否则 0
 *
 * finalScore = (0.40*relevance + 0.20*precision + 0.20*novelty + 0.10*needScore + 0.10*recency) * (1-risk) * (1-redundancyPenalty)
 */
object EvidenceScorer {
    /** 最小余弦相似度阈值（A源） */
    private const val MIN_COS_SIM = 0.3f

    /** novelty 检查的字符数 */
    private const val NOVELTY_CHECK_CHARS = 120

    /**
     * 评分入口
     *
     * @param candidate 候选
     * @param queryContext 查询上下文
     * @param needScore 需求分数（从 NeedGate 计算）
     * @param maxNodeIndex 最大 node_index（用于 recency 归一化）
     * @return 证据评分
     */
    fun score(
        candidate: Candidate,
        queryContext: QueryContext,
        needScore: Float,
        maxNodeIndex: Int
    ): EvidenceScores {
        val relevance = computeRelevance(candidate, queryContext)
        val precision = computePrecision(candidate)
        val novelty = computeNovelty(candidate, queryContext)
        val recency = computeRecency(candidate, maxNodeIndex)
        val risk = computeRisk(relevance, precision, queryContext.explicitSignal.explicit)
        val redundancyPenalty = computeRedundancyPenalty(candidate, queryContext)

        val finalScore = (
            0.40f * relevance +
            0.20f * precision +
            0.20f * novelty +
            0.10f * needScore +
            0.10f * recency
        ) * (1f - risk) * (1f - redundancyPenalty)

        return EvidenceScores(
            needScore = needScore,
            relevance = relevance,
            precision = precision,
            novelty = novelty,
            recency = recency,
            risk = risk,
            redundancyPenalty = redundancyPenalty,
            finalScore = finalScore.coerceIn(0f, 1f)
        )
    }

    /**
     * 计算相关性（relevance）
     * - P源：keyword hit rate + rank 归一化
     * - A源：maxCosSim 归一化（<0.3 丢弃 => relevance=0）
     */
    private fun computeRelevance(candidate: Candidate, queryContext: QueryContext): Float {
        return when (candidate.source) {
            CandidateSource.P_TEXT -> computeRelevancePSource(candidate, queryContext)
            CandidateSource.A_ARCHIVE -> {
                // A源：从 evidenceRaw 中获取 maxCosSim（如果有）
                val maxCosSim = candidate.evidenceRaw["max_cos_sim"]?.toFloatOrNull() ?: 0f
                if (maxCosSim < MIN_COS_SIM) return 0f
                return maxCosSim
            }
        }
    }

    /**
     * P源相关性：keyword hit rate
     */
    private fun computeRelevancePSource(candidate: Candidate, queryContext: QueryContext): Float {
        val text = candidate.content.lowercase()
        val query = queryContext.lastUserText.lowercase()

        // 简单的 keyword hit rate
        val queryWords = query.split(Regex("\\s+")).filter { it.length >= 2 }
        if (queryWords.isEmpty()) return 0.5f  // 默认中等相关性

        val hitCount = queryWords.count { word -> text.contains(word) }
        val hitRate = hitCount.toFloat() / queryWords.size

        // 结合 rank 归一化（假设候选已经按相关性排序，这里简化处理）
        return hitRate.coerceIn(0f, 1f)
    }

    /**
     * 计算精确度（precision）
     * - title 命中 => 1.0
     * - 命中显式短语 => 0.7
     * - 否则 0.3
     */
    private fun computePrecision(candidate: Candidate): Float {
        val hasTitle = candidate.anchors.any { it.startsWith("title:") }
        if (hasTitle) return 1.0f

        // 检查是否命中显式短语（从 content 中判断）
        val explicitPhrases = listOf("原文", "全文", "逐字", "一字不差", "复述", "引用")
        val hasExplicitPhrase = explicitPhrases.any { phrase ->
            candidate.content.contains(phrase, ignoreCase = true)
        }
        if (hasExplicitPhrase) return 0.7f

        return 0.3f
    }

    /**
     * 计算新颖性（novelty）
     * - 前 120 chars 在 windowTexts 中出现 => 0
     * - 否则 1
     */
    private fun computeNovelty(candidate: Candidate, queryContext: QueryContext): Float {
        val prefix = candidate.content.take(NOVELTY_CHECK_CHARS)

        // 检查是否在 windowTexts 中出现
        val isInWindow = queryContext.windowTexts.any { windowText ->
            windowText.contains(prefix, ignoreCase = true)
        }

        return if (isInWindow) 0f else 1f
    }

    /**
     * 计算时新性（recency）
     * - P源：nodeIndex / maxNodeIndex
     * - A源：createdAt 归一化（暂未实现，返回 0.5）
     */
    private fun computeRecency(candidate: Candidate, maxNodeIndex: Int): Float {
        return when (candidate.source) {
            CandidateSource.P_TEXT -> {
                // 从 anchors 中提取 node_indices
                val nodeIndicesStr = candidate.anchors
                    .firstOrNull { it.startsWith("node_indices:") }
                    ?.substringAfter("node_indices:") ?: ""

                if (nodeIndicesStr.isEmpty() || maxNodeIndex == 0) return 0.5f

                val nodeIndices = nodeIndicesStr.split(",").mapNotNull { it.toIntOrNull() }
                if (nodeIndices.isEmpty()) return 0.5f

                // 使用平均 node_index
                val avgNodeIndex = nodeIndices.average().toFloat()
                (avgNodeIndex / maxNodeIndex).coerceIn(0f, 1f)
            }
            CandidateSource.A_ARCHIVE -> {
                // A源：从 evidenceRaw 中获取 created_at（如果有）
                val createdAt = candidate.evidenceRaw["created_at"]?.toLongOrNull()
                if (createdAt == null) return 0.5f

                // 简化：使用当前时间戳（实际应该用会话时间范围）
                val now = System.currentTimeMillis() / 1000
                val ageSeconds = now - createdAt
                val ageDays = ageSeconds / 86400f

                // 越新越近：0-30天 => 1.0，30+天 => 线性递减
                (1f - (ageDays / 30f)).coerceIn(0f, 1f)
            }
        }
    }

    /**
     * 计算风险（risk）
     * risk = clamp(1 - relevance + (explicit==false && precision<0.5 ? 0.15 : 0), 0, 1)
     */
    private fun computeRisk(relevance: Float, precision: Float, isExplicit: Boolean): Float {
        var risk = 1f - relevance

        if (!isExplicit && precision < 0.5f) {
            risk += 0.15f
        }

        return risk.coerceIn(0f, 1f)
    }

    /**
     * 计算重复惩罚（redundancyPenalty）
     * - 相同 contentHash 或 candidateId 在冷却内 => 1
     * - 例外：如果上一轮是 ACCEPT 且允许升级，绕过冷却一次（Phase F：使用 evidenceKey 比对）
     * - 否则 0
     */
    private fun computeRedundancyPenalty(candidate: Candidate, queryContext: QueryContext): Float {
        // Phase F：检查是否允许升级绕过冷却（使用 evidenceKey 比对）
        val canUpgrade = queryContext.ledger.canUpgradeOnce(candidate.evidenceKey)
        if (canUpgrade) {
            // 允许升级，绕过冷却一次
            return 0f
        }

        // 检查 candidateId 是否在冷却中
        val isInCooldown = queryContext.ledger.isInCooldown(
            candidateId = candidate.id,
            currentTurnIndex = queryContext.nowTurnIndex
        )

        return if (isInCooldown) 1f else 0f
    }
}
