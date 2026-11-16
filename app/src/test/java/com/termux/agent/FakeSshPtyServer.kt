package com.termux.agent

import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.shell.ShellFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * In-process ephemeral SSH+PTY server for integration and E2E tests.
 */
class FakeSshPtyServer(
    private val expectedUser: String = "termux",
    private val expectedPassword: String = "secret"
) : AutoCloseable {
    val receivedCommands = CopyOnWriteArrayList<String>()
    private val rawBytes = ByteArrayOutputStream()

    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        val hostKeyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        keyPairProvider = KeyPairProvider.wrap(hostKeyPair)
        passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
            username == expectedUser && password == expectedPassword
        }
        publickeyAuthenticator = PublickeyAuthenticator { username, _, _ ->
            username == expectedUser
        }
        shellFactory = ShellFactory { FakePtyCommand(receivedCommands, rawBytes) }
        start()
    }

    val port: Int
        get() = server.port

    fun receivedRawString(): String = synchronized(rawBytes) {
        rawBytes.toString(Charsets.UTF_8.name())
    }

    override fun close() {
        runCatching { server.stop(true) }
    }

    private class FakePtyCommand(
        private val receivedCommands: CopyOnWriteArrayList<String>,
        private val rawBytes: ByteArrayOutputStream
    ) : Command {
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var exitCallback: ExitCallback? = null
        @Volatile
        private var running = true
        private var worker: Thread? = null

        override fun setInputStream(inputStream: InputStream?) {
            input = inputStream
        }

        override fun setOutputStream(outputStream: OutputStream?) {
            output = outputStream
        }

        override fun setErrorStream(errorStream: OutputStream?) = Unit

        override fun setExitCallback(callback: ExitCallback?) {
            exitCallback = callback
        }

        override fun start(channel: ChannelSession?, env: Environment?) {
            val out = output ?: return
            val inp = input ?: return

            out.write("\u001B[?2004h\u001B[1;32mtermux@local\u001B[0m:~$ ".toByteArray(Charsets.UTF_8))
            out.flush()

            worker = thread(start = true, isDaemon = true, name = "fake-ssh-pty") {
                val lineBytes = ByteArrayOutputStream()
                var lastWasCr = false
                try {
                    while (running) {
                        val b = inp.read()
                        if (b < 0) break
                        synchronized(rawBytes) { rawBytes.write(b) }

                        when (b) {
                            '\r'.code -> {
                                lastWasCr = true
                                val cmd = lineBytes.toString(Charsets.UTF_8)
                                lineBytes.reset()
                                if (!handleCommand(cmd, out)) break
                            }
                            '\n'.code -> {
                                if (lastWasCr) {
                                    lastWasCr = false
                                } else {
                                    val cmd = lineBytes.toString(Charsets.UTF_8)
                                    lineBytes.reset()
                                    if (!handleCommand(cmd, out)) break
                                }
                            }
                            else -> {
                                lastWasCr = false
                                lineBytes.write(b)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Client disconnected or stream closed during teardown.
                }
            }
        }

        private fun handleCommand(cmd: String, out: OutputStream): Boolean {
            receivedCommands.add(cmd)
            if (cmd == "exit") {
                exitCallback?.onExit(0)
                return false
            }
            out.write("ACK:$cmd\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
            return true
        }

        override fun destroy(channel: ChannelSession?) {
            running = false
            runCatching { input?.close() }
            worker?.interrupt()
        }
    }
}
