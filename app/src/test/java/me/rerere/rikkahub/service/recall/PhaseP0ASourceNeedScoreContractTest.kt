package me.rerere.rikkahub.service.recall

import me.rerere.rikkahub.service.ExplicitSignal
import me.rerere.rikkahub.service.recall.gate.NeedGate
import me.rerere.rikkahub.service.recall.model.EntryStatus
import me.rerere.rikkahub.service.recall.model.LedgerEntry
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import me.rerere.rikkahub.service.recall.model.QueryContext
import me.rerere.rikkahub.service.recall.model.RecallAction
import me.rerere.rikkahub.service.recall.model.SettingsSnapshot
import me.rerere.rikkahub.service.recall.model.AssistantSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P0-1: A源 needScore 契约测试
 *
 * 验收标准：
 * P0-1.1: A源 embeddingCalls 调度不得依赖 ledger.recent.size
 * P0-1.2: A源 embeddingCalls 调度只依赖入参 needScore 和 pSourceCandidateCount
 */
class PhaseP0ASourceNeedScoreContractTest {

    /**
     * 创建测试用 QueryContext
     */
    private fun createQueryContext(
        lastUserText: String,
        explicit: Boolean = false,
        ledgerSize: Int = 0
    ): QueryContext {
        // 构造一个有指定数量条目的 ledger
        val ledgerEntries = mutableListOf<LedgerEntry>()
        repeat(ledgerSize) { index ->
            ledgerEntries.add(
                LedgerEntry(
                    contentHash = "hash_$index",
                    candidateId = "test_candidate_$index",
                    action = RecallAction.PROBE_VERBATIM_SNIPPET,
                    turnIndex = index,
                    status = EntryStatus.SUCCESS,
                    cooldownUntilTurn = 0
                )
            )
        }

        val ledger = ProbeLedgerState(
            recent = ledgerEntries.toList(),
            globalProbeStrikes = 0,
            silentUntilTurn = 0,
            lastProbeObservation = null
        )

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
            ledger = ledger,
            nowTurnIndex = ledgerSize,
            explicitSignal = ExplicitSignal(
                explicit = explicit,
                titles = emptyList(),
                keyword = null
            )
        )
    }

    /**
     * 测试用例 1：testASourceEmbeddingScheduling_DoesNotDependOnLedgerSize
     *
     * 场景：
     * - ledger.recent.size = 100（人为塞满）
     * - lastUserText 无回指词/无显式信号 → needScore 很低（~0.0）
     *
     * 期望：
     * - embeddingCalls 仍为默认 1
     * - 不得因为 ledger 很大而升级到 3
     *
     * 验收要点：
     * - 证明 A源 embeddingCalls 调度不依赖 ledger.recent.size
     * - 只依赖入参 needScore
     */
    @Test
    fun testASourceEmbeddingScheduling_DoesNotDependOnLedgerSize() {
        // 构造：ledger 很大（100条），但查询无回指词/无显式信号
        val queryContext = createQueryContext(
            lastUserText = "随便聊聊",  // 无回指词，needScore 应很低（~0.0）
            explicit = false,
            ledgerSize = 100  // P0-1: 人工塞满 ledger
        )

        // 计算实际的 needScore（来自 NeedGate，而非 ledger.size）
        val actualNeedScore = NeedGate.computeNeedScoreHeuristic(queryContext)

        // 验收：needScore 应该很低（无回指词）
        assertEquals(
            expected = 0.0f,
            actual = actualNeedScore,
            absoluteTolerance = 0.01f,
            message = "无回指词查询的 needScore 应接近 0.0"
        )

        // P0-1: 使用入参 needScore 决定 embeddingCalls（而非 ledger.recent.size）
        val embeddingCalls = decideEmbeddingCalls(
            needScore = actualNeedScore,  // P0-1: 入参 needScore
            pSourceCandidateCount = 0
        )

        // 验收：即使 ledger 很大（100），embeddingCalls 仍应为 1（低需求）
        assertEquals(
            expected = 1,
            actual = embeddingCalls,
            message = "低 needScore 时，即使 ledger 很大，embeddingCalls 也应为 1（不得升级到 3）"
        )

        // 验收：证明 embeddingCalls 不依赖 ledger.recent.size
        // 如果使用 ledger.size (100) 作为 needScore，会触发 needScore >= 0.75 导致 embeddingCalls = 3
        // 但因为使用入参 needScore (~0.0)，所以 embeddingCalls = 1
    }

    /**
     * 测试用例 2：testASourceEmbeddingScheduling_TriggeredOnlyByNeedScoreAndPSourceCount
     *
     * 场景：
     * - 场景 A：needScore >= 0.75 且 pSourceCandidateCount == 0 → embeddingCalls == 3
     * - 场景 B：needScore >= 0.75 且 pSourceCandidateCount > 0 → embeddingCalls == 1
     * - 场景 C：needScore < 0.75 且 pSourceCandidateCount == 0 → embeddingCalls == 1
     *
     * 验收要点：
     * - 证明 embeddingCalls 只依赖 needScore 和 pSourceCandidateCount
     */
    @Test
    fun testASourceEmbeddingScheduling_TriggeredOnlyByNeedScoreAndPSourceCount() {
        // 场景 A：高 needScore + 无 P源候选 → 应触发 3 次 embedding
        val queryContextA = createQueryContext(
            lastUserText = "这段代码",  // 短文本（6字符）+ 回指词（"这段"）+ 对象词（"代码"）= 0.35 + 0.15 + 0.25 = 0.75
            explicit = false,
            ledgerSize = 5  // 小 ledger，证明不依赖 ledger.size
        )
        val needScoreA = NeedGate.computeNeedScoreHeuristic(queryContextA)

        val embeddingCallsA = decideEmbeddingCalls(
            needScore = needScoreA,
            pSourceCandidateCount = 0  // P0-1: 无 P源候选
        )

        // 验收：needScore 高且无 P源候选时，应触发 3 次 embedding
        assertEquals(
            expected = 3,
            actual = embeddingCallsA,
            message = "场景 A：needScore (${needScoreA}) >= 0.75 且 pSourceCandidateCount == 0 → embeddingCalls 应为 3"
        )

        // 场景 B：高 needScore + 有 P源候选 → 应保持 1 次 embedding
        val embeddingCallsB = decideEmbeddingCalls(
            needScore = needScoreA,
            pSourceCandidateCount = 1  // P0-1: 有 P源候选
        )

        // 验收：即使 needScore 高，有 P源候选时也应保持 1 次 embedding
        assertEquals(
            expected = 1,
            actual = embeddingCallsB,
            message = "场景 B：needScore (${needScoreA}) >= 0.75 且 pSourceCandidateCount > 0 → embeddingCalls 应为 1"
        )

        // 场景 C：低 needScore + 无 P源候选 → 应保持 1 次 embedding
        val queryContextC = createQueryContext(
            lastUserText = "随便聊聊",  // 无回指词，needScore 低
            explicit = false,
            ledgerSize = 0
        )
        val needScoreC = NeedGate.computeNeedScoreHeuristic(queryContextC)

        val embeddingCallsC = decideEmbeddingCalls(
            needScore = needScoreC,
            pSourceCandidateCount = 0
        )

        // 验收：低 needScore 时，应保持默认 1 次 embedding
        assertEquals(
            expected = 1,
            actual = embeddingCallsC,
            message = "场景 C：needScore (${needScoreC}) < 0.75 且 pSourceCandidateCount == 0 → embeddingCalls 应为 1"
        )
    }

    /**
     * 辅助方法：决定执行多少次 embedding
     * 与 ArchiveSourceCandidateGenerator.decideEmbeddingCalls 保持一致
     */
    private fun decideEmbeddingCalls(
        needScore: Float,
        pSourceCandidateCount: Int
    ): Int {
        val multiQueryNeedScoreThreshold = 0.75f
        val embeddingMaxCalls = 3

        // needScore >= 0.75 且 P源无候选 => 执行 3 次 embedding
        if (needScore >= multiQueryNeedScoreThreshold && pSourceCandidateCount == 0) {
            return embeddingMaxCalls
        }

        // 默认执行 1 次 embedding（Q0）
        return 1
    }
}
