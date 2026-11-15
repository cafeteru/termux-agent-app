package com.termux.agent

import org.junit.Assert.assertEquals
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
}
