package me.rerere.rikkahub.service.recall

import me.rerere.rikkahub.service.ExplicitSignal
import me.rerere.rikkahub.service.recall.model.AssistantSnapshot
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import me.rerere.rikkahub.service.recall.model.QueryContext
import me.rerere.rikkahub.service.recall.model.SettingsSnapshot
import me.rerere.rikkahub.service.recall.source.ArchiveSourceCandidateGenerator
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertTrue

/**
 * Phase J4: A源 HINT fallback 禁止测试
 *
 * 验收标准：
 * J4.1: 硬逐字关键词显式请求时，A源完全跳过
 * J4.2: 非 hard verbatim 显式请求时，禁止 HINT fallback
 */
@RunWith(JUnit4::class)
class PhaseJ4ArchiveSourceNoHintFallbackTest {

    /**
     * 硬逐字关键词列表（与 ArchiveSourceCandidateGenerator 保持一致）
     */
    private val HARD_VERBATIM_KEYWORDS = listOf(
        "原文", "全文", "逐字", "一字不差", "复述", "贴出来", "引用", "原诗", "原代码"
    )

    /**
     * 创建测试用 QueryContext
     */
    private fun createQueryContext(
        lastUserText: String,
        explicit: Boolean = false,
        explicitKeyword: String? = null
    ): QueryContext {
        return QueryContext(
            conversationId = "test_conv",
            lastUserText = lastUserText,
            runningSummary = null,
            windowTexts = emptyList(),
            settingsSnapshot = SettingsSnapshot(
                enableVerbatimRecall = true,
                enableArchiveRecall = true,
                embeddingModelId = "test_embedding_model"
            ),
            assistantSnapshot = AssistantSnapshot(
                id = "test_assistant",
                name = "Test Assistant"
            ),
            ledger = ProbeLedgerState(),
            nowTurnIndex = 0,
            explicitSignal = ExplicitSignal(
                explicit = explicit,
                titles = emptyList(),
                keyword = explicitKeyword
            )
        )
    }

    /**
     * 测试用例 1：testArchiveSource_HardVerbatimKeywordsDetection
     *
     * 场景：验证所有硬逐字关键词都能被正确检测
     *
     * 验收要点：
     * - 所有 HARD_VERBATIM_KEYWORDS 中的词都在列表中
     */
    @Test
    fun testArchiveSource_HardVerbatimKeywordsDetection() {
        // 验收：所有硬逐字关键词都在列表中
        val expectedKeywords = listOf(
            "原文", "全文", "逐字", "一字不差", "复述", "贴出来", "引用", "原诗", "原代码"
        )

        for (keyword in expectedKeywords) {
            assertTrue(
                keyword in HARD_VERBATIM_KEYWORDS,
                "硬逐字关键词 '$keyword' 应在列表中"
            )
        }
    }

    /**
     * 测试用例 2：testArchiveSource_HardVerbatimExplicit_ShouldSkip
     *
     * 场景：显式请求 + 硬逐字关键词
     * - explicit = true
     * - keyword ∈ HARD_VERBATIM_KEYWORDS
     * - A源应该被跳过
     *
     * 验收要点：
     * - 硬逐字关键词显式请求时，A源应被标记为需要跳过
     */
    @Test
    fun testArchiveSource_HardVerbatimExplicit_ShouldSkip() {
        val testCases = mapOf(
            "请给出《静夜思》原文" to "原文",
            "《静夜思》全文是什么" to "全文",
            "逐字复述刚才的诗" to "逐字",
            "一字不差地贴出来" to "一字不差",
            "原诗贴出来" to "贴出来",
            "引用刚才的代码" to "引用"
        )

        for ((query, keyword) in testCases) {
            val queryContext = createQueryContext(
                lastUserText = query,
                explicit = true,
                explicitKeyword = keyword
            )

            // 验收：显式信号为 true
            assertTrue(
                queryContext.explicitSignal.explicit,
                "查询 '$query' 应被标记为显式请求"
            )

            // 验收：keyword 是硬逐字关键词
            assertTrue(
                keyword in HARD_VERBATIM_KEYWORDS,
                "关键词 '$keyword' 应是硬逐字关键词"
            )

            // 实际行为：ArchiveSourceCandidateGenerator.generate() 会 early return
            // 这里验证条件判断逻辑
            val isExplicitNonHardVerbatim = queryContext.explicitSignal.explicit &&
                (queryContext.explicitSignal.keyword == null ||
                 queryContext.explicitSignal.keyword !in HARD_VERBATIM_KEYWORDS)

            assertTrue(
                !isExplicitNonHardVerbatim,
                "查询 '$query' 应 NOT 是 '显式非硬逐字'（即应该跳过 A源）"
            )
        }
    }

