package me.rerere.rikkahub.service.recall.model

import kotlinx.serialization.Serializable

/**
 * 召回候选
 *
 * @param id 稳定ID：P源用 "P:${conversationId}:${kind}:${nodeIndices}"，A源用 "A:${archiveId}:${kind}"
 * @param source 候选来源（P_TEXT 或 A_ARCHIVE）
 * @param kind 候选种类（SNIPPET、HINT 或 FULL）
 * @param content 候选内容
 * @param anchors 锚点列表（title、nodeIndex 等）
 * @param cost 成本（字符数）
 * @param evidenceKey 证据键（Phase F：用于升级绕过冷却，不含 kind）
 * @param evidenceRaw 原始证据数据（用于调试和日志）
 */
@Serializable
data class Candidate(
    val id: String,
    val source: CandidateSource,
    val kind: CandidateKind,
    val content: String,
    val anchors: List<String>,
    val cost: Int,
    val evidenceKey: String,  // Phase F: 证据键（不含 kind）
    val evidenceRaw: Map<String, String?>
)

/**
 * 候选构建器（用于生成稳定ID和evidenceKey）
 */
object CandidateBuilder {
    /**
     * 生成 P源候选ID
     * 格式：P:${conversationId}:${kind}:${nodeIndices}
     *
     * nodeIndices 会先去重排序，确保相同证据生成相同 ID（避免顺序漂移导致冷却失效）
     */
    fun buildPSourceId(
        conversationId: String,
        kind: CandidateKind,
        nodeIndices: List<Int>
    ): String {
        val normalized = nodeIndices.distinct().sorted()
        return "P:$conversationId:$kind:${normalized.joinToString(",")}"
    }

    /**
     * 生成 A源候选ID
     * 格式：A:${archiveId}:${kind}
     */
    fun buildASourceId(
        archiveId: String,
        kind: CandidateKind
    ): String {
        return "A:$archiveId:$kind"
    }

    /**
     * 生成 P源证据键（Phase F：用于升级绕过冷却，不含 kind）
     * 格式：P:${conversationId}:${normalizedNodeIndices}
     *
     * 用途：支持同一证据的不同 kind 之间升级（如 HINT → SNIPPET）
     */
    fun buildPSourceEvidenceKey(
        conversationId: String,
        nodeIndices: List<Int>
    ): String {
        val normalized = nodeIndices.distinct().sorted()
        return "P:$conversationId:${normalized.joinToString(",")}"
    }

    /**
     * 生成 A源证据键（Phase F：用于升级绕过冷却，不含 kind）
     * 格式：A:${archiveId}
     *
     * 用途：支持同一归档的不同 kind 之间升级（如 HINT → SNIPPET）
     */
    fun buildASourceEvidenceKey(
        archiveId: String
    ): String {
        return "A:$archiveId"
    }
}
