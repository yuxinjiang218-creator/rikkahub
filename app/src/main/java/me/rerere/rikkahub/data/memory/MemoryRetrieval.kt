package me.rerere.rikkahub.data.memory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

private const val DEFAULT_MIN_CHUNK_TOKENS = 300
private const val DEFAULT_MAX_CHUNK_TOKENS = 450
private const val DEFAULT_OVERLAP_RATIO = 0.1
private const val MIN_FINAL_RECALL_SCORE = 0.62

internal data class MemorySummaryChunk(
    val sectionKey: String,
    val chunkOrder: Int,
    val content: String,
    val tokenEstimate: Int,
)

internal data class RankedMemoryChunk(
    val docIndex: Int,
    val bm25Score: Double,
    val vectorScore: Double,
    val finalScore: Double,
)

internal fun buildMemoryIndexChunks(
    rollingSummaryJson: String,
    charsPerToken: Float,
    minChunkTokens: Int = DEFAULT_MIN_CHUNK_TOKENS,
    maxChunkTokens: Int = DEFAULT_MAX_CHUNK_TOKENS,
    overlapRatio: Double = DEFAULT_OVERLAP_RATIO,
): List<MemorySummaryChunk> {
    val json = runCatching {
        JsonInstant.parseToJsonElement(rollingSummaryJson).jsonObject
    }.getOrElse { JsonObject(emptyMap()) }

    val chunks = mutableListOf<MemorySummaryChunk>()
    json.forEach { (sectionKey, value) ->
        val rawLines = when (value) {
            is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            else -> emptyList()
        }.map { it.trim() }
            .filter { it.isNotBlank() }

        if (rawLines.isEmpty()) return@forEach

        val units = rawLines
            .flatMap { splitOversizedLine(it, charsPerToken, maxChunkTokens) }
            .map { text ->
                ChunkUnit(
                    text = text,
                    tokenEstimate = estimateSemanticTokens(text, charsPerToken)
                )
            }

        if (units.isEmpty()) return@forEach

        var start = 0
        var order = 0
        while (start < units.size) {
            var end = start
            var chunkTokens = 0

            while (end < units.size) {
                val nextTokens = units[end].tokenEstimate.coerceAtLeast(1)
                val shouldCloseChunk = chunkTokens >= minChunkTokens && chunkTokens + nextTokens > maxChunkTokens
                if (shouldCloseChunk) break
                chunkTokens += nextTokens
                end++
                if (chunkTokens >= maxChunkTokens) break
            }

            if (end == start) {
                chunkTokens = units[end].tokenEstimate.coerceAtLeast(1)
                end++
            }

            val chunkLines = units.subList(start, end).map { it.text }
            val content = buildChunkContent(sectionKey = sectionKey, lines = chunkLines)
            chunks += MemorySummaryChunk(
                sectionKey = sectionKey,
                chunkOrder = order++,
                content = content,
                tokenEstimate = estimateSemanticTokens(content, charsPerToken)
            )

            if (end >= units.size) break

            val overlapTarget = max(1, (chunkTokens * overlapRatio).toInt())
            var overlapTokens = 0
            var nextStart = end
            while (nextStart > start && overlapTokens < overlapTarget) {
                nextStart--
                overlapTokens += units[nextStart].tokenEstimate.coerceAtLeast(1)
            }
            start = if (nextStart <= start) end else nextStart
        }
    }

    return chunks
}

internal fun rankMemoryChunks(
    query: String,
    documents: List<String>,
    documentEmbeddings: List<List<Float>>,
    queryEmbedding: List<Float>,
    bm25TopK: Int,
    vectorRerankK: Int,
    minFinalScore: Double = MIN_FINAL_RECALL_SCORE,
): List<RankedMemoryChunk> {
    val queryTerms = tokenizeForRetrieval(query)
    if (queryTerms.isEmpty() || documents.isEmpty()) return emptyList()

    val docTerms = documents.map(::tokenizeForRetrieval)
    val avgDocLength = docTerms.map { it.size }.average().takeIf { it > 0 } ?: 1.0
    val docFrequency = mutableMapOf<String, Int>()
    docTerms.forEach { terms ->
        terms.toSet().forEach { term ->
            docFrequency[term] = (docFrequency[term] ?: 0) + 1
        }
    }
    val totalDocs = docTerms.size.toDouble()

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

    val bm25Ranked = documents.indices
        .map { index -> index to bm25Score(docTerms[index]) }
        .filter { it.second > 0.0 }
        .sortedByDescending { it.second }
        .take(bm25TopK)
    if (bm25Ranked.isEmpty()) return emptyList()

    val bm25Max = bm25Ranked.maxOfOrNull { it.second } ?: 1.0
    return bm25Ranked
        .take(vectorRerankK)
        .mapNotNull { (docIndex, bm25) ->
            val embedding = documentEmbeddings.getOrNull(docIndex) ?: return@mapNotNull null
            val vectorScore = cosineSimilarity(queryEmbedding, embedding)
            val bm25Norm = (bm25 / bm25Max).coerceIn(0.0, 1.0)
            val vectorNorm = ((vectorScore + 1.0) / 2.0).coerceIn(0.0, 1.0)
            val finalScore = 0.55 * bm25Norm + 0.45 * vectorNorm
            if (finalScore < minFinalScore) return@mapNotNull null
            RankedMemoryChunk(
                docIndex = docIndex,
                bm25Score = bm25Norm,
                vectorScore = vectorNorm,
                finalScore = finalScore,
            )
        }
        .sortedByDescending { it.finalScore }
}

