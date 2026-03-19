package me.rerere.rikkahub.data.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DocumentTextExtractorTest {

    @Test
    fun `knowledge base support accepts modern office and text formats`() {
        assertTrue(DocumentTextExtractor.isKnowledgeBaseSupported("manual.pdf", null))
        assertTrue(DocumentTextExtractor.isKnowledgeBaseSupported("legacy.doc", "application/msword"))
        assertTrue(DocumentTextExtractor.isKnowledgeBaseSupported("legacy.xls", "application/vnd.ms-excel"))
        assertTrue(DocumentTextExtractor.isKnowledgeBaseSupported("legacy.ppt", "application/vnd.ms-powerpoint"))
        assertTrue(DocumentTextExtractor.isKnowledgeBaseSupported("notes.md", "text/markdown"))
        assertTrue(
            DocumentTextExtractor.isKnowledgeBaseSupported(
                "slides.pptm",
                "application/vnd.ms-powerpoint.presentation.macroEnabled.12"
            )
        )
        assertTrue(
            DocumentTextExtractor.isKnowledgeBaseSupported(
                "sheet.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        )
        assertTrue(DocumentTextExtractor.isKnowledgeBaseSupported("script.kt", "text/plain"))
    }

    @Test
    fun `knowledge base support rejects obvious unsupported binary formats`() {
        assertFalse(DocumentTextExtractor.isKnowledgeBaseSupported("archive.zip", "application/zip"))
        assertFalse(DocumentTextExtractor.isKnowledgeBaseSupported("photo.png", "image/png"))
    }

    @Test
    fun `normalize extracted text trims noise and collapses blank lines`() {
        val normalized = DocumentTextExtractor.normalizeExtractedText(
            "Hello \r\n\r\n\tworld \r\n\r\n\r\n from\t\tKB  "
        )

        assertEquals("Hello\n\n world\n\n from KB", normalized)
    }

    @Test
    fun `stream text reads large plain text files in blocks`() {
        val file = Files.createTempFile("kb-stream", ".txt").toFile().apply {
            writeText((1..4_000).joinToString("\n") { "line-$it" })
            deleteOnExit()
        }

        val blocks = mutableListOf<DocumentTextBlock>()
        DocumentTextExtractor.streamText(file, file.name, "text/plain") { block ->
            blocks += block
            true
        }

        assertTrue(blocks.size > 1)
        assertTrue(blocks.all { it.text.isNotBlank() })
        assertEquals("文本块", blocks.first().progressLabel)
    }

    @Test
    fun `extract preview text truncates streamed content`() {
        val file = Files.createTempFile("kb-preview", ".md").toFile().apply {
            writeText((1..2_000).joinToString("\n") { "preview-$it" })
            deleteOnExit()
        }

        val preview = DocumentTextExtractor.extractPreviewText(
            file = file,
            fileName = file.name,
            mimeType = "text/markdown",
            maxChars = 256
        )

        assertTrue(preview.isNotBlank())
        assertTrue(preview.length <= 256)
    }
}
