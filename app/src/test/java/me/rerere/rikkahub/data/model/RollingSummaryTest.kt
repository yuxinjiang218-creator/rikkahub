package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RollingSummaryTest {

    @Test
    fun `normalizeRollingSummaryJson upgrades legacy string arrays to object schema`() {
        val legacyJson = JsonInstant.encodeToString(
            buildJsonObject {
                put(
                    "facts",
                    buildJsonArray {
                        add(JsonPrimitive("Database port is 6432"))
                    }
                )
                put(
                    "tasks",
                    buildJsonArray {
                        add(JsonPrimitive("Polish the compression summary card UI"))
                    }
                )
            }
        )

        val normalized = parseRollingSummaryDocument(
            normalizeRollingSummaryJson(
                rawSummary = legacyJson,
                summaryTurn = 15,
                updatedAt = Instant.ofEpochMilli(2000)
            )
        )

        assertEquals(3, normalized.meta.schemaVersion)
        assertEquals(15, normalized.meta.summaryTurn)
        assertEquals("Database port is 6432", normalized.facts.first().text)
        assertTrue(normalized.facts.first().id.startsWith("facts_"))
        assertEquals("active", normalized.tasks.first().status)
    }

    @Test
    fun `parseRollingSummaryDocument accepts fenced json aliases and wrapped section items`() {
        val raw = """
            ```json
            {
              "meta": {
                "schemaVersion": 3,
                "summaryTurn": 9,
                "updatedAt": 1700000000000
              },
              "facts": [
                {
                  "content": "Database port is 6432",
                  "entityKeys": ["db.port"],
                  "sourceRoles": "assistant",
                  "updatedAtTurn": 9
                }
              ],
              "openQuestions": {
                "items": [
                  {
                    "summary": "Need a smoke test on the new recall flow",
                    "sourceRoles": ["user"]
                  }
                ]
              }
            }
            ```
        """.trimIndent()

        val parsed = parseRollingSummaryDocument(raw)

        assertEquals(9, parsed.meta.summaryTurn)
        assertEquals("Database port is 6432", parsed.facts.single().text)
        assertEquals(listOf("db.port"), parsed.facts.single().entityKeys)
        assertEquals(listOf("assistant"), parsed.facts.single().sourceRoles)
        assertEquals(
            "Need a smoke test on the new recall flow",
            parsed.openQuestions.single().text
        )
        assertEquals(listOf("user"), parsed.openQuestions.single().sourceRoles)
    }

    @Test
    fun `current view projection includes chronology and referenced detail capsules`() {
        val summary = RollingSummaryDocument(
            facts = listOf(
                RollingSummaryEntry(
                    id = "fact_1",
                    text = "User wrote a windmill poem",
                    relatedIds = listOf("capsule_1"),
                    salience = 0.9
                )
            ),
            chronology = listOf(
                RollingSummaryChronologyEpisode(
                    id = "chrono_1",
                    turnRange = "12-14",
                    summary = "The user shared a poem and asked for revision ideas",
                    relatedDetailIds = listOf("capsule_1"),
                    salience = 0.8
                )
            ),
            detailCapsules = listOf(
                RollingSummaryDetailCapsule(
                    id = "capsule_1",
                    kind = "poem",
                    title = "大风车诗歌",
                    summary = "A short poem about a windmill and childhood memories",
                    keyExcerpt = "大风车吱呀转，童年的风还在吹",
                    salience = 0.92
                )
            )
        )

        val projection = summary.toCurrentViewProjection()
        val snapshot = summary.toSummarySnapshot()

        assertTrue(projection.contains("[chronology]"))
        assertTrue(projection.contains("[detail_capsules]"))
        assertTrue(snapshot.sections.any { it.key == "chronology" })
        assertTrue(snapshot.sections.any { it.key == "detail_capsules" })
    }

    @Test
    fun `current view projection includes historical timeline when it is the only available summary`() {
        val summary = RollingSummaryDocument(
            timeline = listOf(
                RollingSummaryEntry(
                    id = "timeline_old",
                    text = "Switched the service port from 5432 to 6432",
                    status = "historical",
                    salience = 0.9,
                    timeRef = "2026-03-06"
                )
            )
        )

        val projection = summary.toCurrentViewProjection()
        val snapshot = summary.toSummarySnapshot()

        assertTrue(projection.contains("Switched the service port from 5432 to 6432"))
        assertFalse(snapshot.preview.isBlank())
        assertEquals("timeline", snapshot.sections.single().key)
    }

    @Test
    fun `parseRollingSummaryDocument falls back to timeline entries for plain text output`() {
        val raw = """
            Key constraints:
            - Keep the latest 6 visible messages outside compression
            - Use the embedding model before rebuilding indexes
        """.trimIndent()

        val parsed = parseRollingSummaryDocument(raw)

        assertTrue(parsed.timeline.isNotEmpty())
        assertTrue(parsed.toSummarySnapshot().preview.contains("Keep the latest 6 visible messages"))
    }
}
