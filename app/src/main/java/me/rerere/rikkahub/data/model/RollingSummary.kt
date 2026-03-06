package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import java.security.MessageDigest
import java.time.Instant

private val ROLLING_SUMMARY_SECTIONS = listOf(
    "facts",
    "preferences",
    "tasks",
    "decisions",
    "constraints",
    "open_questions",
    "artifacts",
    "timeline",
)

private val ALL_SUMMARY_SECTIONS = ROLLING_SUMMARY_SECTIONS + listOf(
    "chronology",
    "detail_capsules",
)

private val CURRENT_VIEW_SECTIONS = listOf(
    "constraints",
    "tasks",
    "artifacts",
    "decisions",
    "facts",
    "preferences",
    "timeline",
)

private val SECTION_KEY_ALIASES = mapOf(
    "facts" to listOf("facts"),
    "preferences" to listOf("preferences"),
    "tasks" to listOf("tasks"),
    "decisions" to listOf("decisions"),
    "constraints" to listOf("constraints"),
    "open_questions" to listOf("open_questions", "openQuestions"),
    "artifacts" to listOf("artifacts"),
    "timeline" to listOf("timeline"),
    "chronology" to listOf("chronology"),
    "detail_capsules" to listOf("detail_capsules", "detailCapsules"),
)

private val ENTRY_WRAPPER_KEYS = listOf("items", "entries", "bullets", "points", "list", "values")
private val FALLBACK_JSON_META_KEYS = setOf(
    "meta",
    "preview",
    "sections",
    "schema_version",
    "schemaVersion",
    "summary_turn",
    "summaryTurn",
    "updated_at",
    "updatedAt",
)

private const val STATUS_ACTIVE = "active"
private const val STATUS_DONE = "done"
private const val STATUS_BLOCKED = "blocked"
private const val STATUS_SUPERSEDED = "superseded"
private const val STATUS_HISTORICAL = "historical"

@Serializable
data class RollingSummaryDocument(
    val meta: RollingSummaryMeta = RollingSummaryMeta(),
    val facts: List<RollingSummaryEntry> = emptyList(),
    val preferences: List<RollingSummaryEntry> = emptyList(),
    val tasks: List<RollingSummaryEntry> = emptyList(),
    val decisions: List<RollingSummaryEntry> = emptyList(),
    val constraints: List<RollingSummaryEntry> = emptyList(),
    @SerialName("open_questions")
    val openQuestions: List<RollingSummaryEntry> = emptyList(),
    val artifacts: List<RollingSummaryEntry> = emptyList(),
    val timeline: List<RollingSummaryEntry> = emptyList(),
    val chronology: List<RollingSummaryChronologyEpisode> = emptyList(),
    @SerialName("detail_capsules")
    val detailCapsules: List<RollingSummaryDetailCapsule> = emptyList(),
) {
    fun sectionEntries(sectionKey: String): List<RollingSummaryEntry> = when (sectionKey) {
        "facts" -> facts
        "preferences" -> preferences
        "tasks" -> tasks
        "decisions" -> decisions
        "constraints" -> constraints
        "open_questions" -> openQuestions
        "artifacts" -> artifacts
        "timeline" -> timeline
        else -> emptyList()
    }

    fun withUpdatedMeta(summaryTurn: Int, updatedAt: Instant): RollingSummaryDocument {
        return copy(
            meta = meta.copy(
                schemaVersion = 3,
                summaryTurn = summaryTurn,
                updatedAt = updatedAt.toEpochMilli()
            )
        )
    }

    fun toJson(): String = JsonInstant.encodeToString(this)

    fun toCurrentViewProjection(maxItemsPerSection: Int = 6): String {
        val primary = projectionLines(
            currentViewOnly = true,
            maxItemsPerSection = maxItemsPerSection
        )
        return if (primary.isNotBlank()) primary else {
            projectionLines(
                currentViewOnly = false,
                maxItemsPerSection = maxItemsPerSection
            )
        }
    }

    fun toSummarySnapshot(): CompressionSummarySnapshot {
        val primarySections = snapshotSections(currentViewOnly = true)
        val sections = if (primarySections.isNotEmpty()) primarySections else snapshotSections(currentViewOnly = false)
        val preview = sections
            .flatMap { section -> section.items }
            .take(3)
            .joinToString(" · ")
            .take(220)
        return CompressionSummarySnapshot(
            preview = preview,
            sections = sections
        )
    }

    fun totalEntryCount(): Int = ROLLING_SUMMARY_SECTIONS.sumOf { sectionEntries(it).size }

    private fun projectionLines(
        currentViewOnly: Boolean,
        maxItemsPerSection: Int,
    ): String {
        val lines = buildList {
            CURRENT_VIEW_SECTIONS.forEach { sectionKey ->
                val filtered = projectionEntries(
                    sectionKey = sectionKey,
                    currentViewOnly = currentViewOnly
                ).take(maxItemsPerSection)
                if (filtered.isNotEmpty()) {
                    add("[${sectionKey}]")
                    filtered.forEach { entry ->
                        add("- ${entry.renderForProjection(sectionKey)}")
                    }
                }
            }
            chronologyForContext(maxItemsPerSection).takeIf { it.isNotEmpty() }?.let { episodes ->
                add("[chronology]")
                episodes.forEach { episode ->
                    add("- ${episode.renderForProjection()}")
                }
            }
            referencedDetailCapsules(maxItemsPerSection).takeIf { it.isNotEmpty() }?.let { capsules ->
                add("[detail_capsules]")
                capsules.forEach { capsule ->
                    add("- ${capsule.renderForProjection()}")
                }
            }
        }
        return lines.joinToString("\n").trim()
    }

    private fun snapshotSections(currentViewOnly: Boolean): List<CompressionSummarySection> {
        return buildList {
            CURRENT_VIEW_SECTIONS.forEach { sectionKey ->
                val items = projectionEntries(
                    sectionKey = sectionKey,
                    currentViewOnly = currentViewOnly
                )
                    .take(if (sectionKey == "timeline") 2 else 3)
                    .map { it.renderForProjection(sectionKey) }
                if (items.isNotEmpty()) {
                    add(
                        CompressionSummarySection(
                            key = sectionKey,
                            title = sectionKey.toSummaryTitle(),
                            items = items
                        )
                    )
                }
            }
            chronologyForContext(3).takeIf { it.isNotEmpty() }?.let { episodes ->
                add(
                    CompressionSummarySection(
                        key = "chronology",
                        title = "叙事主线",
                        items = episodes.map { it.renderForProjection() }
                    )
                )
            }
            referencedDetailCapsules(3).takeIf { it.isNotEmpty() }?.let { capsules ->
                add(
                    CompressionSummarySection(
                        key = "detail_capsules",
                        title = "关键细节",
                        items = capsules.map { it.renderForProjection() }
                    )
                )
            }
        }
    }

    private fun projectionEntries(
        sectionKey: String,
        currentViewOnly: Boolean,
    ): List<RollingSummaryEntry> {
        return sectionEntries(sectionKey)
            .filter { entry ->
                if (!currentViewOnly) {
                    entry.text.isNotBlank()
                } else {
                    entry.shouldIncludeInCurrentView(sectionKey)
                }
            }
            .sortedByDescending { it.salience }
    }

    fun chronologyForContext(limit: Int = 6): List<RollingSummaryChronologyEpisode> {
        return chronology
            .filter { it.summary.isNotBlank() }
            .takeLast(limit.coerceAtLeast(1))
    }

    fun referencedDetailCapsules(limit: Int = 8): List<RollingSummaryDetailCapsule> {
        if (detailCapsules.isEmpty()) return emptyList()
        val capsuleById = detailCapsules.associateBy { it.id }
        val referencedIds = linkedSetOf<String>()
        chronologyForContext()
            .flatMapTo(referencedIds) { it.relatedDetailIds }
        CURRENT_VIEW_SECTIONS.forEach { sectionKey ->
            projectionEntries(sectionKey, currentViewOnly = true)
                .flatMapTo(referencedIds) { it.relatedIds }
        }
        val referenced = referencedIds.mapNotNull(capsuleById::get)
        val fallback = detailCapsules
            .asSequence()
            .filter { it.id !in referencedIds }
            .sortedByDescending { it.salience }
            .toList()
        return (referenced + fallback).take(limit.coerceAtLeast(1))
    }
}

