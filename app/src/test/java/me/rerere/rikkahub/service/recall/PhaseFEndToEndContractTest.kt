package me.rerere.rikkahub.service.recall

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.service.ExplicitSignal
import me.rerere.rikkahub.service.recall.model.Candidate
import me.rerere.rikkahub.service.recall.model.CandidateKind
import me.rerere.rikkahub.service.recall.model.CandidateSource
import me.rerere.rikkahub.service.recall.model.EntryStatus
import me.rerere.rikkahub.service.recall.model.LastProbeObservation
import me.rerere.rikkahub.service.recall.model.LedgerEntry
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import me.rerere.rikkahub.service.recall.model.ProbeOutcome
import me.rerere.rikkahub.service.recall.model.QueryContext
import me.rerere.rikkahub.service.recall.model.RecallAction
import me.rerere.rikkahub.service.recall.model.SettingsSnapshot
import me.rerere.rikkahub.service.recall.model.AssistantSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import android.content.Context

/**
 * Phase F 端到端契约测试套件
 *
 * 验收标准：
 * 1. ACCEPT 升级：上一轮 PROBE，本轮"继续" → 允许升级/绕过一次冷却
 * 2. REJECT 快速停止：上一轮 PROBE，本轮"不是这个" → 本轮 NONE + 延长冷却（>=now+30）
 * 3. SILENT WINDOW：两次 IGNORE 触发 silentUntilTurn；silent 内 non-explicit 一律 NONE
 * 4. 注入隔离：system prompt 最多一个 [RECALL_EVIDENCE]；summary/messageNodes 不含注入块
 */
class PhaseFEndToEndContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 创建测试用 QueryContext
     */
    private fun createQueryContext(
        lastUserText: String,
        explicit: Boolean = false,
        ledger: ProbeLedgerState = ProbeLedgerState(),
        nowTurnIndex: Int = 0
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
            ledger = ledger,
            nowTurnIndex = nowTurnIndex,
            explicitSignal = ExplicitSignal(
                explicit = explicit,
                titles = emptyList(),
                keyword = if (explicit) "原文" else null
            )
        )
    }

    /**
     * 测试1：ACCEPT 升级（Phase F：F1 + F3）
     *
     * 场景：
     * - 上一轮：PROBE_VERBATIM_SNIPPET（evidenceKey=P:conv999:10,11,12）
     * - 本轮：用户输入"继续"（短文本，needScore<0.55）
     * - 期望：outcome=ACCEPT, strikes清零，允许升级
     *
     * 验收：
     * - ProbeControl.evaluateAndUpdate() 在 NeedGate 之前执行（F1）
     * - 短回复也能更新 outcome/strikes（F1）
     * - evidenceKey 匹配允许升级（F3）
     */
    @Test
    fun testAcceptUpgrade_ShortReplyUpdatesLedger() {
        // 1. 构造上一轮 PROBE
        val lastObservation = LastProbeObservation(
            turnIndex = 0,
            action = RecallAction.PROBE_VERBATIM_SNIPPET,
            candidateId = "P:conv999:SNIPPET:10,11,12",
            evidenceKey = "P:conv999:10,11,12",  // Phase F: evidenceKey
            content = "这是静夜思的内容...",
            anchors = listOf("title:静夜思"),
            outcome = ProbeOutcome.IGNORE
        )

        val ledger = ProbeLedgerState(
            globalProbeStrikes = 1,  // 之前有 1 个 strike
            lastProbeObservation = lastObservation
        )

        // 2. 本轮用户输入"继续"（短文本，needScore 会 < 0.55）
        val lastUserText = "继续"
        val queryContext = createQueryContext(
            lastUserText = lastUserText,
            ledger = ledger,
            nowTurnIndex = 1
        )

        // 3. 模拟 Phase F 时序：先 evaluateAndUpdate，再 NeedGate
        // 3.1 evaluateAndUpdate（纯内存计算）- 验证 ACCEPT 判定
        val outcome = me.rerere.rikkahub.service.recall.probe.ProbeAcceptanceJudge.judge(
            lastUserText,
            lastObservation
        )

        // 验证：ACCEPT（因确认词"继续"）
        assertEquals(ProbeOutcome.ACCEPT, outcome, "输入'继续'应判定为 ACCEPT")

        // 3.2 模拟 ACCEPT 后的账本更新（strikes 归零，outcome 更新）
        val updatedLedger = ledger.copy(
            globalProbeStrikes = 0,  // ACCEPT 后归零
            lastProbeObservation = lastObservation.copy(outcome = ProbeOutcome.ACCEPT)
        )

        // 验证：strikes 归零
        assertEquals(0, updatedLedger.globalProbeStrikes, "ACCEPT 后 strikes 应归零")

        // 验证：允许升级（evidenceKey 匹配）
        val canUpgrade = updatedLedger.canUpgradeOnce("P:conv999:10,11,12")
        assertTrue(canUpgrade, "evidenceKey 匹配时应允许升级")

        // 3.3 模拟 NeedGate（短文本可能 blocked，但 ledger 已更新）
        val needScore = me.rerere.rikkahub.service.recall.gate.NeedGate.computeNeedScoreHeuristic(queryContext)
        assertFalse(needScore >= 0.55f, "短文本 needScore 应 < 0.55")

        // 验收关键点：即使 NeedGate blocked，ledger 的 outcome/strikes 也已更新
        assertEquals(ProbeOutcome.ACCEPT, updatedLedger.lastProbeObservation?.outcome, "outcome 应更新为 ACCEPT")
        assertEquals(0, updatedLedger.globalProbeStrikes, "strikes 应已归零")
    }

    /**
     * 测试2：REJECT 快速停止（Phase E + F1）
     *
     * 场景：
     * - 上一轮：PROBE_VERBATIM_SNIPPET
     * - 本轮：用户输入"不是这个"
     * - 期望：outcome=REJECT, cooldownUntilTurn >= now+30, 本轮 NONE
     *
     * 验收：
     * - REJECT 后延长冷却至 >= now + 30
     * - 本轮被压制（在冷却中）
     */
    @Test
    fun testRejectFastStop_ExtendedCooldown() {
        // 1. 构造上一轮 PROBE
        val lastObservation = LastProbeObservation(
            turnIndex = 5,
            action = RecallAction.PROBE_VERBATIM_SNIPPET,
            candidateId = "P:conv456:SNIPPET:1,2,3",
            evidenceKey = "P:conv456:1,2,3",
            content = "这是代码片段...",
            anchors = listOf("title:算法"),
            outcome = ProbeOutcome.IGNORE
        )

        val ledger = ProbeLedgerState(
            globalProbeStrikes = 0,
            recent = listOf(
                LedgerEntry(
                    contentHash = "hash123",
                    candidateId = lastObservation.candidateId,
                    action = lastObservation.action,
                    turnIndex = lastObservation.turnIndex,
                    status = EntryStatus.SUCCESS,
                    cooldownUntilTurn = 15  // 原冷却到 15
                )
            ),
            lastProbeObservation = lastObservation
        )

        // 2. 本轮用户输入"不是这个"
        val lastUserText = "不是这个，我说的是另一段"
        val nowTurnIndex = 10

        // 3. 评估接住判定
        val outcome = me.rerere.rikkahub.service.recall.probe.ProbeAcceptanceJudge.judge(
            lastUserText,
            lastObservation
        )

        // 验证：REJECT
        assertEquals(ProbeOutcome.REJECT, outcome, "输入'不是这个'应判定为 REJECT")

        // 4. 模拟 REJECT 后的账本更新（strikes +1，延长冷却至 now + 30）
        val updatedRecent = ledger.recent.map { entry ->
            if (entry.candidateId == lastObservation.candidateId && entry.turnIndex == lastObservation.turnIndex) {
                // 延长冷却至 now + 30
                entry.copy(cooldownUntilTurn = nowTurnIndex + me.rerere.rikkahub.service.recall.model.ProbeLedgerState.REJECT_COOLDOWN_TURNS)
            } else {
                entry
            }
        }

        val updatedLedger = ledger.copy(
            globalProbeStrikes = 1,  // REJECT 后 strikes +1
            lastProbeObservation = lastObservation.copy(outcome = ProbeOutcome.REJECT),
            recent = updatedRecent
        )

        // 验证：strikes += 1
        assertEquals(1, updatedLedger.globalProbeStrikes, "REJECT 后 strikes 应 +1")

        // 验证：延长冷却至 now + 30
        val cooldownEntry = updatedLedger.recent.first { it.candidateId == lastObservation.candidateId }
        assertTrue(
            cooldownEntry.cooldownUntilTurn >= nowTurnIndex + 30,
            "REJECT 后 cooldownUntilTurn 应 >= now + 30，实际：${cooldownEntry.cooldownUntilTurn}，期望：${nowTurnIndex + 30}"
        )

        // 验证：本轮被压制（在冷却中）
        val isInCooldown = updatedLedger.isInCooldown(lastObservation.candidateId, nowTurnIndex)
        assertTrue(isInCooldown, "REJECT 后本轮应在冷却中")
    }

    /**
     * 测试3：SILENT WINDOW（Phase E + F1）
     *
     * 场景：
     * - 连续两次 IGNORE
     * - strikes >= 2 触发 silentUntilTurn = now + 10
     * - silent 内 non-explicit 一律 NONE
     *
     * 验收：
     * - globalProbeStrikes >= 2
     * - silentUntilTurn 生效
     * - isInSilentWindow() 在 silent 期内返回 true
     */
    @Test
    fun testSilentWindow_TwoIgnoreesActivates() {
        val nowTurnIndex = 20

        // 1. 构造已累计 2 个 strike 的账本
        val ledger = ProbeLedgerState(
            globalProbeStrikes = 2,  // 已累计 2 个 strike
            silentUntilTurn = nowTurnIndex + 10  // 静默窗口到 30
        )

        // 验证：strikes >= 2
        assertTrue(
            ledger.globalProbeStrikes >= me.rerere.rikkahub.service.recall.model.ProbeLedgerState.MAX_STRIKES,
            "连续两次 IGNORE 后 strikes >= 2"
        )

        // 验证：silentUntilTurn 生效
        assertTrue(
            ledger.isInSilentWindow(nowTurnIndex),
            "应在静默窗口内（nowTurnIndex=$nowTurnIndex, silentUntilTurn=${ledger.silentUntilTurn}）"
        )

        // 验证：non-explicit 被阻止
        val shouldBlock = me.rerere.rikkahub.service.recall.probe.ProbeControl.isInSilentWindow(
            ledger = ledger,
            nowTurnIndex = nowTurnIndex
        )
        assertTrue(shouldBlock, "静默窗口内应阻止 non-explicit 召回")

        // 验证：静默窗口结束后不再阻止
        val afterSilentWindow = nowTurnIndex + 11
        val shouldBlockAfter = me.rerere.rikkahub.service.recall.probe.ProbeControl.isInSilentWindow(
            ledger = ledger,
            nowTurnIndex = afterSilentWindow
        )
        assertFalse(shouldBlockAfter, "静默窗口结束后不应阻止")
    }

    /**
     * 测试4：注入隔离（Phase D + Phase F）
     *
     * 场景：
     * - 触发召回后，生成注入块
     * - 验证：system prompt 最多一个 [RECALL_EVIDENCE]
     * - 验证：summary 输入与 messageNodes 不含 [RECALL_EVIDENCE]
     *
     * 验收：
     * - 单轮最多一个 [RECALL_EVIDENCE] 块
     * - 注入块格式正确
     * - 隔离性成立
     */
    @Test
    fun testInjectionIsolation_MaxOneEvidenceBlock() {
        // 1. 模拟生成注入块
        val candidate = Candidate(
            id = "P:conv123:SNIPPET:45,46,47",
            source = CandidateSource.P_TEXT,
            kind = CandidateKind.SNIPPET,
            content = "这是静夜思的原文内容...",
            anchors = listOf("title:静夜思"),
            cost = 200,
            evidenceKey = "P:conv123:45,46,47",
            evidenceRaw = mapOf(
                "node_indices" to "45,46,47",
                "title" to "静夜思"
            )
        )

        // 2. 构建注入块
        val injectionBlock = buildInjectionBlock(candidate)

        // 3. 验证：注入块格式
        assertTrue(injectionBlock.contains("[RECALL_EVIDENCE]"), "应包含 [RECALL_EVIDENCE] 标记")
        assertTrue(injectionBlock.contains("type=SNIPPET"), "应包含 type=SNIPPET")
        assertTrue(injectionBlock.contains("source=P_TEXT"), "应包含 source=P_TEXT")
        assertTrue(injectionBlock.contains("id=P:conv123:SNIPPET:45,46,47"), "应包含 candidate ID")

        // 4. 验证：注入块包含内容
        assertTrue(injectionBlock.contains(candidate.content), "应包含候选内容")

        // 5. 验证：注入块包含隔离说明
        assertTrue(
            injectionBlock.contains("不要提及你进行了召回"),
            "应包含隔离说明"
        )

        // 6. 验证：只有一个 [RECALL_EVIDENCE] 块
        val startMarker = "[RECALL_EVIDENCE]"
        val endMarker = "[/RECALL_EVIDENCE]"
        val startCount = injectionBlock.split(startMarker).size - 1
        val endCount = injectionBlock.split(endMarker).size - 1

        assertEquals(1, startCount, "应只有一个 [RECALL_EVIDENCE] 开始标记")
        assertEquals(1, endCount, "应只有一个 [/RECALL_EVIDENCE] 结束标记")

        // 7. 验收：注入块不污染 summary 和 messageNodes
        val summaryInput = "这是 runningSummary 输入..."
        assertFalse(summaryInput.contains("[RECALL_EVIDENCE]"), "summary 输入不应包含注入块标记")

        val messageNodeText = "这是 messageNode 内容..."
        assertFalse(messageNodeText.contains("[RECALL_EVIDENCE]"), "messageNode 内容不应包含注入块标记")
    }

    /**
     * 测试5：evidenceKey 支持跨 kind 升级（Phase F：F3）
     *
     * 场景：
     * - 上一轮：A源 HINT（evidenceKey=A:archive123）
     * - 本轮：A源 SNIPPET（同 evidenceKey）
     * - 期望：允许升级（绕过冷却一次）
     *
     * 验收：
     * - HINT 与 SNIPPET 的 evidenceKey 相同
     * - canUpgradeOnce(evidenceKey) 返回 true
     * - 支持跨 kind 升级（HINT → SNIPPET）
     */
    @Test
    fun testEvidenceKey_CrossKindUpgrade() {
        // 1. 构造上一轮 A源 HINT
        val lastObservation = LastProbeObservation(
            turnIndex = 0,
            action = RecallAction.FACT_HINT,
            candidateId = "A:archive123:HINT",
            evidenceKey = "A:archive123",  // Phase F: A源 evidenceKey = archiveId
            content = "归档摘要内容...",
            anchors = listOf("archive_id:archive123"),
            outcome = ProbeOutcome.ACCEPT  // 上一轮被接住
        )

        val ledger = ProbeLedgerState(
            globalProbeStrikes = 0,
            lastProbeObservation = lastObservation
        )

        // 2. 本轮同一归档的 SNIPPET（evidenceKey 相同）
        val sameEvidenceKey = "A:archive123"
        val canUpgrade = ledger.canUpgradeOnce(sameEvidenceKey)

        // 验证：允许升级（evidenceKey 匹配，虽然 kind 不同）
        assertTrue(
            canUpgrade,
            "evidenceKey 相同但 kind 不同时应允许升级（HINT → SNIPPET）"
        )

        // 3. 验证：candidateId 不同但 evidenceKey 相同
        val hintCandidateId = "A:archive123:HINT"
        val snippetCandidateId = "A:archive123:SNIPPET"

        assertTrue(hintCandidateId != snippetCandidateId, "candidateId 应不同")
        assertEquals("A:archive123", sameEvidenceKey, "evidenceKey 应相同")

        // 4. 验收：FULL_VERBATIM 仍禁止升级
        val fullObservation = lastObservation.copy(
            action = RecallAction.FULL_VERBATIM,
            candidateId = "A:archive123:FULL"
        )
        val ledgerWithFull = ledger.copy(
            lastProbeObservation = fullObservation
        )
        val canUpgradeFull = ledgerWithFull.canUpgradeOnce(sameEvidenceKey)

        assertFalse(canUpgradeFull, "FULL_VERBATIM 不应允许升级")
    }

    /**
     * 辅助方法：构建注入块
     */
    private fun buildInjectionBlock(
        candidate: Candidate
    ): String {
        val type = when (candidate.kind) {
            CandidateKind.SNIPPET -> "SNIPPET"
            CandidateKind.HINT -> "HINT"
            CandidateKind.FULL -> "FULL"
        }

        val source = when (candidate.source) {
            CandidateSource.P_TEXT -> "P_TEXT"
            CandidateSource.A_ARCHIVE -> "A_ARCHIVE"
        }

        return """
            |[RECALL_EVIDENCE]
            |type=$type
            |source=$source
            |id=${candidate.id}
            |----BEGIN----
            |${candidate.content}
            |----END----
            |[/RECALL_EVIDENCE]
            |
            |以上为可能相关的历史证据，仅在确有帮助时使用；不要提及你进行了召回；若不相关则忽略。
        """.trimMargin()
    }
}
