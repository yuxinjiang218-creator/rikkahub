package me.rerere.rikkahub.service.recall.util

/**
 * Bigram 相似度工具（Phase K：通用信号触发）
 *
 * 提供语言无关的文本相似度计算，支持中文和英文。
 * 用于替代关键词列表，实现通用信号触发。
 */
object BigramUtil {
    /**
     * 计算字符 bigram Jaccard 相似度
     *
     * 使用字符 bigram 的 Jaccard 系数衡量两个文本的相似度。
     * 对中文和英文都有效，不依赖分词。
     *
     * Phase K 优化：
     * - 添加短文本抑制门槛（length < 4 返回 0）
     * - 避免极短文本产生噪声误判
     *
     * @param text1 文本1
     * @param text2 文本2
     * @return Jaccard 相似度 [0, 1]
     */
    fun computeJaccardSimilarity(text1: String, text2: String): Float {
        // Phase K: 短文本抑制门槛（避免噪声）
        val cleaned1 = text1.replace(Regex("""[ \p{Punct}]"""), "")
        val cleaned2 = text2.replace(Regex("""[ \p{Punct}]"""), "")

        if (cleaned1.length < 4 || cleaned2.length < 4) {
            return 0f  // 任一文本过短，不计算相似度
        }

        val bigrams1 = extractBigrams(text1)
        val bigrams2 = extractBigrams(text2)

        if (bigrams2.isEmpty()) return 0f

        val intersectionSize = bigrams1.intersect(bigrams2).size
        val unionSize = bigrams1.union(bigrams2).size

        return if (unionSize > 0) {
            intersectionSize.toFloat() / unionSize.toFloat()
        } else {
            0f
        }
    }

    /**
     * 计算与窗口文本的最大相似度
     *
     * 遍历 windowTexts，返回与任意一条窗口文本的最大相似度。
     *
     * @param text 查询文本
     * @param windowTexts 窗口文本列表
     * @return 最大相似度 [0, 1]
     */
    fun computeMaxWindowSimilarity(text: String, windowTexts: List<String>): Float {
        if (windowTexts.isEmpty()) return 0f

        return windowTexts.maxOfOrNull { windowText ->
            computeJaccardSimilarity(text, windowText)
        } ?: 0f
    }

    /**
     * 提取字符 bigram（deterministic 分词）
     *
     * 示例：
     * - "你好世界" -> ["你好", "好世", "世界"]
     * - "ABC" -> ["AB", "BC"]
     *
     * @param text 输入文本
     * @return bigram 集合
     */
    private fun extractBigrams(text: String): Set<String> {
        val bigrams = mutableSetOf<String>()
        val cleaned = text.replace(Regex("""[ \p{Punct}]"""), "")  // 移除空白和标点

        for (i in 0 until cleaned.length - 1) {
            val bigram = cleaned.substring(i, i + 2)
            bigrams.add(bigram)
        }

        return bigrams
    }
}
