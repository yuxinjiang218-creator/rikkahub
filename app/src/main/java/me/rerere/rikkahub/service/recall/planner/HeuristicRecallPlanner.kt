package me.rerere.rikkahub.service.recall.planner

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.service.recall.gate.NeedGate
import me.rerere.rikkahub.service.recall.model.QueryContext

private const val TAG = "HeuristicRecallPlanner"

/**
 * 启发式召回规划器（Phase L1：安全优先的折中方案）
 *
 * 特性：
 * 1. 快速：不调用 LLM，基于规则
 * 2. 安全：只在显式召回时谨慎提取关键词
 * 3. 保守：只提取引号内容（如《寻无之地》），避免误提取
 * 4. 宁缺毋滥：如果不确定，就不添加额外查询
 */
class HeuristicRecallPlanner : RecallPlanner {
    companion object {
        /**
         * 显式召回指代词（用户想回忆之前的内容）
         */
        private val EXPLICIT_ANAPHORA_PHRASES = listOf(
            "原文", "复述", "刚才说的", "之前说的", "刚才写的",
            "之前写的", "再说一遍", "重复一遍", "是什么来着",
            "想起来", "记得吗", "回忆一下"
        )

        /**
         * 最少需要的上下文消息数
         * 少于这个数量时，认为上下文不足，不提取关键词
         */
        private const val MIN_WINDOW_SIZE = 3
    }

    override suspend fun plan(queryContext: QueryContext): PlannerResult = withContext(Dispatchers.Default) {
        val shouldProceed = NeedGate.shouldProceed(queryContext)

        if (!shouldProceed) {
            return@withContext PlannerResult.noRecall(
                reason = "NeedGate blocked: needScore below threshold and not explicit"
            )
        }

        val isExplicit = queryContext.explicitSignal.explicit
        val lastUserText = queryContext.lastUserText

        // 折中方案：只在显式召回时谨慎提取关键词
        val queries = if (isExplicit) {
            val improvedQueries = generateImprovedQueries(queryContext)
            Log.i(TAG, "Explicit recall: generated ${improvedQueries.size} query(ies)")
            improvedQueries
        } else {
            // 隐式召回：保持原样，不添加任何内容
            listOf(lastUserText)
        }

        return@withContext PlannerResult(
            shouldRecall = true,
            pQueries = queries,
            aQueries = queries,
            reason = if (isExplicit && queries.size > 1) {
                "Heuristic planner: explicit recall with safe keyword extraction"
            } else {
                "Heuristic planner: using original query"
            },
            confidence = if (isExplicit && queries.size > 1) 0.7f else 0.6f
        )
    }

    /**
     * 为显式召回生成改进的查询（安全优先）
     *
     * 策略：
     * 1. 只在检测到指代词时提取关键词
     * 2. 只提取引号内容（《标题》、'内容'）
     * 3. windowTexts 少于 MIN_WINDOW_SIZE 时不提取
     * 4. 限制提取的关键词数量（最多 3 个）
     */
    private fun generateImprovedQueries(queryContext: QueryContext): List<String> {
        val lastUserText = queryContext.lastUserText

        // 检测是否有指代词
        val hasAnaphora = EXPLICIT_ANAPHORA_PHRASES.any { lastUserText.contains(it) }

        if (!hasAnaphora) {
            // 没有指代词，用户可能已经提到了关键词
            return listOf(lastUserText)
        }

        // 上下文安全检查
        if (queryContext.windowTexts.size < MIN_WINDOW_SIZE) {
            Log.i(TAG, "Window size too small (${queryContext.windowTexts.size}), skipping keyword extraction")
            return listOf(lastUserText)
        }

        // 安全地提取关键词：只提取引号内容
        val safeKeywords = extractSafeKeywords(queryContext)

        if (safeKeywords.isEmpty()) {
            Log.i(TAG, "No safe keywords found, using original query")
            return listOf(lastUserText)
        }

        // 生成改进的查询（只添加 1 条改进查询，避免污染）
        val improvedQuery = "${safeKeywords.take(3).joinToString(" ")} $lastUserText"

        return listOf(
            lastUserText,  // 原始查询
            improvedQuery   // 改进查询（关键词 + 原始查询）
        )
    }

    /**
     * 安全地提取关键词（只提取引号内容）
     *
     * 只提取引号包裹的内容，如：
     * - 《寻无之地》
     * - "纸人"
     * - '裂缝'
     *
     * 不做任何词汇推断，避免误提取
     */
    private fun extractSafeKeywords(queryContext: QueryContext): Set<String> {
        val keywords = mutableSetOf<String>()

        // 从 windowTexts 提取引号内容
        queryContext.windowTexts.forEach { text ->
            // 匹配《》包裹的内容
            Regex("""《([^《》》]+)》""").findAll(text).forEach {
                keywords.add(it.groupValues[1])
            }
            // 匹配"" ''包裹的内容
            Regex("""["']([^"']+)["']""").findAll(text).forEach {
                keywords.add(it.groupValues[1])
            }
        }

        // 从 runningSummary 提取引号内容
        if (!queryContext.runningSummary.isNullOrBlank()) {
            val summary = queryContext.runningSummary
            Regex("""《([^《》》]+)》""").findAll(summary).forEach {
                keywords.add(it.groupValues[1])
            }
            Regex("""["']([^"']+)["']""").findAll(summary).forEach {
                keywords.add(it.groupValues[1])
            }
        }

        Log.i(TAG, "Extracted ${keywords.size} safe keyword(s): ${keywords.take(3)}")
        return keywords
    }
}
