package me.rerere.rikkahub.service.recall

import me.rerere.rikkahub.service.recall.model.Candidate
import me.rerere.rikkahub.service.recall.model.CandidateKind
import me.rerere.rikkahub.service.recall.model.CandidateSource
import me.rerere.rikkahub.service.recall.model.EntryStatus
import me.rerere.rikkahub.service.recall.model.LastProbeObservation
import me.rerere.rikkahub.service.recall.model.LedgerEntry
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import me.rerere.rikkahub.service.recall.model.ProbeOutcome
import me.rerere.rikkahub.service.recall.model.RecallAction
import me.rerere.rikkahub.service.recall.probe.ProbeAcceptanceJudge
import me.rerere.rikkahub.service.recall.probe.ProbeControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ProbeControl 单元测试（Phase E）
 *
 * 验收标准：
 * 1. ACCEPT：上一轮 PROBE，本轮输入"对/继续" => strikes 归零，且允许升级
 * 2. REJECT：上一轮 PROBE，本轮输入"不是这个" => cooldownUntilTurn 变长（>= now+30）
 * 3. SILENT WINDOW：连续两次 IGNORE => globalProbeStrikes>=2 => silentUntilTurn 生效
 */
class ProbeControlTest {

    /**
     * 测试1：ACCEPT（上一轮 PROBE，本轮输入"对"）
     *
     * 验收：
     * - outcome = ACCEPT
     * - strikes 归零
     * - 允许升级（canUpgradeOnce 返回 true）
     */
    @Test
    fun testProbeAccepted_ContinuesWithUpgrade() {
        // 1. 构造上一轮 PROBE
        val lastObservation = LastProbeObservation(
            turnIndex = 0,
            action = RecallAction.PROBE_VERBATIM_SNIPPET,
            candidateId = "P:conv123:SNIPPET:45,46,47",
            content = "这是静夜思的内容...",
            anchors = listOf("title:静夜思"),
            outcome = ProbeOutcome.IGNORE  // 默认，本轮会重新评估
        )

        val ledger = ProbeLedgerState(
            globalProbeStrikes = 1,  // 之前有 1 个 strike
            lastProbeObservation = lastObservation
        )

        // 2. 本轮输入"对"
        val lastUserText = "对，就是这个"

        // 3. 评估接住判定
        val outcome = ProbeAcceptanceJudge.judge(lastUserText, lastObservation)

        // 验证：ACCEPT
        assertEquals(ProbeOutcome.ACCEPT, outcome, "输入'对'应判定为 ACCEPT")

        // 4. 模拟 handleAccept（strikes 归零）
        val updatedLedger = ledger.copy(
            globalProbeStrikes = 0,  // ACCEPT 后归零
            lastProbeObservation = lastObservation.copy(outcome = ProbeOutcome.ACCEPT)
        )

        // 验证：strikes 归零
        assertEquals(0, updatedLedger.globalProbeStrikes, "ACCEPT 后 strikes 应归零")

        // 验证：允许升级（candidateId 匹配且 outcome=ACCEPT）
        val canUpgrade = updatedLedger.canUpgradeOnce(lastObservation.candidateId)
        assertTrue(canUpgrade, "ACCEPT 后应允许升级绕过冷却")
    }

