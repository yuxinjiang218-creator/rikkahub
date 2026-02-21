package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import java.io.File

object DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                val content = readDocumentContent(document)
                                val prompt = """
                  ## user sent a file: ${document.fileName}
                  <content>
                  ```
                  $content
                  ```
                  </content>
                  """.trimMargin()
                                add(0, UIMessagePart.Text(prompt))
                            }
                        }
                    }
                )
            }
        }
    }

    private fun parsePdfAsText(file: File): String {
        return PdfParser.parserPdf(file)
    }

    private fun parseDocxAsText(file: File): String {
        return DocxParser.parse(file)
    }

    private fun parsePptxAsText(file: File): String {
        return PptxParser.parse(file)
    }

    /**
     * 判断是否为二进制文件，这类文件不应直接读取内容到提示词
     * 压缩文件(zip/tar等)会单独处理，提示AI使用工具
     */
    private fun isBinaryFile(fileName: String, mime: String?): Boolean {
        val archiveExtensions = setOf("zip", "tar", "gz", "tgz", "bz2", "7z", "rar", "xz")

        val binaryExtensions = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg",
            "mp3", "mp4", "avi", "mov", "wav", "ogg", "webm",
            "exe", "dll", "so", "dylib", "bin"
        )
        val binaryMimePrefixes = listOf(
            "application/pdf", "image/", "audio/", "video/",
            "application/octet-stream"
        )

        val ext = fileName.substringAfterLast('.', "").lowercase()
        // 压缩文件不按二进制处理，会单独提示AI使用工具
        if (archiveExtensions.contains(ext)) return false
        if (binaryExtensions.contains(ext)) return true
        if (mime != null && binaryMimePrefixes.any { mime.startsWith(it) }) return true

        return false
    }

    private fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[错误: 无效的文件URI: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[错误: 文件不存在: ${document.fileName}]"
        }

        val fileName = document.fileName.lowercase()

        // 压缩文件：提示AI使用工具解压查看
        if (fileName.endsWith(".zip") || fileName.endsWith(".tar") ||
            fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz") ||
            fileName.endsWith(".gz") || fileName.endsWith(".bz2") ||
            fileName.endsWith(".7z") || fileName.endsWith(".rar")) {

            val toolHint = when {
                fileName.endsWith(".zip") -> "sandbox_file工具的unzip操作"
                fileName.endsWith(".tar") || fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz") -> "sandbox_shell工具的tar命令"
                else -> "相应工具"
            }

            return "[已上传文件: ${document.fileName}, 大小: ${formatFileSize(file.length())}, 已导入沙箱。" +
                   "此文件已自动导入当前对话的沙箱工作区。" +
                   "如需查看内容，请使用${toolHint}解压并浏览。"
        }

        // 二进制文件：提示使用工具处理
        if (isBinaryFile(document.fileName, document.mime)) {
            return "[二进制文件: ${document.fileName}, 大小: ${formatFileSize(file.length())}。" +
                   "已自动导入沙箱，请使用相应工具处理。]"
        }

        // 大文本文件（>100KB），只读取部分内容
        val maxSize = 100 * 1024 // 100KB
        if (file.length() > maxSize) {
            return runCatching {
                val content = file.readText().take(maxSize)
                "$content\n\n[文件已截断: ${document.fileName} 大小为 ${formatFileSize(file.length())}, 仅显示前100KB]"
            }.getOrElse {
                "[错误: 无法读取文件: ${document.fileName}]"
            }
        }

        return runCatching {
            when (document.mime) {
                "application/pdf" -> parsePdfAsText(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptxAsText(file)
                else -> file.readText()
            }
        }.getOrElse {
            "[错误: 无法读取文件: ${document.fileName}]"
        }
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.2f KB", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format("%.2f MB", size / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
