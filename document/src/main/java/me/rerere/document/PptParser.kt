package me.rerere.document

import org.apache.poi.hslf.extractor.QuickButCruddyTextExtractor
import java.io.File

object PptParser {
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
            return "Error parsing PPT file: ${error.message}"
        }
        return result.toString().trim()
    }

    fun stream(
        file: File,
        sink: (blockIndex: Int, totalBlocks: Int, text: String) -> Boolean,
    ) {
        file.inputStream().buffered().use { input ->
            val extractor = QuickButCruddyTextExtractor(input)
            try {
                val slides = extractor.textAsVector
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                val totalSlides = slides.size.coerceAtLeast(1)
                var blockIndex = 0

                slides.forEach { slideText ->
                    val normalized = slideText
                        .replace('\r', '\n')
                        .lines()
                        .map { it.trimEnd() }
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                        .trim()
                    if (normalized.isBlank()) return@forEach

                    val chunks = normalized.chunked(TEXT_BLOCK_SIZE)
                    chunks.forEachIndexed { chunkIndex, chunk ->
                        blockIndex += 1
                        val prefix = if (chunks.size == 1) {
                            "## Slide $blockIndex"
                        } else {
                            "## Slide $blockIndex (${chunkIndex + 1}/${chunks.size})"
                        }
                        val rendered = "$prefix\n\n${chunk.trim()}".trim()
                        if (!sink(blockIndex, totalSlides, rendered)) {
                            return
                        }
                    }
                }
            } finally {
                extractor.close()
            }
        }
    }
}
