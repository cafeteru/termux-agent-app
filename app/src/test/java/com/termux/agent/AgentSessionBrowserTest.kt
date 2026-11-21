package com.termux.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset

class AgentSessionBrowserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun supportsSessionsAndCommandDispatchCoverAgyAndCopilot() {
        assertTrue(AgentSessionBrowser.supportsSessions("agy"))
        assertTrue(AgentSessionBrowser.supportsSessions("AGY"))
        assertTrue(AgentSessionBrowser.supportsSessions("copilot"))
        assertTrue(AgentSessionBrowser.supportsSessions("COPILOT"))
        assertFalse(AgentSessionBrowser.supportsSessions("unknown"))

        assertEquals(AgentSessionBrowser.LIST_AGY_SESSIONS_COMMAND, AgentSessionBrowser.listSessionsCommandFor("agy"))
        assertEquals(AgentSessionBrowser.LIST_COPILOT_SESSIONS_COMMAND, AgentSessionBrowser.listSessionsCommandFor("copilot"))
        assertEquals(AgentSessionBrowser.LIST_AGY_IDS_COMMAND, AgentSessionBrowser.listStateIdsCommandFor("agy"))
        assertEquals(AgentSessionBrowser.LIST_COPILOT_IDS_COMMAND, AgentSessionBrowser.listStateIdsCommandFor("copilot"))
    }

    @Test
    fun isValidConversationIdAcceptsIdsAndRejectsTraversalOrInvalidChars() {
        assertTrue(AgentSessionBrowser.isValidConversationId("c063592d-5610-4f16-8041-4ffd9a2447ff"))
        assertTrue(AgentSessionBrowser.isValidConversationId("sess_1"))
        assertFalse(AgentSessionBrowser.isValidConversationId(""))
        assertFalse(AgentSessionBrowser.isValidConversationId("   "))
        assertFalse(AgentSessionBrowser.isValidConversationId("../secret"))
        assertFalse(AgentSessionBrowser.isValidConversationId("a/b"))
        assertFalse(AgentSessionBrowser.isValidConversationId("id with space"))
        assertFalse(AgentSessionBrowser.isValidConversationId("id;rm"))
    }

    @Test
    fun parseWorkspaceUriDecodesFileUrisAndPreservesPlainPaths() {
        assertEquals(
            "/data/data/com.termux/files/home/termux-agent-app",
            AgentSessionBrowser.parseWorkspaceUri("""["file:///data/data/com.termux/files/home/termux-agent-app"]""")
        )
        assertEquals(
            "/home/termux/My Project+1",
            AgentSessionBrowser.parseWorkspaceUri("""["file:///home/termux/My%20Project+1/"]""")
        )
        assertEquals(
            "/home/termux/100%real/",
            AgentSessionBrowser.parseWorkspaceUri("/home/termux/100%real/") + "/"
        )
        assertEquals("/home/termux/100%real", AgentSessionBrowser.parseWorkspaceUri("/home/termux/100%real/"))
        assertEquals("/", AgentSessionBrowser.parseWorkspaceUri("""["file:///"]"""))
        assertEquals("", AgentSessionBrowser.parseWorkspaceUri("[]"))
        assertEquals("", AgentSessionBrowser.parseWorkspaceUri(""))
        assertEquals("/tmp/plain", AgentSessionBrowser.parseWorkspaceUri("file:///tmp/plain/"))
    }

    @Test
    fun parseSessionsOutputParsesRowsFallbacksDeduplicationAndErrors() {
        val raw = "MOTD noise\n" +
            AgentSessionBrowser.MARKER +
            "c063592d-5610-4f16-8041-4ffd9a2447ff\tSession One\t[\"file:///home/termux/app\"]\t2026-11-21 08:16:18+00:00\t12\t1\n" +
            "c063592d-5610-4f16-8041-4ffd9a2447ff\tDuplicate\t[\"file:///home/termux/app\"]\t2026-11-21 08:16:18+00:00\t12\t0\n" +
            "abcdef12-0000-0000-0000-000000000000\t\t\t1795249115\t4\t0\n" +
            "../bad-id\tIgnored\t\t\t1\t0\n"

        val result = AgentSessionBrowser.parseSessionsOutput(raw, exitStatus = 0)
        assertTrue(result is AgentSessionListingResult.Success)
        val sessions = (result as AgentSessionListingResult.Success).sessions
        assertEquals(2, sessions.size)
        assertEquals(
            AgentSessionSummary(
                id = "c063592d-5610-4f16-8041-4ffd9a2447ff",
                title = "Session One",
                workspacePath = "/home/termux/app",
                lastModifiedIso = "2026-11-21 08:16:18+00:00",
                stepCount = 12,
                isRunning = true
            ),
            sessions[0]
        )
        assertEquals(
            AgentSessionSummary(
                id = "abcdef12-0000-0000-0000-000000000000",
                title = "Sesión abcdef12",
                workspacePath = "",
                lastModifiedIso = "1795249115",
                stepCount = 4,
                isRunning = false
            ),
            sessions[1]
        )

        assertEquals(
            AgentSessionListingResult.Error("Error al listar sesiones (código 2)."),
            AgentSessionBrowser.parseSessionsOutput(raw, exitStatus = 2)
        )
        assertEquals(
            AgentSessionListingResult.Error("Respuesta inesperada al listar sesiones."),
            AgentSessionBrowser.parseSessionsOutput("no marker here", exitStatus = 0)
        )
    }

    @Test
    fun matchesWorkspaceNormalizesTrailingSlashes() {
        assertTrue(AgentSessionBrowser.matchesWorkspace("/home/termux/app", ""))
        assertTrue(AgentSessionBrowser.matchesWorkspace("/home/termux/app/", "/home/termux/app"))
        assertTrue(AgentSessionBrowser.matchesWorkspace("/", "/"))
        assertFalse(AgentSessionBrowser.matchesWorkspace("/home/termux/notes", "/home/termux/app"))
        assertFalse(AgentSessionBrowser.matchesWorkspace("", "/home/termux/app"))
    }

    @Test
    fun groupSessionsByWorkspaceAndDefaultExpandedSelection() {
        val s1 = AgentSessionSummary("id-1", "First", "/home/termux/notes/", "2026-11-21 09:00:00+00:00", 3, false)
        val s2 = AgentSessionSummary("id-2", "Second", "/home/termux/app", "2026-11-21 08:00:00+00:00", 5, false)
        val s3 = AgentSessionSummary("id-3", "Third", "/home/termux/notes", "2026-11-20 12:00:00+00:00", 2, false)
        val s4 = AgentSessionSummary("id-4", "NoDir", "", "2026-11-19 10:00:00+00:00", 1, false)

        assertEquals(emptyList<AgentWorkspaceGroup>(), AgentSessionBrowser.groupSessionsByWorkspace(emptyList()))
        assertEquals(null, AgentSessionBrowser.defaultExpandedWorkspaceKey(emptyList(), null, ""))

        val naturalGroups = AgentSessionBrowser.groupSessionsByWorkspace(listOf(s1, s2, s3, s4))
        assertEquals(listOf("/home/termux/notes", "/home/termux/app", ""), naturalGroups.map { it.workspacePath })
        assertEquals(listOf("notes", "app", "Sin directorio"), naturalGroups.map { it.displayName })
        assertEquals(listOf("id-1", "id-3"), naturalGroups[0].sessions.map { it.id })

        val preferredGroups = AgentSessionBrowser.groupSessionsByWorkspace(
            listOf(s1, s2, s3, s4),
            preferredWorkspacePath = "/home/termux/app/"
        )
        assertEquals(listOf("/home/termux/app", "/home/termux/notes", ""), preferredGroups.map { it.workspacePath })

        assertEquals(
            "/home/termux/notes",
            AgentSessionBrowser.defaultExpandedWorkspaceKey(preferredGroups, "id-3", "/home/termux/app")
        )
        assertEquals(
            "/home/termux/app",
            AgentSessionBrowser.defaultExpandedWorkspaceKey(naturalGroups, null, "/home/termux/app/")
        )
        assertEquals(
            "/home/termux/notes",
            AgentSessionBrowser.defaultExpandedWorkspaceKey(naturalGroups, "missing-id", "")
        )
    }

    @Test
    fun formatShortTimestampFormatsSqliteIsoAndEpochValues() {
        assertEquals(
            "21/11 08:16",
            AgentSessionBrowser.formatShortTimestamp("2026-11-21 08:16:18.020204934+00:00", ZoneOffset.UTC)
        )
        assertEquals(
            "21/11 08:16",
            AgentSessionBrowser.formatShortTimestamp("2026-11-21T08:16:18Z", ZoneOffset.UTC)
        )
        assertEquals(
            "21/11 08:18",
            AgentSessionBrowser.formatShortTimestamp("1795249115", ZoneOffset.UTC)
        )
        assertEquals("", AgentSessionBrowser.formatShortTimestamp("0001-01-01 00:00:00+00:00", ZoneOffset.UTC))
        assertEquals("", AgentSessionBrowser.formatShortTimestamp("", ZoneOffset.UTC))
        assertEquals("", AgentSessionBrowser.formatShortTimestamp("not-a-date", ZoneOffset.UTC))
    }

    @Test
    fun listAgySessionsShellCommandQueriesReadOnlyDbFallsBackToBrainAndHandlesEmptyHome() {
        val emptyHome = tempFolder.newFolder("empty-agy-home")
        assertEquals(
            AgentSessionListingResult.Success(emptyList()),
            runSessionsCommand(AgentSessionBrowser.LIST_AGY_SESSIONS_COMMAND, emptyHome.absolutePath)
        )

        val fallbackHome = tempFolder.newFolder("fallback-agy-home")
        val oldTranscript = File(fallbackHome, ".gemini/antigravity-cli/brain/sess-old/.system_generated/logs/transcript.jsonl")
        val newTranscript = File(fallbackHome, ".gemini/antigravity-cli/brain/sess-new/.system_generated/logs/transcript.jsonl")
        oldTranscript.parentFile?.mkdirs()
        newTranscript.parentFile?.mkdirs()
        oldTranscript.writeText("{}\n")
        oldTranscript.setLastModified(1_700_000_000_000L)
        newTranscript.writeText("{}\n")
        newTranscript.setLastModified(1_800_000_000_000L)

        val fallbackResult = runSessionsCommand(AgentSessionBrowser.LIST_AGY_SESSIONS_COMMAND, fallbackHome.absolutePath)
        assertTrue("Expected Success but got $fallbackResult", fallbackResult is AgentSessionListingResult.Success)
        assertEquals(
            listOf("sess-new", "sess-old"),
            (fallbackResult as AgentSessionListingResult.Success).sessions.map { it.id }
        )
        assertEquals(
            listOf("sess-new", "sess-old"),
            runIdsCommand(AgentSessionBrowser.LIST_AGY_IDS_COMMAND, fallbackHome.absolutePath)
        )

        val dbHome = tempFolder.newFolder("db-agy-home")
        val agyDir = File(dbHome, ".gemini/antigravity-cli").apply { mkdirs() }
        val dbFile = File(agyDir, "conversation_summaries.db")
        val pyExit = ProcessBuilder(
            "python3",
            "-c",
            """
            import sqlite3, sys
            con = sqlite3.connect(sys.argv[1])
            con.execute('''
                CREATE TABLE conversation_summaries (
                    conversation_id TEXT PRIMARY KEY,
                    title TEXT NOT NULL DEFAULT "",
                    workspace_uris TEXT NOT NULL,
                    last_modified_time DATETIME NOT NULL,
                    step_count INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT "",
                    parent_conversation_id TEXT NOT NULL DEFAULT "",
                    nesting_depth INTEGER NOT NULL DEFAULT 0,
                    killed NUMERIC NOT NULL DEFAULT 0
                )
            ''')
            rows = [
                ("id-running", "Active Task", '["file:///home/termux/termux-agent-app"]', "2026-11-21 08:23:20+00:00", 15, "CASCADE_RUN_STATUS_RUNNING", "", 0, 0),
                ("id-older", "Older Task", '["file:///home/termux/notes"]', "2026-11-20 10:00:00+00:00", 8, "CASCADE_RUN_STATUS_IDLE", "", 0, 0),
                ("id-empty", "Empty", '["file:///home/termux/notes"]', "2026-11-21 09:00:00+00:00", 0, "CASCADE_RUN_STATUS_IDLE", "", 0, 0),
                ("id-subagent", "Subagent", '["file:///home/termux/notes"]', "2026-11-21 09:00:00+00:00", 10, "CASCADE_RUN_STATUS_IDLE", "id-running", 1, 0),
                ("id-killed", "Killed", '["file:///home/termux/notes"]', "2026-11-21 09:00:00+00:00", 10, "CASCADE_RUN_STATUS_IDLE", "", 0, 1)
            ]
            con.executemany("INSERT INTO conversation_summaries VALUES (?,?,?,?,?,?,?,?,?)", rows)
            con.commit()
            con.close()
            """.trimIndent(),
            dbFile.absolutePath
        ).start().waitFor()
        assertEquals(0, pyExit)

        val dbResult = runSessionsCommand(AgentSessionBrowser.LIST_AGY_SESSIONS_COMMAND, dbHome.absolutePath)
        assertTrue("Expected Success but got $dbResult", dbResult is AgentSessionListingResult.Success)
        val dbSessions = (dbResult as AgentSessionListingResult.Success).sessions
        assertEquals(listOf("id-running", "id-older"), dbSessions.map { it.id })
        assertTrue(dbSessions[0].isRunning)
        assertFalse(dbSessions[1].isRunning)
        assertEquals("/home/termux/termux-agent-app", dbSessions[0].workspacePath)
        assertEquals(15, dbSessions[0].stepCount)
    }

    @Test
    fun listCopilotSessionsShellCommandQueriesSessionStoreFallsBackToWorkspaceYamlAndHandlesEmptyHome() {
        val emptyHome = tempFolder.newFolder("empty-copilot-home")
        assertEquals(
            AgentSessionListingResult.Success(emptyList()),
            runSessionsCommand(AgentSessionBrowser.LIST_COPILOT_SESSIONS_COMMAND, emptyHome.absolutePath)
        )

        // Fallback scanning session-state/*/events.jsonl + workspace.yaml (skips directories missing events.jsonl)
        val fallbackHome = tempFolder.newFolder("fallback-copilot-home")
        val noEventsDir = File(fallbackHome, ".copilot/session-state/cop-no-events").apply { mkdirs() }
        File(noEventsDir, "workspace.yaml").writeText("id: cop-no-events\ncwd: /home/termux/ignored\nname: Ignored\n")

        val oldEvents = File(fallbackHome, ".copilot/session-state/cop-old/events.jsonl")
        val newEvents = File(fallbackHome, ".copilot/session-state/cop-new/events.jsonl")
        oldEvents.parentFile?.mkdirs()
        newEvents.parentFile?.mkdirs()
        oldEvents.writeText("{}\n")
        oldEvents.setLastModified(1_700_000_000_000L)
        File(oldEvents.parentFile, "workspace.yaml").writeText(
            "id: cop-old\ncwd: /home/termux/notes\nname: Old Copilot\nupdated_at: 2026-11-18T10:00:00Z\n"
        )
        newEvents.writeText("{}\n")
        newEvents.setLastModified(1_800_000_000_000L)
        File(newEvents.parentFile, "workspace.yaml").writeText(
            "id: cop-new\ncwd: /home/termux/app\nname: New Copilot\nupdated_at: 2026-11-19T12:00:00Z\n"
        )

        val fallbackResult = runSessionsCommand(AgentSessionBrowser.LIST_COPILOT_SESSIONS_COMMAND, fallbackHome.absolutePath)
        assertTrue("Expected Success but got $fallbackResult", fallbackResult is AgentSessionListingResult.Success)
        val fallbackSessions = (fallbackResult as AgentSessionListingResult.Success).sessions
        assertEquals(listOf("cop-new", "cop-old"), fallbackSessions.map { it.id })
        assertEquals(listOf("New Copilot", "Old Copilot"), fallbackSessions.map { it.title })
        assertEquals(listOf("/home/termux/app", "/home/termux/notes"), fallbackSessions.map { it.workspacePath })
        assertEquals(
            listOf("cop-new", "cop-old"),
            runIdsCommand(AgentSessionBrowser.LIST_COPILOT_IDS_COMMAND, fallbackHome.absolutePath)
        )

        // Real session-store.db via python3
        val dbHome = tempFolder.newFolder("db-copilot-home")
        val copilotDir = File(dbHome, ".copilot").apply { mkdirs() }
        val dbFile = File(copilotDir, "session-store.db")
        val pyExit = ProcessBuilder(
            "python3",
            "-c",
            """
            import sqlite3, sys
            con = sqlite3.connect(sys.argv[1])
            con.execute('''
                CREATE TABLE sessions (
                    id TEXT PRIMARY KEY,
                    cwd TEXT,
                    summary TEXT,
                    created_at TEXT,
                    updated_at TEXT
                )
            ''')
            con.execute('''
                CREATE TABLE turns (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    turn_index INTEGER NOT NULL
                )
            ''')
            con.executemany(
                "INSERT INTO sessions VALUES (?,?,?,?,?)",
                [
                    ("cop-1", "/home/termux/balearia-ia", "Review Agent Skills", "2026-11-17T20:00:00Z", "2026-11-17T20:30:56Z"),
                    ("cop-2", "/home/termux/notes", "Quick Summary", "2026-11-16T10:00:00Z", "2026-11-16T10:05:00Z"),
                    ("cop-empty", "/home/termux/notes", "", "2026-11-18T10:00:00Z", "2026-11-18T10:00:00Z")
                ]
            )
            con.executemany(
                "INSERT INTO turns (session_id, turn_index) VALUES (?,?)",
                [("cop-1", 0), ("cop-1", 1), ("cop-1", 2)]
            )
            con.commit()
            con.close()
            """.trimIndent(),
            dbFile.absolutePath
        ).start().waitFor()
        assertEquals(0, pyExit)

        val dbResult = runSessionsCommand(AgentSessionBrowser.LIST_COPILOT_SESSIONS_COMMAND, dbHome.absolutePath)
        assertTrue("Expected Success but got $dbResult", dbResult is AgentSessionListingResult.Success)
        val dbSessions = (dbResult as AgentSessionListingResult.Success).sessions
        assertEquals(listOf("cop-1", "cop-2"), dbSessions.map { it.id })
        assertEquals("Review Agent Skills", dbSessions[0].title)
        assertEquals("/home/termux/balearia-ia", dbSessions[0].workspacePath)
        assertEquals(3, dbSessions[0].stepCount)
        assertEquals(0, dbSessions[1].stepCount)
    }

    private fun runSessionsCommand(command: String, homePath: String): AgentSessionListingResult {
        val process = ProcessBuilder("sh", "-c", command).apply {
            environment()["HOME"] = homePath
            redirectErrorStream(false)
        }.start()
        val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val exitCode = process.waitFor()
        return AgentSessionBrowser.parseSessionsOutput(stdout, exitCode)
    }

    private fun runIdsCommand(command: String, homePath: String): List<String> {
        val process = ProcessBuilder("sh", "-c", command).apply {
            environment()["HOME"] = homePath
            redirectErrorStream(false)
        }.start()
        val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        return AgentSessionBrowser.parseStateIdsOutput(stdout)
    }
}
