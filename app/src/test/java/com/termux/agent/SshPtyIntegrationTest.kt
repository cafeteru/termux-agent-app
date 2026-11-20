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

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", condition())
    }
}
