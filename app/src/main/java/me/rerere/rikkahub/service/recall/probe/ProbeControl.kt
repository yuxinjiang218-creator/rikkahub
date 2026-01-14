package me.rerere.rikkahub.service.recall.probe

import me.rerere.rikkahub.debug.DebugLogger
import me.rerere.rikkahub.debug.model.LogLevel
import me.rerere.rikkahub.service.recall.model.Candidate
import me.rerere.rikkahub.service.recall.model.CandidateKind
import me.rerere.rikkahub.service.recall.model.EntryStatus
import me.rerere.rikkahub.service.recall.model.LastProbeObservation
import me.rerere.rikkahub.service.recall.model.LedgerEntry
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import me.rerere.rikkahub.service.recall.model.ProbeOutcome
import me.rerere.rikkahub.service.recall.model.RecallAction

/**
 * 探针控制器（Phase E：ProbeControl 协调器）
 *
 * 负责：
 * 1. 接住判定评估（调用 ProbeAcceptanceJudge）
 * 2. 升级机制（选项A：同证据强化注入）
 * 3. 快速停止（延长冷却 + 静默窗口）
 * 4. Strikes 管理与清零
 */
object ProbeControl {

    /**
     * 评估上一轮试探结果并更新账本
     *
     * @param ledger 当前账本
     * @param lastUserText 用户最新输入
     * @param nowTurnIndex 当前回合索引
     * @param context Android Context（用于日志）
     * @return 更新后的账本
     */
    suspend fun evaluateAndUpdate(
        ledger: ProbeLedgerState,
        lastUserText: String,
        nowTurnIndex: Int,
        context: android.content.Context
    ): ProbeLedgerState {
        val debugLogger = DebugLogger.getInstance(context)

        val lastObservation = ledger.lastProbeObservation
        if (lastObservation == null) {
            // 没有上一轮试探，无需评估
            return ledger
        }

        // 1. 判定接住结果
        val outcome = ProbeAcceptanceJudge.judge(lastUserText, lastObservation)

        debugLogger.log(
            LogLevel.DEBUG,
            "ProbeControl",
            "Probe outcome evaluated",
            mapOf(
                "outcome" to outcome.name,
                "lastAction" to lastObservation.action.name,
                "lastTurnIndex" to lastObservation.turnIndex,
                "nowTurnIndex" to nowTurnIndex
            )
        )

        // 2. 根据结果更新账本
        return when (outcome) {
            ProbeOutcome.ACCEPT -> handleAccept(ledger, nowTurnIndex, context)
            ProbeOutcome.REJECT -> handleReject(ledger, nowTurnIndex, context)
            ProbeOutcome.IGNORE -> handleIgnore(ledger, nowTurnIndex, context)
        }
    }

    /**
     * 处理 ACCEPT（接住）
     *
     * - globalProbeStrikes 归零
     * - 更新 lastProbeObservation.outcome = ACCEPT
     * - 允许升级（下一轮可绕过冷却一次）
     */
    private fun handleAccept(
        ledger: ProbeLedgerState,
        nowTurnIndex: Int,
        context: android.content.Context
    ): ProbeLedgerState {
        val debugLogger = DebugLogger.getInstance(context)

        // Strikes 归零
        val updatedLedger = ledger.copy(
            globalProbeStrikes = 0,
            lastProbeObservation = ledger.lastProbeObservation?.copy(outcome = ProbeOutcome.ACCEPT)
        )

        debugLogger.log(
            LogLevel.INFO,
            "ProbeControl",
            "Probe accepted, strikes cleared",
            mapOf("strikes" to 0)
        )

        return updatedLedger
    }

