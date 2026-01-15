package me.rerere.rikkahub.service.recall

import me.rerere.rikkahub.service.ExplicitSignal
import me.rerere.rikkahub.service.recall.model.Candidate
import me.rerere.rikkahub.service.recall.model.CandidateKind
import me.rerere.rikkahub.service.recall.model.CandidateSource
import me.rerere.rikkahub.service.recall.model.EvidenceScores
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import me.rerere.rikkahub.service.recall.model.QueryContext
import me.rerere.rikkahub.service.recall.model.SettingsSnapshot
import me.rerere.rikkahub.service.recall.model.AssistantSnapshot
import me.rerere.rikkahub.service.recall.scorer.EvidenceScorer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase J1: P源 relevance 对中文可用测试
 *
 * 验收标准：
 * J1.1: 有 fts_rank_norm 时直接生效（中文 query 也不为 0）
 * J1.2: 无 fts_rank_norm 时，中文 bigram 兜底能让相关文本 > 不相关文本
 * J1.3: 英文回归不退化
 */
class PhaseJPSourceRelevanceChineseTest {

    /**
     * 创建测试用 QueryContext
     */
    private fun createQueryContext(
        lastUserText: String = "测试查询"
    ): QueryContext {
        return QueryContext(
            conversationId = "test_conv",
            lastUserText = lastUserText,
            runningSummary = null,
            windowTexts = emptyList(),
            settingsSnapshot = SettingsSnapshot(
                enableVerbatimRecall = true,
                enableArchiveRecall = false,
                embeddingModelId = null
            ),
            assistantSnapshot = AssistantSnapshot(
                id = "test_assistant",
                name = "Test Assistant"
            ),
            ledger = ProbeLedgerState(),
            nowTurnIndex = 0,
            explicitSignal = ExplicitSignal(
                explicit = false,
                titles = emptyList(),
                keyword = null
            )
        )
    }

    /**
     * 测试用例 1：testPSourceRelevance_Chinese_UsesFtsRankNorm
     *
     * 场景：
     * - P源候选包含 fts_rank_norm
     * - 中文查询（"你还记得原诗全文吗"）
     *
     * 期望：
     * - relevance 直接等于 fts_rank_norm（不为 0）
     * - 证明 FTS 信号优先级最高
     *
     * 验收要点：
     * - candidate.evidenceRaw["fts_rank_norm"] = "1.0"
     * - EvidenceScorer.score().relevance = 1.0
     */
    @Test
    fun testPSourceRelevance_Chinese_UsesFtsRankNorm() {
        val queryContext = createQueryContext(
            lastUserText = "你还记得原诗全文吗"  // 中文 query
        )

        // 构造 P源候选，包含 fts_rank_norm
        val candidate = Candidate(
            id = "P:conv123:SNIPPET:10,11,12",
            source = CandidateSource.P_TEXT,
            kind = CandidateKind.SNIPPET,
            content = "床前明月光，疑是地上霜。举头望明月，低头思故乡。",
            anchors = emptyList(),
            cost = 200,
            evidenceKey = "P:conv123:10,11,12",
            evidenceRaw = mapOf(
                "node_indices" to "10,11,12",
                "fts_rank_norm" to "1.0"  // Phase J1: FTS 排名信号
            )
        )

        // 评分
        val scores = EvidenceScorer.score(
            candidate = candidate,
            queryContext = queryContext,
            needScore = 0.6f,
            maxNodeIndex = 20
        )

        // 验收：relevance = fts_rank_norm = 1.0（不为 0）
        assertEquals(
            1.0f,
            scores.relevance,
            "有 fts_rank_norm 时应直接使用 FTS 排名分数"
        )

        // 验收：证明中文 query 也能获得高 relevance
        assertTrue(
            scores.relevance > 0.9f,
            "中文 query 通过 FTS 信号应获得高 relevance"
        )
    }

