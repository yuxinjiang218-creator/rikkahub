package me.rerere.document

import com.artifex.mupdf.fitz.PDFDocument
import java.io.File

object PdfParser {
    /**
     * 最大页数限制（防止内存溢出）
     * 使用流式处理，可以支持更多页数
     * 1000页约等于500KB-1MB纯文本（取决于内容密度）
     */
    private const val MAX_PAGES = 1000

    /**
     * 流式解析PDF（带页数限制）
     * @param file PDF文件
     * @param onPage 每解析一页的回调，返回true继续，false停止
     * @return 总文本（如果onPage为null）或空字符串（如果使用回调）
     */
    fun parserPdf(file: File): String {
        val document = PDFDocument.openDocument(file.absolutePath).asPDF()
        val pages = document.countPages()

        // 页数保护
        if (pages > MAX_PAGES) {
            throw IllegalArgumentException(
                "PDF has too many pages: $pages (max: $MAX_PAGES). " +
                "Please split the PDF into smaller files."
            )
        }

        val result = StringBuilder()
        for (i in 0 until pages) {
            val page = document.loadPage(i).toStructuredText()
            result.append("---")
            result.append("Page ${i + 1}:\n")
            result.append(page.asText())
            result.appendLine()
        }
        return result.toString()
    }

    /**
     * 流式解析PDF（带页数限制和回调）
     * 适用于需要边解析边处理的场景
     */
    fun parsePdfStreaming(file: File, onPage: (pageIndex: Int, pageText: String) -> Boolean) {
        val document = PDFDocument.openDocument(file.absolutePath).asPDF()
        val pages = document.countPages()

        if (pages > MAX_PAGES) {
            throw IllegalArgumentException(
                "PDF has too many pages: $pages (max: $MAX_PAGES). " +
                "Please split the PDF into smaller files."
            )
        }

        for (i in 0 until pages) {
            val page = document.loadPage(i).toStructuredText()
            val pageText = "---\nPage ${i + 1}:\n${page.asText()}\n"
            val shouldContinue = onPage(i, pageText)
            if (!shouldContinue) break
        }
    }
}
