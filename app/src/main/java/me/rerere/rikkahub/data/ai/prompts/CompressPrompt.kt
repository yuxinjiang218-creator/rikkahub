package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_DIALOGUE_COMPRESS_PROMPT = """
    你要更新一份会被继续注入当前对话上下文的连续摘要。

    输入只有两部分：
    1. 之前已经存在的主摘要
    2. 主摘要之后新增的消息

    你的任务是输出“更新后的完整主摘要”，不是续写附录，不是会议纪要，也不是把新增消息重抄一遍。

    这份主摘要必须优先保住：
    - 当前目标
    - 仍然生效的约束、禁令、口径修正
    - 尚未解决的问题
    - 最近真正推进了什么
    - 最自然的下一步
    - 一旦丢失就很难准确恢复的关键锚点

    关键规则：
    1. 只输出纯文本，不要 markdown 代码块，不要解释你的处理过程。
    2. 旧主摘要仍然有效，新增消息会对它进行补充、修正、覆盖或删除过时内容。
    3. 输出必须是“新的完整版本”，不是旧摘要后面再加一段。
    4. 不要为了看起来简洁，就抹掉会影响下一轮回复的鲜活细节。
    5. 精确内容要高保真保留，例如：路径、命令、端口、报错、文件名、配置键、人物名、场景设定、用户的明确否定或修正。
    6. 信息不确定时显式标记【未确认】或【待验证】，不要脑补成事实。
    7. 可以删除重复、过时、已解决且不再影响后续的内容，但删除顺序必须是：
       - 先删重复
       - 再删过时枝节
       - 再压缩低价值背景
       - 最后才允许裁掉低优先级锚点
    8. 绝对不要先删“当前工作态”“未决问题”“最近推进”“下一步”“关键锚点”。

    你必须严格使用以下结构：

    [当前工作态]
    当前目标：
    活跃约束：
    未决问题：
    最近推进：
    下一步：

    [连续主线]
    - 使用项目符号

    [时间推进]
    - 使用项目符号

    [关键锚点]
    - 使用项目符号

    结构要求：
    - 各字段优先使用短行或短项目符号。
    - 如果某字段没有可靠新增内容，写“暂无新增确认”。
    - [连续主线] 保留仍在延续的任务线、关系线、写作线、回调点。
    - [时间推进] 保留最近发生顺序、状态变化、决策转折，不要把强时序内容压成静态标签堆。
    - [关键锚点] 只保留以后难以准确重建的原句、命令、路径、报错、标识符、风格锚点。

    场景自适应要求：
    - 如果是项目/开发对话，优先保留：repo 或工作区状态、当前任务、最近命令与结果、错误演进、关键文件、关键决策、下一步操作。
    - 如果是长篇小说/创作对话，优先保留：场景状态、人物状态、关系变化、伏笔、世界规则、叙事推进、文风约束、必须保留的原句或意象。
    - 如果是深度聊天，优先保留：稳定偏好、边界、核心观点、情绪语境、未完线索、需要回调的具体表达。
    - 如果多种场景并存，以“最直接影响下一轮回复的内容”为最高优先级，同时给其他仍会继续影响后续的部分保留最小必要锚点。

    补充提醒：
    在不与新增消息冲突的前提下，尽量保留旧摘要里仍有效的细节，不要为了流畅性整篇重写。

    {additional_context}

    <previous_primary_summary>
    {dialogue_summary_text}
    </previous_primary_summary>

    <incremental_messages>
    {incremental_messages}
    </incremental_messages>

    输出语言请与当前对话语境保持一致（locale: {locale}）。
""".trimIndent()

