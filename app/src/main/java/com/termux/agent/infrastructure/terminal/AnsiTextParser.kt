package com.termux.agent.infrastructure.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Parses ANSI SGR color and text-style escape sequences into Compose [AnnotatedString].
 */
object AnsiTextParser {
    private val OSC_REGEX = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    private val CHARSET_REGEX = Regex("\u001B[()][0-9A-Za-z]")
    private val CSI_REGEX = Regex("\u001B\\[([0-9;?]*)([A-Za-z])")
    private val UNSUPPORTED_CSI_REGEX = Regex("\u001B\\[[0-9;]*(?:>|\\?|:)[-0-9;?:]*[A-Za-z]")
    private val STRAY_ESC_REGEX = Regex("\u001B(?:\\[[^A-Za-z]*[A-Za-z])?|[\u0007]")

    private val ANSI_COLORS = listOf(
        Color(0xFF4E4E4E), // 0: black
        Color(0xFFCC0000), // 1: red
        Color(0xFF4E9A06), // 2: green
        Color(0xFFC4A000), // 3: yellow
        Color(0xFF3465A4), // 4: blue
        Color(0xFF75507B), // 5: magenta
        Color(0xFF06989A), // 6: cyan
        Color(0xFFD3D7CF), // 7: white
        Color(0xFF767676), // 8: bright black
        Color(0xFFEF2929), // 9: bright red
        Color(0xFF8AE234), // 10: bright green
        Color(0xFFFCE94F), // 11: bright yellow
        Color(0xFF729FCF), // 12: bright blue
        Color(0xFFAD7FA8), // 13: bright magenta
        Color(0xFF34E2E2), // 14: bright cyan
        Color(0xFFEEEEEC)  // 15: bright white
    )

    private data class SgrState(
        val foreground: Color = Color.Unspecified,
        val background: Color = Color.Unspecified,
        val bold: Boolean = false,
        val underline: Boolean = false
    ) {
        fun toSpanStyleOrNull(): SpanStyle? {
            if (foreground == Color.Unspecified &&
                background == Color.Unspecified &&
                !bold &&
                !underline
            ) {
                return null
            }
            return SpanStyle(
                color = foreground,
                background = background,
                fontWeight = if (bold) FontWeight.Bold else null,
                textDecoration = if (underline) TextDecoration.Underline else null
            )
        }
    }

    /**
     * Converts raw PTY output containing ANSI escapes into styled [AnnotatedString].
     */
    fun parse(raw: String): AnnotatedString {
        if (raw.isEmpty()) return AnnotatedString("")

        val cleaned = raw
            .replace(OSC_REGEX, "")
            .replace(CHARSET_REGEX, "")
            .replace(UNSUPPORTED_CSI_REGEX, "")

        return buildAnnotatedString {
            var style = SgrState()
            var cursor = 0

            for (match in CSI_REGEX.findAll(cleaned)) {
                if (match.range.first > cursor) {
                    appendStyled(cleaned.substring(cursor, match.range.first), style)
                }
                if (match.groupValues[2] == "m") {
                    style = applySgr(style, match.groupValues[1])
                }
                cursor = match.range.last + 1
            }

            if (cursor < cleaned.length) {
                appendStyled(cleaned.substring(cursor), style)
            }
        }
    }

    private fun AnnotatedString.Builder.appendStyled(text: String, style: SgrState) {
        val visibleText = text.replace(STRAY_ESC_REGEX, "")
        if (visibleText.isEmpty()) return

        val span = style.toSpanStyleOrNull()
        if (span == null) {
            append(visibleText)
        } else {
            pushStyle(span)
            append(visibleText)
            pop()
        }
    }

    private fun applySgr(current: SgrState, params: String): SgrState {
        if (params.isEmpty()) return SgrState()
        if (params.contains('?')) return current

        val codes = params.split(';').map { it.toIntOrNull() ?: 0 }
        var state = current
        var index = 0

        while (index < codes.size) {
            val code = codes[index]
            when (code) {
                0 -> state = SgrState()
                1 -> state = state.copy(bold = true)
                4 -> state = state.copy(underline = true)
                22 -> state = state.copy(bold = false)
                24 -> state = state.copy(underline = false)
                in 30..37 -> state = state.copy(foreground = ANSI_COLORS[code - 30])
                39 -> state = state.copy(foreground = Color.Unspecified)
                in 40..47 -> state = state.copy(background = ANSI_COLORS[code - 40])
                49 -> state = state.copy(background = Color.Unspecified)
                in 90..97 -> state = state.copy(foreground = ANSI_COLORS[code - 90 + 8])
                in 100..107 -> state = state.copy(background = ANSI_COLORS[code - 100 + 8])
                38, 48 -> {
                    val (parsedColor, consumed) = parseExtendedColor(codes, index)
                    if (parsedColor != null) {
                        state = if (code == 38) {
                            state.copy(foreground = parsedColor)
                        } else {
                            state.copy(background = parsedColor)
                        }
                    }
                    index += consumed
                }
            }
            index++
        }
        return state
    }

    private fun parseExtendedColor(codes: List<Int>, startIndex: Int): Pair<Color?, Int> {
        val mode = codes.getOrNull(startIndex + 1) ?: return null to 0
        return when (mode) {
            5 -> {
                val colorIndex = codes.getOrNull(startIndex + 2) ?: return null to 1
                xterm256Color(colorIndex) to 2
            }
            2 -> {
                val r = codes.getOrNull(startIndex + 2)?.coerceIn(0, 255) ?: return null to 1
                val g = codes.getOrNull(startIndex + 3)?.coerceIn(0, 255) ?: return null to 2
                val b = codes.getOrNull(startIndex + 4)?.coerceIn(0, 255) ?: return null to 3
                Color(r, g, b) to 4
            }
            else -> null to 0
        }
    }

    private fun xterm256Color(index: Int): Color? {
        if (index !in 0..255) return null
        if (index < 16) return ANSI_COLORS[index]
        if (index >= 232) {
            val gray = 8 + (index - 232) * 10
            return Color(gray, gray, gray)
        }
        val cube = index - 16
        val r = cubeToByte(cube / 36)
        val g = cubeToByte((cube % 36) / 6)
        val b = cubeToByte(cube % 6)
        return Color(r, g, b)
    }

    private fun cubeToByte(value: Int): Int = if (value == 0) 0 else 55 + value * 40
}
