package me.rerere.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

object PptxParser {
    fun parse(file: File): String {
        val result = StringBuilder()
        runCatching {
            stream(file) { _, _, text ->
                if (result.isNotEmpty()) {
                    result.appendLine()
                }
                result.append(text.trimEnd())
                true
            }
        }.onFailure { error ->
            return "Error parsing PPTX file: ${error.message}"
        }
        return result.toString().trim()
    }

    fun stream(
        file: File,
        sink: (slideNumber: Int, totalSlides: Int, text: String) -> Boolean,
    ) {
        ZipFile(file).use { zipFile ->
            val slideEntries = zipFile.entries().toList()
                .filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { entry ->
                    entry.name.substringAfter("slide").substringBefore(".xml").toIntOrNull() ?: 0
                }

            if (slideEntries.isEmpty()) {
                error("No slides found in PPTX file")
            }

            val totalSlides = slideEntries.size
            slideEntries.forEachIndexed { index, entry ->
                val slideNumber = index + 1
                val slideContent = zipFile.getInputStream(entry).use(::parseSlideXml)
                val notesEntry = zipFile.getEntry("ppt/notesSlides/notesSlide$slideNumber.xml")
                val notes = notesEntry?.let { zipFile.getInputStream(it).use(::parseNotesXml) }.orEmpty()
                val rendered = buildString {
                    append("## Slide $slideNumber")
                    appendLine()
                    appendLine()
                    append(slideContent.trim())
                    if (notes.isNotBlank()) {
                        appendLine()
                        appendLine()
                        append("### Speaker Notes")
                        appendLine()
                        appendLine()
                        append(notes.trim())
                    }
                }.trim()
                if (!sink(slideNumber, totalSlides, rendered)) {
                    return
                }
            }
        }
    }

    private fun parseSlideXml(inputStream: InputStream): String {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val result = StringBuilder()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "sp" -> processShape(parser, result)
                        "graphicFrame" -> processGraphicFrame(parser, result)
                    }
                }
            }
            parser.next()
        }

        return result.toString().trim()
    }

    private fun parseNotesXml(inputStream: InputStream): String {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val result = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "t") {
                parser.next()
                if (parser.eventType == XmlPullParser.TEXT) {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        if (result.isNotEmpty()) {
                            result.appendLine()
                        }
                        result.append(text)
                    }
                }
            }
            parser.next()
        }

        return result.toString().trim()
    }

    private fun processShape(parser: XmlPullParser, result: StringBuilder) {
        val shapeStartDepth = parser.depth
        val textContent = StringBuilder()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "p") {
                        processParagraph(parser, textContent)
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "sp" && parser.depth == shapeStartDepth) {
                        break
                    }
                }
            }
        }

        val text = textContent.toString().trim()
        if (text.isNotBlank()) {
            if (result.isNotEmpty()) {
                result.appendLine()
                result.appendLine()
            }
            result.append(text)
        }
    }

    private fun processParagraph(parser: XmlPullParser, result: StringBuilder) {
        val paragraphStartDepth = parser.depth
        val paragraphText = StringBuilder()
        var hasBullet = false
        var bulletLevel = 0
        var isNumbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "pPr" -> {
                            val bulletInfo = extractBulletInfo(parser)
                            hasBullet = bulletInfo.first
                            bulletLevel = bulletInfo.second
                            isNumbered = bulletInfo.third
                        }

                        "r" -> extractTextRun(parser, paragraphText)
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "p" && parser.depth == paragraphStartDepth) {
                        break
                    }
                }
            }
        }

        val text = paragraphText.toString().trim()
        if (text.isBlank()) return

        if (result.isNotEmpty()) {
            result.appendLine()
        }
        if (hasBullet) {
            val indent = "  ".repeat(bulletLevel)
            val marker = if (isNumbered) "1. " else "- "
            result.append(indent)
            result.append(marker)
        }
        result.append(text)
    }

    private fun extractBulletInfo(parser: XmlPullParser): Triple<Boolean, Int, Boolean> {
        val pPrStartDepth = parser.depth
        var hasBullet = false
        var level = 0
        var isNumbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "buChar" -> {
                            hasBullet = true
                            isNumbered = false
                        }

                        "buAutoNum" -> {
                            hasBullet = true
                            isNumbered = true
                        }

                        "lvl" -> {
                            parser.getAttributeValue(null, "val")?.let {
                                level = it.toIntOrNull() ?: 0
                            }
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

        return Triple(hasBullet, level, isNumbered)
    }

    private fun extractTextRun(parser: XmlPullParser, result: StringBuilder) {
        val runStartDepth = parser.depth

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        parser.next()
                        if (parser.eventType == XmlPullParser.TEXT) {
                            result.append(parser.text ?: "")
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

    private fun processGraphicFrame(parser: XmlPullParser, result: StringBuilder) {
        val frameStartDepth = parser.depth

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tbl") {
                        val tableText = processTable(parser)
                        if (tableText.isNotBlank()) {
                            if (result.isNotEmpty()) {
                                result.appendLine()
                                result.appendLine()
                            }
                            result.append(tableText)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "graphicFrame" && parser.depth == frameStartDepth) {
                        break
                    }
                }
            }
        }
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
                    if (parser.name == "txBody") {
                        val cellText = extractTextBody(parser)
                        if (cellText.isNotBlank()) {
                            result.append(cellText)
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

    private fun extractTextBody(parser: XmlPullParser): String {
        val bodyStartDepth = parser.depth
        val result = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "p") {
                        processParagraph(parser, result)
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "txBody" && parser.depth == bodyStartDepth) {
                        break
                    }
                }
            }
        }
        return result.toString().trim()
    }
}
