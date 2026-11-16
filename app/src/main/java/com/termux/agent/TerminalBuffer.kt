package com.termux.agent

/**
 * In-memory 2D terminal buffer that handles ANSI/VT100 escape codes, carriage returns,
 * backspaces, and line-editing control sequences so interactive shell output and line
 * redraws (history navigation, tab completion, editing) overwrite properly instead of concatenating.
 */
class TerminalBuffer(private val maxLines: Int = 1_000) {

    data class StyledChar(val char: Char, val sgr: String = "")

    private val lines = ArrayList<ArrayList<StyledChar>>().apply {
        add(ArrayList())
    }
    private var cursorRow: Int = 0
    private var cursorCol: Int = 0
    private var currentSgr: String = ""

    @Synchronized
    fun feed(data: String) {
        if (data.isEmpty()) return

        var i = 0
        val len = data.length

        while (i < len) {
            val c = data[i]

            when {
                // Carriage Return: move cursor to column 0 without advancing row
                c == '\r' -> {
                    cursorCol = 0
                    i++
                }

                // Line Feed: advance row, reset column
                c == '\n' -> {
                    advanceRow()
                    cursorCol = 0
                    i++
                }

                // Backspace: move cursor backward 1 column
                c == '\u0008' || c == '\u007F' -> {
                    if (c == '\u0008') {
                        cursorCol = maxOf(0, cursorCol - 1)
                    }
                    i++
                }

                // Tab: advance cursor to next 8-column tab stop
                c == '\t' -> {
                    val nextStop = ((cursorCol / 8) + 1) * 8
                    while (cursorCol < nextStop) {
                        writeChar(' ')
                    }
                    i++
                }

                // Escape sequences
                c == '\u001B' -> {
                    i = handleEscapeSequence(data, i)
                }

                // Normal printable characters
                c >= ' ' -> {
                    writeChar(c)
                    i++
                }

                // Other control characters (bell, null, etc.) -> ignore
                else -> {
                    i++
                }
            }
        }
    }

    private fun handleEscapeSequence(data: String, start: Int): Int {
        val len = data.length
        if (start + 1 >= len) return len

        val next = data[start + 1]

        // CSI: \u001B[
        if (next == '[') {
            var j = start + 2
            // Read parameter bytes (digits, ';', '?', '>')
            while (j < len && (data[j].isDigit() || data[j] == ';' || data[j] == '?' || data[j] == '>')) {
                j++
            }
            if (j < len) {
                val finalByte = data[j]
                val param = data.substring(start + 2, j)
                handleCsi(finalByte, param)
                return j + 1
            }
            return len
        }

        // OSC: \u001B] ... (\u0007 | \u001B\)
        if (next == ']') {
            var j = start + 2
            while (j < len) {
                if (data[j] == '\u0007') {
                    return j + 1
                }
                if (data[j] == '\u001B' && j + 1 < len && data[j + 1] == '\\') {
                    return j + 2
                }
                j++
            }
            return len
        }

        // 2-byte escape sequences like charset designation \u001B(B or stray escapes
        if (next in "()#*+") {
            return minOf(len, start + 3)
        }

        return start + 2
    }

