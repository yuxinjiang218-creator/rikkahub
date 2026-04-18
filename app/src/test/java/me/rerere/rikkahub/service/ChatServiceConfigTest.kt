package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatServiceConfigTest {
    @Test
    fun `dialogue summary output cap stays intentionally wide`() {
        val holder = Class.forName("me.rerere.rikkahub.service.ChatCompressionServiceKt")
        val field = holder.getDeclaredField("CHAT_COMPRESSION_DIALOGUE_SUMMARY_MAX_OUTPUT_TOKENS")
        field.isAccessible = true

        assertEquals(65_536, field.getInt(null))
    }
}
