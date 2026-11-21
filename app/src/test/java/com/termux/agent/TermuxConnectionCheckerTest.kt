package com.termux.agent

import com.termux.agent.infrastructure.terminal.AnsiTextParser
import com.termux.agent.infrastructure.ssh.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class TermuxConnectionCheckerTest {

    @Test
    fun returnsErrorWhenTermuxNotInstalled() {
        val state = TermuxConnectionChecker.checkConnection(
            isTermuxInstalled = { false }
        )

        assertTrue(state is ConnectionState.Error)
        val reason = (state as ConnectionState.Error).reason
        assertTrue(reason.contains("com.termux"))
    }

    @Test
    fun returnsConnectedWhenServerSendsSshBanner() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serverThread = thread {
                server.accept().use { client ->
                    client.getOutputStream().apply {
                        write("SSH-2.0-OpenSSH_9.9\r\n".toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                    client.getInputStream().bufferedReader().readLine()
                }
            }

            val state = TermuxConnectionChecker.checkConnection(
                isTermuxInstalled = { true },
                host = "127.0.0.1",
                port = server.localPort,
                timeoutMs = 2_000
            )
            serverThread.join(2_000)

            assertEquals(
                ConnectionState.Connected("127.0.0.1", server.localPort, "SSH-2.0-OpenSSH_9.9"),
                state
            )
        }
    }

    @Test
    fun returnsErrorWhenServerDoesNotSendSshBanner() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serverThread = thread {
                server.accept().use { client ->
                    client.getOutputStream().apply {
                        write("NOT_SSH\r\n".toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                }
            }

            val state = TermuxConnectionChecker.checkConnection(
                isTermuxInstalled = { true },
                host = "127.0.0.1",
                port = server.localPort,
                timeoutMs = 2_000
            )
            serverThread.join(2_000)

            assertTrue(state is ConnectionState.Error)
            val reason = (state as ConnectionState.Error).reason
            assertTrue(reason.contains("NOT_SSH"))
        }
    }

    @Test
    fun returnsErrorMentioningSshdWhenPortRefusesConnection() {
        val unusedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        val state = TermuxConnectionChecker.checkConnection(
            isTermuxInstalled = { true },
            host = "127.0.0.1",
            port = unusedPort,
            timeoutMs = 2_000
        )

        assertTrue(state is ConnectionState.Error)
        val reason = (state as ConnectionState.Error).reason
        assertTrue(reason.contains("sshd"))
    }

    @Test
    fun generateKeyPairProducesPemPrivateAndEcdsaPublicKey() {
        val (privateKeyPem, publicKeyAuthorized) = SshKeyManager.generateKeyPair()

        assertTrue(privateKeyPem.contains("PRIVATE KEY"))
        assertTrue(publicKeyAuthorized.startsWith("ecdsa-sha2-nistp256 "))
        assertTrue(publicKeyAuthorized.endsWith("termux-agent"))
    }

    @Test
    fun buildAuthorizedKeysCommandTargetsSshAuthorizedKeys() {
        val cmd = SshKeyManager.buildAuthorizedKeysCommand("  ecdsa-sha2-nistp256 AAAATEST termux-agent  ")

        assertTrue(cmd.contains("ecdsa-sha2-nistp256 AAAATEST termux-agent"))
        assertTrue(cmd.contains("~/.ssh/authorized_keys"))
        assertTrue(cmd.contains("chmod 700 ~/.ssh"))
        assertTrue(cmd.contains("chmod 600 ~/.ssh/authorized_keys"))
    }

    @Test
    fun checkConnectionWithAuthReturnsErrorWhenPasswordBlank() {
        val state = TermuxConnectionChecker.checkConnectionWithAuth(
            isTermuxInstalled = { true },
            config = SshConfig(
                authMethod = AuthMethod.PASSWORD,
                password = "   "
            )
        )

        assertTrue(state is ConnectionState.Error)
        val reason = (state as ConnectionState.Error).reason
        assertTrue(reason.contains("contraseña"))
    }

    @Test
    fun checkConnectionWithAuthReturnsErrorWhenPublicKeyPrivatePartMissing() {
        val state = TermuxConnectionChecker.checkConnectionWithAuth(
            isTermuxInstalled = { true },
            config = SshConfig(
                authMethod = AuthMethod.PUBLIC_KEY,
                privateKeyPem = ""
            )
        )

        assertTrue(state is ConnectionState.Error)
        val reason = (state as ConnectionState.Error).reason
        assertTrue(reason.contains("clave"))
    }

    @Test
    fun appendBoundedOutputNormalizesCarriageReturnsAndTruncatesToMaxChars() {
        val result = SshSessionManager.appendBoundedOutput(
            current = "abc\n",
            chunk = "123\r\n456\r\n",
            maxChars = 8
        )

        assertEquals("123\n456\n", result)
    }

    @Test
    fun sshSessionManagerConnectUpdatesStateWithErrorWhenTermuxMissing() {
        val result = SshSessionManager.connect(
            isTermuxInstalled = { false },
            config = SshConfig()
        )

        assertTrue(result is ConnectionState.Error)
        assertEquals(result, SshSessionManager.connectionState.value)
        assertTrue((result as ConnectionState.Error).reason.contains("com.termux"))
    }

    @Test
    fun sshSessionManagerConnectUpdatesStateWithErrorWhenPasswordBlank() {
        val result = SshSessionManager.connect(
            isTermuxInstalled = { true },
            config = SshConfig(
                authMethod = AuthMethod.PASSWORD,
                password = "  "
            )
        )

        assertTrue(result is ConnectionState.Error)
        assertEquals(result, SshSessionManager.connectionState.value)
        assertTrue((result as ConnectionState.Error).reason.contains("contraseña"))
    }

    @Test
    fun sshSessionManagerSendCommandReturnsFalseWhenDisconnected() {
        SshSessionManager.disconnect()

        assertFalse(SshSessionManager.sendCommand("pwd"))
    }

    @Test
    fun sshSessionManagerDisconnectSetsDisconnectedState() {
        SshSessionManager.connect(
            isTermuxInstalled = { false },
            config = SshConfig()
        )

        SshSessionManager.disconnect()

        assertEquals(ConnectionState.Disconnected, SshSessionManager.connectionState.value)
        assertEquals("", SshSessionManager.ptyOutput.value)
    }

    @Test
    fun ansiTextParserRendersColorsAndStripsControlSequences() {
        val parsed = AnsiTextParser.parse("\u001B[?2004h\u001B[1;32muser@termux\u001B[0m:~$ ")

        assertEquals("user@termux:~$ ", parsed.text)
        assertEquals(1, parsed.spanStyles.size)
        val span = parsed.spanStyles.first()
        assertEquals(0, span.start)
        assertEquals("user@termux".length, span.end)
        assertEquals(Color(0xFF4E9A06), span.item.color)
        assertEquals(FontWeight.Bold, span.item.fontWeight)
    }

    @Test
    fun formatCommandPayloadAppendsCarriageReturnForEmptyAndNonEmptyCommands() {
        assertEquals("\r", String(SshSessionManager.formatCommandPayload(""), Charsets.UTF_8))
        assertEquals("ls\r", String(SshSessionManager.formatCommandPayload("ls"), Charsets.UTF_8))
    }

    @Test
    fun applyModifiersTransformsCtrlAndAltCorrectly() {
        assertEquals("\u0003", SshSessionManager.applyModifiers("c", ctrl = true, alt = false))
        assertEquals("\u0004", SshSessionManager.applyModifiers("d", ctrl = true, alt = false))
        assertEquals("\u001Bf", SshSessionManager.applyModifiers("f", ctrl = false, alt = true))
        assertEquals("\u001B\u0003", SshSessionManager.applyModifiers("c", ctrl = true, alt = true))
    }

    @Test
    fun ansiTextParserSupports256ColorTrueColorUnderlineBackgroundAndStripsOsc() {
        val parsed = AnsiTextParser.parse(
            "\u001B]0;title\u0007\u001B[4;38;5;196;48;2;10;20;30mERR\u001B[24;39;49m ok"
        )

        assertEquals("ERR ok", parsed.text)
        assertEquals(1, parsed.spanStyles.size)
        val span = parsed.spanStyles.first()
        assertEquals(0, span.start)
        assertEquals("ERR".length, span.end)
        assertEquals(Color(255, 0, 0), span.item.color)
        assertEquals(Color(10, 20, 30), span.item.background)
        assertEquals(TextDecoration.Underline, span.item.textDecoration)
    }
}
