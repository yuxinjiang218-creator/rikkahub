package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.document.DocumentTextExtractor

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

    private fun isBinaryFile(fileName: String, mime: String?): Boolean {
        val archiveExtensions = setOf("zip", "tar", "gz", "tgz", "bz2", "7z", "rar", "xz")
        if (DocumentTextExtractor.isKnowledgeBaseSupported(fileName, mime)) return false

        val binaryExtensions = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg",
            "mp3", "mp4", "avi", "mov", "wav", "ogg", "webm",
            "exe", "dll", "so", "dylib", "bin"
        )
        val binaryMimePrefixes = listOf(
            "image/", "audio/", "video/", "application/octet-stream"
        )

        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (archiveExtensions.contains(ext)) return false
        if (binaryExtensions.contains(ext)) return true
        if (mime != null && binaryMimePrefixes.any { mime.startsWith(it) }) return true
        return false
    }

    private fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[错误: 无效的文件 URI: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[错误: 文件不存在: ${document.fileName}]"
        }

        val fileName = document.fileName.lowercase()
        if (fileName.endsWith(".zip") || fileName.endsWith(".tar") ||
            fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz") ||
            fileName.endsWith(".gz") || fileName.endsWith(".bz2") ||
            fileName.endsWith(".7z") || fileName.endsWith(".rar")
        ) {
            val toolHint = when {
                fileName.endsWith(".zip") -> "container_shell 里的 unzip"
                fileName.endsWith(".tar") || fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz") -> {
                    "container_shell 里的 tar"
                }

                else -> "对应解压工具"
            }
            return "[已上传压缩文件: ${document.fileName}, 大小: ${formatFileSize(file.length())}。如需查看内容，请使用 $toolHint。]"
        }

        if (isBinaryFile(document.fileName, document.mime)) {
            return "[二进制文件: ${document.fileName}, 大小: ${formatFileSize(file.length())}。请使用相应工具处理。]"
        }

        val maxSize = 100 * 1024
        return runCatching {
            if (DocumentTextExtractor.isKnowledgeBaseSupported(document.fileName, document.mime)) {
                val preview = DocumentTextExtractor.extractPreviewText(
                    file = file,
                    fileName = document.fileName,
                    mimeType = document.mime,
                    maxChars = maxSize
                )
                if (file.length() > maxSize) {
                    "$preview\n\n[文件已截断: ${document.fileName}，仅显示前 100KB 可读内容]"
                } else {
                    preview
                }
            } else if (file.length() > maxSize) {
                val content = file.readText().take(maxSize)
                "$content\n\n[文件已截断: ${document.fileName}，仅显示前 100KB]"
            } else {
                file.readText()
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
