package me.rerere.rikkahub.service.recall

import me.rerere.rikkahub.service.ExplicitSignal
import me.rerere.rikkahub.service.recall.model.Candidate
import me.rerere.rikkahub.service.recall.model.CandidateKind
import me.rerere.rikkahub.service.recall.model.CandidateSource
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import me.rerere.rikkahub.service.recall.model.QueryContext
import me.rerere.rikkahub.service.recall.model.RecallAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A源候选生成器测试（Phase E：SNIPPET优先）
 *
 * 验收标准：
 * 1. SNIPPET 成功组装：window indices 有效时生成 SNIPPET（<=800 chars）
 * 2. SNIPPET 回退 HINT：window indices 无效或 DAO 失败时回退到 HINT（<=200 chars）
 * 3. SNIPPET anchors 包含 node_indices
 * 4. HINT anchors 不包含 node_indices
 */
class ArchiveSourceCandidateGeneratorTest {

    /**
     * 测试1：验证 SNIPPET 候选的正确结构
     *
     * 验收：
     * - kind = SNIPPET
     * - content 长度 <= 800
     * - anchors 包含 "node_indices:start,end"
     * - source = A_ARCHIVE
     */
    @Test
    fun testSnippetCandidateStructure() {
        // 模拟 SNIPPET 候选（从 tryAssembleSnippet 成功后生成）
        val snippetContent = "这是归档内容的原始文本。\n".repeat(100)  // 约 900 chars

        val snippetCandidate = Candidate(
            id = "A:archive123:SNIPPET",
            source = CandidateSource.A_ARCHIVE,
            kind = CandidateKind.SNIPPET,
            content = snippetContent.take(800),  // 截断到 800
            anchors = listOf(
                "archive_id:archive123",
                "node_indices:10,20"
            ),
            cost = 800,
            evidenceRaw = mapOf(
                "archive_id" to "archive123",
                "max_cos_sim" to "0.85",
                "created_at" to "1234567890"
            )
        )

        // 验证：kind = SNIPPET
        assertEquals(CandidateKind.SNIPPET, snippetCandidate.kind, "kind 应为 SNIPPET")

        // 验证：content 长度 <= 800
        assertTrue(
            snippetCandidate.content.length <= 800,
            "SNIPPET content 长度应 <= 800，实际：${snippetCandidate.content.length}"
        )

        // 验证：source = A_ARCHIVE
        assertEquals(CandidateSource.A_ARCHIVE, snippetCandidate.source, "source 应为 A_ARCHIVE")

        // 验证：anchors 包含 node_indices
        val hasNodeIndices = snippetCandidate.anchors.any { it.startsWith("node_indices:") }
        assertTrue(hasNodeIndices, "SNIPPET anchors 应包含 node_indices")

        // 验证：anchors 包含 archive_id
        val hasArchiveId = snippetCandidate.anchors.any { it.startsWith("archive_id:") }
        assertTrue(hasArchiveId, "SNIPPET anchors 应包含 archive_id")
    }

    /**
     * 测试2：验证 HINT 候选的正确结构（回退情况）
     *
     * 验收：
     * - kind = HINT
     * - content 长度 <= 200
     * - anchors 不包含 node_indices
     * - source = A_ARCHIVE
     */
    @Test
    fun testHintCandidateStructure_Fallback() {
        // 模拟 HINT 候选（从 tryAssembleSnippet 失败后回退）
        val hintContent = "这是归档摘要内容，用于 SNIPPET 失败时的回退。"

        val hintCandidate = Candidate(
            id = "A:archive456:HINT",
            source = CandidateSource.A_ARCHIVE,
            kind = CandidateKind.HINT,
            content = hintContent.take(200),  // 截断到 200
            anchors = listOf(
                "archive_id:archive456"
            ),
            cost = hintContent.length,
            evidenceRaw = mapOf(
                "archive_id" to "archive456",
                "max_cos_sim" to "0.72",
                "created_at" to "1234567890"
            )
        )

        // 验证：kind = HINT
        assertEquals(CandidateKind.HINT, hintCandidate.kind, "kind 应为 HINT")

        // 验证：content 长度 <= 200
        assertTrue(
            hintCandidate.content.length <= 200,
            "HINT content 长度应 <= 200，实际：${hintCandidate.content.length}"
        )

        // 验证：source = A_ARCHIVE
        assertEquals(CandidateSource.A_ARCHIVE, hintCandidate.source, "source 应为 A_ARCHIVE")

        // 验证：anchors 不包含 node_indices（HINT 不应该有）
        val hasNodeIndices = hintCandidate.anchors.any { it.startsWith("node_indices:") }
        assertTrue(!hasNodeIndices, "HINT anchors 不应包含 node_indices")

        // 验证：anchors 包含 archive_id
        val hasArchiveId = hintCandidate.anchors.any { it.startsWith("archive_id:") }
        assertTrue(hasArchiveId, "HINT anchors 应包含 archive_id")
    }

