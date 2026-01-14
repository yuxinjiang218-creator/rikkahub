package me.rerere.rikkahub.service.recall

import me.rerere.rikkahub.service.recall.anchor.AnchorGenerator
import me.rerere.rikkahub.service.recall.model.Candidate
import me.rerere.rikkahub.service.recall.model.CandidateKind
import me.rerere.rikkahub.service.recall.model.CandidateSource
import me.rerere.rikkahub.service.recall.model.LastProbeObservation
import me.rerere.rikkahub.service.recall.model.ProbeOutcome
import me.rerere.rikkahub.service.recall.model.RecallAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase G 契约测试：anchors 体系收敛（G1）
 *
 * 验收标准：
 * 1. P源 anchors 仅包含：title, keyword, 高信息词（禁止 nodeIndices）
 * 2. A源 anchors 仅包含：query tokens（禁止结构信息）
 * 3. 每个 anchor 截断到 40 chars，最多 10 个
 * 4. 结构锚点不应触发 ACCEPT（除非确认词/overlap 触发）
 * 5. title/keyword 命中应触发 ACCEPT
 */
class PhaseGAnchorConvergenceTest {

    /**
     * 测试1：P源 anchors 不包含结构信息（nodeIndices）
     */
    @Test
    fun testPSourceAnchorsExcludeStructuralInfo() {
        val query = "静夜思的原文"
        val title = "静夜思"

        val anchors = AnchorGenerator.buildPSourceAnchors(
            query = query,
            explicitTitle = title
        )

        // 验证：不包含 "node_indices:" 或 "nodeIndices" 等结构信息
        val hasStructuralAnchor = anchors.any { anchor ->
            anchor.contains("node_indices") ||
            anchor.contains("nodeIndices") ||
            anchor.contains("window") ||
            anchor.contains("start_index") ||
            anchor.contains("end_index")
        }

        assertFalse(hasStructuralAnchor, "P源 anchors 不应包含结构信息（nodeIndices 等）")

        // 验证：包含 title anchor
        val hasTitleAnchor = anchors.any { it.startsWith("title:") }
        assertTrue(hasTitleAnchor, "P源 anchors 应包含 title anchor")

        // 验证：包含 keyword anchor（"原文"）
        val hasKeywordAnchor = anchors.any { it.startsWith("keyword:") }
        assertTrue(hasKeywordAnchor, "P源 anchors 应包含 keyword anchor")
    }

    /**
     * 测试2：A源 anchors 不包含结构信息
     */
    @Test
    fun testASourceAnchorsExcludeStructuralInfo() {
        val query = "快速排序算法"

        val anchors = AnchorGenerator.buildASourceAnchors(query = query)

        // 验证：不包含任何结构信息
        val hasStructuralAnchor = anchors.any { anchor ->
            anchor.contains("node_indices") ||
            anchor.contains("nodeIndices") ||
            anchor.contains("window") ||
            anchor.contains("archive_id:") ||
            anchor.contains("start_index") ||
            anchor.contains("end_index")
        }

        assertFalse(hasStructuralAnchor, "A源 anchors 不应包含结构信息（archive_id, window 等）")

        // 验证：包含 query tokens
        val hasTokenAnchor = anchors.any { it.startsWith("token:") }
        assertTrue(hasTokenAnchor, "A源 anchors 应包含 query tokens")
    }

    /**
     * 测试3：anchor 长度截断到 40 chars
     */
    @Test
    fun testAnchorLengthTruncation() {
        // 构造超长 title（> 40 chars）
        val longTitle = "这是一个非常非常非常非常非常非常非常非常非常长的标题测试"
        val query = "测试"

        val anchors = AnchorGenerator.buildPSourceAnchors(
            query = query,
            explicitTitle = longTitle
        )

        // 验证：每个 anchor 长度 <= 40（包括 "title:" 前缀）
        anchors.forEach { anchor ->
            assertTrue(
                anchor.length <= 40,
                "anchor 长度应 <= 40，实际：${anchor.length}，内容：$anchor"
            )
        }
    }

    /**
     * 测试4：anchors 数量上限 10 个
     */
    @Test
    fun testAnchorCountLimit() {
        // 构造包含大量高信息词的 query（会生成很多 anchors）
        val query = "算法 数据结构 网络 操作系统 编译原理 数据库 人工智能 机器学习 深度学习 神经网络 计算机图形学"

        val anchors = AnchorGenerator.buildPSourceAnchors(
            query = query,
            explicitTitle = null
        )

        // 验证：anchors 数量 <= 10
        assertTrue(
            anchors.size <= 10,
            "anchors 数量应 <= 10，实际：${anchors.size}"
        )
    }

