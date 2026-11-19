package com.termux.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {

    @Test
    fun parseHeadersAndParagraphs() {
        val markdown = """
            # Header 1
            This is a normal paragraph with **bold** text.
            
            ## Header 2
            Another paragraph with *italic* words.
            
            ### Header 3
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(5, blocks.size)

        assertTrue(blocks[0] is MarkdownBlock.Header)
        val h1 = blocks[0] as MarkdownBlock.Header
        assertEquals(1, h1.level)
        assertEquals("Header 1", h1.text)

        assertTrue(blocks[1] is MarkdownBlock.Paragraph)
        val p1 = blocks[1] as MarkdownBlock.Paragraph
        assertEquals("This is a normal paragraph with **bold** text.", p1.text)

        assertTrue(blocks[2] is MarkdownBlock.Header)
        val h2 = blocks[2] as MarkdownBlock.Header
        assertEquals(2, h2.level)
        assertEquals("Header 2", h2.text)

        assertTrue(blocks[3] is MarkdownBlock.Paragraph)

        assertTrue(blocks[4] is MarkdownBlock.Header)
        val h3 = blocks[4] as MarkdownBlock.Header
        assertEquals(3, h3.level)
        assertEquals("Header 3", h3.text)
    }

    @Test
    fun parseFencedCodeBlock() {
        val markdown = """
            Prefix text
            ```kotlin
            fun main() {
                println("Hello Termux")
            }
            ```
            Suffix text
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(3, blocks.size)

        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertTrue(blocks[1] is MarkdownBlock.CodeBlock)
        val code = blocks[1] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("fun main() {\n    println(\"Hello Termux\")\n}", code.code)

        assertTrue(blocks[2] is MarkdownBlock.Paragraph)
    }

    @Test
    fun parseBulletList() {
        val markdown = """
            Features:
            - Item one
            - Item two with `code`
            * Item three
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(4, blocks.size)

        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertTrue(blocks[1] is MarkdownBlock.BulletItem)
        assertEquals("Item one", (blocks[1] as MarkdownBlock.BulletItem).text)

        assertTrue(blocks[2] is MarkdownBlock.BulletItem)
        assertEquals("Item two with `code`", (blocks[2] as MarkdownBlock.BulletItem).text)

        assertTrue(blocks[3] is MarkdownBlock.BulletItem)
        assertEquals("Item three", (blocks[3] as MarkdownBlock.BulletItem).text)
    }

    @Test
    fun parseInlineFormatting() {
        val raw = "Hello `code` and **bold** and *italic* end."
        val annotated = MarkdownParser.parseInline(raw)
        assertEquals("Hello code and bold and italic end.", annotated.text)
        assertEquals(3, annotated.spanStyles.size)
    }
}