@Serializable
data class RollingSummaryMeta(
    @SerialName("schema_version")
    val schemaVersion: Int = 3,
    @SerialName("summary_turn")
    val summaryTurn: Int = 0,
    @SerialName("updated_at")
    val updatedAt: Long = 0L,
)

@Serializable
data class RollingSummaryChronologyEpisode(
    val id: String,
    @SerialName("turn_range")
    val turnRange: String = "",
    val summary: String,
    @SerialName("source_roles")
    val sourceRoles: List<String> = emptyList(),
    @SerialName("time_ref")
    val timeRef: String? = null,
    @SerialName("related_detail_ids")
    val relatedDetailIds: List<String> = emptyList(),
    val salience: Double = 0.5,
) {
    fun normalized(fallbackTurn: Int = 0): RollingSummaryChronologyEpisode {
        val normalizedSummary = summary.trim()
        val fallbackRange = if (fallbackTurn > 0) "$fallbackTurn" else ""
        return copy(
            id = id.ifBlank { stableRollingSummaryId("chronology", "$turnRange::$normalizedSummary") },
            turnRange = turnRange.trim().ifBlank { fallbackRange },
            summary = normalizedSummary,
            sourceRoles = sourceRoles.map { it.lowercase() }.filter { it in VALID_SOURCE_ROLES }.distinct(),
            timeRef = timeRef?.trim()?.takeIf { it.isNotBlank() },
            relatedDetailIds = relatedDetailIds.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            salience = salience.coerceIn(0.0, 1.0),
        )
    }

    fun renderForProjection(): String {
        val prefix = buildString {
            if (turnRange.isNotBlank()) {
                append("[")
                append(turnRange)
                append("] ")
            }
            timeRef?.takeIf { it.isNotBlank() }?.let {
                append(it)
                append(" ")
            }
        }
        return (prefix + summary).trim()
    }
}

