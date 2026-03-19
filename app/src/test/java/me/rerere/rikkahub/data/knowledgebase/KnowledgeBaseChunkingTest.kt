package me.rerere.rikkahub.data.knowledgebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeBaseChunkingTest {

    @Test
    fun `buildKnowledgeBaseChunks returns empty list for blank text`() {
        assertTrue(buildKnowledgeBaseChunks("", charsPerToken = 4.0f).isEmpty())
        assertTrue(buildKnowledgeBaseChunks("   ", charsPerToken = 4.0f).isEmpty())
    }

    @Test
    fun `buildKnowledgeBaseChunks creates ordered overlapping chunks`() {
        val text = (1..260).joinToString(" ") { "token$it" }

        val chunks = buildKnowledgeBaseChunks(
            text = text,
            charsPerToken = 4.0f,
            targetTokens = 40,
            overlapTokens = 10,
        )

        assertTrue(chunks.size > 1)
        assertEquals(chunks.indices.toList(), chunks.map { it.chunkOrder })
        assertTrue(chunks.all { it.tokenEstimate > 0 && it.content.isNotBlank() })

        val firstTokens = chunks[0].content.split(' ').toSet()
        val secondTokens = chunks[1].content.split(' ').toSet()
        assertFalse(firstTokens.intersect(secondTokens).isEmpty())
    }

    @Test
    fun `incremental chunker produces same ordered chunks across block appends`() {
        val chunker = IncrementalKnowledgeBaseChunker(
            charsPerToken = 4.0f,
            targetTokens = 40,
            overlapTokens = 10,
        )
        val source = (1..260).joinToString(" ") { "token$it" }
        val expected = buildKnowledgeBaseChunks(
            text = source,
            charsPerToken = 4.0f,
            targetTokens = 40,
            overlapTokens = 10,
        )

        val streamed = buildList {
            source.split(' ')
                .chunked(18)
                .map { it.joinToString(" ") }
                .forEach { part ->
                    addAll(chunker.appendText(part))
                }
            addAll(chunker.finish())
        }

        assertEquals(expected.map { it.chunkOrder }, streamed.map { it.chunkOrder })
        assertEquals(
            expected.map { normalizeWhitespace(it.content) },
            streamed.map { normalizeWhitespace(it.content) }
        )
    }

    private fun normalizeWhitespace(text: String): String {
        return text.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }
}
