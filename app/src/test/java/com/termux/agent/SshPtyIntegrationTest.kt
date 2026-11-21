package com.termux.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshPtyIntegrationTest {

    @After
    fun tearDown() {
        SshSessionManager.disconnect()
        SshSessionManager.setActiveAdapterById("agy")
    }

    @Test
    fun passwordAuthAndInteractivePtyRoundTripAndDisconnect() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            val state = SshSessionManager.connect(
                isTermuxInstalled = { true },
                config = SshConfig(
                    host = "127.0.0.1",
                    port = server.port,
                    username = "termux",
                    authMethod = AuthMethod.PASSWORD,
                    password = "secret"
                ),
                timeoutMs = 5_000
            )

            assertTrue("Expected Connected but got $state", state is ConnectionState.Connected)

            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.ptyOutput.value.contains(":~$ ")
            }
            assertEquals(
                "termux@local:~$ ",
                AnsiTextParser.parse(SshSessionManager.ptyOutput.value).text
            )

            assertTrue(SshSessionManager.sendCommand(""))
            assertTrue(SshSessionManager.sendCommand("pwd"))

            awaitCondition(timeoutMs = 5_000) {
                val output = SshSessionManager.ptyOutput.value
                output.contains("ACK:\n") && output.contains("ACK:pwd\n")
            }

            assertTrue(server.receivedCommands.contains(""))
            assertTrue(server.receivedCommands.contains("pwd"))

            SshSessionManager.disconnect()
            assertEquals(ConnectionState.Disconnected, SshSessionManager.connectionState.value)
        }
    }

    @Test
    fun publicKeyAuthAndRemoteExitTransitionsToError() {
        val (privateKeyPem, publicKeyAuthorized) = SshKeyManager.generateKeyPair()

        FakeSshPtyServer(expectedUser = "termux").use { server ->
            val state = SshSessionManager.connect(
                isTermuxInstalled = { true },
                config = SshConfig(
                    host = "127.0.0.1",
                    port = server.port,
                    username = "termux",
                    authMethod = AuthMethod.PUBLIC_KEY,
                    privateKeyPem = privateKeyPem,
                    publicKeyAuthorized = publicKeyAuthorized
                ),
                timeoutMs = 5_000
            )

            assertTrue("Expected Connected but got $state", state is ConnectionState.Connected)

            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.ptyOutput.value.contains("termux@local")
            }

            assertTrue(SshSessionManager.sendCommand("exit"))

            awaitCondition(timeoutMs = 5_000) {
                val current = SshSessionManager.connectionState.value
                current is ConnectionState.Error &&
                    current.reason == "Sesión PTY cerrada por Termux."
            }
        }
    }

    @Test
    fun fullAsyncCommandAndAnsiRenderE2EFlow() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            val state = SshSessionManager.connect(
                isTermuxInstalled = { true },
                config = SshConfig(
                    host = "127.0.0.1",
                    port = server.port,
                    username = "termux",
                    authMethod = AuthMethod.PASSWORD,
                    password = "secret"
                ),
                timeoutMs = 5_000
            )
            assertTrue("Expected Connected but got $state", state is ConnectionState.Connected)

            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.ptyOutput.value.contains(":~$ ")
            }
            assertEquals(
                "termux@local:~$ ",
                AnsiTextParser.parse(SshSessionManager.ptyOutput.value).text
            )

            SshSessionManager.sendCommandAsync("whoami")
            SshSessionManager.sendCommandAsync("")

            awaitCondition(timeoutMs = 5_000) {
                val rendered = AnsiTextParser.parse(SshSessionManager.ptyOutput.value).text
                rendered.contains("ACK:whoami\n") && rendered.contains("ACK:\n")
            }
            assertTrue(server.receivedCommands.contains("whoami"))
            assertTrue(server.receivedCommands.contains(""))

            SshSessionManager.disconnect()
            assertEquals(ConnectionState.Disconnected, SshSessionManager.connectionState.value)
        }
    }

    @Test
    fun sendRawAndSendRawAsyncDeliverControlAndArrowSequencesWithoutNewline() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            val state = SshSessionManager.connect(
                isTermuxInstalled = { true },
                config = SshConfig(
                    host = "127.0.0.1",
                    port = server.port,
                    username = "termux",
                    authMethod = AuthMethod.PASSWORD,
                    password = "secret"
                ),
                timeoutMs = 5_000
            )
            assertTrue("Expected Connected but got $state", state is ConnectionState.Connected)

            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.ptyOutput.value.contains(":~$ ")
            }

            assertTrue(SshSessionManager.sendRaw("\u0003"))
            SshSessionManager.sendRawAsync("\u001B\t\u001B[A")

            awaitCondition(timeoutMs = 5_000) {
                server.receivedRawString().contains("\u0003\u001B\t\u001B[A")
            }
            assertTrue(server.receivedCommands.isEmpty())
        }
    }

    @Test
    fun currentCommandLineUpdatesFromPtyStreamAndClearsOnDisconnect() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            val state = SshSessionManager.connect(
                isTermuxInstalled = { true },
                config = SshConfig(
                    host = "127.0.0.1",
                    port = server.port,
                    username = "termux",
                    authMethod = AuthMethod.PASSWORD,
                    password = "secret"
                ),
                timeoutMs = 5_000
            )
            assertTrue("Expected Connected but got $state", state is ConnectionState.Connected)

            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.ptyOutput.value.contains(":~$ ")
            }
            assertEquals("", SshSessionManager.currentCommandLine.value)

            server.sendOutput("\rtermux@local:~$ cd Download/\u001B[K")
            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.currentCommandLine.value == "cd Download/"
            }
            assertEquals("cd Download/", SshSessionManager.currentCommandLine.value)

            SshSessionManager.disconnect()
            assertEquals("", SshSessionManager.currentCommandLine.value)
        }
    }

    @Test
    fun adapterPromptAndPermissionFlowThroughPtySession() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            val state = SshSessionManager.connect(
                isTermuxInstalled = { true },
                config = SshConfig(
                    host = "127.0.0.1",
                    port = server.port,
                    username = "termux",
                    authMethod = AuthMethod.PASSWORD,
                    password = "secret"
                ),
                timeoutMs = 5_000
            )
            assertTrue("Expected Connected but got $state", state is ConnectionState.Connected)

            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.ptyOutput.value.contains(":~$ ")
            }

            // User sends prompt via adapter
            assertTrue(SshSessionManager.sendPrompt("check git"))
            assertEquals(com.termux.agent.adapter.AgentState.WORKING, SshSessionManager.agentState.value)

            // Server prompts for permission
            server.sendOutput("\r\nTool: run_command\r\nCommand: git status\r\nDo you want to allow this execution? [y/n]\r\n")

            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.agentState.value == com.termux.agent.adapter.AgentState.WAITING_PERMISSION
            }
            val event = SshSessionManager.adapterEvent.value
            assertTrue(event is com.termux.agent.adapter.AdapterEvent.PermissionRequired)
            val perm = event as com.termux.agent.adapter.AdapterEvent.PermissionRequired
            assertEquals("run_command", perm.tool)
            assertEquals("git status", perm.command)

            // Resolve permission with Allow
            assertTrue(SshSessionManager.resolvePermission(allow = true))
            assertEquals(com.termux.agent.adapter.AgentState.WORKING, SshSessionManager.agentState.value)

            awaitCondition(timeoutMs = 5_000) {
                server.receivedRawString().contains("y\r")
            }

            SshSessionManager.disconnect()
            assertEquals(com.termux.agent.adapter.AgentState.IDLE, SshSessionManager.agentState.value)
        }
    }

    @Test
    fun chatMessagesFlowAndAgentLaunch() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            val state = SshSessionManager.connect(
                isTermuxInstalled = { true },
                config = SshConfig(
                    host = "127.0.0.1",
                    port = server.port,
                    username = "termux",
                    authMethod = AuthMethod.PASSWORD,
                    password = "secret"
                ),
                timeoutMs = 5_000
            )
            assertTrue(state is ConnectionState.Connected)

            SshSessionManager.clearChat()
            assertEquals(0, SshSessionManager.chatMessages.value.size)

            SshSessionManager.setActiveAdapterById("copilot")
            assertEquals("copilot", SshSessionManager.activeAdapter.value.id)

            assertTrue(SshSessionManager.launchActiveAgent())
            awaitCondition(timeoutMs = 5_000) {
                server.receivedCommands.contains("copilot")
            }

            assertTrue(SshSessionManager.sendPrompt("Explain coroutines"))
            assertEquals(1, SshSessionManager.chatMessages.value.size)
            assertEquals("user", SshSessionManager.chatMessages.value[0].sender)
            assertEquals("Explain coroutines", SshSessionManager.chatMessages.value[0].content)

            SshSessionManager.emitAdapterEventForTesting(
                com.termux.agent.adapter.AdapterEvent.TurnCompleted("Coroutines are lightweight threads.")
            )
            assertEquals(2, SshSessionManager.chatMessages.value.size)
            assertEquals("agent", SshSessionManager.chatMessages.value[1].sender)
            assertEquals("Coroutines are lightweight threads.", SshSessionManager.chatMessages.value[1].content)

            val jsonTranscript = """{"step_index": 3, "type": "PLANNER_RESPONSE", "content": "Done task", "created_at": "12:00:00"}"""
            SshSessionManager.appendTranscriptLine(jsonTranscript)
            assertEquals(3, SshSessionManager.chatMessages.value.size)
            assertEquals("Done task", SshSessionManager.chatMessages.value[2].content)

            // Polling live transcript for agy deduplicates user message and appends agent reply + completes turn
            SshSessionManager.clearChat()
            SshSessionManager.setActiveAdapterById("agy")
            assertTrue(SshSessionManager.sendPrompt("revisa si puedes escribir"))
            assertEquals(com.termux.agent.adapter.AgentState.WORKING, SshSessionManager.agentState.value)

            server.fakeTranscriptJsonl = """
                {"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","created_at":"20:32:52","content":"<USER_REQUEST>\nrevisa si puedes escribir\n</USER_REQUEST>"}
                {"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"20:32:53","content":"Sí, puedo escribir en el chat."}
            """.trimIndent()

            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.chatMessages.value.any { it.sender == "agent" && it.content == "Sí, puedo escribir en el chat." }
            }
            assertEquals(2, SshSessionManager.chatMessages.value.size)
            assertEquals("user", SshSessionManager.chatMessages.value[0].sender)
            assertEquals(0, SshSessionManager.chatMessages.value[0].stepIndex)
            assertEquals("agent", SshSessionManager.chatMessages.value[1].sender)
            assertEquals("Sí, puedo escribir en el chat.", SshSessionManager.chatMessages.value[1].content)

            SshSessionManager.clearChat()
            assertEquals(0, SshSessionManager.chatMessages.value.size)
        }
    }

    @Test
    fun listDirectoriesAndLaunchAgentWithWorkspace() {
        val disconnected = SshSessionManager.listDirectories("")
        assertTrue(disconnected is DirectoryListingResult.Error)

        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            val state = SshSessionManager.connect(
                isTermuxInstalled = { true },
                config = SshConfig(
                    host = "127.0.0.1",
                    port = server.port,
                    username = "termux",
                    authMethod = AuthMethod.PASSWORD,
                    password = "secret"
                ),
                timeoutMs = 5_000
            )
            assertTrue("Expected Connected but got $state", state is ConnectionState.Connected)

            val homeListing = SshSessionManager.listDirectories("")
            assertEquals(
                DirectoryListingResult.Success(
                    currentPath = "/home/termux",
                    subdirectories = listOf(".hidden-repo", "notes", "termux-agent-app")
                ),
                homeListing
            )

            val projectListing = SshSessionManager.listDirectories("/home/termux/termux-agent-app")
            assertEquals(
                DirectoryListingResult.Success(
                    currentPath = "/home/termux/termux-agent-app",
                    subdirectories = listOf(".git", "app")
                ),
                projectListing
            )

            val missingListing = SshSessionManager.listDirectories("/does/not/exist")
            assertTrue(missingListing is DirectoryListingResult.Error)

            assertTrue(SshSessionManager.launchActiveAgent("/home/termux/termux-agent-app"))
            awaitCondition(timeoutMs = 5_000) {
                server.receivedCommands.contains("cd \"/home/termux/termux-agent-app\" && agy")
            }
        }
    }

    @Test
    fun agyFourOptionPermissionMenuRoundTripAndSelection() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            val state = SshSessionManager.connect(
                isTermuxInstalled = { true },
                config = SshConfig(
                    host = "127.0.0.1",
                    port = server.port,
                    username = "termux",
                    authMethod = AuthMethod.PASSWORD,
                    password = "secret"
                ),
                timeoutMs = 5_000
            )
            assertTrue("Expected Connected but got $state", state is ConnectionState.Connected)

            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.ptyOutput.value.contains(":~$ ")
            }

            SshSessionManager.setActiveAdapterById("agy")
            assertTrue(SshSessionManager.sendPrompt("check git"))
            assertEquals(com.termux.agent.adapter.AgentState.WORKING, SshSessionManager.agentState.value)

            server.sendOutput(
                "\r\nCommand\r\n────────────────────────────────────────────────\r\n" +
                    "Requesting permission for:\r\n   git status -sb\r\n\r\nRun this command?\r\n" +
                    "> 1. Yes, run command\r\n" +
                    "  2. Yes, and always allow in this conversation\r\n" +
                    "  3. Yes, and always allow (Persist to settings.json)\r\n" +
                    "  4. No, cancel\r\n"
            )

            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.agentState.value == com.termux.agent.adapter.AgentState.WAITING_PERMISSION
            }
            val perm = SshSessionManager.adapterEvent.value as? com.termux.agent.adapter.AdapterEvent.PermissionRequired
            assertTrue(perm != null)
            assertEquals("Command", perm!!.tool)
            assertEquals(4, perm.options.size)

            assertTrue(SshSessionManager.resolvePermission(allow = true, optionIndex = 2))
            assertEquals(null, SshSessionManager.adapterEvent.value)
            assertEquals(com.termux.agent.adapter.AgentState.WORKING, SshSessionManager.agentState.value)

            awaitCondition(timeoutMs = 5_000) {
                server.receivedRawString().contains("\u001B[B\u001B[B\r")
            }
        }
    }

    @Test
    fun agySessionManagementIsolatesSessionsResumesAndStartsCleanly() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            val sess1 = AgentSessionSummary(
                id = "sess-1",
                title = "Fix auth bug",
                workspacePath = "/home/termux/termux-agent-app",
                lastModifiedIso = "2026-11-21 08:10:00+00:00",
                stepCount = 4,
                isRunning = false
            )
            val sess2 = AgentSessionSummary(
                id = "sess-2",
                title = "Notes cleanup",
                workspacePath = "/home/termux/notes",
                lastModifiedIso = "2026-11-20 12:00:00+00:00",
                stepCount = 2,
                isRunning = false
            )
            server.fakeAgySessions = listOf(sess1, sess2)
            server.fakeTranscripts["sess-1"] = """
                {"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","created_at":"08:10:00","content":"<USER_REQUEST>\nrevisa auth\n</USER_REQUEST>"}
                {"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"08:10:02","content":"Auth revisado."}
            """.trimIndent()
            server.fakeTranscripts["sess-2"] = """
                {"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","created_at":"12:00:00","content":"<USER_REQUEST>\nordena notas\n</USER_REQUEST>"}
                {"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"12:00:02","content":"Notas ordenadas."}
            """.trimIndent()

            val state = SshSessionManager.connect(
                isTermuxInstalled = { true },
                config = SshConfig(
                    host = "127.0.0.1",
                    port = server.port,
                    username = "termux",
                    authMethod = AuthMethod.PASSWORD,
                    password = "secret"
                ),
                timeoutMs = 5_000
            )
            assertTrue("Expected Connected but got $state", state is ConnectionState.Connected)

            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.agentSessions.value.size == 2
            }
            // Connecting must NOT blindly load prior transcripts
            Thread.sleep(1_100)
            assertTrue(SshSessionManager.chatMessages.value.isEmpty())
            assertEquals(null, SshSessionManager.activeConversationId.value)

            // Resume session 1 -> loads only sess-1 and launches agy --conversation sess-1
            assertTrue(SshSessionManager.openConversation(sess1))
            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.chatMessages.value.any { it.content == "Auth revisado." }
            }
            assertEquals("sess-1", SshSessionManager.activeConversationId.value)
            assertEquals("Fix auth bug", SshSessionManager.activeConversationTitle.value)
            assertTrue(SshSessionManager.chatMessages.value.none { it.content.contains("Notas ordenadas") })
            awaitCondition(timeoutMs = 5_000) {
                server.receivedCommands.contains("cd \"/home/termux/termux-agent-app\" && agy --conversation sess-1")
            }

            // Switch to session 2 -> reopens clean PTY channel and replaces messages without leakage
            assertTrue(SshSessionManager.openConversation(sess2))
            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.chatMessages.value.any { it.content == "Notas ordenadas." }
            }
            assertTrue("Expected new PTY channel on switch", server.shellSessionCount.get() >= 2)
            assertEquals("sess-2", SshSessionManager.activeConversationId.value)
            assertEquals("Notes cleanup", SshSessionManager.activeConversationTitle.value)
            assertTrue(SshSessionManager.chatMessages.value.none { it.content.contains("Auth revisado") })
            awaitCondition(timeoutMs = 5_000) {
                server.receivedCommands.contains("cd \"/home/termux/notes\" && agy --conversation sess-2")
            }

            // Start new session -> clears chat, reopens clean PTY, ignores parallel updates to sess-1, binds only new ID
            assertTrue(SshSessionManager.startNewSession("/home/termux/termux-agent-app"))
            assertTrue(SshSessionManager.chatMessages.value.isEmpty())
            assertEquals(null, SshSessionManager.activeConversationId.value)
            assertTrue("Expected new PTY channel on new session", server.shellSessionCount.get() >= 3)
            awaitCondition(timeoutMs = 5_000) {
                server.receivedCommands.contains("cd \"/home/termux/termux-agent-app\" && agy")
            }

            server.fakeTranscripts["sess-1"] = server.fakeTranscripts["sess-1"] + "\n" +
                """{"step_index":2,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"08:12:00","content":"Mensaje paralelo ignorado"}"""
            Thread.sleep(1_200)
            assertTrue(SshSessionManager.chatMessages.value.isEmpty())
            assertEquals(null, SshSessionManager.activeConversationId.value)

            server.fakeTranscripts["sess-new-3"] = """
                {"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","created_at":"08:15:00","content":"<USER_REQUEST>\nnueva sesion\n</USER_REQUEST>"}
                {"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"08:15:02","content":"Sesión nueva lista."}
            """.trimIndent()
            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.activeConversationId.value == "sess-new-3" &&
                    SshSessionManager.chatMessages.value.any { it.content == "Sesión nueva lista." }
            }
            assertEquals(2, SshSessionManager.chatMessages.value.size)
        }
    }

    @Test
    fun copilotSessionManagementIsolatesSessionsResumesAndStartsCleanly() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            val cop1 = AgentSessionSummary(
                id = "cop-1",
                title = "Review Agent Skills",
                workspacePath = "/home/termux/termux-agent-app",
                lastModifiedIso = "2026-11-21 08:10:00+00:00",
                stepCount = 4,
                isRunning = false
            )
            val cop2 = AgentSessionSummary(
                id = "cop-2",
                title = "Notes Check",
                workspacePath = "/home/termux/notes",
                lastModifiedIso = "2026-11-20 12:00:00+00:00",
                stepCount = 2,
                isRunning = false
            )
            server.fakeCopilotSessions = listOf(cop1, cop2)
            server.fakeCopilotTranscripts["cop-1"] = """
                {"type":"user.message","step_index":0,"timestamp":"08:10:00","content":"revisa skills","tools":[]}
                {"type":"assistant.message","step_index":1,"timestamp":"08:10:02","content":"Skills revisadas.","tools":["view: AGENTS.md"]}
            """.trimIndent()
            server.fakeCopilotTranscripts["cop-2"] = """
                {"type":"user.message","step_index":0,"timestamp":"12:00:00","content":"mira notas","tools":[]}
                {"type":"assistant.message","step_index":1,"timestamp":"12:00:02","content":"Notas listas.","tools":[]}
            """.trimIndent()

            val state = SshSessionManager.connect(
                isTermuxInstalled = { true },
                config = SshConfig(
                    host = "127.0.0.1",
                    port = server.port,
                    username = "termux",
                    authMethod = AuthMethod.PASSWORD,
                    password = "secret"
                ),
                timeoutMs = 5_000
            )
            assertTrue("Expected Connected but got $state", state is ConnectionState.Connected)

            SshSessionManager.setActiveAdapterById("copilot")
            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.agentSessions.value.map { it.id } == listOf("cop-1", "cop-2")
            }
            Thread.sleep(1_100)
            assertTrue(SshSessionManager.chatMessages.value.isEmpty())
            assertEquals(null, SshSessionManager.activeConversationId.value)

            // Resume cop-1 -> loads only cop-1 and runs copilot --resume=cop-1
            assertTrue(SshSessionManager.openConversation(cop1))
            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.chatMessages.value.any { it.content == "Skills revisadas." }
            }
            assertEquals("cop-1", SshSessionManager.activeConversationId.value)
            assertEquals("Review Agent Skills", SshSessionManager.activeConversationTitle.value)
            assertEquals(listOf("view: AGENTS.md"), SshSessionManager.chatMessages.value[1].toolCalls)
            assertTrue(SshSessionManager.chatMessages.value.none { it.content.contains("Notas listas") })
            awaitCondition(timeoutMs = 5_000) {
                server.receivedCommands.contains("cd \"/home/termux/termux-agent-app\" && copilot --resume=cop-1")
            }

            // Switch to cop-2 -> reopens clean PTY channel and isolates messages
            assertTrue(SshSessionManager.openConversation(cop2))
            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.chatMessages.value.any { it.content == "Notas listas." }
            }
            assertTrue("Expected new PTY channel on Copilot switch", server.shellSessionCount.get() >= 2)
            assertEquals("cop-2", SshSessionManager.activeConversationId.value)
            assertTrue(SshSessionManager.chatMessages.value.none { it.content.contains("Skills revisadas") })
            awaitCondition(timeoutMs = 5_000) {
                server.receivedCommands.contains("cd \"/home/termux/notes\" && copilot --resume=cop-2")
            }

            // Start new Copilot session -> ignores parallel cop-1 updates, binds cop-new-3
            assertTrue(SshSessionManager.startNewSession("/home/termux/termux-agent-app"))
            assertTrue(SshSessionManager.chatMessages.value.isEmpty())
            assertEquals(null, SshSessionManager.activeConversationId.value)
            assertTrue("Expected new PTY channel on new Copilot session", server.shellSessionCount.get() >= 3)
            awaitCondition(timeoutMs = 5_000) {
                server.receivedCommands.contains("cd \"/home/termux/termux-agent-app\" && copilot")
            }

            server.fakeCopilotTranscripts["cop-1"] = server.fakeCopilotTranscripts["cop-1"] + "\n" +
                """{"type":"assistant.message","step_index":2,"timestamp":"08:12:00","content":"Mensaje copilot paralelo ignorado","tools":[]}"""
            Thread.sleep(1_200)
            assertTrue(SshSessionManager.chatMessages.value.isEmpty())
            assertEquals(null, SshSessionManager.activeConversationId.value)

            server.fakeCopilotTranscripts["cop-new-3"] = """
                {"type":"user.message","step_index":0,"timestamp":"08:15:00","content":"nueva sesion copilot","tools":[]}
                {"type":"assistant.message","step_index":1,"timestamp":"08:15:02","content":"Copilot listo.","tools":[]}
            """.trimIndent()
            awaitCondition(timeoutMs = 5_000) {
                SshSessionManager.activeConversationId.value == "cop-new-3" &&
                    SshSessionManager.chatMessages.value.any { it.content == "Copilot listo." }
            }
            assertEquals(2, SshSessionManager.chatMessages.value.size)
        }
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", condition())
    }
}
