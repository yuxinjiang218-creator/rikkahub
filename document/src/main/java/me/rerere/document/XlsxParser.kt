package me.rerere.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

private data class WorkbookSheet(
    val name: String,
    val relationId: String,
)

private data class WorksheetTarget(
    val name: String,
    val path: String,
)

object XlsxParser {
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
            return "Error parsing XLSX file: ${error.message}"
        }
        return result.toString().trim()
    }

    fun stream(
        file: File,
        sink: (blockIndex: Int, totalBlocks: Int, text: String) -> Boolean,
    ) {
        ZipFile(file).use { zipFile ->
            val sharedStrings = zipFile.getEntry("xl/sharedStrings.xml")
                ?.let { zipFile.getInputStream(it).use(::parseSharedStrings) }
                .orEmpty()
            val sheets = parseSheetTargets(zipFile)
            if (sheets.isEmpty()) {
                error("No sheets found in XLSX file")
            }

            val totalBlocks = sheets.size
            sheets.forEachIndexed { index, sheet ->
                val entry = zipFile.getEntry(sheet.path) ?: return@forEachIndexed
                val rendered = zipFile.getInputStream(entry).use { input ->
                    parseSheetXml(sheet.name, input, sharedStrings)
                }
                if (rendered.isNotBlank() && !sink(index + 1, totalBlocks, rendered)) {
                    return
                }
            }
        }
    }

    private fun parseSheetTargets(zipFile: ZipFile): List<WorksheetTarget> {
        val workbookEntry = zipFile.getEntry("xl/workbook.xml") ?: return emptyList()
        val relationsEntry = zipFile.getEntry("xl/_rels/workbook.xml.rels") ?: return emptyList()

        val sheets = zipFile.getInputStream(workbookEntry).use(::parseWorkbookSheets)
        val relations = zipFile.getInputStream(relationsEntry).use(::parseWorkbookRelationships)

        return sheets.mapIndexedNotNull { index, sheet ->
            val target = relations[sheet.relationId] ?: "worksheets/sheet${index + 1}.xml"
            WorksheetTarget(
                name = sheet.name.ifBlank { "Sheet ${index + 1}" },
                path = normalizeZipPath("xl", target)
            )
        }
    }

    private fun parseWorkbookSheets(inputStream: InputStream): List<WorkbookSheet> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val sheets = mutableListOf<WorkbookSheet>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                val name = parser.getAttributeValue(null, "name") ?: "Sheet"
                val relationId = parser.getAttributeValue(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                    "id"
                ) ?: parser.getAttributeValue(null, "id")
                if (!relationId.isNullOrBlank()) {
                    sheets += WorkbookSheet(name = name, relationId = relationId)
                }
            }
            parser.next()
        }
        return sheets
    }

    private fun parseWorkbookRelationships(inputStream: InputStream): Map<String, String> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val relations = mutableMapOf<String, String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                    relations[id] = target
                }
            }
            parser.next()
        }
        return relations
    }

    private fun parseSharedStrings(inputStream: InputStream): List<String> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val values = mutableListOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "si") {
                values += parseSharedStringItem(parser)
            }
            parser.next()
        }
        return values
    }

    private fun parseSharedStringItem(parser: XmlPullParser): String {
        val itemDepth = parser.depth
        val text = StringBuilder()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        text.append(parser.readElementText())
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "si" && parser.depth == itemDepth) {
                        break
                    }
                }
            }
        }
        return text.toString()
    }

    private fun parseSheetXml(
        sheetName: String,
        inputStream: InputStream,
        sharedStrings: List<String>,
    ): String {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val result = StringBuilder()
        val rowBuffer = mutableListOf<String>()
        var emittedHeader = false

        fun flushRows() {
            if (rowBuffer.isEmpty()) return
            if (!emittedHeader) {
                result.append("## Sheet: ")
                result.append(sheetName)
                result.appendLine()
                result.appendLine()
                emittedHeader = true
            } else if (result.isNotEmpty()) {
                result.appendLine()
            }
            result.append(rowBuffer.joinToString("\n"))
            rowBuffer.clear()
        }

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "row") {
                val rowText = parseRow(parser, sharedStrings)
                if (rowText.isNotBlank()) {
                    rowBuffer += rowText
                    if (rowBuffer.size >= 128 || rowBuffer.sumOf { it.length } >= 16_384) {
                        flushRows()
                    }
                }
            }
            parser.next()
        }

        flushRows()
        return result.toString().trim()
    }

    private fun parseRow(parser: XmlPullParser, sharedStrings: List<String>): String {
        val rowDepth = parser.depth
        val cells = mutableListOf<String>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "c") {
                        val cell = parseCell(parser, sharedStrings)
                        if (cell.isNotBlank()) {
                            cells += cell
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "row" && parser.depth == rowDepth) {
                        break
                    }
                }
            }
        }

        return cells.joinToString(" | ").trim()
    }

    private fun parseCell(parser: XmlPullParser, sharedStrings: List<String>): String {
        val cellDepth = parser.depth
        val cellType = parser.getAttributeValue(null, "t")
        var rawValue = ""

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "v" || parser.name == "t") {
                        rawValue = parser.readElementText()
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "c" && parser.depth == cellDepth) {
                        break
                    }
                }
            }
        }

        return when (cellType) {
            "s" -> rawValue.toIntOrNull()
                ?.let { sharedStrings.getOrNull(it) }
                .orEmpty()

            "b" -> if (rawValue == "1") "TRUE" else "FALSE"
            else -> rawValue
        }.trim()
    }

    private fun XmlPullParser.readElementText(): String {
        if (next() != XmlPullParser.TEXT) return ""
        return text ?: ""
    }

    private fun normalizeZipPath(base: String, target: String): String {
        val normalizedBase = base.trimEnd('/')
        val normalizedTarget = target.removePrefix("/").removePrefix("./")
        return if (normalizedTarget.startsWith("$normalizedBase/")) {
            normalizedTarget
        } else {
            "$normalizedBase/$normalizedTarget"
        }
    }
}