@Serializable
data class RollingSummaryDetailCapsule(
    val id: String,
    val kind: String = "longform",
    val title: String = "",
    val summary: String = "",
    @SerialName("key_excerpt")
    val keyExcerpt: String = "",
    val identifiers: List<String> = emptyList(),
    @SerialName("source_roles")
    val sourceRoles: List<String> = emptyList(),
    @SerialName("source_message_ids")
    val sourceMessageIds: List<String> = emptyList(),
    val locator: String? = null,
    @SerialName("updated_at_turn")
    val updatedAtTurn: Int = 0,
    val salience: Double = 0.5,
    val status: String = STATUS_ACTIVE,
) {
    fun normalized(fallbackTurn: Int = 0): RollingSummaryDetailCapsule {
        val normalizedStatus = status.lowercase().takeIf { it in VALID_ENTRY_STATUSES } ?: STATUS_ACTIVE
        val normalizedTitle = title.trim()
        val normalizedSummary = summary.trim()
        val normalizedExcerpt = keyExcerpt.trim()
        val contentSeed = buildString {
            append(normalizedTitle)
            append("::")
            append(normalizedSummary)
            append("::")
            append(normalizedExcerpt)
        }
        return copy(
            id = id.ifBlank { stableRollingSummaryId("detail_capsules", contentSeed) },
            kind = kind.trim().ifBlank { "longform" },
            title = normalizedTitle,
            summary = normalizedSummary,
            keyExcerpt = normalizedExcerpt,
            identifiers = identifiers.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            sourceRoles = sourceRoles.map { it.lowercase() }.filter { it in VALID_SOURCE_ROLES }.distinct(),
            sourceMessageIds = sourceMessageIds.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            locator = locator?.trim()?.takeIf { it.isNotBlank() },
            updatedAtTurn = updatedAtTurn.coerceAtLeast(fallbackTurn),
            salience = salience.coerceIn(0.0, 1.0),
            status = normalizedStatus,
        )
    }

    fun matchesRole(role: String): Boolean {
        if (role == "any" || sourceRoles.isEmpty()) return true
        return sourceRoles.contains(role)
    }

    fun renderForProjection(): String {
        val titlePart = title.ifBlank {
            when {
                summary.isNotBlank() -> summary.take(32)
                keyExcerpt.isNotBlank() -> keyExcerpt.take(32)
                else -> kind
            }
        }
        val bodyPart = when {
            summary.isNotBlank() -> summary
            keyExcerpt.isNotBlank() -> keyExcerpt.take(80)
            else -> ""
        }
        val suffix = locator?.let { " ($it)" }.orEmpty()
        return listOf(titlePart, bodyPart)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .plus(suffix)
            .trim()
    }
}

