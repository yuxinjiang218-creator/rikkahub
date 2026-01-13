package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_COMPRESSION_PROMPT = """
    Please compress the following conversation messages into a concise summary.
    The summary should:
    1. Preserve key information and important context
    2. Be concise and clear
    3. Use the same language as the original messages
    4. Maintain chronological flow of events

    Messages to compress:
    {messages}

    Provide only the summary without any additional commentary.
""".trimIndent()
