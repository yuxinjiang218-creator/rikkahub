package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.CompressionEvent
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChatMessageWindowTest {

    @Test
    fun `createInitialChatMessageWindow keeps the latest 60 nodes`() {
        val nodes = (0 until 100).map { index ->
            UIMessage.user("message-$index").toMessageNode()
        }

        val window = createInitialChatMessageWindow(nodes)

        assertEquals(100, window.totalStableCount)
        assertEquals(40, window.loadedStartIndex)
        assertEquals(60, window.loadedStableNodes.size)
        assertEquals("message-40", window.loadedStableNodes.first().currentMessage.toText())
        assertEquals("message-99", window.loadedStableNodes.last().currentMessage.toText())
        assertTrue(window.hasOlder)
    }

    @Test
    fun `syncChatMessageWindowWithNodes keeps the window anchored to bottom when new nodes append`() {
        val initialNodes = (0 until 60).map { index ->
            UIMessage.user("message-$index").toMessageNode()
        }
        val current = createInitialChatMessageWindow(initialNodes)
        val expandedNodes = (0 until 65).map { index ->
            UIMessage.user("message-$index").toMessageNode()
        }

        val updated = syncChatMessageWindowWithNodes(current, expandedNodes)

        assertEquals(65, updated.totalStableCount)
        assertEquals(5, updated.loadedStartIndex)
        assertEquals(60, updated.loadedStableNodes.size)
        assertEquals("message-5", updated.loadedStableNodes.first().currentMessage.toText())
        assertEquals("message-64", updated.loadedStableNodes.last().currentMessage.toText())
        assertTrue(updated.hasOlder)
    }

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
            loadedStartIndex = 40,
            loadedNodeCount = 20,
        )

        assertEquals(listOf(2L), localized.map { it.id })
        assertEquals(5, localized.single().boundaryIndex)
    }

    @Test
    fun `computeFocusedWindowStart keeps target inside the focused window`() {
        val startIndex = computeFocusedWindowStart(
            totalCount = 200,
            targetIndex = 150,
            windowSize = 80,
            contextBefore = 20,
        )

        assertEquals(120, startIndex)
        assertTrue(150 in startIndex until (startIndex + 80))
        assertFalse(150 < startIndex)
    }

    @Test
    fun `shouldLoadOlderMessages triggers when first visible message is near window start`() {
        assertTrue(
            shouldLoadOlderMessages(
                firstVisibleMessageGlobalIndex = 40,
                loadedStartIndex = 40,
            )
        )
        assertTrue(
            shouldLoadOlderMessages(
                firstVisibleMessageGlobalIndex = 42,
                loadedStartIndex = 40,
            )
        )
        assertFalse(
            shouldLoadOlderMessages(
                firstVisibleMessageGlobalIndex = 43,
                loadedStartIndex = 40,
            )
        )
        assertFalse(
            shouldLoadOlderMessages(
                firstVisibleMessageGlobalIndex = null,
                loadedStartIndex = 40,
            )
        )
    }
}
