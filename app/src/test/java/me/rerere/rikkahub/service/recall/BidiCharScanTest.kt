package me.rerere.rikkahub.service.recall

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase L0: Unicode Bidi 控制字符扫描测试
 *
 * 检测 recall 相关源码中是否包含 Bidi 控制字符。
 * 这些字符会影响可读性，且有 "Trojan Source" 风险。
 *
 * 扫描的 Bidi 控制字符范围：
 * - U+202A..U+202E: LEFT-TO-RIGHT EMBEDDING .. RIGHT-TO-LEFT OVERRIDE
 * - U+2066..U+2069: LEFT-TO-RIGHT ISOLATE .. POP DIRECTIONAL ISOLATE
 * - U+200E..U+200F: LEFT-TO-RIGHT MARK .. RIGHT-TO-LEFT MARK
 */
class BidiCharScanTest {

    /**
     * Bidi 控制字符 Unicode 码点集合
     */
    private val bidiCodePoints = setOf(
        0x202A, 0x202B, 0x202C, 0x202D, 0x202E,  // LEFT-TO-RIGHT EMBEDDING .. RIGHT-TO-LEFT OVERRIDE
        0x2066, 0x2067, 0x2068, 0x2069,          // LEFT-TO-RIGHT ISOLATE .. POP DIRECTIONAL ISOLATE
        0x200E, 0x200F                           // LEFT-TO-RIGHT MARK .. RIGHT-TO-LEFT MARK
    )

    /**
     * 扫描 recall 目录下的所有 Kotlin 源码文件
     */
    @Test
    fun scanRecallSourceCode_NoBidiCharacters() {
        // 固定扫描路径：测试运行时工作目录是 app 模块，所以直接用 src/main/java
        val recallDir = File("src/main/java/me/rerere/rikkahub/service/recall")
        if (!recallDir.exists()) {
            fail("Recall directory not found: ${recallDir.absolutePath}")
        }
        scanDirectory(recallDir)
    }

    private fun scanDirectory(recallDir: File) {
        val violations = mutableListOf<String>()

        Files.walk(recallDir.toPath())
            .filter { it.toString().endsWith(".kt") }
            .forEach { path ->
                // 使用 UTF-8 显式读取，避免平台编码问题
                val content = path.toFile().readText(Charsets.UTF_8)
                val violationsInFile = scanForBidiCharacters(content, path.toString())
                violations.addAll(violationsInFile)
            }

        // 先打印违规项（用于调试）
        if (violations.isNotEmpty()) {
            println("=== 发现 Bidi 控制字符 ===")
            violations.forEach { println(it) }
            println("========================")
        }

        if (violations.isNotEmpty()) {
            fail(
                """
                |发现 ${violations.size} 处 Bidi 控制字符：
                |
                |${violations.joinToString("\n")}
                |
                |请清理这些字符后再提交。
                """.trimMargin()
            )
        }
    }

    /**
     * 扫描单个文件中的 Bidi 控制字符
     *
     * @param content 文件内容
     * @param filePath 文件路径
     * @return 违规列表（每个元素包含文件路径和行号）
     */
    private fun scanForBidiCharacters(content: String, filePath: String): List<String> {
        val violations = mutableListOf<String>()
        val lines = content.lines()

        lines.forEachIndexed { lineIndex, line ->
            val foundBidiChars = mutableListOf<Int>()
            var i = 0
            while (i < line.length) {
                val cp = line.codePointAt(i)
                if (cp in bidiCodePoints) {
                    foundBidiChars.add(cp)
                }
                i += Character.charCount(cp)
            }

            if (foundBidiChars.isNotEmpty()) {
                val hexValues = foundBidiChars.joinToString(", ") { "U+${it.toString(16).uppercase()}" }
                violations.add("  $filePath:${lineIndex + 1}: 发现 Bidi 字符 [$hexValues]")
            }
        }

        return violations
    }

    /**
     * 验证扫描器能检测到 Bidi 字符（控制测试）
     */
    @Test
    fun scanner_CanDetectBidiCharacters() {
        // 构造包含 Bidi 字符的测试文本
        val testText = "正常文本\u202B隐藏的Bidi文本\u202C继续文本"

        // 手动检查是否包含 Bidi 字符
        var hasBidi = false
        var i = 0
        while (i < testText.length) {
            val cp = testText.codePointAt(i)
            if (cp in bidiCodePoints) {
                hasBidi = true
                break
            }
            i += Character.charCount(cp)
        }

        assertTrue(hasBidi, "扫描器应该能检测到 Bidi 字符")
    }
}
