package me.rerere.ai.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import kotlin.time.Instant
import kotlin.time.Clock

/**
 * 归档摘要 (A)
 *
 * 职责：
 * - 多条、只读、冻结
 * - 一旦生成，永不修改
 * - 只用于检索，不参与 S 生成
 *
 * 生成规则：
 * - Aₖ = archive(Wₖ)
 * - 输入只允许：Wₖ（当前窗口消息）
 * - 禁止输入 S、旧 A、任何回填
 *
 * 内容约束（只能包含这 5 类）：
 * 1. 发生了什么（事件事实）
 * 2. 已达成的结论 / 决策
 * 3. 新增或变更的约束 / 偏好
 * 4. 未解决的问题
 * 5. 检索线索（关键词）
 *
 * 长度：
 * - 目标 100-300 字
 * - 上限 500 字
 */
@Serializable
data class ArchiveSummary(
    val id: Uuid = Uuid.random(),
    val conversationId: Uuid,
    // 窗口索引范围 [windowStartIndex, windowEndIndex)
    val windowStartIndex: Int,
    val windowEndIndex: Int,
    // 归档内容（100-300字，上限500字）
    val content: String,
    // 创建时间
    val createdAt: Instant = Clock.System.now(),
    // 用于生成此归档向量的模型 ID
    // null 表示未生成向量（容错设计）
    val embeddingModelId: Uuid? = null
)
