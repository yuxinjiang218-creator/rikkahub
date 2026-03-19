package me.rerere.rikkahub.data.knowledgebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.data.model.KnowledgeBaseResultQuality

class KnowledgeBaseSearchTest {

    @Test
    fun `rankKnowledgeBaseChunks prefers best lexical and vector match`() {
        val ranked = rankKnowledgeBaseChunks(
            query = "docker deployment manual",
            chunks = listOf(
                KnowledgeBaseCandidateChunk(
                    content = "Docker deployment manual with compose examples and rollout notes",
                    embedding = listOf(1.0f, 0.0f)
                ),
                KnowledgeBaseCandidateChunk(
                    content = "Vacation expense spreadsheet and travel reminders",
                    embedding = listOf(0.0f, 1.0f)
                ),
                KnowledgeBaseCandidateChunk(
                    content = "Cluster deployment checklist with manual rollback steps",
                    embedding = listOf(0.8f, 0.2f)
                ),
            ),
            queryEmbedding = listOf(0.95f, 0.05f),
            bm25TopK = 10,
            vectorTopK = 10,
        )

        assertTrue(ranked.isNotEmpty())
        assertEquals(0, ranked.first().index)
        assertTrue(ranked.first().finalScore >= ranked.last().finalScore)
    }

    @Test
    fun `rankKnowledgeBaseChunks returns empty when query has no useful terms`() {
        val ranked = rankKnowledgeBaseChunks(
            query = " a ",
            chunks = listOf(
                KnowledgeBaseCandidateChunk(
                    content = "Sample content",
                    embedding = listOf(1.0f, 0.0f)
                )
            ),
            queryEmbedding = listOf(1.0f, 0.0f),
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `filterRankedKnowledgeBaseChunks removes weak tail results`() {
        val filtered = filterRankedKnowledgeBaseChunks(
            ranked = listOf(
                RankedKnowledgeBaseChunk(index = 0, bm25Score = 1.0, vectorScore = 1.0, finalScore = 0.62),
                RankedKnowledgeBaseChunk(index = 1, bm25Score = 0.8, vectorScore = 0.7, finalScore = 0.41),
                RankedKnowledgeBaseChunk(index = 2, bm25Score = 0.5, vectorScore = 0.4, finalScore = 0.18),
            )
        )

        assertEquals(KnowledgeBaseResultQuality.GOOD, filtered.quality)
        assertEquals(listOf(0, 1), filtered.chunks.map { it.index })
    }

    @Test
    fun `filterRankedKnowledgeBaseChunks marks low confidence results as weak`() {
        val filtered = filterRankedKnowledgeBaseChunks(
            ranked = listOf(
                RankedKnowledgeBaseChunk(index = 0, bm25Score = 0.4, vectorScore = 0.5, finalScore = 0.19),
                RankedKnowledgeBaseChunk(index = 1, bm25Score = 0.3, vectorScore = 0.3, finalScore = 0.16),
                RankedKnowledgeBaseChunk(index = 2, bm25Score = 0.2, vectorScore = 0.2, finalScore = 0.13),
            )
        )

        assertEquals(KnowledgeBaseResultQuality.WEAK, filtered.quality)
        assertEquals(3, filtered.chunks.size)
        assertEquals(listOf(0, 1, 2), filtered.chunks.map { it.index })
    }
}
