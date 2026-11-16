package com.termux.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshPtyIntegrationTest {

    @After
    fun tearDown() {
        SshSessionManager.disconnect()
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

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", condition())
    }
}
