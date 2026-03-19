package me.rerere.rikkahub.data.document

import kotlinx.coroutines.Dispatchers
import me.rerere.document.DocParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.document.PptParser
import me.rerere.document.DocxParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.document.XlsParser
import me.rerere.document.XlsxParser
import java.io.File

data class DocumentTextBlock(
    val text: String,
    val progressCurrent: Int,
    val progressTotal: Int = 0,
    val progressLabel: String,
)

object DocumentTextExtractor {
    private const val TEXT_BLOCK_SIZE = 16_384

    private val structuredTextExtensions = setOf(
        "txt", "md", "markdown", "mdx", "csv", "tsv", "json", "html", "htm", "xml", "rtf",
        "css", "js", "ts", "tsx", "jsx", "kt", "java", "kts", "py", "rb", "go",
        "rs", "swift", "c", "cc", "cpp", "h", "hpp", "sh", "sql", "yml", "yaml",
        "toml", "ini", "cfg", "conf", "env", "log", "properties", "gradle", "bat", "ps1"
    )

    private val structuredMimeTypes = setOf(
        "application/json",
        "application/javascript",
        "application/xml",
        "application/xhtml+xml",
        "application/rtf",
        "text/csv",
        "text/rtf",
        "text/tab-separated-values",
    )

    private val officeExtensions = setOf(
        "pdf", "doc", "docx", "docm", "ppt", "pptx", "pptm", "xls", "xlsx", "xlsm"
    )

    private val officeMimeTypes = setOf(
        "application/pdf",
        "application/msword",
        "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint",
        "application/vnd.ms-excel.sheet.macroEnabled.12",
        "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
        "application/vnd.ms-word.document.macroEnabled.12",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )

    fun isKnowledgeBaseSupported(fileName: String, mimeType: String?): Boolean {
        val extension = extensionOf(fileName)
        if (extension in officeExtensions) return true
        if (extension in structuredTextExtensions) return true
        if (!mimeType.isNullOrBlank() && mimeType.startsWith("text/")) return true
        return mimeType in structuredMimeTypes || mimeType in officeMimeTypes
    }

    fun streamText(
        file: File,
        fileName: String,
        mimeType: String?,
        sink: (DocumentTextBlock) -> Boolean,
    ) {
        require(file.exists() && file.isFile) { "Document file does not exist: $fileName" }
        require(isKnowledgeBaseSupported(fileName, mimeType)) { "Unsupported document format: $fileName" }

        when (extensionOf(fileName)) {
            "pdf" -> PdfParser.stream(file) { current, total, text ->
                emitNormalized(text, current, total, "页", sink)
            }

            "doc" -> DocParser.stream(file) { current, total, text ->
                emitNormalized(text, current, total, "段落", sink)
            }

            "docx", "docm" -> DocxParser.stream(file) { current, text ->
                emitNormalized(text, current, 0, "段落", sink)
            }

            "ppt" -> PptParser.stream(file) { current, total, text ->
                emitNormalized(text, current, total, "幻灯片", sink)
            }

            "pptx", "pptm" -> PptxParser.stream(file) { current, total, text ->
                emitNormalized(text, current, total, "幻灯片", sink)
            }

            "xls" -> XlsParser.stream(file) { current, total, text ->
                emitNormalized(text, current, total, "工作表", sink)
            }

            "xlsx", "xlsm" -> XlsxParser.stream(file) { current, total, text ->
                emitNormalized(text, current, total, "工作表", sink)
            }

            else -> streamStructuredText(file, sink)
        }
    }

    suspend fun streamTextSuspend(
        file: File,
        fileName: String,
        mimeType: String?,
        sink: suspend (DocumentTextBlock) -> Boolean,
    ) {
        withContext(Dispatchers.IO) {
            streamText(file, fileName, mimeType) { block ->
                runBlocking {
                    sink(block)
                }
            }
        }
    }

    fun extractText(
        file: File,
        fileName: String,
        mimeType: String?,
    ): String {
        val result = StringBuilder()
        streamText(file, fileName, mimeType) { block ->
            if (result.isNotEmpty()) {
                result.appendLine()
                result.appendLine()
            }
            result.append(block.text)
            true
        }
        return result.toString().trim().also { text ->
            require(text.isNotBlank()) { "Document content is empty: $fileName" }
        }
    }

    fun extractPreviewText(
        file: File,
        fileName: String,
        mimeType: String?,
        maxChars: Int,
    ): String {
        val result = StringBuilder()
        streamText(file, fileName, mimeType) { block ->
            if (result.length >= maxChars) {
                return@streamText false
            }
            if (result.isNotEmpty()) {
                result.appendLine()
                result.appendLine()
            }
            val remaining = (maxChars - result.length).coerceAtLeast(0)
            result.append(block.text.take(remaining))
            result.length < maxChars
        }
        return result.toString().trim()
    }

    fun normalizeExtractedText(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .joinToString("\n") { line -> line.trimEnd() }
            .replace(Regex("[\\t\\x0B\\f]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun streamStructuredText(
        file: File,
        sink: (DocumentTextBlock) -> Boolean,
    ) {
        file.bufferedReader().use { reader ->
            var progress = 0
            val buffer = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                if (buffer.isNotEmpty()) {
                    buffer.append('\n')
                }
                buffer.append(line)
                if (buffer.length >= TEXT_BLOCK_SIZE) {
                    progress += 1
                    if (!emitNormalized(buffer.toString(), progress, 0, "文本块", sink)) {
                        return
                    }
                    buffer.clear()
                }
            }
            if (buffer.isNotEmpty()) {
                progress += 1
                emitNormalized(buffer.toString(), progress, 0, "文本块", sink)
            }
        }
    }

    private fun emitNormalized(
        text: String,
        progressCurrent: Int,
        progressTotal: Int,
        progressLabel: String,
        sink: (DocumentTextBlock) -> Boolean,
    ): Boolean {
        val normalized = normalizeExtractedText(text)
        if (normalized.isBlank()) return true
        return sink(
            DocumentTextBlock(
                text = normalized,
                progressCurrent = progressCurrent,
                progressTotal = progressTotal,
                progressLabel = progressLabel,
            )
        )
    }

    private fun extensionOf(fileName: String): String {
        return fileName.substringAfterLast('.', "").lowercase()
    }
}
