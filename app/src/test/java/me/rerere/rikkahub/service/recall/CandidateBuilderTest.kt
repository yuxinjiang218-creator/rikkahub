package me.rerere.rikkahub.service.recall

import me.rerere.rikkahub.service.recall.model.CandidateBuilder
import me.rerere.rikkahub.service.recall.model.CandidateKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CandidateBuilder 单元测试
 *
 * 验收标准：
 * - nodeIndices 排序去重后生成的 candidateId 稳定
 * - [47,45,46,46] 与 [45,46,47] 生成的 candidateId 完全一致
 */
class CandidateBuilderTest {

    /**
     * 测试 P源候选ID排序去重
     */
    @Test
    fun testBuildPSourceIdWithNormalization() {
        val conversationId = "conv123"
        val kind = CandidateKind.SNIPPET

        // 不同顺序、包含重复的 indices
        val indices1 = listOf(47, 45, 46, 46)
        val indices2 = listOf(45, 46, 47)
        val indices3 = listOf(46, 47, 45, 45, 47)

        val id1 = CandidateBuilder.buildPSourceId(conversationId, kind, indices1)
        val id2 = CandidateBuilder.buildPSourceId(conversationId, kind, indices2)
        val id3 = CandidateBuilder.buildPSourceId(conversationId, kind, indices3)

        // 验证所有 ID 完全一致
        assertEquals(id1, id2, "IDs should be identical after normalization")
        assertEquals(id2, id3, "IDs should be identical after normalization")
        assertEquals(id1, id3, "IDs should be identical after normalization")

        // 验证格式正确
        val expected = "P:$conversationId:$kind:45,46,47"
        assertEquals(expected, id1, "Normalized ID should match expected format")
    }

    /**
     * 测试空列表
     */
    @Test
    fun testBuildPSourceIdWithEmptyList() {
        val id = CandidateBuilder.buildPSourceId(
            conversationId = "conv123",
            kind = CandidateKind.SNIPPET,
            nodeIndices = emptyList()
        )

        val expected = "P:conv123:SNIPPET:"
        assertEquals(expected, id, "Empty indices should produce empty suffix")
    }

    /**
     * 测试单个元素
     */
    @Test
    fun testBuildPSourceIdWithSingleElement() {
        val id = CandidateBuilder.buildPSourceId(
            conversationId = "conv456",
            kind = CandidateKind.FULL,
            nodeIndices = listOf(42)
        )

        val expected = "P:conv456:FULL:42"
        assertEquals(expected, id, "Single element should work correctly")
    }

    /**
     * 测试 A源候选ID（不受排序去重影响）
     */
    @Test
    fun testBuildASourceId() {
        val id1 = CandidateBuilder.buildASourceId("archive123", CandidateKind.HINT)
        val id2 = CandidateBuilder.buildASourceId("archive123", CandidateKind.HINT)

        val expected = "A:archive123:HINT"
        assertEquals(expected, id1, "A source ID format should match")
        assertEquals(id1, id2, "Same inputs should produce same ID")
    }

    /**
     * 测试不同 kind 产生不同 ID
     */
    @Test
    fun testDifferentKindProducesDifferentId() {
        val indices = listOf(1, 2, 3)

        val snippetId = CandidateBuilder.buildPSourceId("conv789", CandidateKind.SNIPPET, indices)
        val hintId = CandidateBuilder.buildPSourceId("conv789", CandidateKind.HINT, indices)
        val fullId = CandidateBuilder.buildPSourceId("conv789", CandidateKind.FULL, indices)

        // 验证不同 kind 产生不同 ID
        assertNotEquals(snippetId, hintId, "SNIPPET and HINT should have different IDs")
        assertNotEquals(hintId, fullId, "HINT and FULL should have different IDs")
        assertNotEquals(snippetId, fullId, "SNIPPET and FULL should have different IDs")
    }

    /**
     * 测试不同 conversationId 产生不同 ID
     */
    @Test
    fun testDifferentConversationProducesDifferentId() {
        val indices = listOf(1, 2, 3)

        val id1 = CandidateBuilder.buildPSourceId("conv1", CandidateKind.SNIPPET, indices)
        val id2 = CandidateBuilder.buildPSourceId("conv2", CandidateKind.SNIPPET, indices)

        assertNotEquals(id1, id2, "Different conversations should have different IDs")
    }

    /**
     * 辅助函数：断言不相等
     */
    private fun assertNotEquals(actual: String, expected: String, message: String) {
        if (actual == expected) {
            throw AssertionError("$message\nExpected: $expected\nActual: $actual")
        }
    }
}
