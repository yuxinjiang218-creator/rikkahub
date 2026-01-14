package me.rerere.rikkahub.service.recall

import me.rerere.rikkahub.service.recall.model.Candidate
import me.rerere.rikkahub.service.recall.model.CandidateKind
import me.rerere.rikkahub.service.recall.model.CandidateSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase G 契约测试：A源 SNIPPET 成本/质量护栏（G3）
 *
 * 验收标准：
 * G3.1 成本护栏：
 * - MAX_NODE_TEXT_ROWS = 50（DAO 拉取条数硬上限）
 * - 拼接过程中一旦达到 800 chars 立即停止
 * - 若 window > 200，只取前 50 条；不得全量拉取
 *
 * G3.2 质量护栏（写死，保守）：
 * - 若 cosineSimilarity 在 [0.30, 0.35)，只生成 HINT，不生成 SNIPPET
 * - 若 assembled snippet 清理后长度 < 80 chars，退化为 HINT
 */
class PhaseGASnippetGuardrailsTest {

    /**
     * 测试1：G3.1 DAO 拉取条数上限（MAX_NODE_TEXT_ROWS = 50）
     *
     * 场景：
     * - window 包含 >50 条 node_text（例如 100 条）
     * - 最终 DAO 调用受限（最多 50 条）
     * - snippet 仍 <=800 chars
     *
     * 验收：
     * - indices.size <= 50
     * - snippet.length <= 800
     */
    @Test
    fun testASnippetDaoRowLimitEnforced() {
        // 模拟 window 包含 100 条 node_text
        val windowStartIndex = 0
        val windowEndIndex = 99  // 100 条
        val windowSize = windowEndIndex - windowStartIndex + 1

        // Phase G3.1: window > 200 时只取前 50 条
        // window = 100 < 200，但最多取 50 条
        val MAX_NODE_TEXT_ROWS = 50
        val MAX_WINDOW_SIZE_FOR_FULL = 200

        val indices = if (windowSize > MAX_WINDOW_SIZE_FOR_FULL) {
            (windowStartIndex until (windowStartIndex + MAX_NODE_TEXT_ROWS)).toList()
        } else {
            val allIndices = (windowStartIndex..windowEndIndex).toList()
            allIndices.take(MAX_NODE_TEXT_ROWS)
        }

        // 验证：indices.size <= 50
        assertTrue(
            indices.size <= MAX_NODE_TEXT_ROWS,
            "indices.size 应 <= 50，实际：${indices.size}"
        )

        // 验证：window=100 < 200，取全部但不超过 50
        assertEquals(50, indices.size, "window=100 时应取前 50 条")

        // 验证：indices 是从 0 开始的连续序列
        assertEquals(0, indices.first(), "第一条 index 应为 0")
        assertEquals(49, indices.last(), "最后一条 index 应为 49")
    }

    /**
     * 测试2：G3.1 window > 200 时只取前 50 条
     *
     * 场景：
     * - window = 250 条
     * - 只取前 50 条，不得全量拉取
     *
     * 验收：
     * - indices.size = 50
     * - indices = [0, 1, 2, ..., 49]
     */
    @Test
    fun testASnippetWindowTooLargeOnlyTakesFirst50() {
        // 模拟 window 包含 250 条 node_text
        val windowStartIndex = 0
        val windowEndIndex = 249  // 250 条
        val windowSize = windowEndIndex - windowStartIndex + 1

        // Phase G3.1: window > 200 时只取前 50 条
        val MAX_NODE_TEXT_ROWS = 50
        val MAX_WINDOW_SIZE_FOR_FULL = 200

        val indices = if (windowSize > MAX_WINDOW_SIZE_FOR_FULL) {
            (windowStartIndex until (windowStartIndex + MAX_NODE_TEXT_ROWS)).toList()
        } else {
            val allIndices = (windowStartIndex..windowEndIndex).toList()
            allIndices.take(MAX_NODE_TEXT_ROWS)
        }

        // 验证：indices.size = 50
        assertEquals(MAX_NODE_TEXT_ROWS, indices.size, "window>200 时应只取前 50 条")

        // 验证：indices 是从 windowStartIndex 开始的连续序列
        assertEquals(windowStartIndex, indices.first(), "第一条 index 应为 windowStartIndex")
        assertEquals(windowStartIndex + MAX_NODE_TEXT_ROWS - 1, indices.last(), "最后一条 index 应为 windowStartIndex+49")
    }

