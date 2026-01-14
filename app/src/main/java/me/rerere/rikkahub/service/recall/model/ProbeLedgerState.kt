package me.rerere.rikkahub.service.recall.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 探针账本状态（Phase E：ProbeControl 增强）
 *
 * 用于实现跨回合的冷却机制、重复抑制、试探接住判定、静默窗口。
 *
 * @param recent 最近的账本条目（最多保留 20 条）
 * @param globalProbeStrikes 全局试探计数（连续 IGNORE/REJECT 累计，ACCEPT 时归零）
 * @param silentUntilTurn 静默窗口截止轮数（默认 0 表示不在静默窗口）
 * @param lastProbeObservation 上一轮试探观察（用于接住判定）
 */
@Serializable
data class ProbeLedgerState(
    val recent: List<LedgerEntry> = emptyList(),
    val globalProbeStrikes: Int = 0,
    val silentUntilTurn: Int = 0,  // Phase E: 静默窗口
    val lastProbeObservation: LastProbeObservation? = null  // Phase E: 上一轮试探观察
) {
    companion object {
        /** 最大保留条目数 */
        const val MAX_RECENT_ENTRIES = 20

        /** 冷却轮数 */
        const val COOLDOWN_TURNS = 10

        /** 静默窗口轮数 */
        const val SILENT_WINDOW_TURNS = 10

        /** 快速停止冷却轮数（REJECT） */
        const val REJECT_COOLDOWN_TURNS = 30

        /** 快速停止冷却轮数（IGNORE） */
        const val IGNORE_COOLDOWN_TURNS = 15

        /** 接住判定词重叠率阈值 */
        const val ACCEPT_OVERLAP_THRESHOLD = 0.20f

        /** 最大 strikes 触发静默窗口 */
        const val MAX_STRIKES = 2
    }

    /**
     * 检查候选是否在冷却中
     *
     * @param candidateId 候选ID
     * @param currentTurnIndex 当前回合索引
     * @return true 如果在冷却中，false 否则
     */
    fun isInCooldown(candidateId: String, currentTurnIndex: Int): Boolean {
        return recent.any { entry ->
            entry.candidateId == candidateId && currentTurnIndex <= entry.cooldownUntilTurn
        }
    }

    /**
     * 检查是否在静默窗口内
     *
     * @param currentTurnIndex 当前回合索引
     * @return true 如果在静默窗口内，false 否则
     */
    fun isInSilentWindow(currentTurnIndex: Int): Boolean {
        return currentTurnIndex <= silentUntilTurn
    }

    /**
     * 检查是否允许一次性绕过冷却（升级机制）
     *
     * Phase F 修改：使用 evidenceKey 比对，支持同一证据的不同 kind 之间升级（如 HINT → SNIPPET）
     *
     * 条件：
     * - 上一轮 outcome = ACCEPT
     * - 上一轮 action 属于 PROBE/FACT/HINT（非 FULL）
     * - 本轮 evidenceKey 匹配上一轮
     *
     * @param evidenceKey 证据键（Phase F）
     * @return true 如果上一轮是 ACCEPT 且 evidenceKey 匹配且非 FULL，false 否则
     */
    fun canUpgradeOnce(evidenceKey: String): Boolean {
        return lastProbeObservation?.let { obs ->
            // FULL_VERBATIM 不允许升级
            if (obs.action == RecallAction.FULL_VERBATIM) {
                return false
            }
            // Phase F: ACCEPT 且 evidenceKey 匹配则允许升级（支持 kind 不同）
            obs.outcome == ProbeOutcome.ACCEPT && obs.evidenceKey == evidenceKey
        } ?: false
    }

    /**
     * 添加新条目
     *
     * @param entry 新条目
     * @return 更新后的账本状态
     */
    fun addEntry(entry: LedgerEntry): ProbeLedgerState {
        val updatedRecent = (listOf(entry) + recent)
            .take(MAX_RECENT_ENTRIES)
        return copy(recent = updatedRecent)
    }

    /**
     * 清空上一轮试探观察（用于本轮开始新的试探后更新）
     */
    fun clearLastObservation(): ProbeLedgerState {
        return copy(lastProbeObservation = null)
    }

    /**
     * 更新上一轮试探观察
     */
    fun updateLastObservation(observation: LastProbeObservation): ProbeLedgerState {
        return copy(lastProbeObservation = observation)
    }

    /**
     * 检查是否在静默窗口内（兼容旧版本 JSON）
     */
    private fun readSilentUntilTurn(): Int {
        // 向后兼容：如果旧 JSON 没有 silentUntilTurn 字段，返回 0
        return silentUntilTurn
    }
}

/**
 * 上一轮试探观察（Phase E）
 *
 * @param turnIndex 回合索引
 * @param action 执行的动作
 * @param candidateId 候选ID
 * @param evidenceKey 证据键（Phase F：用于升级绕过冷却，不含 kind）
 * @param content 候选内容
 * @param anchors 锚点列表
 * @param outcome 接住判定结果（本轮评估后填入）
 */
@Serializable
data class LastProbeObservation(
    val turnIndex: Int,
    val action: RecallAction,
    val candidateId: String,
    val evidenceKey: String,  // Phase F: 证据键（不含 kind）
    val content: String,
    val anchors: List<String>,
    val outcome: ProbeOutcome = ProbeOutcome.IGNORE  // 默认 IGNORE
)

/**
 * 探试接住判定结果（Phase E）
 */
@Serializable
enum class ProbeOutcome {
    /** 接住：用户明确接受/继续/确认 */
    ACCEPT,

    /** 拒绝：用户明确否定 */
    REJECT,

    /** 忽略：没有明确反应（转话题/未接住） */
    IGNORE
}

/**
 * 账本条目
 *
 * @param contentHash 内容哈希（用于去重）
 * @param candidateId 候选ID
 * @param action 执行的动作
 * @param turnIndex 回合索引
 * @param status 条目状态
 * @param cooldownUntilTurn 冷却截止回合索引
 */
@Serializable
data class LedgerEntry(
    val contentHash: String,
    val candidateId: String,
    val action: RecallAction,
    val turnIndex: Int,
    val status: EntryStatus,
    val cooldownUntilTurn: Int
)