    /**
     * 测试用例 2：testPSourceRelevance_Chinese_BigramFallbackWorks
     *
     * 场景：
     * - P源候选不含 fts_rank_norm
     * - 中文查询和中文内容
     * - 相关文本包含 query 中的 bigram
     * - 不相关文本不包含
     *
     * 期望：
     * - 相关文本的 relevance > 不相关文本的 relevance
     * - 证明 bigram Jaccard 对中文有效
     *
     * 验收要点：
     * - query = "李白的诗"
     * - 相关内容包含"李白" -> relevance 较高
     * - 不相关内容不包含"李白" -> relevance 较低
     */
    @Test
    fun testPSourceRelevance_Chinese_BigramFallbackWorks() {
        val queryContext = createQueryContext(
            lastUserText = "李白的诗"  // 中文 query，含 bigram: "李白", "白的"
        )

        // 场景 A：相关内容（包含"李白"）
        val relevantCandidate = Candidate(
            id = "P:conv123:SNIPPET:10,11,12",
            source = CandidateSource.P_TEXT,
            kind = CandidateKind.SNIPPET,
            content = "李白《静夜思》：床前明月光，疑是地上霜。",  // 包含"李白"
            anchors = emptyList(),
            cost = 200,
            evidenceKey = "P:conv123:10,11,12",
            evidenceRaw = mapOf(
                "node_indices" to "10,11,12"
                // Phase J1: 没有 fts_rank_norm，使用 bigram 兜底
            )
        )

        // 场景 B：不相关内容（不包含"李白"）
        val irrelevantCandidate = Candidate(
            id = "P:conv456:SNIPPET:20,21,22",
            source = CandidateSource.P_TEXT,
            kind = CandidateKind.SNIPPET,
            content = "杜甫《春望》：国破山河在，城春草木深。",  // 不包含"李白"
            anchors = emptyList(),
            cost = 200,
            evidenceKey = "P:conv456:20,21,22",
            evidenceRaw = mapOf(
                "node_indices" to "20,21,22"
            )
        )

        // 评分
        val relevantScores = EvidenceScorer.score(
            candidate = relevantCandidate,
            queryContext = queryContext,
            needScore = 0.6f,
            maxNodeIndex = 30
        )

        val irrelevantScores = EvidenceScorer.score(
            candidate = irrelevantCandidate,
            queryContext = queryContext,
            needScore = 0.6f,
            maxNodeIndex = 30
        )

        // 验收：相关内容的 relevance > 不相关内容
        assertTrue(
            relevantScores.relevance > irrelevantScores.relevance,
            "相关内容（包含'李白'）的 relevance 应 > 不相关内容（不包含'李白'）\n" +
            "相关: ${relevantScores.relevance}, 不相关: ${irrelevantScores.relevance}"
        )

        // 验收：相关内容的 relevance > 0
        assertTrue(
            relevantScores.relevance > 0f,
            "相关内容通过 bigram Jaccard 应获得 > 0 的 relevance"
        )

        // 验收：不相关内容的 relevance 应较低（< 0.3）
        assertTrue(
            irrelevantScores.relevance < 0.3f,
            "不相关内容的 relevance 应较低"
        )
    }