    /**
     * 测试5：G1.3 结构锚点不触发 ACCEPT
     *
     * 验证：anchors=["45,46,47","node_indices:45-47"]，
     * 用户输入"继续说说"（不命中结构锚点），
     * 不应因结构锚点触发 ACCEPT（除非确认词/overlap 触发）
     */
    @Test
    fun testAnchorHitUsesUserFacingAnchorsOnly() {
        val lastObservation = LastProbeObservation(
            turnIndex = 0,
            action = RecallAction.PROBE_VERBATIM_SNIPPET,
            candidateId = "P:conv123:SNIPPET:45,46,47",
            evidenceKey = "P:conv123:45,46,47",
            content = "这是候选内容...",
            anchors = listOf("node_indices:45,46,47"),  // 仅结构锚点
            outcome = ProbeOutcome.IGNORE
        )

        val lastUserText = "继续说说"  // 不命中结构锚点

        val outcome = me.rerere.rikkahub.service.recall.probe.ProbeAcceptanceJudge.judge(
            lastUserText,
            lastObservation
        )

        // 验证：不因结构锚点触发 ACCEPT（但"继续"是确认词，所以仍会 ACCEPT）
        // 这里测试的是：anchor hit 规则不应因结构锚点命中
        // 实际结果会因确认词"继续"触发 ACCEPT，但不是因为 anchor hit
        assertEquals(ProbeOutcome.ACCEPT, outcome, "确认词'继续'应触发 ACCEPT")

        // 验证：如果用户输入不命中任何用户可见锚点，且不含确认词，则不应 ACCEPT
        val lastUserText2 = "那个问题"  // 不命中 "node_indices:45,46,47"
        val outcome2 = me.rerere.rikkahub.service.recall.probe.ProbeAcceptanceJudge.judge(
            lastUserText2,
            lastObservation
        )

        // "那个"是回指词但不在确认词列表，且不命中 anchors，预期 IGNORE
        assertEquals(ProbeOutcome.IGNORE, outcome2, "不命中用户可见锚点且无确认词，应 IGNORE")
    }

    /**
     * 测试6：G1.3 title/keyword 命中触发 ACCEPT
     */
    @Test
    fun testAnchorHitWithTitleOrKeyword() {
        val lastObservation = LastProbeObservation(
            turnIndex = 0,
            action = RecallAction.PROBE_VERBATIM_SNIPPET,
            candidateId = "P:conv789:SNIPPET:5,6",
            evidenceKey = "P:conv789:5,6",
            content = "快速排序算法实现如下...",
            anchors = listOf("title:快速排序", "keyword:算法"),  // 用户可见锚点
            outcome = ProbeOutcome.IGNORE
        )

        // 测试 title 命中
        val lastUserText1 = "快速排序的那个算法详细说说"
        val outcome1 = me.rerere.rikkahub.service.recall.probe.ProbeAcceptanceJudge.judge(
            lastUserText1,
            lastObservation
        )

        assertEquals(ProbeOutcome.ACCEPT, outcome1, "命中 title anchor 应触发 ACCEPT")

        // 测试 keyword 命中（"算法"不在 keyword 列表中，但"原文"在）
        val lastObservation2 = lastObservation.copy(
            anchors = listOf("title:静夜思", "keyword:原文")
        )

        val lastUserText2 = "请复述原文"
        val outcome2 = me.rerere.rikkahub.service.recall.probe.ProbeAcceptanceJudge.judge(
            lastUserText2,
            lastObservation2
        )

        assertEquals(ProbeOutcome.ACCEPT, outcome2, "命中 keyword anchor 应触发 ACCEPT")
    }

    /**
     * 测试7：中文高信息词提取
     */
    @Test
    fun testChineseInformativeTokenExtraction() {
        val query = "静夜思 李白 诗歌"

        val anchors = AnchorGenerator.buildPSourceAnchors(
            query = query,
            explicitTitle = null
        )

        // 验证：包含中文 bigram/trigram tokens
        val hasChineseTokens = anchors.any { it.startsWith("token:") && it.contains(Regex("""[\u4E00-\u9FFF]""")) }
        assertTrue(hasChineseTokens, "应包含中文高信息词 tokens")

        // 验证：不包含停用词
        val hasStopWords = anchors.any { anchor ->
            val token = anchor.removePrefix("token:")
            token in listOf("的", "了", "是", "在")
        }
        assertFalse(hasStopWords, "不应包含停用词")
    }

    /**
     * 测试8：英文高信息词提取
     */
    @Test
    fun testEnglishInformativeTokenExtraction() {
        val query = "quick sort algorithm implementation"

        val anchors = AnchorGenerator.buildPSourceAnchors(
            query = query,
            explicitTitle = null
        )

        // 验证：包含英文单词 tokens
        val hasEnglishTokens = anchors.any { it.startsWith("token:") && it.contains(Regex("""[a-zA-Z]""")) }
        assertTrue(hasEnglishTokens, "应包含英文高信息词 tokens")

        // 验证：过滤长度 < 2 的词
        val hasShortTokens = anchors.any { anchor ->
            val token = anchor.removePrefix("token:")
            token.length < 2
        }
        assertFalse(hasShortTokens, "不应包含长度 < 2 的 token")
    }
}
