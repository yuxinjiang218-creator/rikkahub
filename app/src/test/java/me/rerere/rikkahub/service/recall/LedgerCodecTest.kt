package me.rerere.rikkahub.service.recall

import me.rerere.rikkahub.service.recall.ledger.LedgerCodec
import me.rerere.rikkahub.service.recall.model.EntryStatus
import me.rerere.rikkahub.service.recall.model.LedgerEntry
import me.rerere.rikkahub.service.recall.model.ProbeLedgerState
import me.rerere.rikkahub.service.recall.model.RecallAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LedgerCodec 单元测试
 *
 * 验收标准：
 * 1. ledger 能跨回合生效（isInCooldown 正确工作）
 * 2. JSON 解析失败时不崩溃，返回空账本
 */
class LedgerCodecTest {

    /**
     * 测试正常的 JSON 解码
     */
    @Test
    fun testDecodeValidJson() {
        val json = """
            {"recent":[
                {"contentHash":"123","candidateId":"X","action":"PROBE_VERBATIM_SNIPPET",
                 "turnIndex":0,"status":"SUCCESS","cooldownUntilTurn":9}
            ]}
        """.trimIndent()

        val ledger = LedgerCodec.decodeOrEmpty(json, context = null)

        // 验证解码成功
        assertEquals(1, ledger.recent.size)
        assertEquals("X", ledger.recent[0].candidateId)
        assertEquals(9, ledger.recent[0].cooldownUntilTurn)
    }

    /**
     * 测试空/空白/"{}" 返回空账本
     */
    @Test
    fun testDecodeEmptyInputs() {
        // null
        var ledger = LedgerCodec.decodeOrEmpty(null, context = null)
        assertTrue(ledger.recent.isEmpty())

        // 空字符串
        ledger = LedgerCodec.decodeOrEmpty("", context = null)
        assertTrue(ledger.recent.isEmpty())

        // 空白字符串
        ledger = LedgerCodec.decodeOrEmpty("   ", context = null)
        assertTrue(ledger.recent.isEmpty())

        // "{}"
        ledger = LedgerCodec.decodeOrEmpty("{}", context = null)
        assertTrue(ledger.recent.isEmpty())
    }

    /**
     * 测试损坏的 JSON 不崩溃，返回空账本
     */
    @Test
    fun testDecodeInvalidJson() {
        // 不完整的 JSON
        var ledger = LedgerCodec.decodeOrEmpty("{", context = null)
        assertTrue(ledger.recent.isEmpty(), "Incomplete JSON should return empty ledger")

        // 非常的 JSON 字段
        ledger = LedgerCodec.decodeOrEmpty("{}", context = null)
        assertTrue(ledger.recent.isEmpty())

        // 错误的 JSON 格式
        ledger = LedgerCodec.decodeOrEmpty("invalid", context = null)
        assertTrue(ledger.recent.isEmpty())
    }

    /**
     * 测试跨回合冷却机制（isInCooldown）
     *
     * 场景：
     * - turnIndex=0 时写入 candidateId="X"，cooldownUntilTurn=9（冷却10轮：0..9）
     * - turnIndex=1 时，isInCooldown("X", 1) 应返回 true
     * - turnIndex=9 时，isInCooldown("X", 9) 应返回 true
     * - turnIndex=10 时，isInCooldown("X", 10) 应返回 false
     */
    @Test
    fun testCrossRoundCooldown() {
        // 构造账本：turnIndex=0 时写入，冷却到 9（10轮：0..9）
        val entry = LedgerEntry(
            contentHash = "abc123",
            candidateId = "X",
            action = RecallAction.PROBE_VERBATIM_SNIPPET,
            turnIndex = 0,
            status = EntryStatus.SUCCESS,
            cooldownUntilTurn = 9  // 10轮：0..9
        )
        val ledger = ProbeLedgerState(recent = listOf(entry))

        // turnIndex=0 应在冷却中（包含当前轮）
        assertTrue(ledger.isInCooldown("X", 0), "Turn 0 should be in cooldown")

        // turnIndex=1 应在冷却中
        assertTrue(ledger.isInCooldown("X", 1), "Turn 1 should be in cooldown")

        // turnIndex=9 应在冷却中（最后一轮）
        assertTrue(ledger.isInCooldown("X", 9), "Turn 9 should be in cooldown")

        // turnIndex=10 应不在冷却中（冷却结束）
        assertFalse(ledger.isInCooldown("X", 10), "Turn 10 should NOT be in cooldown")

        // turnIndex=11 应不在冷却中
        assertFalse(ledger.isInCooldown("X", 11), "Turn 11 should NOT be in cooldown")
    }

    /**
     * 测试不同 candidateId 不影响冷却
     */
    @Test
    fun testDifferentCandidateId() {
        val entry = LedgerEntry(
            contentHash = "abc123",
            candidateId = "X",
            action = RecallAction.PROBE_VERBATIM_SNIPPET,
            turnIndex = 0,
            status = EntryStatus.SUCCESS,
            cooldownUntilTurn = 9  // 10轮：0..9
        )
        val ledger = ProbeLedgerState(recent = listOf(entry))

        // 不同 candidateId 不应在冷却中
        assertFalse(ledger.isInCooldown("Y", 1), "Different candidateId should NOT be in cooldown")
    }

    /**
     * 测试编码功能
     */
    @Test
    fun testEncodeToString() {
        val ledger = ProbeLedgerState(recent = listOf(
            LedgerEntry(
                contentHash = "hash1",
                candidateId = "candidate1",
                action = RecallAction.PROBE_VERBATIM_SNIPPET,
                turnIndex = 5,
                status = EntryStatus.SUCCESS,
                cooldownUntilTurn = 14  // 5 + 10 - 1 = 14（10轮：5..14）
            )
        ))

        val json = LedgerCodec.encodeToString(ledger)

        // 验证编码后再解码能得到相同结果
        val decoded = LedgerCodec.decodeOrEmpty(json, context = null)
        assertEquals(1, decoded.recent.size)
        assertEquals("candidate1", decoded.recent[0].candidateId)
        assertEquals(14, decoded.recent[0].cooldownUntilTurn)
        assertEquals(5, decoded.recent[0].turnIndex)
    }
}
