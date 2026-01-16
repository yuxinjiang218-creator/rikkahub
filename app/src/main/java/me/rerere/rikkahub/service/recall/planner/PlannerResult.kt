package me.rerere.rikkahub.service.recall.planner

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.service.recall.model.QueryContext

/**
 * 召回规划结果（Phase L1）
 *
 * @param shouldRecall 是否强制进入候选生成（可覆盖 NeedGate 的 block）
 * @param pQueries 给 P 源用的查询语句列表（最多 N 条）
 * @param aQueries 给 A 源用的查询语句列表（最多 N 条；可以和 pQueries 相同）
 * @param reason 规划原因（仅日志用）
 * @param confidence 置信度 [0, 1]（仅日志/节流用）
 */
@Serializable
data class PlannerResult(
    val shouldRecall: Boolean,
    val pQueries: List<String>,
    val aQueries: List<String>,
    val reason: String,
    val confidence: Float
) {
    companion object {
        /**
         * 创建空结果（不召回）
         */
        fun noRecall(reason: String = "No recall needed"): PlannerResult {
            return PlannerResult(
                shouldRecall = false,
                pQueries = emptyList(),
                aQueries = emptyList(),
                reason = reason,
                confidence = 0.0f
            )
        }

        /**
         * 创建兜底结果（使用原始查询）
         */
        fun fallback(lastUserText: String, reason: String = "Fallback to original query"): PlannerResult {
            return PlannerResult(
                shouldRecall = true,
                pQueries = listOf(lastUserText),
                aQueries = listOf(lastUserText),
                reason = reason,
                confidence = 0.3f
            )
        }
    }
}

/**
 * 召回规划器接口（Phase L1）
 *
 * 负责分析 QueryContext，决定是否需要召回，并生成检索用的查询语句。
 */
interface RecallPlanner {
    /**
     * 规划召回策略
     *
     * @param queryContext 查询上下文
     * @return 规划结果
     */
    suspend fun plan(queryContext: QueryContext): PlannerResult
}
