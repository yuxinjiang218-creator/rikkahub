package me.rerere.rikkahub.util

/**
 * FTS4 排序工具（Kotlin 侧）
 *
 * 职责：
 * - 解析 matchinfo 字节数组
 * - 计算简化的相似度分数
 * - 避免复杂数学，确保跨机型稳定
 *
 * 设计原则：
 * - 命中词数越多，分越高
 * - 不追求学术完美的 BM25
 * - 追求"稳定召回 + 可控性能"
 */
object FtsRanker {

    /**
     * 计算匹配分数（简化版）
     *
     * @param matchInfo FTS4 matchinfo 返回的字节数组
     * @return 相似度分数（命中词数越多越高）
     */
    fun score(matchInfo: ByteArray): Float {
        // 最简单稳定版本：
        // 命中词数越多，分越高
        // 避免复杂数学，跨机型稳定
        var score = 0f
        for (b in matchInfo) {
            if (b.toInt() != 0) score += 1f
        }
        return score
    }

    /**
     * 对候选结果排序并取 Top 10
     *
     * @param candidates FTS 查询候选结果
     * @param queryText 查询文本（用于意图检测）
     * @return 排序后的 Top 10 node_index 列表
     */
    fun rankAndTakeTop(
        candidates: List<me.rerere.rikkahub.data.db.dao.FtsSearchResult>,
        queryText: String = ""
    ): List<Int> {
        // 检测是否是"原文召回"意图
        val isOriginalContentQuery = listOf(
            "原诗", "原文", "原话", "最初", "完整", "复述", "背诵", "再说一遍"
        ).any { queryText.contains(it) }

        android.util.Log.i("FtsRanker", "=== rankAndTakeTop START ===")
        android.util.Log.i("FtsRanker", "isOriginalContentQuery: $isOriginalContentQuery")
        android.util.Log.i("FtsRanker", "candidates count: ${candidates.size}")

        // 找出最大的node_index（通常是最新的用户问题）
        val maxNodeIndex = candidates.maxOfOrNull { it.node_index } ?: -1
        android.util.Log.i("FtsRanker", "maxNodeIndex: $maxNodeIndex")

        return candidates
            .map { result ->
                val baseScore = FtsRanker.score(result.mi)

                // 当用户问"原诗"、"原文"时，优先选择 USER 角色的消息
                // 注意：MessageRole枚举中 SYSTEM=0, USER=1, ASSISTANT=2, TOOL=3
                var adjustedScore = if (isOriginalContentQuery) {
                    when (result.role) {
                        1 -> baseScore * 1.5f  // USER 角色提升 (ordinal=1)
                        2 -> baseScore * 0.7f  // ASSISTANT 角色降低 (ordinal=2)
                        else -> baseScore
                    }
                } else {
                    baseScore
                }

                // 惩罚最后一个node（避免召回用户刚问的问题本身）
                if (isOriginalContentQuery && result.node_index == maxNodeIndex) {
                    val beforePenalty = adjustedScore
                    adjustedScore *= 0.01f  // 极严重降低分数
                    android.util.Log.i("FtsRanker", "node ${result.node_index}: last node penalty applied - before=$beforePenalty, after=$adjustedScore")
                }

                android.util.Log.i("FtsRanker", "node ${result.node_index}: baseScore=$baseScore, role=${result.role}, adjustedScore=$adjustedScore")

                result.node_index to adjustedScore
            }
            .sortedByDescending { it.second }
            .take(10)
            .also {
                android.util.Log.i("FtsRanker", "=== Top 10 nodes ===")
                it.forEach { (nodeIndex, score) ->
                    android.util.Log.i("FtsRanker", "  - node $nodeIndex: score=$score")
                }
            }
            .map { it.first }
    }

    /**
     * 归一化搜索文本（复用TextNormalization的逻辑）
     */
    private fun normalizeForSearch(input: String): String {
        val sb = StringBuilder(input.length * 2)
        for (ch in input) {
            when {
                ch.isWhitespace() -> sb.append(' ')
                ch.code in 0x4E00..0x9FFF -> { sb.append(ch).append(' ') }
                ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
                else -> sb.append(' ')
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