internal fun tokenizeForRetrieval(text: String): List<String> {
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

internal fun estimateSemanticTokens(text: String, charsPerToken: Float): Int {
    val normalized = text.trim()
    if (normalized.isEmpty()) return 0

    val boundedCharsPerToken = charsPerToken.coerceIn(2.0f, 8.0f)
    val charEstimate = normalized.length / boundedCharsPerToken.toDouble()
    val cjkChars = normalized.count(Char::isCjk)
    val latinWords = Regex("[A-Za-z0-9_]+").findAll(normalized).count()
    val semanticEstimate = (cjkChars / 1.6) + (latinWords * 0.75)
    return max(1, ceil(max(charEstimate, semanticEstimate)).toInt())
}

internal fun cosineSimilarity(a: List<Float>, b: List<Float>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val size = minOf(a.size, b.size)
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in 0 until size) {
        val av = a[i].toDouble()
        val bv = b[i].toDouble()
        dot += av * bv
        normA += av * av
        normB += bv * bv
    }
    if (normA <= 0.0 || normB <= 0.0) return 0.0
    return dot / (sqrt(normA) * sqrt(normB))
}

private data class ChunkUnit(
    val text: String,
    val tokenEstimate: Int,
)

private fun buildChunkContent(sectionKey: String, lines: List<String>): String = buildString {
    append('[')
    append(sectionKey)
    appendLine(']')
    lines.forEach { line ->
        append("- ")
        appendLine(line)
    }
}.trim()

private fun splitOversizedLine(
    line: String,
    charsPerToken: Float,
    maxChunkTokens: Int,
): List<String> {
    val normalized = line.trim()
    if (estimateSemanticTokens(normalized, charsPerToken) <= maxChunkTokens) {
        return listOf(normalized)
    }

    val sentencePieces = normalized
        .split(Regex("(?<=[。！？；.!?;])\\s+|\\n+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }

    if (sentencePieces.size > 1) {
        val packed = mutableListOf<String>()
        val buffer = mutableListOf<String>()
        var bufferTokens = 0
        sentencePieces.forEach { sentence ->
            val sentenceTokens = estimateSemanticTokens(sentence, charsPerToken)
            if (sentenceTokens > maxChunkTokens) {
                if (buffer.isNotEmpty()) {
                    packed += buffer.joinToString(" ")
                    buffer.clear()
                    bufferTokens = 0
                }
                packed += splitByCharacterWindow(sentence, charsPerToken, maxChunkTokens)
                return@forEach
            }
            if (bufferTokens > 0 && bufferTokens + sentenceTokens > maxChunkTokens) {
                packed += buffer.joinToString(" ")
                buffer.clear()
                bufferTokens = 0
            }
            buffer += sentence
            bufferTokens += sentenceTokens
        }
        if (buffer.isNotEmpty()) {
            packed += buffer.joinToString(" ")
        }
        if (packed.isNotEmpty()) return packed
    }

    return splitByCharacterWindow(normalized, charsPerToken, maxChunkTokens)
}

private fun splitByCharacterWindow(
    text: String,
    charsPerToken: Float,
    maxChunkTokens: Int,
): List<String> {
    val compact = text.trim()
    if (compact.isEmpty()) return emptyList()

    val cjkRatio = compact.count(Char::isCjk).toDouble() / compact.length.toDouble().coerceAtLeast(1.0)
    val charsPerWindowToken = if (cjkRatio >= 0.35) 1.8 else charsPerToken.coerceIn(2.0f, 8.0f).toDouble()
    val windowSize = (maxChunkTokens * charsPerWindowToken).toInt().coerceAtLeast(120)

    val result = mutableListOf<String>()
    var start = 0
    while (start < compact.length) {
        val end = (start + windowSize).coerceAtMost(compact.length)
        result += compact.substring(start, end).trim()
        start = end
    }
    return result.filter { it.isNotBlank() }
}

private fun Char.isCjk(): Boolean {
    val code = this.code
    return code in 0x4E00..0x9FFF ||
        code in 0x3400..0x4DBF ||
        code in 0x3040..0x30FF ||
        code in 0xAC00..0xD7AF
}
