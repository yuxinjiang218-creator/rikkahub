package me.rerere.rikkahub.data.memory

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.model.MemoryChunkMetadata
import me.rerere.rikkahub.data.model.RollingSummaryDetailCapsule
import me.rerere.rikkahub.data.model.RollingSummaryEntry
import me.rerere.rikkahub.data.model.SourceDigestMessage
import me.rerere.rikkahub.data.model.SourcePreviewChunk
import me.rerere.rikkahub.data.model.buildLiveTailDigestJson
import me.rerere.rikkahub.data.model.parseRollingSummaryDocument
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

private const val RRF_K = 60.0
private const val MIN_FINAL_RECALL_SCORE = 0.018
private const val SOURCE_WEAK_RESULT_THRESHOLD = 1.05

private val CHUNK_BUDGET_BY_SECTION = mapOf(
    "artifacts" to (120 to 220),
    "constraints" to (120 to 220),
    "decisions" to (120 to 220),
    "tasks" to (80 to 160),
    "open_questions" to (80 to 160),
    "facts" to (180 to 260),
    "preferences" to (180 to 260),
    "timeline" to (220 to 320),
    "detail_capsules" to (160 to 340),
    "live_tail" to (100 to 300),
)

internal data class MemorySummaryChunk(
    val sectionKey: String,
    val chunkOrder: Int,
    val content: String,
    val tokenEstimate: Int,
    val metadata: MemoryChunkMetadata,
)

internal data class RankedMemoryChunk(
    val docIndex: Int,
    val bm25Score: Double,
    val vectorScore: Double,
    val exactScore: Double,
    val finalScore: Double,
)

internal data class RankedSourcePreview(
    val chunkIndex: Int,
    val score: Double,
    val usedFallbackScope: Boolean,
    val matchedSnippet: String,
)

internal data class SourceChunkDraft(
    val messageId: String,
    val role: String,
    val chunkOrder: Int,
    val prefixText: String,
    val searchText: String,
    val blockType: String,
)

@Serializable
internal data class SearchSourceRef(
    val conversationId: String,
    val messageId: String,
)

internal fun buildMemoryIndexChunks(
    rollingSummaryJson: String,
    charsPerToken: Float,
    liveTailDigestJson: String = "",
): List<MemorySummaryChunk> {
    val summary = parseRollingSummaryDocument(rollingSummaryJson)
    val chunks = mutableListOf<MemorySummaryChunk>()

    buildSectionChunks(summary, charsPerToken).forEach(chunks::add)
    buildDetailCapsuleChunks(summary.detailCapsules, charsPerToken).forEach(chunks::add)

    if (liveTailDigestJson.isNotBlank()) {
        val liveTail = parseRollingSummaryDocument(liveTailDigestJson)
        val liveTailEntries = liveTail.chronology
            .mapIndexed { index, episode ->
                RollingSummaryEntry(
                    id = episode.id,
                    text = episode.summary,
                    status = "active",
                    salience = episode.salience,
                    updatedAtTurn = index + 1,
                    sourceRoles = episode.sourceRoles,
                    relatedIds = episode.relatedDetailIds,
                    timeRef = episode.timeRef,
                )
            }
            .ifEmpty {
                liveTail.timeline
            }
            .filter { it.text.isNotBlank() }
            .sortedByDescending { it.updatedAtTurn }
        if (liveTailEntries.isNotEmpty()) {
            val budget = CHUNK_BUDGET_BY_SECTION.getValue("live_tail")
            buildChunksForEntries(
                sectionKey = "live_tail",
                lane = "live_tail",
                entries = liveTailEntries,
                charsPerToken = charsPerToken,
                minChunkTokens = budget.first,
                maxChunkTokens = budget.second
            ).forEach(chunks::add)
        }
        buildDetailCapsuleChunks(
            capsules = liveTail.detailCapsules,
            charsPerToken = charsPerToken,
            lane = "live_tail"
        ).forEach(chunks::add)
    }

    return chunks
}

internal fun buildLiveTailSourceDigest(
    messages: List<SourceDigestMessage>,
    charsPerToken: Float,
): String = buildLiveTailDigestJson(
    messages = messages,
    updatedAt = java.time.Instant.now(),
    charsPerToken = charsPerToken
).json

