package me.rerere.document

import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hslf.usermodel.HSLFTextBox
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.Rectangle
import java.nio.file.Files

class LegacyOfficeParserTest {

    @Test
    fun `xls parser extracts sheet text`() {
        val file = Files.createTempFile("legacy-sheet", ".xls").toFile().apply {
            outputStream().use { output ->
                HSSFWorkbook().use { workbook ->
                    val sheet = workbook.createSheet("Data")
                    val header = sheet.createRow(0)
                    header.createCell(0).setCellValue("Name")
                    header.createCell(1).setCellValue("Value")
                    val row = sheet.createRow(1)
                    row.createCell(0).setCellValue("Alpha")
                    row.createCell(1).setCellValue("42")
                    workbook.write(output)
                }
            }
            deleteOnExit()
        }

        val parsed = XlsParser.parse(file)

        assertTrue(parsed.contains("Sheet: Data"))
        assertTrue(parsed.contains("Alpha"))
        assertTrue(parsed.contains("42"))
    }

    @Test
    fun `ppt parser extracts slide text`() {
        val file = Files.createTempFile("legacy-slide", ".ppt").toFile().apply {
            outputStream().use { output ->
                HSLFSlideShow().use { slideShow ->
                    slideShow.pageSize = Dimension(720, 540)
                    val slide = slideShow.createSlide()
                    val textBox = HSLFTextBox().apply {
                        text = "Legacy PPT content"
                        anchor = Rectangle(50, 50, 600, 120)
                    }
                    slide.addShape(textBox)
                    slideShow.write(output)
                }
            }
            deleteOnExit()
        }

        val parsed = PptParser.parse(file)

        assertTrue(parsed.contains("Slide 1"))
        assertTrue(parsed.contains("Legacy PPT content"))
    }
}