@Serializable
data class RollingSummaryEntry(
    val id: String,
    val text: String,
    val status: String = STATUS_ACTIVE,
    val tags: List<String> = emptyList(),
    @SerialName("entity_keys")
    val entityKeys: List<String> = emptyList(),
    val salience: Double = 0.5,
    @SerialName("updated_at_turn")
    val updatedAtTurn: Int = 0,
    @SerialName("source_roles")
    val sourceRoles: List<String> = emptyList(),
    @SerialName("task_state")
    val taskState: String? = null,
    val reason: String? = null,
    @SerialName("related_ids")
    val relatedIds: List<String> = emptyList(),
    val scope: String? = null,
    val blocker: String? = null,
    val kind: String? = null,
    val locator: String? = null,
    @SerialName("change_type")
    val changeType: String? = null,
    @SerialName("time_ref")
    val timeRef: String? = null,
) {
    fun normalized(sectionKey: String, fallbackTurn: Int = 0): RollingSummaryEntry {
        val normalizedStatus = status.lowercase().takeIf { it in VALID_ENTRY_STATUSES } ?: when {
            sectionKey == "timeline" -> STATUS_HISTORICAL
            sectionKey == "tasks" && taskState.equals("done", ignoreCase = true) -> STATUS_DONE
            else -> STATUS_ACTIVE
        }
        return copy(
            id = id.ifBlank { stableRollingSummaryId(sectionKey, text) },
            text = text.trim(),
            status = normalizedStatus,
            tags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            entityKeys = entityKeys.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            salience = salience.coerceIn(0.0, 1.0),
            updatedAtTurn = updatedAtTurn.coerceAtLeast(fallbackTurn),
            sourceRoles = sourceRoles.map { it.lowercase() }.filter { it in VALID_SOURCE_ROLES }.distinct(),
            relatedIds = relatedIds.filter { it.isNotBlank() }.distinct(),
            taskState = taskState?.lowercase(),
            scope = scope?.trim()?.takeIf { it.isNotBlank() },
            blocker = blocker?.trim()?.takeIf { it.isNotBlank() },
            kind = kind?.trim()?.takeIf { it.isNotBlank() },
            locator = locator?.trim()?.takeIf { it.isNotBlank() },
            reason = reason?.trim()?.takeIf { it.isNotBlank() },
            changeType = changeType?.trim()?.takeIf { it.isNotBlank() },
            timeRef = timeRef?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    fun shouldIncludeInCurrentView(sectionKey: String): Boolean {
        return when (sectionKey) {
            "timeline" -> status != STATUS_SUPERSEDED
            else -> status != STATUS_SUPERSEDED && status != STATUS_HISTORICAL
        }
    }

    fun matchesRole(role: String): Boolean {
        if (role == "any") return true
        if (sourceRoles.isEmpty()) return true
        return sourceRoles.contains(role)
    }

    fun renderForProjection(sectionKey: String): String {
        val suffix = when (sectionKey) {
            "tasks" -> taskState?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
            "decisions" -> reason?.let { " ($it)" }.orEmpty()
            "constraints" -> scope?.let { " [$it]" }.orEmpty()
            "artifacts" -> listOfNotNull(kind, locator)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" · ", prefix = " (", postfix = ")")
                .orEmpty()
            "timeline" -> timeRef?.let { " [$it]" }.orEmpty()
            else -> ""
        }
        return "$text$suffix".trim()
    }
}

@Serializable
data class CompressionSummarySnapshot(
    val preview: String = "",
    val sections: List<CompressionSummarySection> = emptyList(),
) {
    fun toJson(): String = JsonInstant.encodeToString(this)
}

@Serializable
data class CompressionSummarySection(
    val key: String,
    val title: String,
    val items: List<String>,
)

data class LiveTailDigest(
    val json: String,
    val messageCount: Int,
)

fun parseRollingSummaryDocument(rawSummary: String): RollingSummaryDocument {
    if (rawSummary.isBlank()) return RollingSummaryDocument()

    val parsedObject = parseJsonObjectLenient(rawSummary)
    if (parsedObject != null) {
        val meta = parseSummaryMeta(parsedObject["meta"])
        val sections = ROLLING_SUMMARY_SECTIONS.associateWith { sectionKey ->
            normalizeSummarySection(
                sectionKey = sectionKey,
                element = parsedObject.findSectionElement(sectionKey),
                fallbackTurn = meta.summaryTurn
            )
        }

        val document = RollingSummaryDocument(
            meta = meta.copy(schemaVersion = 3),
            facts = sections["facts"].orEmpty(),
            preferences = sections["preferences"].orEmpty(),
            tasks = sections["tasks"].orEmpty(),
            decisions = sections["decisions"].orEmpty(),
            constraints = sections["constraints"].orEmpty(),
            openQuestions = sections["open_questions"].orEmpty(),
            artifacts = sections["artifacts"].orEmpty(),
            timeline = sections["timeline"].orEmpty(),
            chronology = normalizeChronologySection(
                element = parsedObject.findSectionElement("chronology"),
                fallbackTurn = meta.summaryTurn
            ),
            detailCapsules = normalizeDetailCapsulesSection(
                element = parsedObject.findSectionElement("detail_capsules"),
                fallbackTurn = meta.summaryTurn
            ),
        )
        if (document.totalEntryCount() > 0 || document.chronology.isNotEmpty() || document.detailCapsules.isNotEmpty()) {
            return document
        }

        fallbackDocumentFromJson(parsedObject, fallbackTurn = meta.summaryTurn)?.let { fallback ->
            return fallback.copy(meta = meta.copy(schemaVersion = 3))
        }
    }

    return fallbackDocumentFromRawText(rawSummary)
}

fun normalizeRollingSummaryJson(
    rawSummary: String,
    summaryTurn: Int = 0,
    updatedAt: Instant = Instant.now(),
): String {
    return parseRollingSummaryDocument(rawSummary)
        .withUpdatedMeta(summaryTurn = summaryTurn, updatedAt = updatedAt)
        .toJson()
}

fun parseCompressionSummarySnapshot(rawSnapshot: String): CompressionSummarySnapshot? {
    if (rawSnapshot.isBlank()) return null
    val normalized = stripCodeFenceWrapper(rawSnapshot)
    return runCatching {
        JsonInstant.decodeFromString<CompressionSummarySnapshot>(normalized)
    }.getOrNull()
}

fun stableRollingSummaryId(sectionKey: String, text: String): String {
    val digest = MessageDigest.getInstance("SHA-1")
        .digest("$sectionKey::$text".toByteArray())
        .joinToString("") { "%02x".format(it) }
    return "${sectionKey}_${digest.take(16)}"
}

fun buildLiveTailDigestJson(
    messages: List<SourceDigestMessage>,
    updatedAt: Instant,
    charsPerToken: Float,
): LiveTailDigest {
    if (messages.isEmpty()) {
        return LiveTailDigest(
            json = RollingSummaryDocument().withUpdatedMeta(0, updatedAt).toJson(),
            messageCount = 0
        )
    }
    val chronologyEpisodes = messages.mapIndexed { index, message ->
        val truncated = message.text.trim().replace("\r\n", "\n").take(280)
        RollingSummaryChronologyEpisode(
            id = stableRollingSummaryId("chronology", "${message.messageId}:$index:$truncated"),
            turnRange = "${index + 1}",
            summary = "[${message.role.uppercase()}] $truncated",
            sourceRoles = listOf(message.role),
            timeRef = message.createdAt.toString(),
            salience = if (index == messages.lastIndex) 0.95 else 0.75,
        ).normalized(index + 1)
    }
    val detailCapsules = messages.mapIndexedNotNull { index, message ->
        buildLiveTailDetailCapsule(message, index + 1)
    }
    val document = RollingSummaryDocument(
        meta = RollingSummaryMeta(
            schemaVersion = 3,
            summaryTurn = messages.size,
            updatedAt = updatedAt.toEpochMilli()
        ),
        chronology = trimChronologyToBudget(
            episodes = chronologyEpisodes,
            charsPerToken = charsPerToken,
            maxTokens = 300
        ),
        detailCapsules = trimDetailCapsulesToBudget(
            capsules = detailCapsules,
            charsPerToken = charsPerToken,
            maxTokens = 280
        )
    )
    return LiveTailDigest(json = document.toJson(), messageCount = messages.size)
}

private fun trimEntriesToBudget(
    entries: List<RollingSummaryEntry>,
    charsPerToken: Float,
    maxTokens: Int,
): List<RollingSummaryEntry> {
    val result = mutableListOf<RollingSummaryEntry>()
    var usedTokens = 0
    entries.forEach { entry ->
        val nextTokens = (entry.text.length / charsPerToken.coerceIn(2.0f, 8.0f)).toInt().coerceAtLeast(1)
        if (usedTokens + nextTokens > maxTokens && result.isNotEmpty()) return@forEach
        result += entry
        usedTokens += nextTokens
    }
    return result.reversed()
}

private fun trimChronologyToBudget(
    episodes: List<RollingSummaryChronologyEpisode>,
    charsPerToken: Float,
    maxTokens: Int,
): List<RollingSummaryChronologyEpisode> {
    val result = mutableListOf<RollingSummaryChronologyEpisode>()
    var usedTokens = 0
    episodes.asReversed().forEach { episode ->
        val nextTokens = (episode.renderForProjection().length / charsPerToken.coerceIn(2.0f, 8.0f))
            .toInt()
            .coerceAtLeast(1)
        if (usedTokens + nextTokens > maxTokens && result.isNotEmpty()) return@forEach
        result += episode
        usedTokens += nextTokens
    }
    return result.asReversed()
}

private fun trimDetailCapsulesToBudget(
    capsules: List<RollingSummaryDetailCapsule>,
    charsPerToken: Float,
    maxTokens: Int,
): List<RollingSummaryDetailCapsule> {
    val result = mutableListOf<RollingSummaryDetailCapsule>()
    var usedTokens = 0
    capsules.sortedByDescending { it.salience }.forEach { capsule ->
        val nextTokens = (capsule.renderForProjection().length / charsPerToken.coerceIn(2.0f, 8.0f))
            .toInt()
            .coerceAtLeast(1)
        if (usedTokens + nextTokens > maxTokens && result.isNotEmpty()) return@forEach
        result += capsule
        usedTokens += nextTokens
    }
    return result
}

private fun buildLiveTailDetailCapsule(
    message: SourceDigestMessage,
    turnIndex: Int,
): RollingSummaryDetailCapsule? {
    val normalized = message.text.trim().replace("\r\n", "\n")
    if (normalized.isBlank()) return null
    val lines = normalized.lines().filter { it.isNotBlank() }
    val kind = when {
        normalized.contains("```") -> "code"
        lines.size >= 4 && lines.all { it.length in 2..36 } -> "poem"
        normalized.startsWith(">") -> "quote"
        normalized.length > 220 -> "longform"
        else -> return null
    }
    val title = when (kind) {
        "code" -> "代码片段"
        "poem" -> "诗歌/分行文本"
        "quote" -> "引用内容"
        else -> "长文本细节"
    }
    val excerptLines = when (kind) {
        "poem" -> lines.take(4).joinToString("\n")
        else -> normalized.take(220)
    }
    return RollingSummaryDetailCapsule(
        id = stableRollingSummaryId("detail_capsules", "${message.messageId}:$kind:$excerptLines"),
        kind = kind,
        title = title,
        summary = normalized.take(140),
        keyExcerpt = excerptLines,
        identifiers = extractLikelyIdentifiers(normalized),
        sourceRoles = listOf(message.role),
        sourceMessageIds = listOf(message.messageId),
        updatedAtTurn = turnIndex,
        salience = if (kind == "code" || kind == "poem") 0.92 else 0.84,
        status = STATUS_ACTIVE,
    ).normalized(turnIndex)
}

private fun extractLikelyIdentifiers(text: String): List<String> {
    return Regex("[A-Za-z0-9_./:-]{3,}")
        .findAll(text)
        .map { it.value.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(12)
        .toList()
}

private fun parseSummaryMeta(element: JsonElement?): RollingSummaryMeta {
    val obj = element as? JsonObject ?: return RollingSummaryMeta()
    return RollingSummaryMeta(
        schemaVersion = obj.stringValue("schema_version", "schemaVersion")?.toIntOrNull() ?: 3,
        summaryTurn = obj.stringValue("summary_turn", "summaryTurn")?.toIntOrNull() ?: 0,
        updatedAt = obj.stringValue("updated_at", "updatedAt")?.toLongOrNull() ?: 0L,
    )
}

private fun normalizeChronologySection(
    element: JsonElement?,
    fallbackTurn: Int,
): List<RollingSummaryChronologyEpisode> {
    if (element == null) return emptyList()
    val normalized = when (element) {
        is JsonArray -> element.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }?.let { text ->
                    RollingSummaryChronologyEpisode(
                        id = stableRollingSummaryId("chronology", text),
                        turnRange = fallbackTurn.takeIf { it > 0 }?.toString().orEmpty(),
                        summary = text,
                    ).normalized(fallbackTurn)
                }

                is JsonObject -> parseChronologyEpisode(item, fallbackTurn)
                else -> null
            }
        }

        is JsonObject -> {
            val nestedWrapper = element.findWrapperElement()
            when {
                nestedWrapper != null -> normalizeChronologySection(nestedWrapper, fallbackTurn)
                else -> listOfNotNull(parseChronologyEpisode(element, fallbackTurn))
            }
        }

        else -> emptyList()
    }
    return normalized
        .filter { it.summary.isNotBlank() }
        .distinctBy { it.id }
}