    /**
     * 处理 REJECT（拒绝）
     *
     * - globalProbeStrikes += 1
     * - 更新 lastProbeObservation.outcome = REJECT
     * - 对上一轮 candidateId 设置延长冷却（30轮）
     * - 如果 strikes >= 2，进入静默窗口
     */
    private fun handleReject(
        ledger: ProbeLedgerState,
        nowTurnIndex: Int,
        context: android.content.Context
    ): ProbeLedgerState {
        val debugLogger = DebugLogger.getInstance(context)

        val lastObservation = ledger.lastProbeObservation ?: return ledger

        // Strikes += 1
        val newStrikes = ledger.globalProbeStrikes + 1

        // 更新上一轮条目的冷却（延长冷却）
        val updatedRecent = ledger.recent.map { entry ->
            if (entry.candidateId == lastObservation.candidateId && entry.turnIndex == lastObservation.turnIndex) {
                // 延长冷却至 now + 30
                entry.copy(cooldownUntilTurn = nowTurnIndex + ProbeLedgerState.REJECT_COOLDOWN_TURNS)
            } else {
                entry
            }
        }

        // 检查是否进入静默窗口
        val silentUntilTurn = if (newStrikes >= ProbeLedgerState.MAX_STRIKES) {
            nowTurnIndex + ProbeLedgerState.SILENT_WINDOW_TURNS
        } else {
            ledger.silentUntilTurn
        }

        val updatedLedger = ledger.copy(
            globalProbeStrikes = newStrikes,
            lastProbeObservation = lastObservation.copy(outcome = ProbeOutcome.REJECT),
            recent = updatedRecent,
            silentUntilTurn = silentUntilTurn
        )

        debugLogger.log(
            LogLevel.INFO,
            "ProbeControl",
            "Probe rejected, extended cooldown",
            mapOf(
                "strikes" to newStrikes,
                "cooldownUntilTurn" to (nowTurnIndex + ProbeLedgerState.REJECT_COOLDOWN_TURNS),
                "silentUntilTurn" to silentUntilTurn
            )
        )

        return updatedLedger
    }

    /**
     * 处理 IGNORE（未接住/转话题）
     *
     * - globalProbeStrikes += 1
     * - 更新 lastProbeObservation.outcome = IGNORE
     * - 对上一轮 candidateId 设置延长冷却（15轮）
     * - 如果 strikes >= 2，进入静默窗口
     */
    private fun handleIgnore(
        ledger: ProbeLedgerState,
        nowTurnIndex: Int,
        context: android.content.Context
    ): ProbeLedgerState {
        val debugLogger = DebugLogger.getInstance(context)

        val lastObservation = ledger.lastProbeObservation ?: return ledger

        // Strikes += 1
        val newStrikes = ledger.globalProbeStrikes + 1

        // 更新上一轮条目的冷却（延长冷却，但比 REJECT 短）
        val updatedRecent = ledger.recent.map { entry ->
            if (entry.candidateId == lastObservation.candidateId && entry.turnIndex == lastObservation.turnIndex) {
                // 延长冷却至 now + 15
                entry.copy(cooldownUntilTurn = nowTurnIndex + ProbeLedgerState.IGNORE_COOLDOWN_TURNS)
            } else {
                entry
            }
        }

        // 检查是否进入静默窗口
        val silentUntilTurn = if (newStrikes >= ProbeLedgerState.MAX_STRIKES) {
            nowTurnIndex + ProbeLedgerState.SILENT_WINDOW_TURNS
        } else {
            ledger.silentUntilTurn
        }

        val updatedLedger = ledger.copy(
            globalProbeStrikes = newStrikes,
            lastProbeObservation = lastObservation.copy(outcome = ProbeOutcome.IGNORE),
            recent = updatedRecent,
            silentUntilTurn = silentUntilTurn
        )

        debugLogger.log(
            LogLevel.INFO,
            "ProbeControl",
            "Probe ignored, extended cooldown",
            mapOf(
                "strikes" to newStrikes,
                "cooldownUntilTurn" to (nowTurnIndex + ProbeLedgerState.IGNORE_COOLDOWN_TURNS),
                "silentUntilTurn" to silentUntilTurn
            )
        )

        return updatedLedger
    }

