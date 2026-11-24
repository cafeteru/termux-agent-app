package com.termux.agent.infrastructure.agent

import com.termux.agent.HostTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AgyTranscriptParserTest {

    @TempDir
    lateinit var tempFolder: File

    @Test
    fun testParseUserInputLineExtractsUserRequest() {
        val jsonLine = """{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","created_at":"2026-11-17T20:49:17Z","content":"<USER_REQUEST>\nseguimos\n</USER_REQUEST>\n<ADDITIONAL_METADATA>\ntime\n</ADDITIONAL_METADATA>"}"""

        val message = AgyTranscriptParser.parseLine(jsonLine)
        assertNotNull(message)
        assertEquals(0, message?.stepIndex)
        assertEquals("user", message?.sender)
        assertEquals("seguimos", message?.content)
        assertEquals("2026-11-17T20:49:17Z", message?.timestamp)
    }

    @Test
    fun testParsePlannerResponseWithToolCalls() {
        val jsonLine = """{"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"2026-11-17T20:49:17Z","content":"","thinking":"Thinking...","tool_calls":[{"name":"run_command","args":{"CommandLine":"git status","WaitMsBeforeAsync":"5000","toolSummary":"Git status check"}}]}"""

        val message = AgyTranscriptParser.parseLine(jsonLine)
        assertNotNull(message)
        assertEquals(1, message?.stepIndex)
        assertEquals("agent", message?.sender)
        assertTrue(message?.content?.contains("run_command: git status") == true)
        assertEquals(1, message?.toolCalls?.size)
        assertEquals("run_command: git status", message?.toolCalls?.first())
    }

    @Test
    fun testParseInvalidLineReturnsNull() {
        assertNull(AgyTranscriptParser.parseLine(""))
        assertNull(AgyTranscriptParser.parseLine("not json"))
        assertNull(AgyTranscriptParser.parseLine("""{"foo":"bar"}"""))
    }

    @Test
    fun testParseIgnoresGenericToolOutputLinesWithSourceModel() {
        val genericLine = """{"step_index":6,"source":"MODEL","type":"GENERIC","status":"DONE","created_at":"2026-11-20T20:33:59Z","content":"Command exited with code 0.\n\nCommand output:\nfile.txt"}"""
        assertNull(AgyTranscriptParser.parseLine(genericLine))
    }

    @Test
    fun testReadTranscriptByIdCommandReadsOnlyRequestedIdAndRejectsTraversal() {
        HostTestSupport.assumeShellContract(
            AgyTranscriptParser.READ_TRANSCRIPT_BY_ID_COMMAND,
            tempFolder
        )
        val fakeHome = File(tempFolder, "home").apply { mkdirs() }
        val targetFile = File(fakeHome, ".gemini/antigravity-cli/brain/sess-1/.system_generated/logs/transcript.jsonl")
        val otherFile = File(fakeHome, ".gemini/antigravity-cli/brain/sess-2/.system_generated/logs/transcript.jsonl")
        targetFile.parentFile?.mkdirs()
        otherFile.parentFile?.mkdirs()
        targetFile.writeText("{\"step_index\":0,\"type\":\"USER_INPUT\",\"content\":\"<USER_REQUEST>only one</USER_REQUEST>\"}\n")
        otherFile.writeText("{\"step_index\":0,\"type\":\"USER_INPUT\",\"content\":\"<USER_REQUEST>other two</USER_REQUEST>\"}\n")

        val (stdout, exitCode) = runReadTranscript("sess-1", fakeHome.absolutePath)
        assertEquals(0, exitCode)
        assertTrue(stdout.contains("only one"))
        assertTrue(!stdout.contains("other two"))

        val (_, traversalExit) = runReadTranscript("../secret", fakeHome.absolutePath)
        assertEquals(1, traversalExit)
    }

    private fun runReadTranscript(id: String, homePath: String): Pair<String, Int> {
        val process = ProcessBuilder("sh", "-c", AgyTranscriptParser.READ_TRANSCRIPT_BY_ID_COMMAND).apply {
            environment()["HOME"] = homePath
            redirectErrorStream(false)
        }.start()
        process.outputStream.use { out ->
            out.write("$id\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        return out to process.waitFor()
    }
}