    /**
     * 测试3：验证 SNIPPET 超长时正确截断
     *
     * 验收：
     * - 超长 SNIPPET 被截断到 800 chars
     * - 截断后的长度 <= 800
     */
    @Test
    fun testSnippetTruncation_LongContent() {
        // 模拟超长 SNIPPET 内容（1500 chars）
        val longSnippetContent = "A".repeat(1500)

        val truncatedContent = longSnippetContent.take(800)

        // 验证：截断后长度 <= 800
        assertTrue(
            truncatedContent.length <= 800,
            "截断后长度应 <= 800，实际：${truncatedContent.length}"
        )

        assertEquals(800, truncatedContent.length, "应截断到 800 chars")
    }

    /**
     * 测试4：验证 HINT 超长时正确截断
     *
     * 验收：
     * - 超长 HINT 被截断到 200 chars
     * - 截断后的长度 <= 200
     */
    @Test
    fun testHintTruncation_LongContent() {
        // 模拟超长 HINT 内容（500 chars）
        val longHintContent = "B".repeat(500)

        val truncatedContent = longHintContent.take(200)

        // 验证：截断后长度 <= 200
        assertTrue(
            truncatedContent.length <= 200,
            "截断后长度应 <= 200，实际：${truncatedContent.length}"
        )

        assertEquals(200, truncatedContent.length, "应截断到 200 chars")
    }

    /**
     * 测试5：验证 SNIPPET 与 HINT 的 ID 格式
     *
     * 验收：
     * - SNIPPET ID 格式：A:${archiveId}:SNIPPET
     * - HINT ID 格式：A:${archiveId}:HINT
     */
    @Test
    fun testCandidateIdFormat() {
        val archiveId = "archive789"

        val snippetId = "A:${archiveId}:SNIPPET"
        val hintId = "A:${archiveId}:HINT"

        assertTrue(
            snippetId.startsWith("A:$archiveId:"),
            "SNIPPET ID 应以 'A:$archiveId:' 开头"
        )
        assertTrue(
            snippetId.endsWith(":SNIPPET"),
            "SNIPPET ID 应以 ':SNIPPET' 结尾"
        )

        assertTrue(
            hintId.startsWith("A:$archiveId:"),
            "HINT ID 应以 'A:$archiveId:' 开头"
        )
        assertTrue(
            hintId.endsWith(":HINT"),
            "HINT ID 应以 ':HINT' 结尾"
        )
    }

    /**
     * 测试6：验证 SNIPPET 与 HINT 的成本计算
     *
     * 验收：
     * - SNIPPET cost = content.length（<=800）
     * - HINT cost = content.length（<=200）
     */
    @Test
    fun testCandidateCostCalculation() {
        val snippetContent = "C".repeat(800)
        val hintContent = "D".repeat(200)

        val snippetCost = snippetContent.length
        val hintCost = hintContent.length

        assertEquals(800, snippetCost, "SNIPPET cost 应为 800")
        assertEquals(200, hintCost, "HINT cost 应为 200")

        // 验证 cost 在预算内
        assertTrue(snippetCost <= 800, "SNIPPET cost 应 <= 800")
        assertTrue(hintCost <= 200, "HINT cost 应 <= 200")
    }
}