    /**
     * 记录本轮试探（用于下一轮判定）
     *
     * 预算护栏：
     * - content 截断到 ≤200 chars（避免 JSON 过大）
     * - anchors 限制 ≤10 条（避免列表过长）
     *
     * @param ledger 当前账本
     * @param action 执行的动作
     * @param candidate 选中的候选
     * @param nowTurnIndex 当前回合索引
     * @return 更新后的账本（包含 lastProbeObservation）
     */
    fun recordProbe(
        ledger: ProbeLedgerState,
        action: RecallAction,
        candidate: Candidate?,
        nowTurnIndex: Int
    ): ProbeLedgerState {
        if (action == RecallAction.NONE || candidate == null) {
            // 没有执行试探，不记录
            return ledger.clearLastObservation()
        }

        val observation = LastProbeObservation(
            turnIndex = nowTurnIndex,
            action = action,
            candidateId = candidate.id,
            content = candidate.content.take(200),  // 预算护栏：≤200 chars
            anchors = candidate.anchors.take(10),   // 预算护栏：≤10 条
            outcome = ProbeOutcome.IGNORE  // 默认，下一轮会重新评估
        )

        return ledger.updateLastObservation(observation)
    }

    /**
     * 检查是否允许升级（选项A：同证据强化注入）
     *
     * 条件：
     * - 上一轮 outcome = ACCEPT
     * - 上一轮 action 属于 PROBE/FACT/HINT（非 FULL）
     * - 本轮 candidateId 匹配上一轮
     *
     * 返回：
     * - ALLOW_UPGRADE：允许升级（绕过冷却一次）
     * - NO_UPGRADE：不允许升级
     */
    enum class UpgradeDecision {
        ALLOW_UPGRADE,
        NO_UPGRADE,
        NO_LAST_OBSERVATION
    }

    fun checkUpgrade(ledger: ProbeLedgerState, candidateId: String?): UpgradeDecision {
        val lastObs = ledger.lastProbeObservation

        if (lastObs == null) {
            return UpgradeDecision.NO_LAST_OBSERVATION
        }

        if (candidateId == null) {
            return UpgradeDecision.NO_UPGRADE
        }

        // 检查上一轮是否 ACCEPT 且 candidateId 匹配
        if (lastObs.outcome == ProbeOutcome.ACCEPT && lastObs.candidateId == candidateId) {
            // 检查上一轮 action 是否可升级（PROBE/FACT/HINT）
            return when (lastObs.action) {
                RecallAction.PROBE_VERBATIM_SNIPPET,
                RecallAction.FACT_HINT -> UpgradeDecision.ALLOW_UPGRADE
                RecallAction.FULL_VERBATIM -> UpgradeDecision.NO_UPGRADE  // FULL 不升级
                RecallAction.NONE -> UpgradeDecision.NO_UPGRADE
            }
        }

        return UpgradeDecision.NO_UPGRADE
    }

    /**
     * 检查是否在静默窗口内（禁止 non-explicit 召回）
     */
    fun isInSilentWindow(ledger: ProbeLedgerState, nowTurnIndex: Int): Boolean {
        return ledger.isInSilentWindow(nowTurnIndex)
    }

    /**
     * 应用升级策略（选项A：同证据强化注入）
     *
     * 规则：
     * - A源 HINT -> 优先 SNIPPET（如果可生成）
     * - P源 SNIPPET -> 允许再次注入 SNIPPET（绕过冷却）
     * - FULL 仍只允许 explicit
     *
     * 注意：升级只在候选生成阶段应用，不在决策阶段。
     * 本函数仅检查"是否允许绕过冷却一次"。
     */
    fun shouldBypassCooldownForUpgrade(
        ledger: ProbeLedgerState,
        candidateId: String,
        nowTurnIndex: Int
    ): Boolean {
        // 检查是否在冷却中
        if (!ledger.isInCooldown(candidateId, nowTurnIndex)) {
            // 不在冷却中，无需绕过
            return false
        }

        // 检查是否允许升级
        return when (checkUpgrade(ledger, candidateId)) {
            UpgradeDecision.ALLOW_UPGRADE -> true
            else -> false
        }
    }
}