internal fun rankMemoryChunks(
    query: String,
    chunks: List<MemorySummaryChunk>,
    documentEmbeddings: List<List<Float>>,
    queryEmbedding: List<Float>,
    channel: String,
    role: String,
    bm25TopK: Int,
    vectorTopK: Int,
    minFinalScore: Double = MIN_FINAL_RECALL_SCORE,
): List<RankedMemoryChunk> {
    val normalizedQuery = query.trim()
    val queryTerms = tokenizeForRetrieval(normalizedQuery)
    if (queryTerms.isEmpty() || chunks.isEmpty()) return emptyList()

    val candidateChunks = chunks.mapIndexedNotNull { index, chunk ->
        if (role != "any" && chunk.metadata.sourceRoles.isNotEmpty() && !chunk.metadata.sourceRoles.contains(role)) {
            null
        } else {
            index to chunk
        }
    }
    if (candidateChunks.isEmpty()) return emptyList()

    val candidateIndexes = candidateChunks.map { it.first }
    val documents = candidateChunks.map { it.second.content }
    val docTerms = documents.map(::tokenizeForRetrieval)
    val queryIdentifiers = extractExactIdentifiers(normalizedQuery)
    val avgDocLength = docTerms.map { it.size }.average().takeIf { it > 0 } ?: 1.0
    val docFrequency = mutableMapOf<String, Int>()
    docTerms.forEach { terms ->
        terms.toSet().forEach { term ->
            docFrequency[term] = (docFrequency[term] ?: 0) + 1
        }
    }
    val totalDocs = docTerms.size.toDouble().coerceAtLeast(1.0)

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

    val bm25Ranked = candidateChunks.mapIndexed { localIndex, (_, chunk) ->
        localIndex to bm25Score(docTerms[localIndex])
    }.filter { it.second > 0.0 }
        .sortedByDescending { it.second }
        .take(bm25TopK)
    val vectorRanked = candidateChunks.mapIndexedNotNull { localIndex, (globalIndex, _) ->
        val embedding = documentEmbeddings.getOrNull(globalIndex) ?: return@mapIndexedNotNull null
        localIndex to cosineSimilarity(queryEmbedding, embedding)
    }.sortedByDescending { it.second }
        .take(vectorTopK)
    val exactRanked = candidateChunks.mapIndexedNotNull { localIndex, (_, chunk) ->
        val exactScore = exactIdentifierScore(queryIdentifiers, chunk.metadata, chunk.content)
        if (exactScore <= 0.0) null else localIndex to exactScore
    }.sortedByDescending { it.second }
        .take(20)

    val bm25Ranks = bm25Ranked.mapIndexed { rank, pair -> pair.first to rank + 1 }.toMap()
    val vectorRanks = vectorRanked.mapIndexed { rank, pair -> pair.first to rank + 1 }.toMap()
    val exactRanks = exactRanked.mapIndexed { rank, pair -> pair.first to rank + 1 }.toMap()
    val bm25ScoresByIndex = bm25Ranked.toMap()
    val vectorScoresByIndex = vectorRanked.toMap()
    val exactScoresByIndex = exactRanked.toMap()

    val candidateSet = (bm25Ranks.keys + vectorRanks.keys + exactRanks.keys).toSet()
    if (candidateSet.isEmpty()) return emptyList()

    val ranked = candidateSet.mapNotNull { localIndex ->
        val chunk = candidateChunks[localIndex].second
        val rrf = sequenceOf(
            bm25Ranks[localIndex],
            vectorRanks[localIndex],
            exactRanks[localIndex]
        ).filterNotNull()
            .sumOf { rank -> 1.0 / (RRF_K + rank.toDouble()) }
        val metadataBoost = channelLaneBoost(channel, chunk.metadata) *
            roleBoost(role, chunk.metadata.sourceRoles) *
            salienceBoost(chunk.metadata.salience) *
            exactMetadataBoost(exactScoresByIndex[localIndex] ?: 0.0)
        val finalScore = rrf * metadataBoost
        if (finalScore < minFinalScore) return@mapNotNull null
        RankedMemoryChunk(
            docIndex = candidateIndexes[localIndex],
            bm25Score = normalizeScore(bm25ScoresByIndex[localIndex], bm25Ranked.maxOfOrNull { it.second } ?: 1.0),
            vectorScore = normalizeCosine(vectorScoresByIndex[localIndex] ?: 0.0),
            exactScore = exactScoresByIndex[localIndex] ?: 0.0,
            finalScore = finalScore,
        )
    }.sortedByDescending { it.finalScore }

    return applyMmrToMemoryChunks(
        ranked = ranked,
        chunks = chunks,
        embeddings = documentEmbeddings,
        lambda = 0.72,
    )
}

