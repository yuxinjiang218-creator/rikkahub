package me.rerere.rikkahub.service.knowledge

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.document.DocxParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import java.io.File

/**
 * 文档提取器
 * 从不同格式的文档中提取纯文本
 */
object DocumentExtractor {

    /**
     * 文件大小限制（写死）
     */
    private const val MAX_FILE_SIZE = 10 * 1024 * 1024L  // 10MB
    private const val MAX_PDF_SIZE = 10 * 1024 * 1024L  // PDF限制10MB
    private const val MAX_DOCX_SIZE = 10 * 1024 * 1024L  // DOCX限制10MB

    /**
     * 从文件中提取文本
     *
     * @param file 文件
     * @param mime MIME 类型
     * @return 提取的文本内容
     * @throws DocumentParseException 解析失败时抛出
     */
    suspend fun extractText(file: File, mime: String): String = withContext(Dispatchers.IO) {
        // 在解析前检查文件大小
        val maxSize = when (mime) {
            "application/pdf" -> MAX_PDF_SIZE
            else -> MAX_FILE_SIZE
        }

        if (file.length() > maxSize) {
            val maxSizeMB = maxSize / (1024 * 1024)
            throw DocumentParseException(
                "File too large: ${file.length() / (1024 * 1024)}MB (max: ${maxSizeMB}MB for ${mime}). " +
                "Please split the file or compress it."
            )
        }

        try {
            when (mime) {
                "application/pdf" -> parsePdf(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocx(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptx(file)
                "text/plain" -> file.readText()
                "text/markdown" -> file.readText()
                else -> {
                    // 尝试作为纯文本读取
                    file.readText()
                }
            }
        } catch (e: Exception) {
            throw DocumentParseException("Failed to extract text from ${file.name}: ${e.message}", e)
        }
    }

    /**
     * 从 Uri 提取文本
     */
    suspend fun extractText(uri: Uri, file: File, mime: String): String {
        return extractText(file, mime)
    }

    private fun parsePdf(file: File): String {
        return PdfParser.parserPdf(file)
    }

    private fun parseDocx(file: File): String {
        return DocxParser.parse(file)
    }

    private fun parsePptx(file: File): String {
        return PptxParser.parse(file)
    }

    /**
     * 获取支持的 MIME 类型列表
     */
    fun getSupportedMimeTypes(): List<String> = listOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain",
        "text/markdown"
    )

    /**
     * 检查 MIME 类型是否支持
     */
    fun isSupported(mime: String): Boolean {
        return getSupportedMimeTypes().contains(mime)
    }
}

/**
 * 文档解析异常
 */
class DocumentParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
