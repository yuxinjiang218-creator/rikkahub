package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.model.CompressionEvent
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.ui.components.message.ChatMessageRenderModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ChatTimelineHelpersTest {

    @Test
    fun `localizeCompressionEvents keeps only events inside the loaded window`() {
        val createdAt = Instant.parse("2026-04-13T04:00:00Z")
        val events = listOf(
            CompressionEvent(id = 1L, boundaryIndex = 10, createdAt = createdAt),
            CompressionEvent(id = 2L, boundaryIndex = 45, createdAt = createdAt),
            CompressionEvent(id = 3L, boundaryIndex = 70, createdAt = createdAt),
        )

        val localized = localizeCompressionEvents(
            events = events,
            totalStableCount = 100,
            startIndex = 40,
            nodeCount = 20,
        )

        assertEquals(listOf(2L), localized.map { it.id })
        assertEquals(5, localized.single().boundaryIndex)
    }

    @Test
    fun `renderedListIndexForMessage accounts for inserted compression cards`() {
        val localizedEvents = listOf(
            CompressionEvent(id = 1L, boundaryIndex = 0, createdAt = Instant.EPOCH),
            CompressionEvent(id = 2L, boundaryIndex = 2, createdAt = Instant.EPOCH),
            CompressionEvent(id = 3L, boundaryIndex = 2, createdAt = Instant.EPOCH),
        )

        assertEquals(1, renderedListIndexForMessage(localMessageIndex = 0, localizedEvents = localizedEvents))
        assertEquals(2, renderedListIndexForMessage(localMessageIndex = 1, localizedEvents = localizedEvents))
        assertEquals(5, renderedListIndexForMessage(localMessageIndex = 2, localizedEvents = localizedEvents))
    }

    @Test
    fun `findCompressionListIndex returns rendered compression positions`() {
        val localizedEvents = listOf(
            CompressionEvent(id = 10L, boundaryIndex = 0, createdAt = Instant.EPOCH),
            CompressionEvent(id = 20L, boundaryIndex = 2, createdAt = Instant.EPOCH),
        )

        assertEquals(0, findCompressionListIndex(eventId = 10L, localizedEvents = localizedEvents, messageCount = 3))
        assertEquals(3, findCompressionListIndex(eventId = 20L, localizedEvents = localizedEvents, messageCount = 3))
        assertNull(findCompressionListIndex(eventId = 99L, localizedEvents = localizedEvents, messageCount = 3))
    }

    @Test
    fun `chat timeline listIndexForMessage uses compression offsets`() {
        val timelineState = ChatTimelineUiState(
            messageItems = List(3) { index -> messageItem(index) },
            compressionItems = listOf(
                ChatCompressionBoundaryItem(
                    boundaryIndex = 0,
                    model = ChatCompressionBoundaryModel(
                        event = CompressionEvent(id = 10L, boundaryIndex = 0, createdAt = Instant.EPOCH),
                        isLatest = false,
                        ledgerStatus = null,
                        ledgerError = null,
                    )
                ),
                ChatCompressionBoundaryItem(
                    boundaryIndex = 2,
                    model = ChatCompressionBoundaryModel(
                        event = CompressionEvent(id = 20L, boundaryIndex = 2, createdAt = Instant.EPOCH),
                        isLatest = true,
                        ledgerStatus = null,
                        ledgerError = null,
                    )
                ),
            )
        )

        assertEquals(1, timelineState.listIndexForMessage(0))
        assertEquals(2, timelineState.listIndexForMessage(1))
        assertEquals(4, timelineState.listIndexForMessage(2))
    }

    @Test
    fun `chat timeline listIndexForCompressionEvent returns inserted card position`() {
        val timelineState = ChatTimelineUiState(
            messageItems = List(3) { index -> messageItem(index) },
            compressionItems = listOf(
                ChatCompressionBoundaryItem(
                    boundaryIndex = 0,
                    model = ChatCompressionBoundaryModel(
                        event = CompressionEvent(id = 10L, boundaryIndex = 0, createdAt = Instant.EPOCH),
                        isLatest = false,
                        ledgerStatus = null,
                        ledgerError = null,
                    )
                ),
                ChatCompressionBoundaryItem(
                    boundaryIndex = 2,
                    model = ChatCompressionBoundaryModel(
                        event = CompressionEvent(id = 20L, boundaryIndex = 2, createdAt = Instant.EPOCH),
                        isLatest = true,
                        ledgerStatus = null,
                        ledgerError = null,
                    )
                ),
            )
        )

        assertEquals(0, timelineState.listIndexForCompressionEvent(10L))
        assertEquals(3, timelineState.listIndexForCompressionEvent(20L))
        assertNull(timelineState.listIndexForCompressionEvent(99L))
    }

    @Test
    fun `chat timeline listIndexForNode keeps node mapping stable with compression cards`() {
        val first = messageItem(0)
        val second = messageItem(1)
        val third = messageItem(2)
        val timelineState = ChatTimelineUiState(
            messageItems = listOf(first, second, third),
            compressionItems = listOf(
                ChatCompressionBoundaryItem(
                    boundaryIndex = 0,
                    model = ChatCompressionBoundaryModel(
                        event = CompressionEvent(id = 10L, boundaryIndex = 0, createdAt = Instant.EPOCH),
                        isLatest = false,
                        ledgerStatus = null,
                        ledgerError = null,
                    )
                ),
                ChatCompressionBoundaryItem(
                    boundaryIndex = 2,
                    model = ChatCompressionBoundaryModel(
                        event = CompressionEvent(id = 20L, boundaryIndex = 2, createdAt = Instant.EPOCH),
                        isLatest = true,
                        ledgerStatus = null,
                        ledgerError = null,
                    )
                ),
            )
        )

        assertEquals(1, timelineState.listIndexForNode(first.node.id))
        assertEquals(2, timelineState.listIndexForNode(second.node.id))
        assertEquals(4, timelineState.listIndexForNode(third.node.id))
    }

    private fun messageItem(index: Int): ChatMessageItemModel {
        val message = if (index % 2 == 0) UIMessage.user("u$index") else UIMessage.assistant("a$index")
        val node = MessageNode(messages = listOf(message))
        return ChatMessageItemModel(
            renderModel = ChatMessageRenderModel(
                node = node,
                message = message,
            ),
            globalIndex = index,
            isLastMessage = index == 2,
        )
    }
}