    /**
     * 测试用例 3：testPSourceRelevance_EnglishStillWorks
     *
     * 场景：
     * - 英文查询和英文内容
     * - 无 fts_rank_norm
     *
     * 期望：
     * - bigram Jaccard 对英文仍然有效
     * - 不会退化
     *
     * 验收要点：
     * - query = "machine learning"
     * - 相关内容包含"machine learning" -> relevance 较高
     * - 不相关内容不包含 -> relevance 较低
     */
    @Test
    fun testPSourceRelevance_EnglishStillWorks() {
        val queryContext = createQueryContext(
            lastUserText = "machine learning algorithms"  // 英文 query
        )

        // 场景 A：相关英文内容
        val relevantCandidate = Candidate(
            id = "P:conv789:SNIPPET:30,31,32",
            source = CandidateSource.P_TEXT,
            kind = CandidateKind.SNIPPET,
            content = "We discuss machine learning algorithms in detail, including neural networks and decision trees.",
            anchors = emptyList(),
            cost = 300,
            evidenceKey = "P:conv789:30,31,32",
            evidenceRaw = mapOf(
                "node_indices" to "30,31,32"
                // Phase J1: 没有 fts_rank_norm，使用 bigram 兜底
            )
        )

        // 场景 B：不相关英文内容
        val irrelevantCandidate = Candidate(
            id = "P:conv101:SNIPPET:40,41,42",
            source = CandidateSource.P_TEXT,
            kind = CandidateKind.SNIPPET,
            content = "Today we will discuss database optimization and query performance tuning.",
            anchors = emptyList(),
            cost = 300,
            evidenceKey = "P:conv101:40,41,42",
            evidenceRaw = mapOf(
                "node_indices" to "40,41,42"
            )
        )

        // 评分
        val relevantScores = EvidenceScorer.score(
            candidate = relevantCandidate,
            queryContext = queryContext,
            needScore = 0.6f,
            maxNodeIndex = 50
        )

        val irrelevantScores = EvidenceScorer.score(
            candidate = irrelevantCandidate,
            queryContext = queryContext,
            needScore = 0.6f,
            maxNodeIndex = 50
        )

        // 验收：相关内容的 relevance > 不相关内容
        assertTrue(
            relevantScores.relevance > irrelevantScores.relevance,
            "英文相关内容的 relevance 应 > 不相关内容\n" +
            "相关: ${relevantScores.relevance}, 不相关: ${irrelevantScores.relevance}"
        )

        // 验收：相关内容的 relevance > 0
        assertTrue(
            relevantScores.relevance > 0f,
            "英文相关内容通过 bigram Jaccard 应获得 > 0 的 relevance"
        )

        // 验收：英文不退化（相关内容应 > 0.2）
        assertTrue(
            relevantScores.relevance > 0.2f,
            "英文 bigram Jaccard 不应退化"
        )
    }

    /**
     * 测试用例 4：testPSourceRelevance_FtsRankNormPriority
     *
     * 场景：
     * - 候选同时有 fts_rank_norm 和内容匹配
     *
     * 期望：
     * - 优先使用 fts_rank_norm，忽略内容匹配计算
     *
     * 验收要点：
     * - 即使内容不匹配，fts_rank_norm=1.0 时 relevance 仍 = 1.0
     * - 证明 FTS 信号优先级最高
     */
    @Test
    fun testPSourceRelevance_FtsRankNormPriority() {
        val queryContext = createQueryContext(
            lastUserText = "完全不同的查询"  // 与内容不匹配
        )

        // 内容不匹配的候选，但有 fts_rank_norm
        val candidate = Candidate(
            id = "P:conv123:SNIPPET:10,11,12",
            source = CandidateSource.P_TEXT,
            kind = CandidateKind.SNIPPET,
            content = "这是完全不相关的内容，没有关键词匹配。",
            anchors = emptyList(),
            cost = 200,
            evidenceKey = "P:conv123:10,11,12",
            evidenceRaw = mapOf(
                "node_indices" to "10,11,12",
                "fts_rank_norm" to "1.0"  // Phase J1: FTS 信号优先
            )
        )

        // 评分
        val scores = EvidenceScorer.score(
            candidate = candidate,
            queryContext = queryContext,
            needScore = 0.6f,
            maxNodeIndex = 20
        )

        // 验收：即使内容不匹配，relevance 仍 = fts_rank_norm = 1.0
        assertEquals(
            1.0f,
            scores.relevance,
            "有 fts_rank_norm 时应优先使用 FTS 信号，即使内容不匹配"
        )
    }
}
