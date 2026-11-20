package com.termux.agent.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgyTranscriptParserTest {

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
}
