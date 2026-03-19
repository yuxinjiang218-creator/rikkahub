package me.rerere.document

import org.apache.poi.hssf.extractor.ExcelExtractor
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import java.io.File

object XlsParser {
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
            return "Error parsing XLS file: ${error.message}"
        }
        return result.toString().trim()
    }

    fun stream(
        file: File,
        sink: (blockIndex: Int, totalBlocks: Int, text: String) -> Boolean,
    ) {
        file.inputStream().buffered().use { input ->
            POIFSFileSystem(input).use { fileSystem ->
                ExcelExtractor(fileSystem).use { extractor ->
                    extractor.setIncludeSheetNames(true)
                    extractor.setIncludeCellComments(false)
                    extractor.setIncludeBlankCells(false)
                    extractor.setIncludeHeadersFooters(false)
                    extractor.setFormulasNotResults(false)

                    val lines = extractor.text
                        .replace('\r', '\n')
                        .lines()
                        .map { it.trimEnd() }
                        .filter { it.isNotBlank() }

                    val totalBlocks = lines.size.coerceAtLeast(1)
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

                    lines.forEach { line ->
                        if (buffer.isNotEmpty()) {
                            buffer.appendLine()
                        }
                        buffer.append(line)
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
}
