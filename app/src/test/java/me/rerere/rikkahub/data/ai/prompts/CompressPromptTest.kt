package me.rerere.rikkahub.data.ai.prompts

import org.junit.Assert.assertFalse
import org.junit.Test

class CompressPromptTest {
    @Test
    fun `dialogue prompt hides token budget placeholders`() {
        assertFalse(DEFAULT_DIALOGUE_COMPRESS_PROMPT.contains("incremental_input_tokens"))
        assertFalse(DEFAULT_DIALOGUE_COMPRESS_PROMPT.contains("target_output_tokens"))
        assertFalse(DEFAULT_DIALOGUE_COMPRESS_PROMPT.contains("hard_cap_tokens"))
    }
}