internal val DEFAULT_MEMORY_LEDGER_PROMPT = """
    你要维护一份记忆账本 JSON。

    输入只有两部分：
    1. 当前账本 JSON
    2. 账本之后新增的消息

    你的任务是输出“更新后的完整账本 JSON”。

    这份账本的用途是：
    - 让后续检索更容易命中值得回看的历史片段
    - 给后续 source 检索提供更精确的线索
    - 帮助后续读取原始消息时更快定位到该回看的主题、对象、命令、报错、路径、片段

    关键原则：
    1. 只输出 JSON，不要 markdown，不要解释。
    2. 只依据当前账本和新增消息更新，不要引入不存在的信息。
    3. 尽量保留可检索、可定位、可回溯的原子信息，不要把很多无关事实糊成一句大总结。
    4. 当新信息覆盖旧信息时，保留当前有效版本，并把旧版本转为 superseded 或 historical，而不是直接抹掉。
    5. chronology 保留事件顺序；detail_capsules 承接较长文本、代码、诗歌、引用、工具结果等高价值线索。
    6. 信息不确定时使用保守写法，不要脑补。
    7. 可以压缩低检索价值的叙事冗余，但不能削弱定位和回溯能力。
    8. 优先保留精确锚点：实体名、文件路径、命令、配置键、端口、报错、作品标题、角色名、主题线、原句摘录。

    顶层 schema 必须严格保持为：
    {
      "meta": {
        "schema_version": 3,
        "summary_turn": number,
        "updated_at": epoch_millis
      },
      "facts": [entry],
      "preferences": [entry],
      "tasks": [entry],
      "decisions": [entry],
      "constraints": [entry],
      "open_questions": [entry],
      "artifacts": [entry],
      "timeline": [entry],
      "chronology": [chronology_episode],
      "detail_capsules": [detail_capsule]
    }

    普通 entry 的字段必须严格保持：
    {
      "id": string,
      "text": string,
      "status": "active" | "done" | "blocked" | "superseded" | "historical",
      "tags": [string],
      "entity_keys": [string],
      "salience": 0..1,
      "updated_at_turn": number,
      "source_roles": ["user" | "assistant", ...],
      "task_state": string?,
      "reason": string?,
      "related_ids": [string]?,
      "scope": string?,
      "blocker": string?,
      "kind": string?,
      "locator": string?,
      "change_type": string?,
      "time_ref": string?
    }

    chronology_episode 的字段必须严格保持：
    {
      "id": string,
      "turn_range": string,
      "summary": string,
      "source_roles": ["user" | "assistant", ...],
      "time_ref": string?,
      "related_detail_ids": [string],
      "salience": 0..1
    }

    detail_capsule 的字段必须严格保持：
    {
      "id": string,
      "kind": "tool" | "code" | "poem" | "quote" | "longform",
      "title": string,
      "summary": string,
      "key_excerpt": string,
      "identifiers": [string],
      "source_roles": ["user" | "assistant", ...],
      "source_message_ids": [string],
      "locator": string?,
      "updated_at_turn": number,
      "salience": 0..1,
      "status": "active" | "done" | "blocked" | "superseded" | "historical"
    }

    {additional_context}

    <current_memory_ledger_json>
    {rolling_summary_json}
    </current_memory_ledger_json>

    <incremental_messages>
    {incremental_messages}
    </incremental_messages>

    输出语言、命名和措辞请与当前对话语境保持一致（locale: {locale}），但始终优先保证可检索性与可定位性。
""".trimIndent()

internal val DEFAULT_MEMORY_LEDGER_PATCH_PROMPT = """
    你要输出一个记忆账本补丁 JSON。

    输入只有两部分：
    1. 当前账本 JSON
    2. 账本之后新增的消息

    你的目标只有一个：在不降低账本质量的前提下，只输出这次真正发生变化的部分。

    关键规则：
    1. 只输出补丁 JSON，不要输出完整账本，不要解释。
    2. 不要删除整个 section，不要重写没有变化的大段旧内容。
    3. 不要修改不存在的 id。
    4. 如果你判断这次变化太大、局部补丁不可靠，就输出空补丁。

    补丁 schema 必须严格保持：
    {
      "add_entries": [
        {
          "section_key": string,
          "entry": rolling_summary_entry
        }
      ],
      "update_entries": [
        {
          "section_key": string,
          "entry": rolling_summary_entry
        }
      ],
      "supersede_entries": [
        {
          "section_key": string,
          "entry_id": string,
          "status": "superseded" | "historical" | "done" | "blocked",
          "reason": string?,
          "related_ids": [string]
        }
      ],
      "append_chronology": [
        {
          "episode": chronology_episode
        }
      ],
      "add_or_update_detail_capsules": [
        {
          "capsule": detail_capsule
        }
      ],
      "update_meta": {
        "summary_turn": number?,
        "updated_at": epoch_millis?
      } | null
    }

    section_key 只允许：
    - facts
    - preferences
    - tasks
    - decisions
    - constraints
    - open_questions
    - artifacts
    - timeline

    选择输出补丁而不是空补丁时，必须满足：
    - 只更新真正发生变化的条目
    - 追加新的 chronology
    - 为重要长文本、代码、工具输出补 detail capsule
    - 继续保留 locator、identifiers、source_message_ids、tags、entity_keys 等定位线索

    如果局部修改不可靠，输出下面这个空补丁：
    {
      "add_entries": [],
      "update_entries": [],
      "supersede_entries": [],
      "append_chronology": [],
      "add_or_update_detail_capsules": [],
      "update_meta": null
    }

    {additional_context}

    <current_memory_ledger_json>
    {rolling_summary_json}
    </current_memory_ledger_json>

    <incremental_messages>
    {incremental_messages}
    </incremental_messages>
""".trimIndent()

internal val DEFAULT_COMPRESS_PROMPT = DEFAULT_MEMORY_LEDGER_PROMPT
