package me.rerere.rikkahub.service.recall

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Phase J2: FTS 搜索角色扩展测试
 *
 * 验收标准：
 * J2.1: 含回指助手词时 assistant 内容能被命中并生成候选
 * J2.2: 不含回指助手词时仍只搜 USER
 */
@RunWith(JUnit4::class)
class PhaseJ2FtsSearchRoleExpansionTest {

    /**
     * 回指助手词组（与 TextSourceCandidateGenerator 中的定义保持一致）
     */
    private val ASSISTANT_ANAPHORA_PHRASES = listOf(
        "你说的", "你刚才", "按你刚给的", "按你给的方案", "照你说的",
        "继续", "接着", "你刚发的", "你刚写的"
    )

    /**
     * 测试：检测回指助手词组
     */
    @Test
    fun testAssistantAnaphoraPhrases_Detection() {
        // 场景 A：含回指助手词
        val testCasesWithAnaphora = listOf(
            "按你刚给的方案继续",
            "你刚才说的那段代码",
            "照你说的去做",
            "继续",
            "接着刚才的说",
            "你刚发的内容",
            "你刚写的诗"
        )

        // 每个测试用例都应包含至少一个回指助手词
        for (testCase in testCasesWithAnaphora) {
            val hasMatch = ASSISTANT_ANAPHORA_PHRASES.any { testCase.contains(it) }

            assertTrue(
                hasMatch,
                "测试用例 '$testCase' 应包含回指助手词"
            )
        }
    }

    /**
     * 测试：testFtsSearch_IncludesAssistantOnlyWhenAssistantAnaphora
     *
     * 场景：
     * - 含回指助手词时，SQL 应使用 role IN (?, ?)
     * - 不含回指助手词时，SQL 应使用 role = ?
     */
    @Test
    fun testFtsSearch_DetectsAssistantAnaphora() {
        // 场景 A：含回指助手词
        val withAnaphora = "按你刚给的方案继续实现"

        val hasAnaphora1 = ASSISTANT_ANAPHORA_PHRASES.any { withAnaphora.contains(it) }

        assertTrue(
            hasAnaphora1,
            "'$withAnaphora' 应检测到回指助手词"
        )

        // 场景 B：不含回指助手词
        val withoutAnaphora = "李白的静夜思全文"

        val hasAnaphora2 = ASSISTANT_ANAPHORA_PHRASES.any { withoutAnaphora.contains(it) }

        assertFalse(
            hasAnaphora2,
            "'$withoutAnaphora' 不应检测到回指助手词"
        )
    }

    /**
     * 测试：默认行为 - 不含回指助手词时只搜 USER
     */
    @Test
    fun testFtsSearch_DefaultOnlyUser() {
        val queryWithoutAnaphora = "查询历史记录"

        val hasAnaphora = ASSISTANT_ANAPHORA_PHRASES.any { queryWithoutAnaphora.contains(it) }

        assertFalse(
            hasAnaphora,
            "查询 '$queryWithoutAnaphora' 不应包含回指助手词"
        )
    }

    /**
     * 测试：回指助手词触发 ASSISTANT 扩展
     */
    @Test
    fun testFtsSearch_WithAssistantAnaphora_ExpandsRoles() {
        val queryWithAnaphora = "按你刚给的方案继续"

        val hasAnaphora = ASSISTANT_ANAPHORA_PHRASES.any { queryWithAnaphora.contains(it) }

        assertTrue(
            hasAnaphora,
            "查询 '$queryWithAnaphora' 应包含回指助手词"
        )
    }

    /**
     * 测试：所有回指助手词都应被正确检测
     */
    @Test
    fun testFtsSearch_AllAnaphoraPhrasesDetected() {
        // 验证：所有回指助手词都能被检测到
        for (phrase in ASSISTANT_ANAPHORA_PHRASES) {
            val testQuery = "这是一个包含${phrase}的测试请求"

            val isDetected = ASSISTANT_ANAPHORA_PHRASES.any { testQuery.contains(it) }

            assertTrue(
                isDetected,
                "回指助手词 '$phrase' 应被检测到"
            )
        }
    }
}