private fun parseChronologyEpisode(
    obj: JsonObject,
    fallbackTurn: Int,
): RollingSummaryChronologyEpisode? {
    val summary = obj.stringValue("summary", "text", "content", "value")?.trim().orEmpty()
    if (summary.isBlank()) return null
    return RollingSummaryChronologyEpisode(
        id = obj.stringValue("id", "key").orEmpty(),
        turnRange = obj.stringValue("turn_range", "turnRange").orEmpty(),
        summary = summary,
        sourceRoles = obj.valueForKeys("source_roles", "sourceRoles", "roles").asStringList(),
        timeRef = obj.stringValue("time_ref", "timeRef", "timestamp"),
        relatedDetailIds = obj.valueForKeys("related_detail_ids", "relatedDetailIds").asStringList(),
        salience = obj.stringValue("salience", "importance", "priority")?.toDoubleOrNull() ?: 0.5,
    ).normalized(fallbackTurn)
}

private fun normalizeDetailCapsulesSection(
    element: JsonElement?,
    fallbackTurn: Int,
): List<RollingSummaryDetailCapsule> {
    if (element == null) return emptyList()
    val normalized = when (element) {
        is JsonArray -> element.mapNotNull { item ->
            when (item) {
                is JsonObject -> parseDetailCapsule(item, fallbackTurn)
                is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }?.let { text ->
                    RollingSummaryDetailCapsule(
                        id = stableRollingSummaryId("detail_capsules", text),
                        title = text.take(48),
                        summary = text,
                        keyExcerpt = text.take(160),
                    ).normalized(fallbackTurn)
                }

                else -> null
            }
        }

        is JsonObject -> {
            val nestedWrapper = element.findWrapperElement()
            when {
                nestedWrapper != null -> normalizeDetailCapsulesSection(nestedWrapper, fallbackTurn)
                else -> listOfNotNull(parseDetailCapsule(element, fallbackTurn))
            }
        }

        else -> emptyList()
    }
    return normalized
        .filter { it.title.isNotBlank() || it.summary.isNotBlank() || it.keyExcerpt.isNotBlank() }
        .distinctBy { it.id }
}