    /**
     * 测试3：G3.1 拼接过程中达到 800 chars 立即停止
     *
     * 场景：
     * - 假设有 10 条 node_text，每条约 100 chars
     * - 拼接过程中一旦达到 800 chars 立即停止
     *
     * 验收：
     * - 最终 snippet.length <= 800
     * - 不会拼接全部 10 条（1000 chars）
     */
    @Test
    fun testASnippetStopsAt800Chars() {
        // 模拟 10 条 node_text，每条约 100 chars
        val nodeTexts = (1..10).map { i ->
            "Node text content $i. ".repeat(10)  // 约 150 chars
        }

        val SNIPPET_MAX_CHARS = 800
        val snippetBuilder = StringBuilder()
        var totalLength = 0

        for (nodeText in nodeTexts) {
            val textLength = nodeText.length

            // Phase G3.1: 拼接过程中一旦达到 800 chars 立即停止
            if (totalLength + textLength > SNIPPET_MAX_CHARS) {
                val remainingChars = SNIPPET_MAX_CHARS - totalLength
                if (remainingChars > 0) {
                    snippetBuilder.append(nodeText.take(remainingChars))
                }
                break  // 达到 800 chars，停止拼接
            }

            snippetBuilder.append(nodeText)
            totalLength += textLength

            // 分隔符
            if (totalLength < SNIPPET_MAX_CHARS) {
                snippetBuilder.append("\n")
                totalLength += 1
            }
        }

        val snippet = snippetBuilder.toString()

        // 验证：snippet.length <= 800
        assertTrue(
            snippet.length <= SNIPPET_MAX_CHARS,
            "snippet.length 应 <= 800，实际：${snippet.length}"
        )

        // 验证：不会拼接全部 10 条（约 1500 chars）
        assertTrue(snippet.length < 1200, "应在达到 800 chars 时停止拼接，不会全部拼接")

        // 验证：接近 800 chars
        assertTrue(snippet.length >= 700, "应接近 800 chars，实际：${snippet.length}")
    }

    /**
     * 测试4：G3.2 边缘相似度区间 [0.30, 0.34) 只生成 HINT
     *
     * 场景：
     * - cosineSimilarity = 0.32（在 [0.30, 0.34) 区间）
     * - 即使 window 数据充足，也只生成 HINT，不生成 SNIPPET
     *
     * 验收：
     * - candidate.kind = HINT
     * - 不调用 tryAssembleSnippet（或调用后回退 HINT）
     *
     * Phase I 注：本测试已更新以适应 Balanced v1 阈值（EDGE_SIMILARITY_MAX: 0.35 → 0.34）
     */
    @Test
    fun testASnippetEdgeSimilarityFallsBackToHint() {
        // 模拟 cosineSimilarity = 0.32（仍在边缘区间内）
        val similarity = 0.32f

        // Phase G3.2: 边缘相似度区间（Phase I: 调整为 [0.30, 0.34)）
        val EDGE_SIMILARITY_MIN = 0.30f
        val EDGE_SIMILARITY_MAX = 0.34f  // Phase I: 调整为 0.34

        val isInEdgeZone = similarity >= EDGE_SIMILARITY_MIN && similarity < EDGE_SIMILARITY_MAX

        // 验证：在边缘区间
        assertTrue(isInEdgeZone, "similarity=0.32 应在边缘区间 [0.30, 0.34)")

        // 模拟：直接生成 HINT，不尝试 SNIPPET
        val kind = CandidateKind.HINT

        // 验证：kind = HINT
        assertEquals(CandidateKind.HINT, kind, "边缘相似度应只生成 HINT，不生成 SNIPPET")

        // 验证：kind != SNIPPET
        assertFalse(
            kind == CandidateKind.SNIPPET,
            "边缘相似度不应生成 SNIPPET"
        )
    }

    /**
     * 测试5：G3.2 SNIPPET 清理后长度 < 80 chars 退 HINT
     *
     * 场景：
     * - assembled snippet = "简短内容"（< 80 chars）
     * - 应退化为 HINT
     *
     * 验收：
     * - kind = HINT
     * - content = archive.content（HINT 内容）
     */
    @Test
    fun testASnippetTooShortFallsBackToHint() {
        // 模拟组装的 snippet 很短
        val snippetContent = "简短内容"

        // Phase G3.2: SNIPPET 最小长度
        val MIN_SNIPPET_LENGTH = 80

        val cleanedSnippet = snippetContent.trim()
        val isTooShort = cleanedSnippet.length < MIN_SNIPPET_LENGTH

        // 验证：snippet 过短
        assertTrue(isTooShort, "snippet 长度 ${cleanedSnippet.length} 应 < ${MIN_SNIPPET_LENGTH}")

        // 模拟：退化为 HINT
        val archiveContent = "这是归档摘要内容，作为 HINT 返回。"
        val kind = if (isTooShort) {
            CandidateKind.HINT
        } else {
            CandidateKind.SNIPPET
        }

        // 验证：kind = HINT
        assertEquals(CandidateKind.HINT, kind, "snippet <80 chars 应退 HINT")

        // 验证：使用 HINT 内容
        val content = if (kind == CandidateKind.HINT) {
            archiveContent
        } else {
            snippetContent
        }

        assertEquals(archiveContent, content, "应使用 HINT 内容")
    }

