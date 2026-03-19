package me.rerere.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

private data class ListInfo(
    val level: Int,
    val isNumbered: Boolean,
    val number: Int,
)

object DocxParser {
    fun parse(file: File): String {
        val result = StringBuilder()
        runCatching {
            stream(file) { _, text ->
                if (result.isNotEmpty()) {
                    result.appendLine()
                }
                result.append(text.trimEnd())
                true
            }
        }.onFailure { error ->
            return "Error parsing DOCX file: ${error.message}"
        }
        return result.toString().trim()
    }

    fun stream(
        file: File,
        sink: (blockIndex: Int, text: String) -> Boolean,
    ) {
        file.inputStream().use { fileInputStream ->
            ZipInputStream(fileInputStream).use { zipStream ->
                var entry = zipStream.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        parseDocumentXml(zipStream, sink)
                        return
                    }
                    entry = zipStream.nextEntry
                }
                error("Unable to find document content in DOCX file")
            }
        }
    }

    private fun parseDocumentXml(
        inputStream: InputStream,
        sink: (blockIndex: Int, text: String) -> Boolean,
    ) {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        var inBody = false
        var blockIndex = 0

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "body" -> inBody = true
                        "p" -> if (inBody) {
                            val paragraph = processParagraph(parser)
                            if (paragraph.isNotBlank() && !sink(++blockIndex, paragraph)) {
                                return
                            }
                        }
                        "tbl" -> if (inBody) {
                            val table = processTable(parser)
                            if (table.isNotBlank() && !sink(++blockIndex, table)) {
                                return
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "body") {
                        inBody = false
                    }
                }
            }
            parser.next()
        }
    }

    private fun processParagraph(parser: XmlPullParser): String {
        val paragraphStartDepth = parser.depth
        val paragraphContent = StringBuilder()
        var listInfo: ListInfo? = null
        var headingLevel = 0

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "r" -> extractRunText(parser, paragraphContent)
                        "pPr" -> {
                            listInfo = extractListInfo(parser)
                            headingLevel = extractHeadingLevel(parser)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "p" && parser.depth == paragraphStartDepth) {
                        break
                    }
                }
            }
        }

        val paragraphText = paragraphContent.toString().trim()
        if (paragraphText.isBlank()) return ""

        return when {
            listInfo != null -> {
                val indent = "  ".repeat(listInfo.level)
                val marker = if (listInfo.isNumbered) "${listInfo.number}. " else "- "
                "$indent$marker$paragraphText"
            }

            headingLevel > 0 -> {
                val headingPrefix = "#".repeat(headingLevel)
                "$headingPrefix $paragraphText"
            }

            else -> paragraphText
        }
    }

    private fun extractRunText(parser: XmlPullParser, result: StringBuilder) {
        val runStartDepth = parser.depth
        var isBold = false
        var isItalic = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "rPr" -> {
                            val formatting = extractFormatting(parser)
                            isBold = formatting.first
                            isItalic = formatting.second
                        }

                        "t" -> {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                var text = parser.text ?: ""
                                text = when {
                                    isBold && isItalic -> "***$text***"
                                    isBold -> "**$text**"
                                    isItalic -> "*$text*"
                                    else -> text
                                }
                                result.append(text)
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "r" && parser.depth == runStartDepth) {
                        break
                    }
                }
            }
        }
    }

    private fun extractFormatting(parser: XmlPullParser): Pair<Boolean, Boolean> {
        val rPrStartDepth = parser.depth
        var isBold = false
        var isItalic = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "b" -> isBold = true
                        "i" -> isItalic = true
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "rPr" && parser.depth == rPrStartDepth) {
                        break
                    }
                }
            }
        }

        return isBold to isItalic
    }

    private fun processTable(parser: XmlPullParser): String {
        val tableStartDepth = parser.depth
        val rows = mutableListOf<List<String>>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tr") {
                        val cells = extractTableRow(parser)
                        if (cells.isNotEmpty()) {
                            rows += cells
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "tbl" && parser.depth == tableStartDepth) {
                        break
                    }
                }
            }
        }

        if (rows.isEmpty()) return ""

        val result = StringBuilder()
        val maxCols = rows.maxOfOrNull { it.size } ?: 0
        rows.forEachIndexed { index, row ->
            result.append("| ")
            for (colIndex in 0 until maxCols) {
                result.append(if (colIndex < row.size) row[colIndex] else "")
                result.append(" | ")
            }
            result.appendLine()
            if (index == 0) {
                result.append("| ")
                repeat(maxCols) {
                    result.append("--- | ")
                }
                result.appendLine()
            }
        }
        return result.toString().trim()
    }

    private fun extractTableRow(parser: XmlPullParser): List<String> {
        val rowStartDepth = parser.depth
        val cells = mutableListOf<String>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tc") {
                        cells += extractCellText(parser)
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "tr" && parser.depth == rowStartDepth) {
                        break
                    }
                }
            }
        }

        return cells
    }

    private fun extractCellText(parser: XmlPullParser): String {
        val cellStartDepth = parser.depth
        val result = StringBuilder()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "p") {
                        val paragraphText = extractCellParagraphText(parser)
                        if (paragraphText.isNotBlank()) {
                            if (result.isNotEmpty()) {
                                result.append(' ')
                            }
                            result.append(paragraphText)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "tc" && parser.depth == cellStartDepth) {
                        break
                    }
                }
            }
        }

        return result.toString().trim()
    }

    private fun extractCellParagraphText(parser: XmlPullParser): String {
        val paragraphStartDepth = parser.depth
        val result = StringBuilder()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "r") {
                        extractRunText(parser, result)
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "p" && parser.depth == paragraphStartDepth) {
                        break
                    }
                }
            }
        }

        return result.toString().trim()
    }

    private fun extractListInfo(parser: XmlPullParser): ListInfo? {
        val pPrStartDepth = parser.depth
        var level = 0
        var isNumbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "numPr") {
                        val numInfo = extractNumberingInfo(parser)
                        level = numInfo.first
                        isNumbered = numInfo.second
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "pPr" && parser.depth == pPrStartDepth) {
                        break
                    }
                }
            }
        }

        return if (level > 0 || isNumbered) {
            ListInfo(level = level, isNumbered = isNumbered, number = 1)
        } else {
            null
        }
    }

    private fun extractNumberingInfo(parser: XmlPullParser): Pair<Int, Boolean> {
        val numPrStartDepth = parser.depth
        var level = 0
        var isNumbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "ilvl" -> {
                            parser.getAttributeValue(null, "val")?.let {
                                level = it.toIntOrNull() ?: 0
                            }
                        }

                        "numId" -> {
                            isNumbered = parser.getAttributeValue(null, "val") != "0"
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "numPr" && parser.depth == numPrStartDepth) {
                        break
                    }
                }
            }
        }

        return level to isNumbered
    }

    private fun extractHeadingLevel(parser: XmlPullParser): Int {
        val pPrStartDepth = parser.depth

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "pStyle") {
                        val style = parser.getAttributeValue(null, "val") ?: continue
                        if (style.startsWith("Heading", ignoreCase = true)) {
                            return style.removePrefix("Heading").toIntOrNull()?.coerceIn(1, 6) ?: 0
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "pPr" && parser.depth == pPrStartDepth) {
                        break
                    }
                }
            }
        }

        return 0
    }
}
