package me.rerere.rikkahub.service.recall.gate

import me.rerere.rikkahub.service.recall.model.QueryContext

/**
 * NeedGate（需求门控）
 *
 * 基于启发式规则判断是否需要召回。
 * 阈值：T_NEED = 0.55
 *
 * 规则（写死，不包含显式逐字关键词）：
 * - 回指词（那个/这段/上次/之前/刚才/你说的/我们讨论过/继续/接着）=> +0.35
 * - 新话题（另外/顺便/换个/新问题/不相关）=> -0.30
 * - 短文本（<=8 chars）含回指词 => +0.15
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

    /** 回指词列表 */
    private val ANAPHORA_WORDS = listOf(
        "那个", "这段", "上次", "之前", "刚才", "你说的",
        "我们讨论过", "继续", "接着", "按你刚给的方案",
        "这首", "这个", "那首", "该", "上述", "刚才说的"
    )

    /** 新话题词列表 */
    private val NEW_TOPIC_WORDS = listOf(
        "另外", "顺便", "换个", "新问题", "不相关"
    )

    /**
     * Phase J3: 对象词列表（指代清晰加分）
     * 与回指词组合触发时额外 +0.25
     *
     * 注意：这些词应该不与 ANAPHORA_WORDS 中的词重叠，
     * 否则会导致一个词既触发 anaphora 又触发 object 加分
     */
    private val OBJECT_WORDS = listOf(
        "诗", "代码", "方案", "步骤", "公式", "参数",
        "阈值", "设置", "配置", "实现", "逻辑"
    )

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
     * 计算需求分数（启发式评分）
     *
     * @param queryContext 查询上下文
     * @return 需求分数 [0, 1]
     */
    fun computeNeedScoreHeuristic(queryContext: QueryContext): Float {
        val text = queryContext.lastUserText
        var score = 0f

        // 检测回指词
        val hasAnaphora = ANAPHORA_WORDS.any { text.contains(it) }
        if (hasAnaphora) {
            score += 0.35f
        }

        // Phase J3: 指代清晰加分（组合触发：回指词 + 对象词）
        if (hasAnaphora) {
            val hasObject = OBJECT_WORDS.any { text.contains(it) }
            if (hasObject) {
                score += 0.25f
            }
        }

        // 检测新话题词
        val hasNewTopic = NEW_TOPIC_WORDS.any { text.contains(it) }
        if (hasNewTopic) {
            score -= 0.30f
        }

        // 短文本 + 回指词
        if (text.length <= 8 && hasAnaphora) {
            score += 0.15f
        }

        return score.coerceIn(0f, 1f)
    }

    /**
     * 获取阈值（用于日志和调试）
     */
    fun getThreshold(): Float = T_NEED
}
