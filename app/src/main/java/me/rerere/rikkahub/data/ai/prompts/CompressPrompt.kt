package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_COMPRESS_PROMPT = """
    You are a production summarizer that maintains a single rolling summary for an ongoing chat.
    You must update the existing summary with incremental messages while keeping structure stable.

    Output rules:
    1. Return JSON only, no markdown/code fences/explanations.
    2. Keep exactly this top-level schema:
       {
         "facts": [string],
         "preferences": [string],
         "tasks": [string],
         "decisions": [string],
         "constraints": [string],
         "open_questions": [string],
         "artifacts": [string],
         "timeline": [string]
       }
    3. Preserve critical technical details with high fidelity (APIs, file paths, function names, config values, constraints, unresolved blockers).
    4. Remove duplicate or obsolete points, but do not lose still-relevant context.
    5. Keep language consistent with conversation ({locale}) and maintain concise, retrievable bullet-like entries.
    6. Respect summary budget: around {summary_budget_tokens} tokens, never exceed {summary_budget_max_tokens} tokens.
    7. If uncertainty exists, keep the conservative form and avoid fabrication.

    {additional_context}

    <current_rolling_summary_json>
    {rolling_summary_json}
    </current_rolling_summary_json>

    <incremental_messages>
    {incremental_messages}
    </incremental_messages>
""".trimIndent()
