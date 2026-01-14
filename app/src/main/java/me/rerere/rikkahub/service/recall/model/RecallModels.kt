package me.rerere.rikkahub.service.recall.model

import kotlinx.serialization.Serializable

/**
 * 候选来源
 */
@Serializable
enum class CandidateSource {
    P_TEXT,      // 逐字文本源（当前对话）
    A_ARCHIVE    // 归档摘要源（历史对话）
}

/**
 * 候选种类
 */
@Serializable
enum class CandidateKind {
    SNIPPET,     // 片段（<=800 chars）
    HINT,        // 提示（<=200 chars）
    FULL         // 完整逐字（<=6000 chars，仅显式请求）
}

/**
 * 召回动作
 */
@Serializable
enum class RecallAction {
    NONE,                    // 不召回
    PROBE_VERBATIM_SNIPPET,  // 试探性逐字片段
    FACT_HINT,               // 事实提示
    FULL_VERBATIM            // 完整逐字
}

/**
 * 账本条目状态
 */
@Serializable
enum class EntryStatus {
    SUCCESS,           // 成功
    SKIPPED_COOLDOWN,  // 跳过（冷却中）
    SKIPPED_BUDGET     // 跳过（预算不足）
}
