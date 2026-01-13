package me.rerere.rikkahub

import me.rerere.rikkahub.service.IntentRouter
import me.rerere.rikkahub.service.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单元测试：Intent Router 路由判定
 *
 * 测试 Intent Router 的路由判定逻辑
 * 验证 VERBATIM 和 SEMANTIC 路径的划分
 */
class IntentRouterTest {

    @Test
    fun testRouteVerbatimKeyword复述() {
        val input = "请复述一下刚才的内容"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.VERBATIM, route)
    }

    @Test
    fun testRouteVerbatimKeyword原文() {
        val input = "给我原文"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.VERBATIM, route)
    }

    @Test
    fun testRouteVerbatimKeyword全文() {
        val input = "我需要全文内容"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.VERBATIM, route)
    }

    @Test
    fun testRouteVerbatimKeyword逐字() {
        val input = "请逐字背诵"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.VERBATIM, route)
    }

    @Test
    fun testRouteVerbatimKeyword一字不差() {
        val input = "一字不差地重复"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.VERBATIM, route)
    }

    @Test
    fun testRouteVerbatimKeyword把() {
        val input = "把那首诗给我"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.VERBATIM, route)
    }

    @Test
    fun testRouteVerbatimKeyword贴出来() {
        val input = "把代码贴出来"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.VERBATIM, route)
    }

    @Test
    fun testRouteVerbatimKeyword引用() {
        val input = "请引用原文"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.VERBATIM, route)
    }

    @Test
    fun testRouteVerbatimKeyword原诗() {
        val input = "背诵原诗"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.VERBATIM, route)
    }

    @Test
    fun testRouteVerbatimKeyword原代码() {
        val input = "给我原代码"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.VERBATIM, route)
    }

    @Test
    fun testRouteVerbatimKeyword那段() {
        val input = "那段话是什么"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.VERBATIM, route)
    }

    @Test
    fun testRouteVerbatimTitlePattern() {
        val input = "把《静夜思》的内容给我"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.VERBATIM, route)
    }

    @Test
    fun testRouteVerbatimTitlePatternMultiple() {
        val input = "对比《静夜思》和《春晓》"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.VERBATIM, route)
    }

    @Test
    fun testRouteVerbatimTitlePatternLongTitle() {
        // 测试书名号内容长度在 1-40 之间
        val input = "请背诵《这是一首非常非常非常非常非常非常长的诗歌标题》"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.VERBATIM, route)
    }

    @Test
    fun testRouteSemanticNormalQuery() {
        val input = "今天天气怎么样"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.SEMANTIC, route)
    }

    @Test
    fun testRouteSemanticQuestion() {
        val input = "什么是机器学习"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.SEMANTIC, route)
    }

    @Test
    fun testRouteSemanticCodeHelp() {
        val input = "帮我写一个排序算法"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.SEMANTIC, route)
    }

    @Test
    fun testRouteSemanticConversation() {
        val input = "我们继续讨论刚才的话题"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.SEMANTIC, route)
    }

    @Test
    fun testExtractTitlesSingle() {
        val input = "请背诵《静夜思》"
        val titles = IntentRouter.extractTitles(input)
        assertEquals(1, titles.size)
        assertEquals("静夜思", titles[0])
    }

    @Test
    fun testExtractTitlesMultiple() {
        val input = "对比《静夜思》和《春晓》这两首诗"
        val titles = IntentRouter.extractTitles(input)
        assertEquals(2, titles.size)
        assertTrue(titles.contains("静夜思"))
        assertTrue(titles.contains("春晓"))
    }

    @Test
    fun testExtractTitlesEmpty() {
        val input = "今天天气怎么样"
        val titles = IntentRouter.extractTitles(input)
        assertEquals(0, titles.size)
    }

    @Test
    fun testExtractTitlesLimitToThree() {
        // IntentRouter.extractTitles 应该最多返回 3 个标题
        val input = "对比《A》《B》《C》《D》《E》"
        val titles = IntentRouter.extractTitles(input)
        assertEquals(3, titles.size)  // take(3) 限制
    }

    @Test
    fun testExtractTitlesBoundaryLength() {
        // 测试边界：1个字符
        val input1 = "《A》"
        val titles1 = IntentRouter.extractTitles(input1)
        assertEquals(1, titles1.size)
        assertEquals("A", titles1[0])

        // 测试边界：40个字符
        val longTitle = "A".repeat(40)
        val input2 = "《$longTitle》"
        val titles2 = IntentRouter.extractTitles(input2)
        assertEquals(1, titles2.size)
        assertEquals(longTitle, titles2[0])

        // 测试边界：41个字符（超过40，不应该匹配）
        val tooLongTitle = "A".repeat(41)
        val input3 = "《$tooLongTitle》"
        val titles3 = IntentRouter.extractTitles(input3)
        assertEquals(0, titles3.size)  // 超过40个字符不匹配
    }

    @Test
    fun testRouteVerbatimKeywordInSentence() {
        // 测试关键词在句子中间的情况
        val input = "你能不能把那段内容复述一遍"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.VERBATIM, route)
    }

    @Test
    fun testRouteSemanticWithPartialKeywordMatch() {
        // 测试部分匹配不应触发 VERBATIM
        val input = "这是一个原创的想法"
        val route = IntentRouter.routeIntent(input)
        assertEquals(Route.SEMANTIC, route)
    }
}
