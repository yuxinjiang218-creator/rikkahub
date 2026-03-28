package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MAX_CAUSE_DEPTH = 8
private const val MAX_CAUSE_MESSAGE_LENGTH = 2_000
private const val MAX_ERROR_SUMMARY_LENGTH = 8_000
private const val MAX_STACK_TRACE_LENGTH = 24_000

internal fun buildToolExecutionFailurePayload(error: Throwable) = buildJsonObject {
    val causeChain = error.causeChain()
    val rootCause = causeChain.lastOrNull() ?: error
    put("error", error.buildErrorSummary())
    put("error_code", "TOOL_EXECUTION_FAILED")
    put("exception_type", error.javaClass.name)
    put("root_cause_type", rootCause.javaClass.name)
    put("root_cause_message", rootCause.message.orEmpty().take(MAX_CAUSE_MESSAGE_LENGTH))
    put("stack_trace", error.stackTraceToString().take(MAX_STACK_TRACE_LENGTH))
    put(
        "cause_chain",
        buildJsonArray {
            causeChain.forEach { cause ->
                add(
                    buildJsonObject {
                        put("type", cause.javaClass.name)
                        put("message", cause.message.orEmpty().take(MAX_CAUSE_MESSAGE_LENGTH))
                    }
                )
            }
        }
    )
}

private fun Throwable.buildErrorSummary(): String {
    return causeChain()
        .joinToString(separator = " <- ") { cause ->
            "${cause.javaClass.name}: ${cause.message.orEmpty().ifBlank { "<no message>" }}"
        }
        .take(MAX_ERROR_SUMMARY_LENGTH)
}

private fun Throwable.causeChain(): List<Throwable> {
    val chain = mutableListOf<Throwable>()
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = this
    while (current != null && visited.add(current) && chain.size < MAX_CAUSE_DEPTH) {
        chain += current
        current = current.cause
    }
    return chain
}