    private fun handleCsi(command: Char, param: String) {
        val currentLine = ensureCurrentLine()

        when (command) {
            // SGR: Set Graphics Rendition (colors, bold, etc.)
            'm' -> {
                if (param.isEmpty() || param == "0") {
                    currentSgr = ""
                } else {
                    currentSgr = "\u001B[${param}m"
                }
            }

            // Erase in Line: EL
            'K' -> {
                val mode = param.toIntOrNull() ?: 0
                when (mode) {
                    // Erase from cursor to end of line
                    0 -> {
                        if (cursorCol < currentLine.size) {
                            currentLine.subList(cursorCol, currentLine.size).clear()
                        }
                    }
                    // Erase from start of line to cursor
                    1 -> {
                        val limit = minOf(cursorCol + 1, currentLine.size)
                        for (idx in 0 until limit) {
                            currentLine[idx] = StyledChar(' ', currentSgr)
                        }
                    }
                    // Erase entire line
                    2 -> {
                        currentLine.clear()
                    }
                }
            }

            // Erase in Display: ED
            'J' -> {
                val mode = param.toIntOrNull() ?: 0
                if (mode == 2 || mode == 3) {
                    lines.clear()
                    lines.add(ArrayList())
                    cursorRow = 0
                    cursorCol = 0
                }
            }

            // Delete characters: DCH
            'P' -> {
                val count = param.toIntOrNull() ?: 1
                if (cursorCol < currentLine.size) {
                    val end = minOf(cursorCol + count, currentLine.size)
                    currentLine.subList(cursorCol, end).clear()
                }
            }

            // Insert blank characters: ICH
            '@' -> {
                val count = param.toIntOrNull() ?: 1
                padLineToCursor(currentLine)
                repeat(count) {
                    currentLine.add(cursorCol, StyledChar(' ', currentSgr))
                }
            }

            // Cursor Backward: CUB
            'D' -> {
                val count = param.toIntOrNull() ?: 1
                cursorCol = maxOf(0, cursorCol - count)
            }

            // Cursor Forward: CUF
            'C' -> {
                val count = param.toIntOrNull() ?: 1
                cursorCol += count
            }

            // Cursor Up: CUU
            'A' -> {
                val count = param.toIntOrNull() ?: 1
                cursorRow = maxOf(0, cursorRow - count)
            }

            // Cursor Down: CUD
            'B' -> {
                val count = param.toIntOrNull() ?: 1
                cursorRow += count
                ensureLineExists(cursorRow)
            }

            // Cursor Position: CUP / HVP
            'H', 'f' -> {
                val parts = param.split(';')
                val r = parts.getOrNull(0)?.toIntOrNull() ?: 1
                val c = parts.getOrNull(1)?.toIntOrNull() ?: 1
                cursorRow = maxOf(0, r - 1)
                cursorCol = maxOf(0, c - 1)
                ensureLineExists(cursorRow)
            }
        }
    }

    private fun writeChar(char: Char) {
        val currentLine = ensureCurrentLine()
        padLineToCursor(currentLine)

        val styled = StyledChar(char, currentSgr)
        if (cursorCol < currentLine.size) {
            currentLine[cursorCol] = styled
        } else {
            currentLine.add(styled)
        }
        cursorCol++
    }

    private fun ensureCurrentLine(): ArrayList<StyledChar> {
        ensureLineExists(cursorRow)
        return lines[cursorRow]
    }

    private fun ensureLineExists(targetRow: Int) {
        while (lines.size <= targetRow) {
            lines.add(ArrayList())
        }
    }

    private fun padLineToCursor(line: ArrayList<StyledChar>) {
        while (line.size < cursorCol) {
            line.add(StyledChar(' ', currentSgr))
        }
    }

    private fun advanceRow() {
        cursorRow++
        ensureLineExists(cursorRow)
        if (lines.size > maxLines) {
            val removeCount = lines.size - maxLines
            for (idx in 0 until removeCount) {
                lines.removeAt(0)
            }
            cursorRow = maxOf(0, cursorRow - removeCount)
        }
    }

    @Synchronized
    fun render(): String {
        val sb = StringBuilder()
        var activeSgr = ""

        for (lineIdx in lines.indices) {
            val line = lines[lineIdx]
            for (cell in line) {
                if (cell.sgr != activeSgr) {
                    if (cell.sgr.isEmpty()) {
                        sb.append("\u001B[0m")
                    } else {
                        sb.append(cell.sgr)
                    }
                    activeSgr = cell.sgr
                }
                sb.append(cell.char)
            }
            if (activeSgr.isNotEmpty()) {
                sb.append("\u001B[0m")
                activeSgr = ""
            }
            if (lineIdx < lines.size - 1) {
                sb.append('\n')
            }
        }

        return sb.toString()
    }

    @Synchronized
    fun renderPlainText(): String {
        val sb = StringBuilder()
        for (lineIdx in lines.indices) {
            val line = lines[lineIdx]
            for (cell in line) {
                sb.append(cell.char)
            }
            if (lineIdx < lines.size - 1) {
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    @Synchronized
    fun clear() {
        lines.clear()
        lines.add(ArrayList())
        cursorRow = 0
        cursorCol = 0
        currentSgr = ""
    }

    @Synchronized
    fun getCurrentLineText(): String {
        val row = cursorRow.coerceIn(0, lines.size - 1)
        val line = lines[row]
        val sb = StringBuilder()
        for (cell in line) {
            sb.append(cell.char)
        }
        return sb.toString()
    }

    companion object {
        /**
         * Extracts the active user command following a standard shell prompt ($ , # , % , ❯ , > ).
         * Returns null if the line does not appear to be a shell prompt line.
         */
        fun extractCommandFromLine(line: String): String? {
            val promptMarkers = listOf("$ ", "# ", "% ", "❯ ", "> ")
            for (marker in promptMarkers) {
                val idx = line.indexOf(marker)
                if (idx >= 0) {
                    return line.substring(idx + marker.length)
                }
            }
            return null
        }
    }
}