internal fun buildSourcePreviewChunks(
    messages: List<IndexedSourceMessage>,
): List<SourceChunkDraft> {
    return messages.flatMap { message ->
        splitMessageForPreview(message.text).mapIndexed { index, block ->
            SourceChunkDraft(
                messageId = message.messageId,
                role = message.role,
                chunkOrder = index,
                prefixText = message.text.trim().replace("\r\n", "\n").take(80),
                searchText = block.text,
                blockType = block.type,
            )
        }
    }
}

internal fun rankSourcePreviewChunks(
    query: String,
    chunks: List<SourcePreviewChunk>,
    role: String,
    candidateConversationIds: Set<String>,
    usedFallbackScope: Boolean,
): List<RankedSourcePreview> {
    val normalizedQuery = query.trim()
    val queryTerms = tokenizeForRetrieval(normalizedQuery)
    if (queryTerms.isEmpty() || chunks.isEmpty()) return emptyList()

    val exactIdentifiers = extractExactIdentifiers(normalizedQuery)
    val filtered = chunks.mapIndexedNotNull { index, chunk ->
        if (role != "any" && !chunk.role.equals(role, ignoreCase = true)) {
            null
        } else {
            index to chunk
        }
    }
    if (filtered.isEmpty()) return emptyList()

    val documents = filtered.map { it.second.searchText }
    val docTerms = documents.map(::tokenizeForRetrieval)
    val avgDocLength = docTerms.map { it.size }.average().takeIf { it > 0 } ?: 1.0
    val docFrequency = mutableMapOf<String, Int>()
    docTerms.forEach { terms ->
        terms.toSet().forEach { term ->
            docFrequency[term] = (docFrequency[term] ?: 0) + 1
        }
    }
    val totalDocs = docTerms.size.toDouble().coerceAtLeast(1.0)

    fun bm25Score(terms: List<String>): Double {
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

    return filtered.mapIndexedNotNull { localIndex, (globalIndex, chunk) ->
        val lexical = bm25Score(docTerms[localIndex])
        val exact = exactIdentifiers.count { id ->
            chunk.searchText.contains(id, ignoreCase = true) || chunk.prefixText.contains(id, ignoreCase = true)
        }.toDouble()
        val coverage = queryTerms.toSet().count { term -> docTerms[localIndex].contains(term) }
            .toDouble() / queryTerms.toSet().size.coerceAtLeast(1).toDouble()
        val preferredBoost = if (candidateConversationIds.contains(chunk.conversationId.toString())) 1.25 else 0.82
        val score = (lexical + exact * 0.8 + coverage * 1.5) * preferredBoost
        if (score <= 0.0) return@mapIndexedNotNull null
        RankedSourcePreview(
            chunkIndex = globalIndex,
            score = score,
            usedFallbackScope = usedFallbackScope,
            matchedSnippet = buildMatchedSnippet(chunk.searchText, queryTerms)
        )
    }.sortedByDescending { it.score }
}

internal fun isWeakSourceResult(result: RankedSourcePreview?): Boolean {
    return result == null || result.score < SOURCE_WEAK_RESULT_THRESHOLD
}

internal fun sourceRef(conversationId: String, messageId: String): String {
    return "$conversationId::$messageId"
}

internal fun parseSourceRef(value: String): SearchSourceRef? {
    val parts = value.split("::")
    if (parts.size != 2 || parts.any { it.isBlank() }) return null
    return SearchSourceRef(conversationId = parts[0], messageId = parts[1])
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

internal data class IndexedSourceMessage(
    val messageId: String,
    val role: String,
    val text: String,
)

private fun buildSectionChunks(
    summary: me.rerere.rikkahub.data.model.RollingSummaryDocument,
    charsPerToken: Float,
): List<MemorySummaryChunk> {
    return buildList {
        listOf(
            "facts",
            "preferences",
            "tasks",
            "decisions",
            "constraints",
            "open_questions",
            "artifacts",
            "timeline",
        ).forEach { sectionKey ->
            val entries = summary.sectionEntries(sectionKey)
                .filter { it.text.isNotBlank() }
            if (entries.isEmpty()) return@forEach

            val currentEntries = entries.filter { determineLane(sectionKey, it) == "current" }
            val historyEntries = entries.filter { determineLane(sectionKey, it) == "history" }
            val budget = CHUNK_BUDGET_BY_SECTION.getValue(sectionKey)
            addAll(buildChunksForEntries(sectionKey, "current", currentEntries, charsPerToken, budget.first, budget.second))
            addAll(buildChunksForEntries(sectionKey, "history", historyEntries, charsPerToken, budget.first, budget.second))
        }
    }
}

private fun buildDetailCapsuleChunks(
    capsules: List<RollingSummaryDetailCapsule>,
    charsPerToken: Float,
    lane: String? = null,
): List<MemorySummaryChunk> {
    if (capsules.isEmpty()) return emptyList()
    val budget = CHUNK_BUDGET_BY_SECTION.getValue("detail_capsules")
    return capsules
        .filter { it.title.isNotBlank() || it.summary.isNotBlank() || it.keyExcerpt.isNotBlank() }
        .sortedByDescending { it.updatedAtTurn }
        .mapIndexed { index, capsule ->
            val effectiveLane = lane ?: if (capsule.status == "superseded" || capsule.status == "historical") {
                "history"
            } else {
                "current"
            }
            val content = renderDetailCapsuleForRetrieval(capsule)
            MemorySummaryChunk(
                sectionKey = "detail_capsules",
                chunkOrder = index,
                content = buildChunkContent(
                    sectionKey = "detail_capsules",
                    lane = effectiveLane,
                    entries = listOf(content)
                ),
                tokenEstimate = estimateSemanticTokens(content, charsPerToken)
                    .coerceIn(budget.first, budget.second),
                metadata = MemoryChunkMetadata(
                    lane = effectiveLane,
                    status = capsule.status,
                    sectionKey = "detail_capsules",
                    detailKind = capsule.kind,
                    tags = listOf(capsule.kind) + capsule.identifiers,
                    entityKeys = capsule.identifiers,
                    salience = capsule.salience,
                    relatedIds = emptyList(),
                    sourceRoles = capsule.sourceRoles,
                    sourceMessageIds = capsule.sourceMessageIds,
                )
            )
        }
}

private fun buildChunksForEntries(
    sectionKey: String,
    lane: String,
    entries: List<RollingSummaryEntry>,
    charsPerToken: Float,
    minChunkTokens: Int,
    maxChunkTokens: Int,
): List<MemorySummaryChunk> {
    if (entries.isEmpty()) return emptyList()
    val chunks = mutableListOf<MemorySummaryChunk>()
    val units = entries.map { entry ->
        ChunkUnit(
            content = renderEntryForRetrieval(sectionKey, entry),
            tokenEstimate = estimateSemanticTokens(renderEntryForRetrieval(sectionKey, entry), charsPerToken),
            metadata = MemoryChunkMetadata(
                lane = lane,
                status = entry.status,
                sectionKey = sectionKey,
                tags = entry.tags,
                entityKeys = entry.entityKeys,
                salience = entry.salience,
                timeRef = entry.timeRef,
                relatedIds = entry.relatedIds,
                sourceRoles = entry.sourceRoles,
            )
        )
    }

    var start = 0
    var order = 0
    while (start < units.size) {
        var end = start
        var chunkTokens = 0
        val mergedMetadata = mutableListOf<MemoryChunkMetadata>()
        while (end < units.size) {
            val nextTokens = units[end].tokenEstimate.coerceAtLeast(1)
            val shouldClose = chunkTokens >= minChunkTokens && chunkTokens + nextTokens > maxChunkTokens
            if (shouldClose) break
            chunkTokens += nextTokens
            mergedMetadata += units[end].metadata
            end++
            if (chunkTokens >= maxChunkTokens) break
        }
        if (end == start) {
            chunkTokens = units[end].tokenEstimate.coerceAtLeast(1)
            mergedMetadata += units[end].metadata
            end++
        }

        val chunkContent = buildChunkContent(
            sectionKey = sectionKey,
            lane = lane,
            entries = units.subList(start, end).map { it.content }
        )
        chunks += MemorySummaryChunk(
            sectionKey = sectionKey,
            chunkOrder = order++,
            content = chunkContent,
            tokenEstimate = estimateSemanticTokens(chunkContent, charsPerToken),
            metadata = mergeChunkMetadata(sectionKey, lane, mergedMetadata)
        )
        start = end
    }
    return chunks
}

private fun mergeChunkMetadata(
    sectionKey: String,
    lane: String,
    entries: List<MemoryChunkMetadata>,
): MemoryChunkMetadata {
    return MemoryChunkMetadata(
        lane = lane,
        status = when {
            entries.any { it.status == "superseded" } -> "superseded"
            entries.any { it.status == "historical" } -> "historical"
            entries.any { it.status == "blocked" } -> "blocked"
            entries.any { it.status == "done" } -> "done"
            else -> "active"
        },
        sectionKey = sectionKey,
        detailKind = entries.mapNotNull { it.detailKind }.firstOrNull(),
        tags = entries.flatMap { it.tags }.distinct(),
        entityKeys = entries.flatMap { it.entityKeys }.distinct(),
        salience = entries.maxOfOrNull { it.salience } ?: 0.5,
        timeRef = entries.mapNotNull { it.timeRef }.maxOrNull(),
        relatedIds = entries.flatMap { it.relatedIds }.distinct(),
        sourceRoles = entries.flatMap { it.sourceRoles }.distinct(),
        sourceMessageIds = entries.flatMap { it.sourceMessageIds }.distinct(),
    )
}

private fun renderEntryForRetrieval(
    sectionKey: String,
    entry: RollingSummaryEntry,
): String = buildString {
    append(entry.text)
    if (entry.tags.isNotEmpty()) {
        appendLine()
        append("tags: ")
        append(entry.tags.joinToString(", "))
    }
    if (entry.entityKeys.isNotEmpty()) {
        appendLine()
        append("identifiers: ")
        append(entry.entityKeys.joinToString(", "))
    }
    when (sectionKey) {
        "tasks" -> entry.taskState?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            append("task_state: ")
            append(it)
        }

        "decisions" -> entry.reason?.let {
            appendLine()
            append("reason: ")
            append(it)
        }

        "constraints" -> entry.scope?.let {
            appendLine()
            append("scope: ")
            append(it)
        }

        "artifacts" -> {
            entry.kind?.let {
                appendLine()
                append("kind: ")
                append(it)
            }
            entry.locator?.let {
                appendLine()
                append("locator: ")
                append(it)
            }
        }

        "timeline" -> {
            entry.changeType?.let {
                appendLine()
                append("change_type: ")
                append(it)
            }
            entry.timeRef?.let {
                appendLine()
                append("time_ref: ")
                append(it)
            }
        }
    }
}.trim()

private fun buildChunkContent(
    sectionKey: String,
    lane: String,
    entries: List<String>,
): String = buildString {
    append('[')
    append(sectionKey)
    append('/')
    append(lane)
    appendLine(']')
    entries.forEach { entry ->
        append("- ")
        appendLine(entry.replace("\n", "\n  "))
    }
}.trim()

private fun renderDetailCapsuleForRetrieval(capsule: RollingSummaryDetailCapsule): String = buildString {
    append(capsule.title.ifBlank { capsule.kind })
    if (capsule.summary.isNotBlank()) {
        appendLine()
        append("summary: ")
        append(capsule.summary)
    }
    if (capsule.keyExcerpt.isNotBlank()) {
        appendLine()
        append("excerpt: ")
        append(capsule.keyExcerpt)
    }
    if (capsule.identifiers.isNotEmpty()) {
        appendLine()
        append("identifiers: ")
        append(capsule.identifiers.joinToString(", "))
    }
    capsule.locator?.takeIf { it.isNotBlank() }?.let {
        appendLine()
        append("locator: ")
        append(it)
    }
}.trim()

private fun determineLane(sectionKey: String, entry: RollingSummaryEntry): String {
    return when {
        sectionKey == "timeline" -> "history"
        entry.status == "superseded" || entry.status == "historical" -> "history"
        else -> "current"
    }
}

private fun channelLaneBoost(channel: String, metadata: MemoryChunkMetadata): Double {
    val laneBoost = when (channel) {
        "history" -> when (metadata.lane) {
            "history" -> 1.25
            "live_tail" -> 0.92
            else -> 1.02
        }

        else -> when (metadata.lane) {
            "current" -> 1.2
            "live_tail" -> 1.14
            else -> 0.82
        }
    }
    val statusBoost = when (metadata.status) {
        "active" -> 1.12
        "blocked" -> 1.05
        "done" -> 0.96
        "superseded" -> if (channel == "history") 1.12 else 0.72
        "historical" -> if (channel == "history") 1.08 else 0.76
        else -> 1.0
    }
    val sectionBoost = when (channel) {
        "history" -> when (metadata.sectionKey) {
            "timeline" -> 1.16
            "decisions" -> 1.12
            "detail_capsules" -> if (metadata.detailKind in setOf("tool", "code", "poem", "quote", "longform")) 1.1 else 1.04
            else -> 1.0
        }

        else -> when (metadata.sectionKey) {
            "timeline" -> 0.86
            "decisions" -> 1.04
            "detail_capsules" -> 1.08
            else -> 1.0
        }
    }
    return laneBoost * statusBoost * sectionBoost
}

private fun roleBoost(role: String, sourceRoles: List<String>): Double {
    if (role == "any" || sourceRoles.isEmpty()) return 1.0
    return if (sourceRoles.contains(role)) 1.08 else 0.55
}

private fun salienceBoost(salience: Double): Double {
    return 0.9 + salience.coerceIn(0.0, 1.0) * 0.25
}

private fun exactMetadataBoost(exactScore: Double): Double {
    return 1.0 + exactScore.coerceAtMost(3.0) * 0.08
}

private fun normalizeScore(score: Double?, maxScore: Double): Double {
    if (score == null || maxScore <= 0.0) return 0.0
    return (score / maxScore).coerceIn(0.0, 1.0)
}

private fun normalizeCosine(score: Double): Double {
    return ((score + 1.0) / 2.0).coerceIn(0.0, 1.0)
}

private fun exactIdentifierScore(
    identifiers: Set<String>,
    metadata: MemoryChunkMetadata,
    content: String,
): Double {
    if (identifiers.isEmpty()) return 0.0
    val haystack = buildString {
        append(content.lowercase())
        if (metadata.entityKeys.isNotEmpty()) {
            append(' ')
            append(metadata.entityKeys.joinToString(" ").lowercase())
        }
        if (metadata.tags.isNotEmpty()) {
            append(' ')
            append(metadata.tags.joinToString(" ").lowercase())
        }
    }
    return identifiers.count { haystack.contains(it) }.toDouble()
}

private fun extractExactIdentifiers(query: String): Set<String> {
    return Regex("[A-Za-z0-9_./:-]{2,}").findAll(query.lowercase())
        .map { it.value.trim('.', '/', '_', '-', ':') }
        .filter { it.length >= 2 }
        .toSet()
}

private fun applyMmrToMemoryChunks(
    ranked: List<RankedMemoryChunk>,
    chunks: List<MemorySummaryChunk>,
    embeddings: List<List<Float>>,
    lambda: Double,
): List<RankedMemoryChunk> {
    if (ranked.size <= 1) return ranked
    val selected = mutableListOf<RankedMemoryChunk>()
    val remaining = ranked.toMutableList()
    while (remaining.isNotEmpty() && selected.size < ranked.size) {
        val next = remaining.maxByOrNull { candidate ->
            val noveltyPenalty = selected.maxOfOrNull { chosen ->
                val chosenEmbedding = embeddings.getOrNull(chosen.docIndex)
                val candidateEmbedding = embeddings.getOrNull(candidate.docIndex)
                when {
                    chosenEmbedding != null && candidateEmbedding != null ->
                        normalizeCosine(cosineSimilarity(chosenEmbedding, candidateEmbedding))

                    else -> lexicalSimilarity(
                        chunks[candidate.docIndex].content,
                        chunks[chosen.docIndex].content
                    )
                }
            } ?: 0.0
            lambda * candidate.finalScore - (1.0 - lambda) * noveltyPenalty
        } ?: break
        selected += next
        remaining.remove(next)
    }
    return selected
}

private fun lexicalSimilarity(a: String, b: String): Double {
    val aTerms = tokenizeForRetrieval(a).toSet()
    val bTerms = tokenizeForRetrieval(b).toSet()
    if (aTerms.isEmpty() || bTerms.isEmpty()) return 0.0
    val intersection = aTerms.intersect(bTerms).size.toDouble()
    val union = aTerms.union(bTerms).size.toDouble().coerceAtLeast(1.0)
    return (intersection / union).coerceIn(0.0, 1.0)
}

private fun splitMessageForPreview(text: String): List<SourceBlock> {
    val normalized = text.trim().replace("\r\n", "\n")
    if (normalized.isBlank()) return emptyList()

    val lines = normalized.lines().filter { it.isNotBlank() }
    if (lines.size >= 4 && lines.all { it.length in 2..36 }) {
        return listOf(SourceBlock(text = normalized, type = "poem"))
    }

    if (normalized.contains("```")) {
        val blocks = mutableListOf<SourceBlock>()
        val regex = Regex("```[\\s\\S]*?```")
        var cursor = 0
        regex.findAll(normalized).forEach { match ->
            val before = normalized.substring(cursor, match.range.first).trim()
            if (before.isNotBlank()) {
                blocks += splitParagraphBlock(before)
            }
            blocks += SourceBlock(text = match.value.trim(), type = "code")
            cursor = match.range.last + 1
        }
        val after = normalized.substring(cursor).trim()
        if (after.isNotBlank()) {
            blocks += splitParagraphBlock(after)
        }
        return blocks.ifEmpty { listOf(SourceBlock(text = normalized, type = "text")) }
    }

    return splitParagraphBlock(normalized)
}

private fun splitParagraphBlock(text: String): List<SourceBlock> {
    val paragraphs = text.split(Regex("\\n{2,}"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (paragraphs.isEmpty()) return emptyList()

    return paragraphs.flatMap { paragraph ->
        when {
            paragraph.lines().size >= 3 && paragraph.lines().all { it.length in 2..40 } ->
                listOf(SourceBlock(text = paragraph, type = "poem"))

            paragraph.lines().all { it.trim().startsWith("-") || it.trim().matches(Regex("\\d+\\..*")) } ->
                listOf(SourceBlock(text = paragraph, type = "list"))

            paragraph.length <= 320 ->
                listOf(SourceBlock(text = paragraph, type = "text"))

            else -> splitLongParagraph(paragraph)
        }
    }
}

private fun splitLongParagraph(text: String): List<SourceBlock> {
    val pieces = text.split(Regex("(?<=[。！？!?；;])|\\n"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (pieces.isEmpty()) return listOf(SourceBlock(text = text, type = "text"))

    val result = mutableListOf<SourceBlock>()
    val buffer = StringBuilder()
    pieces.forEach { piece ->
        if (buffer.isNotEmpty() && buffer.length + piece.length > 320) {
            result += SourceBlock(text = buffer.toString().trim(), type = "text")
            buffer.clear()
        }
        if (buffer.isNotEmpty()) buffer.append(' ')
        buffer.append(piece)
    }
    if (buffer.isNotEmpty()) {
        result += SourceBlock(text = buffer.toString().trim(), type = "text")
    }
    return result
}

private fun buildMatchedSnippet(text: String, queryTerms: List<String>): String {
    val normalized = text.trim().replace("\n", " ")
    val hit = queryTerms.firstOrNull { term -> normalized.contains(term, ignoreCase = true) }
    if (hit == null) return normalized.take(120)
    val index = normalized.indexOf(hit, ignoreCase = true).coerceAtLeast(0)
    val start = (index - 36).coerceAtLeast(0)
    val end = (index + hit.length + 48).coerceAtMost(normalized.length)
    return normalized.substring(start, end).trim()
}

private data class ChunkUnit(
    val content: String,
    val tokenEstimate: Int,
    val metadata: MemoryChunkMetadata,
)

private data class SourceBlock(
    val text: String,
    val type: String,
)

private fun Char.isCjk(): Boolean {
    val code = this.code
    return code in 0x4E00..0x9FFF ||
        code in 0x3400..0x4DBF ||
        code in 0x3040..0x30FF ||
        code in 0xAC00..0xD7AF
}
