package com.termux.agent.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CopilotTranscriptParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parseLineHandlesCompactAndRawEventsJsonlAndIgnoresNonChatEvents() {
        val compactUser = """{"type":"user.message","step_index":0,"timestamp":"2026-11-17T20:30:56Z","content":"check \u003Crepo\u003E","tools":[]}"""
        val uMsg = CopilotTranscriptParser.parseLine(compactUser)
        assertNotNull(uMsg)
        assertEquals(0, uMsg?.stepIndex)
        assertEquals("user", uMsg?.sender)
        assertEquals("check <repo>", uMsg?.content)
        assertEquals("2026-11-17T20:30:56Z", uMsg?.timestamp)

        val compactAssistant = """{"type":"assistant.message","step_index":1,"timestamp":"2026-11-17T20:31:00Z","content":"","tools":["bash: git status","view: README.md"]}"""
        val aMsg = CopilotTranscriptParser.parseLine(compactAssistant)
        assertNotNull(aMsg)
        assertEquals(1, aMsg?.stepIndex)
        assertEquals("agent", aMsg?.sender)
        assertTrue(aMsg?.content?.contains("bash: git status") == true)
        assertEquals(listOf("bash: git status", "view: README.md"), aMsg?.toolCalls)

        // Raw events.jsonl line with transformedContent before content and nested toolRequests
        val rawUser = """{"type":"user.message","data":{"transformedContent":"<current_datetime>now</current_datetime>\n\nactual prompt","content":"actual prompt"},"timestamp":"2026-11-19T12:51:38Z"}"""
        val rawUMsg = CopilotTranscriptParser.parseLine(rawUser, fallbackStepIndex = 4)
        assertNotNull(rawUMsg)
        assertEquals(4, rawUMsg?.stepIndex)
        assertEquals("user", rawUMsg?.sender)
        assertEquals("actual prompt", rawUMsg?.content)

        val rawAssistant = """{"type":"assistant.message","data":{"content":"Listo","toolRequests":[{"toolCallId":"c1","name":"bash","arguments":{"command":"ls -la"}}]},"timestamp":"2026-11-19T12:51:40Z"}"""
        val rawAMsg = CopilotTranscriptParser.parseLine(rawAssistant, fallbackStepIndex = 5)
        assertNotNull(rawAMsg)
        assertEquals(5, rawAMsg?.stepIndex)
        assertEquals("agent", rawAMsg?.sender)
        assertEquals("Listo", rawAMsg?.content)
        assertEquals(listOf("bash: ls -la"), rawAMsg?.toolCalls)

        assertNull(CopilotTranscriptParser.parseLine("""{"type":"session.start","data":{}}"""))
        assertNull(CopilotTranscriptParser.parseLine("""{"type":"model.messages_snapshot","data":{"content":"ignore"}}"""))
        assertNull(CopilotTranscriptParser.parseLine(""))
    }

    @Test
    fun readTranscriptCommandStreamsEventsStripsBloatFallsBackToDbAndRejectsTraversal() {
        val fakeHome = tempFolder.newFolder("copilot-home")
        val eventsFile = File(fakeHome, ".copilot/session-state/cop-1/events.jsonl")
        val otherEventsFile = File(fakeHome, ".copilot/session-state/cop-other/events.jsonl")
        eventsFile.parentFile?.mkdirs()
        otherEventsFile.parentFile?.mkdirs()

        val hugePadding = "X".repeat(600_000)
        eventsFile.writeText(
            listOf(
                """{"type":"session.start","data":{"sessionId":"cop-1"}}""",
                """{"type":"model.messages_snapshot","data":{"encryptedContent":"$hugePadding"}}""",
                """{"type":"user.message","data":{"content":"revisa repo","transformedContent":"wrapper\nrevisa repo"},"timestamp":"2026-11-17T20:30:56Z"}""",
                """{"type":"assistant.message","data":{"content":"","toolRequests":[{"toolCallId":"c1","name":"view","arguments":{"path":"/home/AGENTS.md"}}]},"timestamp":"2026-11-17T20:30:58Z"}""",
                """{"type":"assistant.message","data":{"content":"Todo revisado.","toolRequests":[]},"timestamp":"2026-11-17T20:31:00Z"}"""
            ).joinToString("\n") + "\n"
        )
        otherEventsFile.writeText("""{"type":"user.message","data":{"content":"other session"},"timestamp":"2026-11-17T00:00:00Z"}""" + "\n")

        val (stdout, exitCode) = runReadTranscript("cop-1", fakeHome.absolutePath)
        assertEquals(0, exitCode)
        assertTrue(!stdout.contains("encryptedContent"))
        assertTrue(!stdout.contains("other session"))

        val parsed = stdout.lineSequence().mapNotNull { CopilotTranscriptParser.parseLine(it) }.toList()
        assertEquals(3, parsed.size)
        assertEquals("user", parsed[0].sender)
        assertEquals("revisa repo", parsed[0].content)
        assertEquals(listOf("view: /home/AGENTS.md"), parsed[1].toolCalls)
        assertEquals("Todo revisado.", parsed[2].content)

        // Fallback to session-store.db turns when events.jsonl is absent
        val dbFile = File(fakeHome, ".copilot/session-store.db")
        val pyExit = ProcessBuilder(
            "python3",
            "-c",
            """
            import sqlite3, sys
            con = sqlite3.connect(sys.argv[1])
            con.execute('CREATE TABLE turns (id INTEGER PRIMARY KEY, session_id TEXT, turn_index INTEGER, user_message TEXT, assistant_response TEXT, timestamp TEXT)')
            con.execute('INSERT INTO turns VALUES (1, "cop-db-only", 0, "pregunta db", "respuesta db", "2026-11-17T10:00:00Z")')
            con.commit()
            con.close()
            """.trimIndent(),
            dbFile.absolutePath
        ).start().waitFor()
        assertEquals(0, pyExit)

        val (dbOut, dbExit) = runReadTranscript("cop-db-only", fakeHome.absolutePath)
        assertEquals(0, dbExit)
        val dbParsed = dbOut.lineSequence().mapNotNull { CopilotTranscriptParser.parseLine(it) }.toList()
        assertEquals(2, dbParsed.size)
        assertEquals("pregunta db", dbParsed[0].content)
        assertEquals("respuesta db", dbParsed[1].content)

        // Traversal rejected
        val (_, badExit) = runReadTranscript("../secret", fakeHome.absolutePath)
        assertEquals(1, badExit)
    }

    private fun runReadTranscript(id: String, homePath: String): Pair<String, Int> {
        val process = ProcessBuilder("sh", "-c", CopilotTranscriptParser.READ_TRANSCRIPT_BY_ID_COMMAND).apply {
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
