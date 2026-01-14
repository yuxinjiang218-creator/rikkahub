package me.rerere.rikkahub.service.recall.model

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.service.ExplicitSignal

/**
 * 查询上下文
 *
 * 召回系统的输入上下文，包含所有必要的信息用于决策。
 *
 * @param conversationId 会话ID
 * @param lastUserText 最后一条用户消息
 * @param runningSummary 运行时摘要（可为 null）
 * @param windowTexts 最近 N 条消息文本（N = min(8, messageNodes.size)）
 * @param settingsSnapshot 设置快照
 * @param assistantSnapshot 助手快照
 * @param ledger 探针账本状态
 * @param nowTurnIndex 当前回合索引
 * @param explicitSignal 显式信号（从 IntentRouter.detectExplicitRecallSignal 获取）
 */
@Serializable
data class QueryContext(
    val conversationId: String,
    val lastUserText: String,
    val runningSummary: String?,
    val windowTexts: List<String>,
    val settingsSnapshot: SettingsSnapshot,
    val assistantSnapshot: AssistantSnapshot,
    val ledger: ProbeLedgerState,
    val nowTurnIndex: Int,
    val explicitSignal: me.rerere.rikkahub.service.ExplicitSignal
)

/**
 * 设置快照（仅包含召回相关的字段）
 */
@Serializable
data class SettingsSnapshot(
    val enableVerbatimRecall: Boolean,
    val enableArchiveRecall: Boolean,
    val embeddingModelId: String?
)

/**
 * 助手快照（仅包含召回相关的字段）
 */
@Serializable
data class AssistantSnapshot(
    val id: String,
    val name: String
)
