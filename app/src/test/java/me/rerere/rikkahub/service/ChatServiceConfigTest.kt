package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatServiceConfigTest {
    @Test
    fun `dialogue summary output cap stays intentionally wide`() {
        assertEquals(65_536, DIALOGUE_SUMMARY_MAX_OUTPUT_TOKENS)
    }
}
