package me.rerere.rikkahub.service.recall.model

import kotlinx.serialization.Serializable

/**
 * 证据评分
 *
 * 评分算法（写死）：
 * - relevance（相关性）：P源为 keyword hit rate + rank 归一化；A源为 maxCosSim 归一化（<0.3 丢弃）
 * - precision（精确度）：title 命中 => 1.0；命中显式短语 => 0.7；否则 0.3
 * - novelty（新颖性）：前 120 chars 在 windowTexts 中出现 => 0；否则 1
 * - recency（时新性）：P源 = nodeIndex / maxNodeIndex；A源 = createdAt 归一化
 * - risk（风险）：clamp(1 - relevance + (explicit==false && precision<0.5 ? 0.15 : 0), 0, 1)
 * - redundancyPenalty（重复惩罚）：相同 contentHash 或 candidateId 在冷却内 => 1；否则 0
 *
 * finalScore = (0.40*relevance + 0.20*precision + 0.20*novelty + 0.10*needScore + 0.10*recency) * (1-risk) * (1-redundancyPenalty)
 *
 * @param needScore 需求分数（来自 NeedGate 或显式信号）
 * @param relevance 相关性分数 [0, 1]
 * @param precision 精确度分数 [0, 1]
 * @param novelty 新颖性分数 [0, 1]
 * @param recency 时新性分数 [0, 1]
 * @param risk 风险分数 [0, 1]
 * @param redundancyPenalty 重复惩罚分数 [0, 1]
 * @param finalScore 最终分数 [0, 1]
 */
@Serializable
data class EvidenceScores(
    val needScore: Float = 0f,
    val relevance: Float = 0f,
    val precision: Float = 0f,
    val novelty: Float = 0f,
    val recency: Float = 0f,
    val risk: Float = 0f,
    val redundancyPenalty: Float = 0f,
    val finalScore: Float = 0f
) {
    companion object {
        /** 需求阈值 */
        const val T_NEED = 0.55f

        /** 试探阈值 */
        const val T_PROBE = 0.75f

        /** 填充阈值 */
        const val T_FILL = 0.88f

        /** 风险阻断阈值 */
        const val RISK_BLOCK = 0.60f
    }
}