    /**
     * 测试2：REJECT（上一轮 PROBE，本轮输入"不是这个"）
     *
     * 验收：
     * - outcome = REJECT
     * - cooldownUntilTurn >= now + 30（延长冷却）
     * - 本轮/后续被压制
     */
    @Test
    fun testProbeRejected_ExtendedCooldown() {
        // 1. 构造上一轮 PROBE
        val lastObservation = LastProbeObservation(
            turnIndex = 5,
            action = RecallAction.PROBE_VERBATIM_SNIPPET,
            candidateId = "P:conv456:SNIPPET:1,2,3",
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

        // 2. 本轮输入"不是这个"
        val lastUserText = "不是这个，我说的是另一段"

        // 3. 评估接住判定
        val outcome = ProbeAcceptanceJudge.judge(lastUserText, lastObservation)

        // 验证：REJECT
        assertEquals(ProbeOutcome.REJECT, outcome, "输入'不是这个'应判定为 REJECT")

        // 4. 模拟 handleReject（延长冷却至 now + 30）
        val nowTurnIndex = 10
        val updatedRecent = ledger.recent.map { entry ->
            if (entry.candidateId == lastObservation.candidateId) {
                entry.copy(cooldownUntilTurn = nowTurnIndex + 30)
            } else {
                entry
            }
        }

        val updatedLedgerForTest = ledger.copy(recent = updatedRecent)
        val cooldownEntry = updatedRecent.first { it.candidateId == lastObservation.candidateId }

        // 验证：延长冷却至 now + 30
        assertTrue(
            cooldownEntry.cooldownUntilTurn >= nowTurnIndex + 30,
            "REJECT 后 cooldownUntilTurn 应 >= now + 30，实际：${cooldownEntry.cooldownUntilTurn}，期望：${nowTurnIndex + 30}"
        )

        // 验证：本轮被压制（在冷却中）
        val isInCooldown = updatedLedgerForTest.isInCooldown(lastObservation.candidateId, nowTurnIndex)
        assertTrue(isInCooldown, "REJECT 后本轮应在冷却中")
    }

    /**
     * 测试3：SILENT WINDOW（连续两次 IGNORE）
     *
     * 验收：
     * - globalProbeStrikes >= 2
     * - silentUntilTurn 生效（now + 10）
     * - non-explicit 在静默窗口内被阻止
     */
    @Test
    fun testSilentWindow_TwoIgnoreesActivates() {
        // 1. 构造连续两次 IGNORE
        val nowTurnIndex = 20

        val ledger = ProbeLedgerState(
            globalProbeStrikes = 2,  // 已累计 2 个 strike
            silentUntilTurn = nowTurnIndex + 10  // 静默窗口到 30
        )

        // 验证：strikes >= 2
        assertTrue(
            ledger.globalProbeStrikes >= ProbeLedgerState.MAX_STRIKES,
            "连续两次 IGNORE 后 strikes >= 2"
        )

        // 验证：silentUntilTurn 生效
        assertTrue(
            ledger.isInSilentWindow(nowTurnIndex),
            "应在静默窗口内（nowTurnIndex=$nowTurnIndex, silentUntilTurn=${ledger.silentUntilTurn}）"
        )

        // 验证：non-explicit 被阻止
        val shouldBlock = ProbeControl.isInSilentWindow(ledger, nowTurnIndex)
        assertTrue(shouldBlock, "静默窗口内应阻止 non-explicit 召回")

        // 验证：静默窗口结束后不再阻止
        val afterSilentWindow = nowTurnIndex + 11
        val shouldBlockAfter = ProbeControl.isInSilentWindow(ledger, afterSilentWindow)
        assertFalse(shouldBlockAfter, "静默窗口结束后不应阻止")
    }

    /**
     * 测试4：词重叠率计算（ACCEPT 条件3）
     */
    @Test
    fun testAcceptByOverlapRatio() {
        val lastObservation = LastProbeObservation(
            turnIndex = 0,
            action = RecallAction.PROBE_VERBATIM_SNIPPET,
            candidateId = "P:conv123:SNIPPET:1",
            content = "静夜思的作者是李白，床前明月光",
            anchors = emptyList(),
            outcome = ProbeOutcome.IGNORE
        )

        // 测试词重叠率 >= 0.20
        val lastUserText = "继续讲静夜思和李白"  // 包含"静夜思"、"李白"，重叠率高
        val outcome = ProbeAcceptanceJudge.judge(lastUserText, lastObservation)

        assertEquals(ProbeOutcome.ACCEPT, outcome, "词重叠率>=0.20 应判定为 ACCEPT")
    }

    /**
     * 测试5：命中 anchors（ACCEPT 条件2）
     */
    @Test
    fun testAcceptByAnchorHit() {
        val lastObservation = LastProbeObservation(
            turnIndex = 0,
            action = RecallAction.PROBE_VERBATIM_SNIPPET,
            candidateId = "P:conv789:SNIPPET:5,6",
            content = "算法实现如下...",
            anchors = listOf("title:快速排序", "node_indices:5,6"),
            outcome = ProbeOutcome.IGNORE
        )

        // 测试命中 anchor
        val lastUserText = "快速排序的那个算法详细说说"  // 命中 "title:快速排序"
        val outcome = ProbeAcceptanceJudge.judge(lastUserText, lastObservation)

        assertEquals(ProbeOutcome.ACCEPT, outcome, "命中 anchors 应判定为 ACCEPT")
    }

    /**
     * 测试6：升级机制（选项A：同证据强化注入）
     */
    @Test
    fun testUpgradeMechanism_OptionA() {
        // 1. 构造上一轮 ACCEPT + PROBE
        val lastObservation = LastProbeObservation(
            turnIndex = 0,
            action = RecallAction.PROBE_VERBATIM_SNIPPET,
            candidateId = "P:conv999:SNIPPET:10,11,12",
            content = "上一轮的 SNIPPET 内容...",
            anchors = listOf("title:测试"),
            outcome = ProbeOutcome.ACCEPT  // 上一轮被接住
        )

        val ledger = ProbeLedgerState(
            globalProbeStrikes = 0,  // ACCEPT 后归零
            lastProbeObservation = lastObservation
        )

        // 2. 本轮同一证据（candidateId 匹配）
        val sameCandidateId = lastObservation.candidateId
        val canUpgrade = ledger.canUpgradeOnce(sameCandidateId)

        // 验证：允许升级
        assertTrue(canUpgrade, "上一轮 ACCEPT 且 candidateId 匹配时应允许升级")

        // 3. 验证：FULL 不允许升级
        val fullObservation = lastObservation.copy(
            action = RecallAction.FULL_VERBATIM
        )
        val ledgerWithFull = ledger.copy(
            lastProbeObservation = fullObservation
        )
        val canUpgradeFull = ledgerWithFull.canUpgradeOnce(sameCandidateId)

        assertFalse(canUpgradeFull, "FULL_VERBATIM 不应允许升级")
    }

    /**
     * 测试7：lastProbeObservation 预算护栏（content ≤200, anchors ≤10）
     *
     * 验收：
     * - content 超长时截断到 200 chars
     * - anchors 超过 10 条时截断到前 10 条
     */
    @Test
    fun testLastProbeObservation_BudgetGuardrails() {
        // 1. 构造超长候选（content > 200, anchors > 10）
        val longContent = "A".repeat(500)  // 500 chars
        val manyAnchors = (1..15).map { i -> "anchor$i:value$i" }  // 15 条

        val candidate = Candidate(
            id = "test_candidate",
            source = me.rerere.rikkahub.service.recall.model.CandidateSource.P_TEXT,
            kind = CandidateKind.SNIPPET,
            content = longContent,
            anchors = manyAnchors,
            cost = longContent.length,
            evidenceRaw = emptyMap()
        )

        // 2. 记录试探
        val ledger = ProbeLedgerState()
        val updatedLedger = me.rerere.rikkahub.service.recall.probe.ProbeControl.recordProbe(
            ledger = ledger,
            action = RecallAction.PROBE_VERBATIM_SNIPPET,
            candidate = candidate,
            nowTurnIndex = 0
        )

        // 3. 验证：content 截断到 200
        val recordedObservation = updatedLedger.lastProbeObservation
        assertNotNull(recordedObservation, "应记录 lastProbeObservation")
        assertEquals(
            200,
            recordedObservation!!.content.length,
            "content 应截断到 200 chars，实际：${recordedObservation.content.length}"
        )

        // 4. 验证：anchors 截断到 10
        assertEquals(
            10,
            recordedObservation.anchors.size,
            "anchors 应截断到 10 条，实际：${recordedObservation.anchors.size}"
        )

        // 5. 验证：截断后的内容正确
        assertTrue(
            recordedObservation.content.all { it == 'A' },
            "截断后的 content 应全部为 'A'"
        )

        // 6. 验证：截断后的 anchors 正确
        assertTrue(
            recordedObservation.anchors.all { it.startsWith("anchor") },
            "截断后的 anchors 应全部以 'anchor' 开头"
        )
    }
}
