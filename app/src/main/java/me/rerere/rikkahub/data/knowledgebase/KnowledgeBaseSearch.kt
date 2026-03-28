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
    exactTopK: Int = 24,
    minFinalScore: Double = 0.08,
): List<RankedKnowledgeBaseChunk> {
    val queryTerms = tokenizeForKnowledgeBase(query)
    val queryIdentifiers = extractExactIdentifiers(query)
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
    val exactRanked = chunks.mapIndexedNotNull { index, chunk ->
        val exactScore = exactIdentifierScore(queryIdentifiers, chunk.content)
        if (exactScore <= 0.0) null else index to exactScore
    }.sortedByDescending { it.second }
        .take(exactTopK)

    val bm25Scores = bm25Ranked.toMap()
    val vectorScores = vectorRanked.toMap()
    val exactScores = exactRanked.toMap()
    val maxBm25 = bm25Ranked.maxOfOrNull { it.second } ?: 1.0
    val maxVector = vectorRanked.maxOfOrNull { it.second } ?: 1.0
    val maxExact = exactRanked.maxOfOrNull { it.second } ?: 1.0
    val candidateIndexes = (bm25Scores.keys + vectorScores.keys + exactScores.keys).toSet()

    return candidateIndexes.mapNotNull { index ->
        val bm25 = normalizeScore(bm25Scores[index], maxBm25)
        val vector = normalizeCosine(vectorScores[index] ?: 0.0, maxVector)
        val exact = normalizeScore(exactScores[index], maxExact)
        val finalScore = (bm25 * 0.32) + (vector * 0.52) + (exact * 0.16)
        if (finalScore < minFinalScore) return@mapNotNull null
        RankedKnowledgeBaseChunk(
            index = index,
            bm25Score = bm25,
            vectorScore = maxOf(vector, exact),
            finalScore = finalScore,
        )
    }.sortedByDescending { it.finalScore }
}

fun rankKnowledgeBaseChunksByVectorScores(
    query: String,
    chunkContents: List<String>,
    vectorScoresByIndex: Map<Int, Double>,
    bm25TopK: Int = 40,
    vectorTopK: Int = 24,
    exactTopK: Int = 24,
    minFinalScore: Double = 0.08,
): List<RankedKnowledgeBaseChunk> {
    val queryTerms = tokenizeForKnowledgeBase(query)
    val queryIdentifiers = extractExactIdentifiers(query)
    if (queryTerms.isEmpty() || chunkContents.isEmpty()) return emptyList()

    val docTerms = chunkContents.map(::tokenizeForKnowledgeBase)
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

    val vectorRanked = vectorScoresByIndex.entries
        .map { it.key to it.value }
        .sortedByDescending { it.second }
        .take(vectorTopK)
    val exactRanked = chunkContents.mapIndexedNotNull { index, content ->
        val exactScore = exactIdentifierScore(queryIdentifiers, content)
        if (exactScore <= 0.0) null else index to exactScore
    }.sortedByDescending { it.second }
        .take(exactTopK)

    val bm25Scores = bm25Ranked.toMap()
    val vectorScores = vectorRanked.toMap()
    val exactScores = exactRanked.toMap()
    val maxBm25 = bm25Ranked.maxOfOrNull { it.second } ?: 1.0
    val maxVector = vectorRanked.maxOfOrNull { it.second } ?: 1.0
    val maxExact = exactRanked.maxOfOrNull { it.second } ?: 1.0
    val candidateIndexes = (bm25Scores.keys + vectorScores.keys + exactScores.keys).toSet()

    return candidateIndexes.mapNotNull { index ->
        val bm25 = normalizeScore(bm25Scores[index], maxBm25)
        val vector = normalizeScore(vectorScores[index], maxVector)
        val exact = normalizeScore(exactScores[index], maxExact)
        val finalScore = (bm25 * 0.32) + (vector * 0.52) + (exact * 0.16)
        if (finalScore < minFinalScore) return@mapNotNull null
        RankedKnowledgeBaseChunk(
            index = index,
            bm25Score = bm25,
            vectorScore = maxOf(vector, exact),
            finalScore = finalScore,
        )
    }.sortedByDescending { it.finalScore }
}

fun filterRankedKnowledgeBaseChunks(
    ranked: List<RankedKnowledgeBaseChunk>,
    absoluteThreshold: Double = 0.08,
    relativeThresholdRatio: Double = 0.40,
    weakThreshold: Double = 0.20,
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
    val normalized = text.lowercase()
    val tokens = linkedSetOf<String>()

    Regex("[\\p{L}\\p{N}_./:-]+").findAll(normalized).forEach { match ->
        val raw = match.value.trim('.', '/', '_', '-', ':')
        if (raw.length >= 2) {
            tokens += raw
        }
        raw.split(Regex("[/_:.-]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .forEach(tokens::add)
    }

    Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]+").findAll(normalized).forEach { match ->
        val segment = match.value
        when {
            segment.length == 1 -> tokens += segment
            else -> {
                tokens += segment
                for (i in 0 until segment.length - 1) {
                    tokens += segment.substring(i, i + 2)
                }
                if (segment.length <= 12) {
                    for (i in 0 until segment.length - 2) {
                        tokens += segment.substring(i, i + 3)
                    }
                    for (i in 0 until segment.length - 3) {
                        tokens += segment.substring(i, i + 4)
                    }
                }
            }
        }
    }

    return tokens.toList()
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

private fun normalizeCosine(score: Double, maxScore: Double): Double {
    if (maxScore <= 0.0) return 0.0
    return (score / maxScore).coerceIn(0.0, 1.0)
}

private fun exactIdentifierScore(
    identifiers: Set<String>,
    content: String,
): Double {
    if (identifiers.isEmpty()) return 0.0
    val haystack = content.lowercase()
    return identifiers.count { haystack.contains(it) }.toDouble()
}

private fun extractExactIdentifiers(query: String): Set<String> {
    return Regex("[A-Za-z0-9_./:-]{2,}")
        .findAll(query.lowercase())
        .map { it.value.trim('.', '/', '_', '-', ':') }
        .filter { it.length >= 2 }
        .toSet()
}
