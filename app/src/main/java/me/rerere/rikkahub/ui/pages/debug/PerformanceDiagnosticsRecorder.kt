package me.rerere.rikkahub.ui.pages.debug

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

class PerformanceDiagnosticsRecorder {
    private val mutex = Mutex()
    private val events = ArrayDeque<DiagnosticEvent>()

    @Volatile
    private var enabled = false

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            events.clear()
        }
    }

    fun isEnabled(): Boolean = enabled

    fun record(
        category: String,
        detail: String,
        conversationId: Uuid? = null,
    ) {
        if (!enabled) return
        val event = DiagnosticEvent(
            timestampMs = System.currentTimeMillis(),
            category = category,
            detail = detail,
            conversationId = conversationId,
        )
        synchronized(events) {
            events.addLast(event)
            while (events.size > MAX_EVENTS) {
                events.removeFirst()
            }
        }
    }

    suspend fun snapshot(sinceMs: Long? = null, limit: Int = 80): List<DiagnosticEvent> {
        return mutex.withLock {
            synchronized(events) {
                events
                    .filter { sinceMs == null || it.timestampMs >= sinceMs }
                    .takeLast(limit)
                    .toList()
            }
        }
    }

    companion object {
        private const val MAX_EVENTS = 240
    }
}
