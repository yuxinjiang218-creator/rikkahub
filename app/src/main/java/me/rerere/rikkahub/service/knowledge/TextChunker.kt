package me.rerere.rikkahub.service.knowledge

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 文本分块器
 * 将长文本切分成固定大小的块，用于 embedding 检索
 */
object TextChunker {

    /**
     * 分块参数（写死）
     */
    private const val CHUNK_MAX_CHARS = 900  // 最大块大小
    private const val CHUNK_OVERLAP_CHARS = 200  // 块间重叠
    private const val MIN_CHUNK_CHARS = 200  // 最小块大小

    /**
     * 内存保护限制
     * 1MB文本约等于200-500页PDF（取决于内容密度）
     */
    private const val MAX_TEXT_LENGTH = 1_000_000  // 最大文本长度（约1MB，约200-500页文档）

    /**
     * 分块结果
     */
    data class Chunk(
        val index: Int,
        val text: String,
        val charCount: Int
    )

    /**
     * 将文本切分成块
     *
     * 算法：
     * 1. 清理文本（统一换行符，压缩多余空行）
     * 2. 按 CHUNK_MAX_CHARS 步长切分，保留 CHUNK_OVERLAP_CHARS 重叠
     * 3. 智能边界检测：在 200 字符窗口内查找句子/段落结尾
     * 4. 如果最后一块太小（< MIN_CHUNK_CHARS），合并到前一块
     *
     * @param text 输入文本
     * @return 文本块列表
     */
    fun chunk(text: String): List<Chunk> {
        if (text.isBlank()) return emptyList()

        // 内存保护：拒绝处理过大的文本
        if (text.length > MAX_TEXT_LENGTH) {
            throw IllegalArgumentException(
                "Text too large to chunk: ${text.length} characters (max: $MAX_TEXT_LENGTH). " +
                "Please split the file into smaller parts or reduce the content."
            )
        }

        // 清理文本：统一换行符，压缩多余空行
        val cleanedText = preprocessText(text)

        // 预估chunk数量，避免ArrayList扩容
        val estimatedChunks = estimateChunkCount(cleanedText.length)
        val chunks = ArrayList<Chunk>(estimatedChunks)
        val length = cleanedText.length

        var startIndex = 0
        var chunkIndex = 0

        while (startIndex < length) {
            val rawEndIndex = (startIndex + CHUNK_MAX_CHARS).coerceAtMost(length)

            // 智能边界检测：在 200 字符窗口内查找句子结尾
            val endIndex = findSmartBoundary(cleanedText, startIndex, rawEndIndex)

            val chunkText = cleanedText.substring(startIndex, endIndex).trim()
            if (chunkText.isNotEmpty()) {
                chunks.add(
                    Chunk(
                        index = chunkIndex,
                        text = chunkText,
                        charCount = chunkText.length
                    )
                )
                chunkIndex++
            }

            // 移动到下一块（保留重叠）
            startIndex = endIndex - CHUNK_OVERLAP_CHARS
            if (startIndex < 0) startIndex = endIndex
        }

        // 处理最后一块太小的情况
        if (chunks.size > 1) {
            val lastChunk = chunks.last()
            if (lastChunk.charCount < MIN_CHUNK_CHARS) {
                // 合并到倒数第二块
                val secondLastChunk = chunks[chunks.size - 2]
                val mergedText = secondLastChunk.text + "\n\n" + lastChunk.text
                chunks[chunks.size - 2] = secondLastChunk.copy(
                    text = mergedText,
                    charCount = mergedText.length
                )
                chunks.removeLast()
            }
        }

        return chunks
    }

    /**
     * 预处理文本：统一换行符，压缩多余空行
     */
    private fun preprocessText(text: String): String {
        return text
            .replace("\r\n", "\n")  // 统一换行符
            .replace(Regex("\\n{3,}"), "\n\n")  // 压缩多余空行
            .trim()
    }

    /**
     * 智能边界检测：在 200 字符窗口内查找句子/段落结尾
     * @return 实际的结束位置
     */
    private fun findSmartBoundary(text: String, start: Int, rawEnd: Int): Int {
        // 如果已经是文本结尾，直接返回
        if (rawEnd >= text.length) return text.length

        // 在 rawEnd 前后 200 字符窗口内查找边界
        val windowStart = max(start, rawEnd - 200)
        val windowEnd = min(text.length, rawEnd + 200)

        // 优先从 rawEnd 向后查找（避免块太小）
        for (i in rawEnd until windowEnd) {
            if (isSentenceBoundary(text[i])) {
                return i + 1
            }
        }

        // 向前查找
        for (i in rawEnd downTo windowStart) {
            if (isSentenceBoundary(text[i])) {
                return i + 1
            }
        }

        // 没找到边界，使用原始位置
        return rawEnd
    }

    /**
     * 判断是否是句子/段落边界
     */
    private fun isSentenceBoundary(char: Char): Boolean {
        return char in listOf('\n', '。', '！', '？', '.', '!', '?', ';', '；')
    }

    /**
     * 估算分块数量
     */
    fun estimateChunkCount(textLength: Int): Int {
        if (textLength <= 0) return 0
        val effectiveChunkSize = CHUNK_MAX_CHARS - CHUNK_OVERLAP_CHARS
        return ceil(textLength.toDouble() / effectiveChunkSize).toInt()
    }

    /**
     * 获取分块参数（用于测试）
     */
    fun getParams(): ChunkParams = ChunkParams(
        maxChars = CHUNK_MAX_CHARS,
        overlapChars = CHUNK_OVERLAP_CHARS,
        minChars = MIN_CHUNK_CHARS
    )

    data class ChunkParams(
        val maxChars: Int,
        val overlapChars: Int,
        val minChars: Int
    )
}