private fun parseDetailCapsule(
    obj: JsonObject,
    fallbackTurn: Int,
): RollingSummaryDetailCapsule? {
    val title = obj.stringValue("title", "name").orEmpty()
    val summary = obj.stringValue("summary", "text", "content", "value").orEmpty()
    val excerpt = obj.stringValue("key_excerpt", "keyExcerpt", "excerpt", "quote").orEmpty()
    if (title.isBlank() && summary.isBlank() && excerpt.isBlank()) return null
    return RollingSummaryDetailCapsule(
        id = obj.stringValue("id", "key").orEmpty(),
        kind = obj.stringValue("kind", "type").orEmpty(),
        title = title,
        summary = summary,
        keyExcerpt = excerpt,
        identifiers = obj.valueForKeys("identifiers", "entity_keys", "entityKeys").asStringList(),
        sourceRoles = obj.valueForKeys("source_roles", "sourceRoles", "roles").asStringList(),
        sourceMessageIds = obj.valueForKeys("source_message_ids", "sourceMessageIds", "message_ids").asStringList(),
        locator = obj.stringValue("locator", "location", "path", "reference"),
        updatedAtTurn = obj.stringValue("updated_at_turn", "updatedAtTurn")?.toIntOrNull() ?: fallbackTurn,
        salience = obj.stringValue("salience", "importance", "priority")?.toDoubleOrNull() ?: 0.5,
        status = obj.stringValue("status", "state") ?: STATUS_ACTIVE,
    ).normalized(fallbackTurn)
}

private fun normalizeSummarySection(
    sectionKey: String,
    element: JsonElement?,
    fallbackTurn: Int,
): List<RollingSummaryEntry> {
    if (element == null) return emptyList()

    val normalized = when (element) {
        is JsonArray -> element.flatMap { item ->
            when (item) {
                is JsonPrimitive -> textToEntries(sectionKey, item.contentOrNull.orEmpty(), fallbackTurn)
                is JsonObject -> {
                    val nestedWrapper = item.findWrapperElement()
                    when {
                        nestedWrapper != null -> normalizeSummarySection(sectionKey, nestedWrapper, fallbackTurn)
                        else -> listOfNotNull(parseSummaryEntry(sectionKey, item, fallbackTurn))
                    }
                }
                else -> emptyList()
            }
        }

        is JsonObject -> {
            val nestedWrapper = element.findWrapperElement()
            when {
                nestedWrapper != null -> normalizeSummarySection(sectionKey, nestedWrapper, fallbackTurn)
                else -> listOfNotNull(parseSummaryEntry(sectionKey, element, fallbackTurn))
                    .ifEmpty {
                        element.collectVisibleStrings().flatMap { text ->
                            textToEntries(sectionKey, text, fallbackTurn)
                        }
                    }
            }
        }

        is JsonPrimitive -> textToEntries(sectionKey, element.contentOrNull.orEmpty(), fallbackTurn)
        else -> emptyList()
    }

    return normalized
        .filter { it.text.isNotBlank() }
        .distinctBy { it.id }
}

