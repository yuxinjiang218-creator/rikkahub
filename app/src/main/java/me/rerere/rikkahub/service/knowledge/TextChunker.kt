package me.rerere.rikkahub.service.knowledge

import kotlin.math.ceil

/**
 * 文本分块器
 * 将长文本切分成固定大小的块，用于 embedding 检索
 */
object TextChunker {

    /**
     * 分块参数（写死）
     */
    private const val CHUNK_MAX_CHARS = 1200  // 最大块大小
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
     * 1. 按 CHUNK_MAX_CHARS 步长切分，保留 CHUNK_OVERLAP_CHARS 重叠
     * 2. 如果最后一块太小（< MIN_CHUNK_CHARS），合并到前一块
     * 3. 每块去除首尾空白
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

        // 预估chunk数量，避免ArrayList扩容
        val estimatedChunks = estimateChunkCount(text.length)
        val chunks = ArrayList<Chunk>(estimatedChunks)
        val length = text.length

        var startIndex = 0
        var chunkIndex = 0

        // 优化：避免在循环中创建过多临时字符串
        while (startIndex < length) {
            val endIndex = (startIndex + CHUNK_MAX_CHARS).coerceAtMost(length)

            // 找到实际的trim边界（不创建新字符串）
            var actualStart = startIndex
            var actualEnd = endIndex

            // 跳过前导空白
            while (actualStart < actualEnd && text[actualStart].isWhitespace()) {
                actualStart++
            }

            // 跳过尾随空白
            while (actualEnd > actualStart && text[actualEnd - 1].isWhitespace()) {
                actualEnd--
            }

            // 只有非空chunk才添加
            if (actualStart < actualEnd) {
                val chunkText = text.substring(actualStart, actualEnd)
                chunks.add(
                    Chunk(
                        index = chunkIndex,
                        text = chunkText,
                        charCount = actualEnd - actualStart
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
