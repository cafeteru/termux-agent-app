package com.termux.agent

import com.termux.agent.infrastructure.terminal.AnsiTextParser
import com.termux.agent.infrastructure.terminal.TerminalBuffer
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
    fun unsupportedExtendedCsiSequenceIsRemovedWithoutLeakingText() {
        val parsed = AnsiTextParser.parse("before\u001B[>4;2mbetween\u001B[38:2:255:0:0mafter")
        assertEquals("beforebetweenafter", parsed.text)
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

    @Test
    fun extractCommandHandlesGitBranchPromptAndArgumentsWithDollarSign() {
        assertEquals(
            "git status",
            TerminalBuffer.extractCommandFromLine("~/termux-agent-app (feat/pty) $ git status")
        )
        assertEquals(
            "echo \"$ USER\" > file.txt",
            TerminalBuffer.extractCommandFromLine("termux@local:~$ echo \"$ USER\" > file.txt")
        )
    }

    @Test
    fun extractCommandReturnsNullForStandardOutputAndLogs() {
        assertEquals(null, TerminalBuffer.extractCommandFromLine("drwxr-xr-x 2 termux termux 4096 Nov 16 20:00 Download"))
        assertEquals(null, TerminalBuffer.extractCommandFromLine("ls: cannot access '/nonexistent': No such file or directory"))
        assertEquals(null, TerminalBuffer.extractCommandFromLine("PING 127.0.0.1 (127.0.0.1) 56(84) bytes of data."))
    }

    @Test
    fun multiLineBufferTracksCurrentCommandLineAcrossOutputCycles() {
        val buffer = TerminalBuffer()
        buffer.feed("termux@local:~$ ls\r\nfile1.txt  file2.txt\r\ntermux@local:~$ cat file1.txt")
        val currentLine = buffer.getCurrentLineText()
        assertEquals("cat file1.txt", TerminalBuffer.extractCommandFromLine(currentLine))
    }

    @Test
    fun getLinesReturnsListOfPlainStrings() {
        val buffer = TerminalBuffer()
        buffer.feed("line1\r\n\u001B[32mline2\u001B[0m\r\nline3")
        val lines = buffer.getLines()
        assertEquals(3, lines.size)
        assertEquals("line1", lines[0])
        assertEquals("line2", lines[1])
        assertEquals("line3", lines[2])
    }

    @Test
    fun eraseInDisplayZeroAndCursorColumnEscapeClearOldDialogLines() {
        val buffer = TerminalBuffer()
        buffer.feed("Header\nRun this command?\n> 1. Yes\n  2. No")
        buffer.feed("\u001B[2;1H\u001B[0JDone")
        assertEquals("Header\nDone", buffer.renderPlainText())

        buffer.feed("\u001B[1GOK\u001B[K")
        assertEquals("Header\nOK", buffer.renderPlainText())

        buffer.feed("\nLine2\u001B[1F\u001B[J")
        assertEquals("Header\n", buffer.renderPlainText())
    }
}