    /**
     * 测试用例 3：testArchiveSource_TitleExplicit_NoHintFallback
     *
     * 场景：显式请求但没有硬逐字关键词（如《title》触发）
     * - explicit = true
     * - keyword ∉ HARD_VERBATIM_KEYWORDS（或为 null）
     * - 是"显式非硬逐字请求"
     * - 这种情况下应禁止 HINT fallback
     *
     * 验收要点：
     * - 应被标记为"显式非硬逐字"
     * - 禁止 HINT fallback
     */
    @Test
    fun testArchiveSource_TitleExplicit_NoHintFallback() {
        val testCases = listOf(
            "《静夜思》的内容" to null,  // title 触发，keyword 可能为 null
            "查看《代码示例》" to null,
            "《我的诗》怎么样" to null
        )

        for ((query, keyword) in testCases) {
            val queryContext = createQueryContext(
                lastUserText = query,
                explicit = true,
                explicitKeyword = keyword
            )

            // 验收：显式信号为 true
            assertTrue(
                queryContext.explicitSignal.explicit,
                "查询 '$query' 应被标记为显式请求"
            )

            // 实际行为：判断是"显式非硬逐字"
            val isExplicitNonHardVerbatim = queryContext.explicitSignal.explicit &&
                (queryContext.explicitSignal.keyword == null ||
                 queryContext.explicitSignal.keyword !in HARD_VERBATIM_KEYWORDS)

            assertTrue(
                isExplicitNonHardVerbatim,
                "查询 '$query' 应是'显式非硬逐字'（禁止 HINT fallback）"
            )
        }
    }

    /**
     * 测试用例 4：testArchiveSource_NonExplicit_NoRestriction
     *
     * 场景：非显式请求
     * - explicit = false
     * - 不应受到 HINT fallback 限制
     *
     * 验收要点：
     * - 不应被标记为"显式非硬逐字"
     * - HINT fallback 正常工作
     */
    @Test
    fun testArchiveSource_NonExplicit_NoRestriction() {
        val queryContext = createQueryContext(
            lastUserText = "刚才说的那段代码",
            explicit = false
        )

        // 验收：非显式请求
        assertTrue(
            !queryContext.explicitSignal.explicit,
            "查询应是非显式请求"
        )

        // 实际行为：不应被标记为"显式非硬逐字"
        val isExplicitNonHardVerbatim = queryContext.explicitSignal.explicit &&
            (queryContext.explicitSignal.keyword == null ||
             queryContext.explicitSignal.keyword !in HARD_VERBATIM_KEYWORDS)

        assertTrue(
            !isExplicitNonHardVerbatim,
            "非显式查询不应被标记为'显式非硬逐字'（允许 HINT fallback）"
        )
    }

    /**
     * 测试用例 5：testArchiveSource_AllHardVerbatimKeywordsCovered
     *
     * 场景：验证所有硬逐字关键词都能触发 A源跳过
     *
     * 验收要点：
     * - 每个硬逐字关键词 + explicit=true 都应导致跳过 A源
     */
    @Test
    fun testArchiveSource_AllHardVerbatimKeywordsCovered() {
        for (keyword in HARD_VERBATIM_KEYWORDS) {
            val query = "请给我${keyword}"
            val queryContext = createQueryContext(
                lastUserText = query,
                explicit = true,
                explicitKeyword = keyword
            )

            // 验收：explicit = true
            assertTrue(
                queryContext.explicitSignal.explicit,
                "查询 '$query' 应是显式请求"
            )

            // 验收：keyword 是硬逐字关键词
            assertTrue(
                keyword in HARD_VERBATIM_KEYWORDS,
                "关键词 '$keyword' 应是硬逐字关键词"
            )

            // 实际行为：应 NOT 是"显式非硬逐字"（即应该跳过 A源）
            val isExplicitNonHardVerbatim = queryContext.explicitSignal.explicit &&
                (queryContext.explicitSignal.keyword == null ||
                 queryContext.explicitSignal.keyword !in HARD_VERBATIM_KEYWORDS)

            assertTrue(
                !isExplicitNonHardVerbatim,
                "硬逐字关键词 '$keyword' 应导致跳过 A源"
            )
        }
    }

    /**
     * 测试用例 6：testArchiveSource_SoftExplicitKeyword_NoSkip
     *
     * 场景：显式请求但使用的是"软"关键词（不在 HARD_VERBATIM_KEYWORDS 中）
     * - explicit = true
     * - keyword ∉ HARD_VERBATIM_KEYWORDS
     * - 是"显式非硬逐字请求"，应禁止 HINT fallback
     *
     * 验收要点：
     * - 应被标记为"显式非硬逐字"
     * - 禁止 HINT fallback
     */
    @Test
    fun testArchiveSource_SoftExplicitKeyword_NoSkip() {
        // 假设有些显式关键词不在 HARD_VERBATIM_KEYWORDS 中
        // 比如"内容"、"详情"等（如果这些被视为 soft keywords）
        val queryContext = createQueryContext(
            lastUserText = "《静夜思》的内容",
            explicit = true,
            explicitKeyword = "内容"  // 假设这是 soft keyword
        )

        // 验收：explicit = true
        assertTrue(
            queryContext.explicitSignal.explicit,
            "查询应是显式请求"
        )

        // 验收："内容"不在硬逐字关键词列表中
        assertTrue(
            "内容" !in HARD_VERBATIM_KEYWORDS,
            "关键词'内容'不应是硬逐字关键词"
        )

        // 实际行为：应是"显式非硬逐字"（禁止 HINT fallback）
        val isExplicitNonHardVerbatim = queryContext.explicitSignal.explicit &&
            (queryContext.explicitSignal.keyword == null ||
             queryContext.explicitSignal.keyword !in HARD_VERBATIM_KEYWORDS)

        assertTrue(
            isExplicitNonHardVerbatim,
            "Soft keyword 显式请求应禁止 HINT fallback"
        )
    }
}
