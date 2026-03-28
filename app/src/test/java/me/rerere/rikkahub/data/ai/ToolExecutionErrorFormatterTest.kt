package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionErrorFormatterTest {

    @Test
    fun `buildToolExecutionFailurePayload keeps cause chain and stack trace`() {
        val error = IllegalStateException(
            "Callable threw exception",
            IllegalArgumentException("database is locked")
        )

        val payload = buildToolExecutionFailurePayload(error)

        assertEquals("TOOL_EXECUTION_FAILED", payload["error_code"]?.jsonPrimitive?.content)
        assertEquals(IllegalStateException::class.java.name, payload["exception_type"]?.jsonPrimitive?.content)
        assertEquals(IllegalArgumentException::class.java.name, payload["root_cause_type"]?.jsonPrimitive?.content)
        assertEquals("database is locked", payload["root_cause_message"]?.jsonPrimitive?.content)
        assertTrue(
            payload["error"]?.jsonPrimitive?.content.orEmpty()
                .contains("IllegalArgumentException: database is locked")
        )
        assertTrue(
            payload["stack_trace"]?.jsonPrimitive?.content.orEmpty()
                .contains(IllegalStateException::class.java.name)
        )
        assertEquals(2, payload["cause_chain"]?.jsonArray?.size)
    }
}
