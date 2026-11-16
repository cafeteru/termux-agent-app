package com.termux.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBufferTest {

    @Test
    fun carriageReturnMovesCursorToColumnZeroAndOverwrites() {
        val buffer = TerminalBuffer()
        buffer.feed("hello world\rhi")
        assertEquals("hillo world", buffer.renderPlainText())
    }

    @Test
    fun carriageReturnWithClearLineReplacesEntireLine() {
        val buffer = TerminalBuffer()
        buffer.feed("~/repo $ git switch main\r~/repo $ git pull\u001B[K")
        assertEquals("~/repo $ git pull", buffer.renderPlainText())
    }

    @Test
    fun backspaceAndEraseInLineDeletesCharacter() {
        val buffer = TerminalBuffer()
        buffer.feed("abc\u0008\u001B[K")
        assertEquals("ab", buffer.renderPlainText())
    }

    @Test
    fun readlineHistoryUpNavigationOverwritesInsteadOfConcatenating() {
        val buffer = TerminalBuffer()
        // Simulate bash prompt and first command
        buffer.feed("bash$ echo second_cmd")
        assertEquals("bash$ echo second_cmd", buffer.renderPlainText())

        // Simulate bash UP arrow readline sequence: backspaces, delete char, write "first", cursor forward
        buffer.feed("\u0008\u0008\u0008\u0008\u0008\u0008\u0008\u0008\u0008\u0008\u001B[1Pfirst\u001B[C\u001B[C\u001B[C\u001B[C")
        assertEquals("bash$ echo first_cmd", buffer.renderPlainText())
    }

    @Test
    fun tabStopAdvancesEveryEightColumns() {
        val buffer = TerminalBuffer()
        buffer.feed("a\tb")
        assertEquals("a       b", buffer.renderPlainText())
    }

    @Test
    fun multiLineTextAdvancesAndBoundsLines() {
        val buffer = TerminalBuffer(maxLines = 3)
        buffer.feed("line1\nline2\nline3\nline4")
        val lines = buffer.renderPlainText().split('\n')
        assertEquals(3, lines.size)
        assertEquals("line2", lines[0])
        assertEquals("line3", lines[1])
        assertEquals("line4", lines[2])
    }

    @Test
    fun preservesAnsiSgrColorsWhenRendering() {
        val buffer = TerminalBuffer()
        buffer.feed("\u001B[1;32muser@termux\u001B[0m:~$ ")
        val rendered = buffer.render()
        assertTrue(rendered.contains("\u001B[1;32muser@termux"))
        val parsed = AnsiTextParser.parse(rendered)
        assertEquals("user@termux:~$ ", parsed.text)
    }

    @Test
    fun appendBoundedOutputOverwritesLinesInsteadOfConcatenating() {
        val result = SshSessionManager.appendBoundedOutput(
            current = "~/repo $ git switch main",
            chunk = "\r~/repo $ git pull\u001B[K"
        )
        assertEquals("~/repo $ git pull", result)
    }

    @Test
    fun extractCommandFromLineExtractsAfterShellPrompt() {
        assertEquals("cd Download/", TerminalBuffer.extractCommandFromLine("termux@local:~$ cd Download/"))
        assertEquals("git status", TerminalBuffer.extractCommandFromLine("~ $ git status"))
        assertEquals("id", TerminalBuffer.extractCommandFromLine("root@localhost:~# id"))
        assertEquals("cat file", TerminalBuffer.extractCommandFromLine("host% cat file"))
        assertEquals("agy", TerminalBuffer.extractCommandFromLine("❯ agy"))
        assertEquals("whoami", TerminalBuffer.extractCommandFromLine("> whoami"))
        assertEquals("", TerminalBuffer.extractCommandFromLine("~ $ "))
        assertEquals(null, TerminalBuffer.extractCommandFromLine("64 bytes from 127.0.0.1: icmp_seq=1"))
    }

    @Test
    fun currentLineUpdatesWhenTabCompletes() {
        val buffer = TerminalBuffer()
        buffer.feed("termux@local:~$ cd Down")
        assertEquals("cd Down", TerminalBuffer.extractCommandFromLine(buffer.getCurrentLineText()))

        // Simulate bash tab completion: erase in line and append completion
        buffer.feed("\u0008\u0008\u0008\u0008Download/\u001B[K")
        assertEquals("cd Download/", TerminalBuffer.extractCommandFromLine(buffer.getCurrentLineText()))
    }
}
