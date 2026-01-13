package me.rerere.rikkahub

import me.rerere.rikkahub.util.normalizeForSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单元测试：文本归一化功能
 *
 * 测试 normalizeForSearch 函数的正确性
 * 验证 CJK、字母数字、空格、特殊字符的处理
 */
class TextNormalizationTest {

    @Test
    fun testNormalizeChineseCharacters() {
        val input = "你好世界"
        val expected = "你 好 世 界"
        assertEquals(expected, normalizeForSearch(input))
    }

    @Test
    fun testNormalizeMixedCJKAndLatin() {
        val input = "Hello世界"
        val expected = "hello 你 好 世 界"
        // CJK 后应添加空格，拉丁字母小写
        val result = normalizeForSearch(input)
        assertEquals(expected, result)
        assertTrue("Should contain CJK character", result.contains("你"))
        assertTrue("Should contain lowercase latin", result.contains("hello"))
    }

    @Test
    fun testNormalizeAlphanumeric() {
        val input = "TestABC123"
        val expected = "testabc123"
        assertEquals(expected, normalizeForSearch(input))
    }

    @Test
    fun testNormalizeWhitespace() {
        val input = "Hello   \t\n  World"
        val expected = "hello world"
        assertEquals(expected, normalizeForSearch(input))
    }

    @Test
    fun testNormalizeSpecialCharacters() {
        val input = "Hello@World#Test"
        val expected = "hello world test"
        assertEquals(expected, normalizeForSearch(input))
    }

    @Test
    fun testNormalizeMixedContent() {
        val input = "测试123Test文本！@#"
        val result = normalizeForSearch(input)
        // CJK 字符后应该有空格
        assertTrue(result.contains("测 "))
        assertTrue(result.contains("试 "))
        // 数字和字母应该小写
        assertTrue(result.contains("123"))
        assertTrue(result.contains("test"))
        // 特殊字符应该变成空格
        assertFalse(result.contains("@"))
        assertFalse(result.contains("#"))
    }

    @Test
    fun testNormalizeEmptyString() {
        val input = ""
        val expected = ""
        assertEquals(expected, normalizeForSearch(input))
    }

    @Test
    fun testNormalizeOnlySpecialCharacters() {
        val input = "@#$%^&*()"
        val expected = ""
        assertEquals(expected, normalizeForSearch(input))
    }

    @Test
    fun testNormalizeMultipleSpaces() {
        val input = "Hello     World"
        val expected = "hello world"
        assertEquals(expected, normalizeForSearch(input))
    }

    @Test
    fun testNormalizeCJKRange() {
        // 测试 CJK 范围 (0x4E00-0x9FFF)
        val input = "汉test字"
        val result = normalizeForSearch(input)
        // 每个 CJK 字符后应该有空格
        assertTrue(result.contains("汉 "))
        assertTrue(result.contains("字"))
        assertTrue(result.contains("test"))
    }
}
