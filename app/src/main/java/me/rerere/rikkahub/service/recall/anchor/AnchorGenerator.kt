package me.rerere.rikkahub.service.recall.anchor

/**
 * Anchor 生成器（Phase G：anchors 体系收敛）
 *
 * 统一 anchors 语义为"用户可能复述/引用的文本锚点"
 * - 禁止包含结构信息（nodeIndices, windowStartIndex 等）
 * - 每个 anchor 截断到 40 chars
 * - 最多 10 个 anchors
 */
object AnchorGenerator {

    private const val MAX_ANCHORS = 10
    private const val MAX_ANCHOR_LENGTH = 40
    private const val MAX_KEYWORD_LENGTH = 8

    /**
     * P源显式逐字关键词（用户可能引用的短语）
     */
    private val EXPLICIT_KEYWORDS = listOf(
        "原文", "全文", "逐字", "一字不差", "复述",
        "贴出来", "引用", "原诗", "原代码", "那段",
        "那段话", "那段代码", "之前说的", "刚才提到的"
    )

    /**
     * 中文停用词（简化版，用于高信息词提取）
     */
    private val STOP_WORDS_ZH = setOf(
        "的", "了", "是", "在", "我", "你", "他", "她", "它",
        "们", "这", "那", "有", "和", "与", "或", "但", "而",
        "吗", "呢", "吧", "啊", "哦", "嗯", "呀", "哪", "怎么",
        "什么", "怎么", "为什么", "多少", "几个", "哪个"
    )

    /**
     * 生成 P源 anchors（Phase G G1.1）
     *
     * 仅允许来自：
     * a) 显式 title（若存在）
     * b) 显式逐字关键词（不超过 8 chars）
     * c) query 中的高信息词（长度>=2的 token）
     *
     * 禁止：nodeIndices 等结构信息
     */
    fun buildPSourceAnchors(
        query: String,
        explicitTitle: String? = null
    ): List<String> {
        val anchors = mutableListOf<String>()

        // a) 显式 title（若存在）
        if (explicitTitle != null) {
            anchors.add(truncateAnchor("title:$explicitTitle"))
        }

        // b) 显式逐字关键词（从 query 中检测，不超过 8 chars）
        val keywordAnchor = detectExplicitKeyword(query)
        if (keywordAnchor != null) {
            anchors.add(truncateAnchor(keywordAnchor))
        }

        // c) query 中的高信息词（去掉停用词后长度>=2的 token）
        val informativeTokens = extractInformativeTokens(query)
        informativeTokens.take(MAX_ANCHORS - anchors.size).forEach { token ->
            anchors.add(truncateAnchor("token:$token"))
        }

        return anchors.take(MAX_ANCHORS)
    }

    /**
     * 生成 A源 anchors（Phase G G1.2）
     *
     * 仅允许来自：
     * a) query 中的高相关 token（与 query 高相关的 token，最多 3 个）
     *
     * 禁止：windowStartIndex/windowEndIndex/node_indices 等结构信息
     * 注意：ArchiveSummaryEntity 当前无 title/keywords 字段，暂只使用 query tokens
     */
    fun buildASourceAnchors(
        query: String
    ): List<String> {
        val anchors = mutableListOf<String>()

        // a) query 中的高相关 token（最多 3 个）
        val informativeTokens = extractInformativeTokens(query)
        informativeTokens.take(3).forEach { token ->
            anchors.add(truncateAnchor("token:$token"))
        }

        return anchors.take(MAX_ANCHORS)
    }

    /**
     * 检测显式逐字关键词（从 query 中查找，不超过 8 chars）
     *
     * 返回格式："keyword:原文" 或 null
     */
    private fun detectExplicitKeyword(query: String): String? {
        for (keyword in EXPLICIT_KEYWORDS) {
            if (query.contains(keyword) && keyword.length <= MAX_KEYWORD_LENGTH) {
                return "keyword:$keyword"
            }
        }
        return null
    }

    /**
     * 提取高信息词（去掉停用词后长度>=2的 token）
     *
     * 简化规则：
     * - 中文：2-3 字窗口
     * - 英文：按空白切分
     * - 过滤停用词
     */
    private fun extractInformativeTokens(query: String): List<String> {
        val tokens = mutableListOf<String>()

        // 中文分词（2-3 字窗口）
        val chineseChars = query.filter { it.code in 0x4E00..0x9FFF }
        if (chineseChars.length >= 2) {
            // 2字窗口
            for (i in 0 until chineseChars.length - 1) {
                val bigram = chineseChars.substring(i, i + 2)
                if (!STOP_WORDS_ZH.contains(bigram)) {
                    tokens.add(bigram)
                }
            }
            // 3字窗口
            if (chineseChars.length >= 3) {
                for (i in 0 until chineseChars.length - 2) {
                    val trigram = chineseChars.substring(i, i + 3)
                    if (!STOP_WORDS_ZH.contains(trigram)) {
                        tokens.add(trigram)
                    }
                }
            }
        }

        // 英文分词（按空白切分，长度>=2）
        val englishWords = query.split(Regex("""[ \p{Punct}]"""))
            .filter { it.isNotEmpty() && it.first().code in 0x0041..0x007A }
            .filter { it.length >= 2 }
        tokens.addAll(englishWords)

        // 去重并返回
        return tokens.distinct()
    }

    /**
     * 截断 anchor 到 40 chars
     */
    private fun truncateAnchor(anchor: String): String {
        return if (anchor.length > MAX_ANCHOR_LENGTH) {
            anchor.take(MAX_ANCHOR_LENGTH)
        } else {
            anchor
        }
    }
}
