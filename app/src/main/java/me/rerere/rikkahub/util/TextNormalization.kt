package me.rerere.rikkahub.util

/**
 * 文本归一化（写死，不得修改）
 * 用途：提升中文在 FTS5 中的可检索性
 *
 * 处理规则：
 * - Whitespace → 单空格
 * - CJK（0x4E00-0x9FFF）→ 添加空格
 * - Alphanumeric → 小写
 * - 其他 → 空格
 */
fun normalizeForSearch(input: String): String {
    val sb = StringBuilder(input.length * 2)
    for (ch in input) {
        when {
            ch.isWhitespace() -> sb.append(' ')
            ch.code in 0x4E00..0x9FFF -> {  // CJK Unified Ideographs
                sb.append(ch).append(' ')
            }
            ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
            else -> sb.append(' ')
        }
    }
    return sb.toString()
        .replace(Regex("\\s+"), " ")
        .trim()
}
