package me.rerere.rikkahub.service.recall

import me.rerere.rikkahub.service.ExplicitSignal
import me.rerere.rikkahub.service.recall.model.Candidate
import me.rerere.rikkahub.service.recall.model.CandidateKind
import me.rerere.rikkahub.service.recall.model.CandidateSource
import me.rerere.rikkahub.service.recall.model.EvidenceScores
import me.rerere.rikkahub.service.recall.model.AssistantSnapshot
import me.rerere.rikkahub.service.recall.model.EntryStatus
import me.rerere.rikkahub.service.recall.model.LedgerEntry
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import me.rerere.rikkahub.service.recall.model.QueryContext
import me.rerere.rikkahub.service.recall.model.RecallAction
import me.rerere.rikkahub.service.recall.model.SettingsSnapshot
import me.rerere.rikkahub.service.recall.scorer.EvidenceScorer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EvidenceScorer 单元测试
 *
 * 验收标准：
 * 1. 评分可复现性（相同输入 => 相同输出）
 * 2. 各维度评分范围正确（0-1）
 * 3. 加权公式正确性
 */
class EvidenceScorerTest {

    /**
     * 创建测试用 QueryContext
     */
    private fun createQueryContext(
        lastUserText: String = "测试查询",
        windowTexts: List<String> = emptyList()
    ): QueryContext {
        return QueryContext(
            conversationId = "test_conv",
            lastUserText = lastUserText,
            runningSummary = null,
            windowTexts = windowTexts,
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
     * 创建测试用 Candidate
     */
    private fun createCandidate(
        content: String,
        anchors: List<String> = emptyList()
    ): Candidate {
        return Candidate(
            id = "test_candidate",
            source = CandidateSource.P_TEXT,
            kind = CandidateKind.SNIPPET,
            content = content,
            anchors = anchors,
            cost = content.length,
            evidenceKey = "test_evidence",  // Phase F: 添加 evidenceKey
            evidenceRaw = emptyMap()
        )
    }

    @Test
    fun testScoringReproducibility() {
        val candidate = createCandidate("这是一段测试内容")
        val queryContext = createQueryContext("测试")
        val needScore = 0.6f
        val maxNodeIndex = 10

        // 两次评分应该完全相同
        val scores1 = EvidenceScorer.score(candidate, queryContext, needScore, maxNodeIndex)
        val scores2 = EvidenceScorer.score(candidate, queryContext, needScore, maxNodeIndex)

        assertEquals(scores1.needScore, scores2.needScore, "needScore 应该相同")
        assertEquals(scores1.relevance, scores2.relevance, "relevance 应该相同")
        assertEquals(scores1.precision, scores2.precision, "precision 应该相同")
        assertEquals(scores1.novelty, scores2.novelty, "novelty 应该相同")
        assertEquals(scores1.recency, scores2.recency, "recency 应该相同")
        assertEquals(scores1.risk, scores2.risk, "risk 应该相同")
        assertEquals(scores1.redundancyPenalty, scores2.redundancyPenalty, "redundancyPenalty 应该相同")
        assertEquals(scores1.finalScore, scores2.finalScore, "finalScore 应该相同")
    }

    @Test
    fun testAllScoresInRange() {
        val candidate = createCandidate("这是一段测试内容")
        val queryContext = createQueryContext("测试")
        val needScore = 0.6f
        val maxNodeIndex = 10

        val scores = EvidenceScorer.score(candidate, queryContext, needScore, maxNodeIndex)

        // 所有评分应该在 [0, 1] 范围内
        assertTrue(scores.needScore in 0f..1f, "needScore 应该在 [0,1] 范围内")
        assertTrue(scores.relevance in 0f..1f, "relevance 应该在 [0,1] 范围内")
        assertTrue(scores.precision in 0f..1f, "precision 应该在 [0,1] 范围内")
        assertTrue(scores.novelty in 0f..1f, "novelty 应该在 [0,1] 范围内")
        assertTrue(scores.recency in 0f..1f, "recency 应该在 [0,1] 范围内")
        assertTrue(scores.risk in 0f..1f, "risk 应该在 [0,1] 范围内")
        assertTrue(scores.redundancyPenalty in 0f..1f, "redundancyPenalty 应该在 [0,1] 范围内")
        assertTrue(scores.finalScore in 0f..1f, "finalScore 应该在 [0,1] 范围内")
    }

    @Test
    fun testRelevanceCalculation() {
        val candidate = createCandidate("测试内容包含关键词")
        val queryContext = createQueryContext("测试 关键词")
        val needScore = 0.6f
        val maxNodeIndex = 10

        val scores = EvidenceScorer.score(candidate, queryContext, needScore, maxNodeIndex)

        // 相关性应该 > 0（因为包含关键词）
        assertTrue(scores.relevance > 0f, "包含关键词时 relevance 应该 > 0")
    }

    @Test
    fun testPrecisionWithTitle() {
        val candidate = createCandidate(
            content = "测试内容",
            anchors = listOf("title:测试标题")
        )
        val queryContext = createQueryContext("测试")
        val needScore = 0.6f
        val maxNodeIndex = 10

        val scores = EvidenceScorer.score(candidate, queryContext, needScore, maxNodeIndex)

        // title 命中 => precision = 1.0
        assertEquals(1.0f, scores.precision, "title 命中时 precision 应该是 1.0")
    }

    @Test
    fun testPrecisionWithExplicitPhrase() {
        val candidate = createCandidate(
            content = "请给出原文内容"
        )
        val queryContext = createQueryContext("测试")
        val needScore = 0.6f
        val maxNodeIndex = 10

        val scores = EvidenceScorer.score(candidate, queryContext, needScore, maxNodeIndex)

        // 命中显式短语 => precision = 0.7
        assertEquals(0.7f, scores.precision, "命中显式短语时 precision 应该是 0.7")
    }

    @Test
    fun testNoveltyDetection() {
        val content = "这是一段测试内容"
        val candidate = createCandidate(content)
        val queryContext = createQueryContext(
            lastUserText = "测试",
            windowTexts = listOf("这是一段测试内容") // 内容在 window 中
        )
        val needScore = 0.6f
        val maxNodeIndex = 10

        val scores = EvidenceScorer.score(candidate, queryContext, needScore, maxNodeIndex)

        // 内容在 window 中 => novelty = 0
        assertEquals(0f, scores.novelty, "内容在 window 中时 novelty 应该是 0")
    }

    @Test
    fun testRedundancyPenalty() {
        val candidate = createCandidate("测试内容")
        val ledger = ProbeLedgerState(
            recent = listOf(
                LedgerEntry(
                    contentHash = "hash",
                    candidateId = "test_candidate",
                    action = RecallAction.PROBE_VERBATIM_SNIPPET,
                    turnIndex = 0,
                    status = EntryStatus.SUCCESS,
                    cooldownUntilTurn = 10 // 冷却到第 10 轮
                )
            )
        )
        val queryContext = QueryContext(
            conversationId = "test_conv",
            lastUserText = "测试",
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
            ledger = ledger,
            nowTurnIndex = 5, // 当前在第 5 轮（仍在冷却中）
            explicitSignal = ExplicitSignal(
                explicit = false,
                titles = emptyList(),
                keyword = null
            )
        )
        val needScore = 0.6f
        val maxNodeIndex = 10

        val scores = EvidenceScorer.score(candidate, queryContext, needScore, maxNodeIndex)

        // 在冷却中 => redundancyPenalty = 1
        assertEquals(1f, scores.redundancyPenalty, "在冷却中时 redundancyPenalty 应该是 1")
        // redundancyPenalty = 1 => finalScore = 0
        assertEquals(0f, scores.finalScore, "redundancyPenalty=1 时 finalScore 应该是 0")
    }

    @Test
    fun testRiskCalculation() {
        val candidate = createCandidate("不相关内容")
        val queryContext = createQueryContext("完全不同的关键词")
        val needScore = 0.6f
        val maxNodeIndex = 10

        val scores = EvidenceScorer.score(candidate, queryContext, needScore, maxNodeIndex)

        // 低相关性 => 高 risk
        assertTrue(scores.risk > 0f, "低相关性时 risk 应该 > 0")
    }

    @Test
    fun testWeightedFormula() {
        val candidate = createCandidate("测试内容")
        val queryContext = createQueryContext("测试")
        val needScore = 0.6f
        val maxNodeIndex = 10

        val scores = EvidenceScorer.score(candidate, queryContext, needScore, maxNodeIndex)

        // 验证加权公式
        val expectedFinalScore = (
            0.40f * scores.relevance +
            0.20f * scores.precision +
            0.20f * scores.novelty +
            0.10f * scores.needScore +
            0.10f * scores.recency
        ) * (1f - scores.risk) * (1f - scores.redundancyPenalty)

        // 允许浮点数误差
        val delta = kotlin.math.abs(expectedFinalScore - scores.finalScore)
        assertTrue(delta < 0.0001f, "加权公式计算结果应该匹配 (delta=$delta)")
    }
}
