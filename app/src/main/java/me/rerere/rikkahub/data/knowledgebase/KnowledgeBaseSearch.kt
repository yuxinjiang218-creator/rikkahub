package me.rerere.rikkahub.data.knowledgebase

import kotlin.math.ln
import kotlin.math.sqrt

data class KnowledgeBaseCandidateChunk(
    val content: String,
    val embedding: List<Float>,
)

data class RankedKnowledgeBaseChunk(
    val index: Int,
    val bm25Score: Double,
    val vectorScore: Double,
    val finalScore: Double,
)

data class FilteredKnowledgeBaseRank(
    val quality: me.rerere.rikkahub.data.model.KnowledgeBaseResultQuality,
    val chunks: List<RankedKnowledgeBaseChunk>,
)

fun rankKnowledgeBaseChunks(
    query: String,
    chunks: List<KnowledgeBaseCandidateChunk>,
    queryEmbedding: List<Float>,
    bm25TopK: Int = 40,
    vectorTopK: Int = 24,
    minFinalScore: Double = 0.08,
): List<RankedKnowledgeBaseChunk> {
    val queryTerms = tokenizeForKnowledgeBase(query)
    if (queryTerms.isEmpty() || chunks.isEmpty() || queryEmbedding.isEmpty()) return emptyList()

    val docTerms = chunks.map { tokenizeForKnowledgeBase(it.content) }
    val avgDocLength = docTerms.map { it.size }.average().takeIf { it > 0 } ?: 1.0
    val totalDocs = docTerms.size.toDouble().coerceAtLeast(1.0)
    val docFrequency = mutableMapOf<String, Int>()
    docTerms.forEach { terms ->
        terms.toSet().forEach { term ->
            docFrequency[term] = (docFrequency[term] ?: 0) + 1
        }
    }

    fun bm25Score(terms: List<String>): Double {
        if (terms.isEmpty()) return 0.0
        val tf = terms.groupingBy { it }.eachCount()
        val docLength = terms.size.toDouble().coerceAtLeast(1.0)
        val k1 = 1.2
        val b = 0.75
        var score = 0.0
        queryTerms.forEach { term ->
            val freq = tf[term]?.toDouble() ?: return@forEach
            val df = docFrequency[term]?.toDouble() ?: 0.0
            val idf = ln((totalDocs - df + 0.5) / (df + 0.5) + 1.0)
            val numerator = freq * (k1 + 1.0)
            val denominator = freq + k1 * (1.0 - b + b * (docLength / avgDocLength))
            score += idf * (numerator / denominator)
        }
        return score
    }

    val bm25Ranked = docTerms.mapIndexed { index, terms ->
        index to bm25Score(terms)
    }.filter { it.second > 0.0 }
        .sortedByDescending { it.second }
        .take(bm25TopK)

    val vectorRanked = chunks.mapIndexedNotNull { index, chunk ->
        if (chunk.embedding.isEmpty()) return@mapIndexedNotNull null
        index to cosineSimilarity(queryEmbedding, chunk.embedding)
    }.sortedByDescending { it.second }
        .take(vectorTopK)

    val bm25Scores = bm25Ranked.toMap()
    val vectorScores = vectorRanked.toMap()
    val maxBm25 = bm25Ranked.maxOfOrNull { it.second } ?: 1.0
    val maxVector = vectorRanked.maxOfOrNull { it.second } ?: 1.0
    val candidateIndexes = (bm25Scores.keys + vectorScores.keys).toSet()

    return candidateIndexes.mapNotNull { index ->
        val bm25 = normalizeScore(bm25Scores[index], maxBm25)
        val vector = normalizeScore(vectorScores[index], maxVector)
        val finalScore = (bm25 * 0.45) + (vector * 0.55)
        if (finalScore < minFinalScore) return@mapNotNull null
        RankedKnowledgeBaseChunk(
            index = index,
            bm25Score = bm25,
            vectorScore = vector,
            finalScore = finalScore,
        )
    }.sortedByDescending { it.finalScore }
}

fun filterRankedKnowledgeBaseChunks(
    ranked: List<RankedKnowledgeBaseChunk>,
    absoluteThreshold: Double = 0.08,
    relativeThresholdRatio: Double = 0.40,
    weakThreshold: Double = 0.18,
    weakMaxResults: Int = 3,
): FilteredKnowledgeBaseRank {
    if (ranked.isEmpty()) {
        return FilteredKnowledgeBaseRank(
            quality = me.rerere.rikkahub.data.model.KnowledgeBaseResultQuality.EMPTY,
            chunks = emptyList(),
        )
    }

    val bestScore = ranked.first().finalScore
    if (bestScore < absoluteThreshold) {
        return FilteredKnowledgeBaseRank(
            quality = me.rerere.rikkahub.data.model.KnowledgeBaseResultQuality.EMPTY,
            chunks = emptyList(),
        )
    }

    val relativeThreshold = maxOf(absoluteThreshold, bestScore * relativeThresholdRatio)
    val filtered = ranked.filterIndexed { index, chunk ->
        if (index == 0) {
            chunk.finalScore >= absoluteThreshold
        } else {
            chunk.finalScore >= relativeThreshold
        }
    }

    val quality = if (bestScore < weakThreshold) {
        me.rerere.rikkahub.data.model.KnowledgeBaseResultQuality.WEAK
    } else {
        me.rerere.rikkahub.data.model.KnowledgeBaseResultQuality.GOOD
    }

    return FilteredKnowledgeBaseRank(
        quality = quality,
        chunks = if (quality == me.rerere.rikkahub.data.model.KnowledgeBaseResultQuality.WEAK) {
            filtered.take(weakMaxResults)
        } else {
            filtered
        }
    )
}

private fun tokenizeForKnowledgeBase(text: String): List<String> {
    return Regex("[\\p{L}\\p{N}_-]+")
        .findAll(text.lowercase())
        .map { it.value }
        .filter { it.length >= 2 }
        .toList()
}

private fun cosineSimilarity(left: List<Float>, right: List<Float>): Double {
    if (left.isEmpty() || right.isEmpty() || left.size != right.size) return 0.0
    var dot = 0.0
    var leftNorm = 0.0
    var rightNorm = 0.0
    left.indices.forEach { index ->
        val l = left[index].toDouble()
        val r = right[index].toDouble()
        dot += l * r
        leftNorm += l * l
        rightNorm += r * r
    }
    if (leftNorm <= 0.0 || rightNorm <= 0.0) return 0.0
    return dot / (sqrt(leftNorm) * sqrt(rightNorm))
}

private fun normalizeScore(score: Double?, maxScore: Double): Double {
    if (score == null || maxScore <= 0.0) return 0.0
    return (score / maxScore).coerceIn(0.0, 1.0)
}