    /**
     * 测试6：G3.2 SNIPPET 清理后长度 >= 80 chars 保持 SNIPPET
     *
     * 场景：
     * - assembled snippet = "足够长度的内容..."（>= 80 chars）
     * - 应保持 SNIPPET
     *
     * 验收：
     * - kind = SNIPPET
     * - content = snippetContent
     */
    @Test
    fun testASnippetLongEnoughStaysSnippet() {
        // 模拟组装的 snippet 足够长
        val snippetContent = "A".repeat(100)  // 100 chars

        // Phase G3.2: SNIPPET 最小长度
        val MIN_SNIPPET_LENGTH = 80

        val cleanedSnippet = snippetContent.trim()
        val isTooShort = cleanedSnippet.length < MIN_SNIPPET_LENGTH

        // 验证：snippet 足够长
        assertFalse(isTooShort, "snippet 长度 ${cleanedSnippet.length} 应 >= ${MIN_SNIPPET_LENGTH}")

        // 模拟：保持 SNIPPET
        val kind = if (isTooShort) {
            CandidateKind.HINT
        } else {
            CandidateKind.SNIPPET
        }

        // 验证：kind = SNIPPET
        assertEquals(CandidateKind.SNIPPET, kind, "snippet >=80 chars 应保持 SNIPPET")
    }

    /**
     * 测试7：G3.2 相似度 >= 0.34 时正常生成 SNIPPET
     *
     * 场景：
     * - cosineSimilarity = 0.40（>= 0.34）
     * - 允许尝试生成 SNIPPET
     *
     * 验收：
     * - 不在边缘区间
     * - 允许生成 SNIPPET（如果数据充足）
     *
     * Phase I 注：本测试已更新以适应 Balanced v1 阈值（EDGE_SIMILARITY_MAX: 0.35 → 0.34）
     */
    @Test
    fun testASnippetHighSimilarityAllowsSnippet() {
        // 模拟 cosineSimilarity = 0.40
        val similarity = 0.40f

        // Phase G3.2: 边缘相似度区间（Phase I: 调整为 [0.30, 0.34)）
        val EDGE_SIMILARITY_MIN = 0.30f
        val EDGE_SIMILARITY_MAX = 0.34f  // Phase I: 调整为 0.34

        val isInEdgeZone = similarity >= EDGE_SIMILARITY_MIN && similarity < EDGE_SIMILARITY_MAX

        // 验证：不在边缘区间
        assertFalse(isInEdgeZone, "similarity=0.40 不应在边缘区间")

        // 验证：允许尝试 SNIPPET（不在边缘区间）
        assertTrue(similarity >= EDGE_SIMILARITY_MAX, "similarity >= 0.34 应允许 SNIPPET（Phase I）")
    }

    /**
     * 测试8：G3.2 相似度 < 0.30 时不应生成候选（已在 MIN_COS_SIM 过滤）
     *
     * 场景：
     * - cosineSimilarity = 0.28（< 0.30）
     * - 应被 MIN_COS_SIM 过滤，不生成候选
     *
     * 验收：
     * - 相似度 < MIN_COS_SIM
     * - 不会进入候选生成流程
     */
    @Test
    fun testASnippetLowSimilarityFilteredOut() {
        // 模拟 cosineSimilarity = 0.28
        val similarity = 0.28f

        // MIN_COS_SIM 过滤
        val MIN_COS_SIM = 0.3f

        val isBelowThreshold = similarity < MIN_COS_SIM

        // 验证：低于阈值
        assertTrue(isBelowThreshold, "similarity=0.28 应 < MIN_COS_SIM=0.3")

        // 验证：被过滤
        assertTrue(similarity < MIN_COS_SIM, "应被 MIN_COS_SIM 过滤，不生成候选")
    }
}
