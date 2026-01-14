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
     * @return 排序后的 Top 10 node_index 列表
     */
    fun rankAndTakeTop(candidates: List<me.rerere.rikkahub.data.db.dao.FtsSearchResult>): List<Int> {
        return candidates
            .map { it.node_index to FtsRanker.score(it.mi) }
            .sortedByDescending { it.second }
            .take(10)
            .map { it.first }
    }
}
