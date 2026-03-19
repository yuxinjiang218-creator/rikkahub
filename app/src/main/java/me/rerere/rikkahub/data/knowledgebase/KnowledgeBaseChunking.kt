package me.rerere.rikkahub.data.knowledgebase

import kotlin.math.ceil
import kotlin.math.max

data class KnowledgeBaseChunkDraft(
    val chunkOrder: Int,
    val content: String,
    val tokenEstimate: Int,
)

class IncrementalKnowledgeBaseChunker(
    charsPerToken: Float,
    targetTokens: Int = 600,
    overlapTokens: Int = 80,
) {
    private val safeCharsPerToken = charsPerToken.coerceAtLeast(1f)
    private val targetChars = max((targetTokens * safeCharsPerToken).toInt(), 1_200)
    private val overlapChars = max((overlapTokens * safeCharsPerToken).toInt(), 160)
    private val boundarySlack = max(targetChars / 8, 120)
    private val buffer = StringBuilder()
    private var nextOrder = 0

    fun appendText(text: String): List<KnowledgeBaseChunkDraft> {
        val normalized = text.trim()
        if (normalized.isBlank()) return emptyList()

        if (buffer.isNotEmpty() && !buffer.last().isWhitespace()) {
            buffer.append('\n')
        }
        buffer.append(normalized)

        return drain(fullOnly = true)
    }

    fun finish(): List<KnowledgeBaseChunkDraft> {
        return drain(fullOnly = false)
    }

    private fun drain(fullOnly: Boolean): List<KnowledgeBaseChunkDraft> {
        val drafts = mutableListOf<KnowledgeBaseChunkDraft>()
        while (buffer.isNotEmpty()) {
            if (fullOnly && buffer.length < targetChars) break

            val bufferText = buffer.toString()
            val preferredEnd = targetChars.coerceAtMost(bufferText.length)
            val end = when {
                bufferText.length <= targetChars && !fullOnly -> bufferText.length
                preferredEnd >= bufferText.length -> bufferText.length
                else -> findChunkBoundary(bufferText, preferredEnd, 0, boundarySlack)
            }.coerceAtLeast(1)

            val chunkText = bufferText.substring(0, end).trim()
            if (chunkText.isNotBlank()) {
                drafts += KnowledgeBaseChunkDraft(
                    chunkOrder = nextOrder++,
                    content = chunkText,
                    tokenEstimate = ceil(chunkText.length / safeCharsPerToken).toInt().coerceAtLeast(1)
                )
            }

            if (end >= bufferText.length) {
                buffer.clear()
                break
            }

            val nextStartBase = (end - overlapChars).coerceAtLeast(0)
            val nextStart = skipBoundaryChars(bufferText, nextStartBase)
            val remainder = bufferText.substring(nextStart).trimStart()
            buffer.clear()
            buffer.append(remainder)
        }
        return drafts
    }
}

fun buildKnowledgeBaseChunks(
    text: String,
    charsPerToken: Float,
    targetTokens: Int = 600,
    overlapTokens: Int = 80,
): List<KnowledgeBaseChunkDraft> {
    val normalized = text.trim()
    if (normalized.isBlank()) return emptyList()

    val chunker = IncrementalKnowledgeBaseChunker(
        charsPerToken = charsPerToken,
        targetTokens = targetTokens,
        overlapTokens = overlapTokens,
    )
    return chunker.appendText(normalized) + chunker.finish()
}

private fun findChunkBoundary(
    text: String,
    preferredEnd: Int,
    chunkStart: Int,
    slack: Int,
): Int {
    val minEnd = (preferredEnd - slack).coerceAtLeast(chunkStart + 1)
    for (index in preferredEnd downTo minEnd) {
        if (index <= 0 || index > text.length) continue
        val previous = text[index - 1]
        if (
            previous.isWhitespace() ||
            previous in setOf('.', '!', '?', '。', '！', '？', '\n')
        ) {
            return index
        }
    }
    return preferredEnd
}

private fun skipBoundaryChars(text: String, start: Int): Int {
    var cursor = start.coerceIn(0, text.length)

    while (
        cursor > 0 &&
        cursor < text.length &&
        !isChunkBoundary(text[cursor - 1]) &&
        !isChunkBoundary(text[cursor])
    ) {
        cursor--
    }

    while (cursor < text.length && isChunkBoundary(text[cursor])) {
        cursor++
    }
    return cursor
}

private fun isChunkBoundary(char: Char): Boolean {
    return char.isWhitespace() || char in setOf('.', '!', '?', '。', '！', '？')
}