private fun parseSummaryEntry(
    sectionKey: String,
    obj: JsonObject,
    fallbackTurn: Int,
): RollingSummaryEntry? {
    val text = obj.stringValue("text", "content", "summary", "value", "item", "note")?.trim().orEmpty()
    if (text.isBlank()) return null

    return RollingSummaryEntry(
        id = obj.stringValue("id", "key").orEmpty(),
        text = text,
        status = obj.stringValue("status", "state") ?: STATUS_ACTIVE,
        tags = obj.valueForKeys("tags", "keywords", "labels").asStringList(),
        entityKeys = obj.valueForKeys("entity_keys", "entityKeys", "entities").asStringList(),
        salience = obj.stringValue("salience", "importance", "priority")?.toDoubleOrNull() ?: 0.5,
        updatedAtTurn = obj.stringValue("updated_at_turn", "updatedAtTurn")?.toIntOrNull() ?: fallbackTurn,
        sourceRoles = obj.valueForKeys("source_roles", "sourceRoles", "roles").asStringList(),
        taskState = obj.stringValue("task_state", "taskState"),
        reason = obj.stringValue("reason", "rationale", "because"),
        relatedIds = obj.valueForKeys("related_ids", "relatedIds").asStringList(),
        scope = obj.stringValue("scope"),
        blocker = obj.stringValue("blocker", "blockingReason"),
        kind = obj.stringValue("kind", "type"),
        locator = obj.stringValue("locator", "location", "path", "reference"),
        changeType = obj.stringValue("change_type", "changeType"),
        timeRef = obj.stringValue("time_ref", "timeRef", "timestamp"),
    ).normalized(sectionKey, fallbackTurn)
}

private fun textToEntries(
    sectionKey: String,
    rawText: String,
    fallbackTurn: Int,
): List<RollingSummaryEntry> {
    val entries = rawText.replace("\r\n", "\n")
        .lines()
        .mapNotNull(::sanitizeFallbackLine)
        .ifEmpty {
            listOfNotNull(sanitizeFallbackLine(rawText))
        }
        .distinct()
        .take(8)

    return entries.mapIndexed { index, text ->
        RollingSummaryEntry(
            id = stableRollingSummaryId(sectionKey, text),
            text = text,
            status = defaultStatusForSection(sectionKey),
            salience = (0.95 - index * 0.08).coerceAtLeast(0.45),
            updatedAtTurn = fallbackTurn.takeIf { it > 0 } ?: (index + 1),
        ).normalized(sectionKey, fallbackTurn)
    }
}

private fun defaultStatusForSection(sectionKey: String): String {
    return if (sectionKey == "timeline") STATUS_HISTORICAL else STATUS_ACTIVE
}

private fun parseJsonObjectLenient(rawSummary: String): JsonObject? {
    val trimmed = rawSummary.trim()
    if (trimmed.isBlank()) return null

    val candidates = buildList {
        add(trimmed)
        add(stripCodeFenceWrapper(trimmed))
        extractFirstBalancedJsonObject(trimmed)?.let(::add)
        extractFirstBalancedJsonObject(stripCodeFenceWrapper(trimmed))?.let(::add)
    }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    candidates.forEach { candidate ->
        runCatching {
            JsonInstant.parseToJsonElement(candidate).jsonObject
        }.getOrNull()?.let { return it }
    }
    return null
}

private fun fallbackDocumentFromJson(
    obj: JsonObject,
    fallbackTurn: Int,
): RollingSummaryDocument? {
    val texts = buildList {
        ALL_SUMMARY_SECTIONS.forEach { sectionKey ->
            val sectionElement = obj.findSectionElement(sectionKey)
            if (sectionElement != null) {
                addAll(sectionElement.collectVisibleStrings())
            }
        }
        if (isEmpty()) {
            obj.entries
                .filterNot { (key, _) -> key in FALLBACK_JSON_META_KEYS }
                .forEach { (_, value) -> addAll(value.collectVisibleStrings()) }
        }
    }
    return buildFallbackTimelineDocument(texts, fallbackTurn)
}

private fun fallbackDocumentFromRawText(rawSummary: String): RollingSummaryDocument {
    val texts = stripCodeFenceWrapper(rawSummary)
        .replace("\r\n", "\n")
        .lines()
        .mapNotNull(::sanitizeFallbackLine)
    return buildFallbackTimelineDocument(texts, fallbackTurn = 0) ?: RollingSummaryDocument()
}

private fun buildFallbackTimelineDocument(
    texts: List<String>,
    fallbackTurn: Int,
): RollingSummaryDocument? {
    val timelineEntries = texts
        .map(String::trim)
        .filter { it.isNotBlank() }
        .distinct()
        .take(8)
        .mapIndexed { index, text ->
            RollingSummaryEntry(
                id = stableRollingSummaryId("timeline", text),
                text = text,
                status = STATUS_HISTORICAL,
                salience = (0.9 - index * 0.08).coerceAtLeast(0.4),
                updatedAtTurn = fallbackTurn.takeIf { it > 0 } ?: (index + 1),
            ).normalized("timeline", fallbackTurn)
        }
    if (timelineEntries.isEmpty()) return null
    return RollingSummaryDocument(timeline = timelineEntries)
}

