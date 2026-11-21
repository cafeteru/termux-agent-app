package com.termux.agent.presentation

import com.termux.agent.infrastructure.terminal.AnsiTextParser

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

sealed class MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
    data class BulletItem(val text: String) : MarkdownBlock()
}

object MarkdownParser {

    fun parse(raw: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val lines = raw.lines()
        var i = 0
        val n = lines.size

        while (i < n) {
            val line = lines[i]

            // 1. Code block fence
            if (line.trimStart().startsWith("```")) {
                val language = line.trimStart().removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < n && !lines[i].trimStart().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                if (i < n && lines[i].trimStart().startsWith("```")) {
                    i++ // skip closing fence
                }
                blocks.add(MarkdownBlock.CodeBlock(language, codeLines.joinToString("\n")))
                continue
            }

            // 2. Headers
            val trimmed = line.trim()
            if (trimmed.startsWith("### ")) {
                blocks.add(MarkdownBlock.Header(3, trimmed.removePrefix("### ").trim()))
                i++
                continue
            }
            if (trimmed.startsWith("## ")) {
                blocks.add(MarkdownBlock.Header(2, trimmed.removePrefix("## ").trim()))
                i++
                continue
            }
            if (trimmed.startsWith("# ")) {
                blocks.add(MarkdownBlock.Header(1, trimmed.removePrefix("# ").trim()))
                i++
                continue
            }

            // 3. Bullet items
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                blocks.add(MarkdownBlock.BulletItem(trimmed.substring(2).trim()))
                i++
                continue
            }

            // 4. Blank line
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // 5. Paragraph: collect contiguous non-empty, non-block lines
            val paraLines = mutableListOf<String>()
            while (i < n) {
                val cur = lines[i]
                val curTrimmed = cur.trim()
                if (curTrimmed.isEmpty() ||
                    curTrimmed.startsWith("```") ||
                    curTrimmed.startsWith("# ") ||
                    curTrimmed.startsWith("## ") ||
                    curTrimmed.startsWith("### ") ||
                    curTrimmed.startsWith("- ") ||
                    curTrimmed.startsWith("* ")
                ) {
                    break
                }
                paraLines.add(cur)
                i++
            }
            if (paraLines.isNotEmpty()) {
                blocks.add(MarkdownBlock.Paragraph(paraLines.joinToString("\n")))
            }
        }

        return blocks
    }

    /**
     * Parses inline markdown (code, bold, italic) into an AnnotatedString.
     */
    fun parseInline(text: String, inlineCodeBg: Color = Color(0x33888888)): AnnotatedString {
        return buildAnnotatedString {
            // Regex to find inline code `code`, bold **text**, and italic *text*
            val tokenRegex = Regex("""(`[^`]+`|\*\*[^*]+\*\*|\*[^*]+\*)""")
            var lastIndex = 0

            for (match in tokenRegex.findAll(text)) {
                val matchRange = match.range
                if (matchRange.first > lastIndex) {
                    append(text.substring(lastIndex, matchRange.first))
                }

                val token = match.value
                when {
                    token.startsWith("`") && token.endsWith("`") -> {
                        val code = token.removeSurrounding("`")
                        val start = length
                        append(code)
                        addStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = inlineCodeBg
                            ),
                            start,
                            length
                        )
                    }
                    token.startsWith("**") && token.endsWith("**") -> {
                        val bold = token.removeSurrounding("**")
                        val start = length
                        append(bold)
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                    }
                    token.startsWith("*") && token.endsWith("*") -> {
                        val italic = token.removeSurrounding("*")
                        val start = length
                        append(italic)
                        addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                    }
                    else -> append(token)
                }
                lastIndex = matchRange.last + 1
            }

            if (lastIndex < text.length) {
                append(text.substring(lastIndex))
            }
        }
    }
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val blocks = MarkdownParser.parse(markdown)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Header -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = MarkdownParser.parseInline(block.text),
                        style = style,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = MarkdownParser.parseInline(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor
                    )
                }
                is MarkdownBlock.BulletItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                        Text(
                            text = MarkdownParser.parseInline(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownBlock.CodeBlock -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E1E1E)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            if (block.language.isNotBlank()) {
                                Text(
                                    text = block.language,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF9E9E9E),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            SelectionContainer {
                                Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                    Text(
                                        text = block.code,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFFD4D4D4)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
