package me.rerere.rikkahub.service.recall.gate

import me.rerere.rikkahub.service.recall.model.QueryContext
import me.rerere.rikkahub.service.recall.util.BigramUtil

/**
 * NeedGate（需求门控）
 *
 * Phase K: 基于通用信号判断是否需要召回（不再依赖关键词列表）
 *
 * 阈值：T_NEED = 0.55
 *
 * 规则（通用信号，写死）：
 * - 短文本加分：<=10 字符 => +0.30（越短越可能是承接）
 * - 相似度加分：与窗口文本/摘要有 bigram 重叠 => +0.40
 * - 长文本低相似度惩罚：>30 字符且相似度 < 0.10 => -0.20
 * - 最终 clamp 到 [0,1]
 *
 * 联合决策：
 * - explicit == true => 进入候选生成
 * - needScore >= T_NEED => 进入候选生成
 * - 否则 => 直接 NONE（不调用任何 DAO）
 */
object NeedGate {
    /** 需求阈值 */
    private const val T_NEED = 0.55f

    /** 短文本阈值（字符数） */
    private const val SHORT_TEXT_THRESHOLD = 10

    /** 长文本阈值（用于低相似度惩罚） */
    private const val LONG_TEXT_THRESHOLD = 30

    /** 低相似度惩罚阈值 */
    private const val LOW_SIMILARITY_THRESHOLD = 0.10f

    /** 短文本加分 */
    private const val SHORT_TEXT_BONUS = 0.30f

    /** 相似度加分 */
    private const val SIMILARITY_BONUS = 0.40f

    /** 低相似度惩罚 */
    private const val LOW_SIMILARITY_PENALTY = 0.20f

    /**
     * 相似度阈值（用于判断是否与窗口/摘要相关）
     * 大于此阈值认为有足够相关性，触发加分
     */
    private const val MIN_SIMILARITY_FOR_BONUS = 0.15f

    /**
     * 判断是否应该进入候选生成
     *
     * @param queryContext 查询上下文
     * @return true 如果需要召回，false 否则
     */
    fun shouldProceed(queryContext: QueryContext): Boolean {
        val explicit = queryContext.explicitSignal.explicit
        val needScore = computeNeedScoreHeuristic(queryContext)

        return explicit || needScore >= T_NEED
    }

    /**
     * 计算需求分数（Phase K：通用信号，不依赖关键词列表）
     *
     * 规则（按顺序计算）：
     * 1. 短文本加分：<=10 字符 => +0.30
     *    理由：越短越可能是承接（如"继续"、"详细点"）
     * 2. 相似度加分：与窗口/摘要 bigram 重叠 >= 0.15 => +0.40
     *    理由：与上下文高度相关，说明是承接讨论
     * 3. 低相似度惩罚：>30 字符且相似度 < 0.10 => -0.20
     *    理由：长文本且与上下文不相关，可能是新话题
     * 4. 最终 clamp 到 [0, 1]
     *
     * @param queryContext 查询上下文
     * @return 需求分数 [0, 1]
     */
    fun computeNeedScoreHeuristic(queryContext: QueryContext): Float {
        val text = queryContext.lastUserText
        var score = 0f

        // 1. 短文本加分（<=10 字符）
        if (text.length <= SHORT_TEXT_THRESHOLD) {
            score += SHORT_TEXT_BONUS
        }

        // 2. 计算与窗口/摘要的最大相似度
        val windowSimilarity = BigramUtil.computeMaxWindowSimilarity(
            text = text,
            windowTexts = queryContext.windowTexts
        )

        val summarySimilarity = if (!queryContext.runningSummary.isNullOrBlank()) {
            BigramUtil.computeJaccardSimilarity(text, queryContext.runningSummary)
        } else {
            0f
        }

        val maxSimilarity = maxOf(windowSimilarity, summarySimilarity)

        // 3. 相似度加分（>= 0.15）
        if (maxSimilarity >= MIN_SIMILARITY_FOR_BONUS) {
            score += SIMILARITY_BONUS
        }

        // 4. 低相似度惩罚（>30 字符且相似度 < 0.10）
        if (text.length > LONG_TEXT_THRESHOLD && maxSimilarity < LOW_SIMILARITY_THRESHOLD) {
            score -= LOW_SIMILARITY_PENALTY
        }

        return score.coerceIn(0f, 1f)
    }

    /**
     * 获取阈值（用于日志和调试）
     */
    fun getThreshold(): Float = T_NEED
}
