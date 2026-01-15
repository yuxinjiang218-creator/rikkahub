package me.rerere.rikkahub.service.knowledge

/**
 * 流式文本分块器
 * 用于处理大文档，边读取边分块，避免一次性加载全部文本导致 OOM
 */
class StreamingTextChunker {

    companion object {
        private const val CHUNK_MAX_CHARS = 1200  // 最大块大小
        private const val CHUNK_OVERLAP_CHARS = 200  // 块间重叠
    }

    private val buffer = StringBuilder()
    private var chunkIndex = 0

    /**
     * 添加新文本片段
     * @return 已完成的分块列表
     */
    fun addText(text: String): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        buffer.append(text)

        // 当 buffer 达到阈值时，提取 chunk
        while (buffer.length >= CHUNK_MAX_CHARS) {
            // 提取一个 chunk
            val chunkText = buffer.substring(0, CHUNK_MAX_CHARS).trim()
            if (chunkText.isNotEmpty()) {
                chunks.add(Chunk(chunkIndex++, chunkText, chunkText.length))
            }

            // 保留重叠部分到 buffer
            val overlapStart = (CHUNK_MAX_CHARS - CHUNK_OVERLAP_CHARS).coerceAtLeast(0)
            val remaining = buffer.substring(overlapStart)
            buffer.clear()
            buffer.append(remaining)
        }

        return chunks
    }

    /**
     * 完成处理，返回剩余的 chunk
     */
    fun finish(): List<Chunk> {
        return if (buffer.isNotEmpty()) {
            val text = buffer.toString().trim()
            if (text.isNotEmpty()) {
                listOf(Chunk(chunkIndex, text, text.length))
            } else emptyList()
        } else emptyList()
    }

    /**
     * 重置分块器状态
     */
    fun reset() {
        buffer.clear()
        chunkIndex = 0
    }

    /**
     * 分块结果
     */
    data class Chunk(
        val index: Int,
        val text: String,
        val charCount: Int
    )
}
