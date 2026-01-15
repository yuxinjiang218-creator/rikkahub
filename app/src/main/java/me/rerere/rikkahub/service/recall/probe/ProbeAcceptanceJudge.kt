package me.rerere.rikkahub.service.recall.probe

import me.rerere.rikkahub.service.recall.model.LastProbeObservation
import me.rerere.rikkahub.service.recall.model.ProbeOutcome

/**
 * 探针接住判定器（Phase E：ProbeControl）
 *
 * 根据 deterministic 规则判定用户对上一轮试探的反应。
 *
 * 判定规则（写死）：
 * 1. REJECT（明确否定）：包含否定词
 * 2. ACCEPT（明显接住）：满足任一条件
 *    - 含确认/继续词且长度>=2
 *    - 命中上一轮 anchors
 *    - 与上一轮 content 词重叠率 >= 0.20
 * 3. IGNORE：其他情况
 */
object ProbeAcceptanceJudge {
    /** Reject 词库（明确否定） */
    private val REJECT_PHRASES = listOf(
        "不是", "不对", "你记错了", "不是这个", "不相关", "别提这个"
    )

    /** Accept 词库（确认/继续） */
    private val ACCEPT_PHRASES = listOf(
        "对", "就是", "没错", "继续", "接着", "展开", "按这个", "好的", "是的"
    )

    /**
     * 判定接住结果
     *
     * @param lastUserText 用户最新输入
     * @param lastObservation 上一轮试探观察
     * @return 接住判定结果
     */
    fun judge(
        lastUserText: String,
        lastObservation: LastProbeObservation?
    ): ProbeOutcome {
        if (lastObservation == null) {
            // 没有上一轮试探，无法判定
            return ProbeOutcome.IGNORE
        }

        // 1. 检查 REJECT（优先级最高）
        if (isReject(lastUserText)) {
            return ProbeOutcome.REJECT
        }

        // 2. 检查 ACCEPT
        if (isAccept(lastUserText, lastObservation)) {
            return ProbeOutcome.ACCEPT
        }

        // 3. 默认 IGNORE
        return ProbeOutcome.IGNORE
    }

    /**
     * 检查是否为 REJECT（明确否定）
     */
    private fun isReject(lastUserText: String): Boolean {
        return REJECT_PHRASES.any { phrase ->
            lastUserText.contains(phrase, ignoreCase = true)
        }
    }

    /**
     * 检查是否为 ACCEPT（明显接住）
     */
    private fun isAccept(lastUserText: String, lastObservation: LastProbeObservation): Boolean {
        // 条件1：含确认/继续词且长度>=2
        if (lastUserText.length >= 2 && hasAcceptPhrase(lastUserText)) {
            return true
        }

        // 条件2：命中上一轮 anchors
        if (hitsAnchors(lastUserText, lastObservation.anchors)) {
            return true
        }

        // 条件3：词重叠率 >= 阈值
        if (calcOverlapRatio(lastUserText, lastObservation.content) >= me.rerere.rikkahub.service.recall.model.ProbeLedgerState.ACCEPT_OVERLAP_THRESHOLD) {
            return true
        }

        return false
    }

    /**
     * 检查是否含确认/继续词
     */
    private fun hasAcceptPhrase(lastUserText: String): Boolean {
        return ACCEPT_PHRASES.any { phrase ->
            lastUserText.contains(phrase, ignoreCase = true)
        }
    }

    /**
     * 检查是否命中 anchors
     *
     * 修复：规范化匹配 keyword/title/token anchors
     * - 对每个 anchor 做 normalize：取 : 后面的部分（如果有前缀）
     * - trim；空字符串跳过
     * - 噪声抑制：长度 < 2 的跳过
     * - 命中判断：lastUserText.contains(normalizedAnchor)
     */
    private fun hitsAnchors(lastUserText: String, anchors: List<String>): Boolean {
        // anchors 格式示例：["title:静夜思", "keyword:原文", "node_indices:45,46,47", "archive_id:xxx"]
        return anchors.any { anchor ->
            // 1. normalize：取 : 后面的部分（如果有前缀）
            val normalizedAnchor = when {
                anchor.startsWith("title:") -> anchor.substringAfter("title:")
                anchor.startsWith("keyword:") -> anchor.substringAfter("keyword:")
                anchor.startsWith("token:") -> anchor.substringAfter("token:")
                anchor.startsWith("node_indices:") -> null  // 跳过结构锚点
                anchor.startsWith("archive_id:") -> null  // 跳过 ID
                else -> anchor
            }

            // 2. 处理：trim + 空字符串跳过
            val trimmed = normalizedAnchor?.trim() ?: return@any false
            if (trimmed.isEmpty()) return@any false

            // 3. 噪声抑制：长度 < 2 的跳过（避免单字误触发）
            if (trimmed.length < 2) return@any false

            // 4. 命中判断
            lastUserText.contains(trimmed, ignoreCase = true)
        }
    }

    /**
     * 计算词重叠率（deterministic，不引入新依赖）
     *
     * 策略：字符 bigram 重叠（对中英文都有效）
     *
     * Phase F 修复：
     * - 去掉"至少包含一个字母"过滤，支持纯中文
     * - 加启用门槛：cleaned.length >= 4 才计算 overlap（避免短文本噪声）
     *
     * @return 重叠率 [0, 1]
     */
    private fun calcOverlapRatio(lastUserText: String, lastContent: String): Float {
        // Phase F: 启用门槛（短文本抑噪）
        val cleanedUser = lastUserText.replace(Regex("""[ \p{Punct}]"""), "")
        val cleanedContent = lastContent.replace(Regex("""[ \p{Punct}]"""), "")

        if (cleanedUser.length < 4 || cleanedContent.length < 4) {
            return 0f  // 任一文本过短，不计算 overlap
        }

        // 提取 bigram（全量，不过滤字母）
        val lastUserBigrams = extractBigrams(lastUserText)
        val lastContentBigrams = extractBigrams(lastContent)

        if (lastContentBigrams.isEmpty()) {
            return 0f
        }

        val intersectionSize = lastUserBigrams.intersect(lastContentBigrams).size
        val unionSize = lastUserBigrams.union(lastContentBigrams).size

        return if (unionSize > 0) {
            intersectionSize.toFloat() / unionSize.toFloat()
        } else {
            0f
        }
    }

    /**
     * 提取字符 bigram（deterministic 分词，Phase F 修复）
     *
     * 修改：去掉"至少包含一个字母"过滤，支持纯中文 bigram
     *
     * 示例：
     * - "你好世界" -> ["你好", "好世", "世界"]
     * - "ABC" -> ["AB", "BC"]
     * - "123" -> ["12", "23"]
     */
    private fun extractBigrams(text: String): Set<String> {
        val bigrams = mutableSetOf<String>()
        val cleaned = text.replace(Regex("""[ \p{Punct}]"""), "")  // 移除空白和标点

        for (i in 0 until cleaned.length - 1) {
            val bigram = cleaned.substring(i, i + 2)
            // Phase F: 去掉字母过滤，直接添加全量 bigram
            bigrams.add(bigram)
        }

        return bigrams
    }
}