private fun sanitizeFallbackLine(line: String): String? {
    var normalized = line.trim()
        .removePrefix("-")
        .removePrefix("*")
        .removePrefix("•")
        .trim()
        .removeSuffix(",")
        .trim()

    if (normalized.isBlank()) return null
    if (normalized == "```") return null

    Regex("^\"?[A-Za-z_][A-Za-z0-9_]*\"?\\s*:\\s*\"(.+)\"$").matchEntire(normalized)?.let { match ->
        normalized = match.groupValues[1].trim()
    }
    Regex("^\"?[A-Za-z_][A-Za-z0-9_]*\"?\\s*:\\s*(.+)$").matchEntire(normalized)?.let { match ->
        val candidate = match.groupValues[1].trim().trim('"').removeSuffix(",").trim()
        if (candidate.isNotBlank()) {
            normalized = candidate
        }
    }

    normalized = normalized.removeSurrounding("\"").trim()
    val simplified = normalized.removeSuffix(":").trim().trim('"')
    if (simplified in FALLBACK_JSON_META_KEYS || simplified in ROLLING_SUMMARY_SECTIONS) return null
    if (!normalized.any { it.isLetterOrDigit() }) return null
    if (normalized.length < 2) return null
    return normalized
}

private fun stripCodeFenceWrapper(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("```")) return trimmed
    val lines = trimmed.lines()
    if (lines.size < 2) return trimmed
    val lastFenceIndex = lines.indexOfLast { it.trim() == "```" }
    if (lastFenceIndex <= 0) return trimmed
    return lines.subList(1, lastFenceIndex).joinToString("\n").trim()
}

private fun extractFirstBalancedJsonObject(raw: String): String? {
    var startIndex = -1
    var depth = 0
    var inString = false
    var escaping = false

    raw.forEachIndexed { index, char ->
        if (startIndex == -1 && char != '{') return@forEachIndexed

        if (startIndex == -1) {
            startIndex = index
            depth = 1
            return@forEachIndexed
        }

        if (inString) {
            when {
                escaping -> escaping = false
                char == '\\' -> escaping = true
                char == '"' -> inString = false
            }
            return@forEachIndexed
        }

        when (char) {
            '"' -> inString = true
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) {
                    return raw.substring(startIndex, index + 1)
                }
            }
        }
    }
    return null
}

private fun JsonObject.findSectionElement(sectionKey: String): JsonElement? {
    return SECTION_KEY_ALIASES.getValue(sectionKey).firstNotNullOfOrNull { alias -> this[alias] }
}

private fun JsonObject.findWrapperElement(): JsonElement? {
    return ENTRY_WRAPPER_KEYS.firstNotNullOfOrNull { key -> this[key] }
}

private fun JsonObject.valueForKeys(vararg keys: String): JsonElement? {
    return keys.firstNotNullOfOrNull { key -> this[key] }
}

private fun JsonObject.stringValue(vararg keys: String): String? {
    return valueForKeys(*keys)?.jsonPrimitive?.contentOrNull
}

private fun JsonElement?.asStringList(): List<String> {
    return when (this) {
        is JsonArray -> this.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> element.stringValue("text", "value", "name", "label")
                else -> null
            }
        }
        is JsonPrimitive -> listOfNotNull(this.contentOrNull)
        is JsonObject -> this.collectVisibleStrings()
        else -> emptyList()
    }.map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun JsonElement.collectVisibleStrings(): List<String> {
    return when (this) {
        is JsonPrimitive -> listOfNotNull(sanitizeFallbackLine(contentOrNull.orEmpty()))
        is JsonArray -> this.flatMap { it.collectVisibleStrings() }
        is JsonObject -> {
            findWrapperElement()?.collectVisibleStrings()
                ?: entries
                    .filterNot { (key, _) -> key in FALLBACK_JSON_META_KEYS }
                    .flatMap { (_, value) -> value.collectVisibleStrings() }
        }
        else -> emptyList()
    }
}

private fun String.toSummaryTitle(): String = when (this) {
    "facts" -> "\u5173\u952e\u4fe1\u606f"
    "preferences" -> "\u504f\u597d"
    "tasks" -> "\u5f53\u524d\u4efb\u52a1"
    "decisions" -> "\u5173\u952e\u51b3\u7b56"
    "constraints" -> "\u7ea6\u675f"
    "open_questions" -> "\u5f85\u786e\u8ba4"
    "artifacts" -> "\u4ea7\u7269"
    "timeline" -> "\u6700\u8fd1\u53d8\u5316"
    "chronology" -> "\u53d9\u4e8b\u4e3b\u7ebf"
    "detail_capsules" -> "\u5173\u952e\u7ec6\u8282"
    else -> this
}

private val VALID_ENTRY_STATUSES = setOf(
    STATUS_ACTIVE,
    STATUS_DONE,
    STATUS_BLOCKED,
    STATUS_SUPERSEDED,
    STATUS_HISTORICAL,
)

private val VALID_SOURCE_ROLES = setOf("user", "assistant")

data class SourceDigestMessage(
    val messageId: String,
    val role: String,
    val text: String,
    val createdAt: Instant,
)
