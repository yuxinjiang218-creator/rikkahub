package me.rerere.document

import org.apache.poi.hwpf.extractor.WordExtractor
import java.io.File

object DocParser {
    private const val TEXT_BLOCK_SIZE = 16_384

    fun parse(file: File): String {
        val result = StringBuilder()
        runCatching {
            stream(file) { _, _, text ->
                if (result.isNotEmpty()) {
                    result.appendLine()
                    result.appendLine()
                }
                result.append(text.trimEnd())
                true
            }
        }.onFailure { error ->
            return "Error parsing DOC file: ${error.message}"
        }
        return result.toString().trim()
    }

    fun stream(
        file: File,
        sink: (blockIndex: Int, totalBlocks: Int, text: String) -> Boolean,
    ) {
        file.inputStream().buffered().use { input ->
            WordExtractor(input).use { extractor ->
                val paragraphs = extractor.paragraphText
                    .map { paragraph ->
                        paragraph
                            .replace('\u0007', ' ')
                            .replace('\r', '\n')
                            .lines()
                            .joinToString("\n") { it.trimEnd() }
                            .trim()
                    }
                    .filter { it.isNotBlank() }

                val totalBlocks = paragraphs.size.coerceAtLeast(1)
                val buffer = StringBuilder()
                var blockIndex = 0

                fun flush(): Boolean {
                    val text = buffer.toString().trim()
                    if (text.isBlank()) return true
                    blockIndex += 1
                    val keepGoing = sink(blockIndex, totalBlocks, text)
                    buffer.clear()
                    return keepGoing
                }

                paragraphs.forEach { paragraph ->
                    if (buffer.isNotEmpty()) {
                        buffer.appendLine()
                    }
                    buffer.append(paragraph)
                    if (buffer.length >= TEXT_BLOCK_SIZE) {
                        if (!flush()) return
                    }
                }

                if (buffer.isNotEmpty()) {
                    flush()
                }
            }
        }
    }
}
