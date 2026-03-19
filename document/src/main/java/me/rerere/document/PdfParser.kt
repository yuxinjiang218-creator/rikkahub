package me.rerere.document

import com.artifex.mupdf.fitz.PDFDocument
import java.io.File

object PdfParser {
    fun parse(file: File): String {
        val result = StringBuilder()
        stream(file) { pageNumber, _, text ->
            if (result.isNotEmpty()) {
                result.appendLine()
            }
            result.append("---")
            result.appendLine()
            result.append("Page $pageNumber:")
            result.appendLine()
            result.append(text.trim())
            true
        }
        return result.toString().trim()
    }

    fun stream(
        file: File,
        sink: (pageNumber: Int, totalPages: Int, text: String) -> Boolean,
    ) {
        val document = PDFDocument.openDocument(file.absolutePath).asPDF()
        try {
            val totalPages = document.countPages()
            for (pageIndex in 0 until totalPages) {
                val page = document.loadPage(pageIndex)
                try {
                    val structuredText = page.toStructuredText()
                    try {
                        val text = structuredText.asText()
                        if (!sink(pageIndex + 1, totalPages, text)) {
                            break
                        }
                    } finally {
                        releaseFitzResource(structuredText)
                    }
                } finally {
                    releaseFitzResource(page)
                }
            }
        } finally {
            releaseFitzResource(document)
        }
    }

    private fun releaseFitzResource(resource: Any?) {
        if (resource == null) return
        if (resource is AutoCloseable) {
            runCatching { resource.close() }
            return
        }
        val releaseMethod = resource.javaClass.methods.firstOrNull { method ->
            method.parameterCount == 0 && (method.name == "destroy" || method.name == "close")
        } ?: return
        runCatching { releaseMethod.invoke(resource) }
    }
}
