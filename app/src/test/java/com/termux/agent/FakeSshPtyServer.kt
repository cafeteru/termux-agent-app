package com.termux.agent

import com.termux.agent.infrastructure.ssh.*
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.shell.ShellFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * In-process ephemeral SSH+PTY server for integration and E2E tests.
 */
class FakeSshPtyServer(
    private val expectedUser: String = "termux",
    private val expectedPassword: String = "secret",
    private val fakeHome: String = DEFAULT_FAKE_HOME,
    private val fakeDirectories: Map<String, List<String>> = DEFAULT_FAKE_DIRECTORIES
) : AutoCloseable {
    val receivedCommands = CopyOnWriteArrayList<String>()
    val shellSessionCount = AtomicInteger(0)
    private val rawBytes = ByteArrayOutputStream()
    @Volatile
    var fakeTranscriptJsonl: String = ""
    @Volatile
    var fakeAgySessions: List<AgentSessionSummary> = emptyList()
    @Volatile
    var fakeCopilotSessions: List<AgentSessionSummary> = emptyList()
    val fakeTranscripts = ConcurrentHashMap<String, String>()
    val fakeCopilotTranscripts = ConcurrentHashMap<String, String>()
    @Volatile
    private var activeCommand: FakePtyCommand? = null

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
        shellFactory = ShellFactory {
            shellSessionCount.incrementAndGet()
            FakePtyCommand(receivedCommands, rawBytes).also { activeCommand = it }
        }
        commandFactory = CommandFactory { _, command ->
            FakeExecCommand(
                command = command,
                fakeHome = fakeHome,
                fakeDirectories = fakeDirectories,
                agySessionsProvider = { fakeAgySessions },
                copilotSessionsProvider = { fakeCopilotSessions },
                agyTranscriptsProvider = fakeTranscripts,
                copilotTranscriptsProvider = fakeCopilotTranscripts,
                legacyTranscriptProvider = { fakeTranscriptJsonl }
            )
        }
        start()
    }

    val port: Int
        get() = server.port

    fun sendOutput(text: String) {
        activeCommand?.sendOutput(text)
    }

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
        private val outputLock = Any()
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

        fun sendOutput(text: String) {
            val out = output ?: return
            synchronized(outputLock) {
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }

        override fun start(channel: ChannelSession?, env: Environment?) {
            val out = output ?: return
            val inp = input ?: return

            synchronized(outputLock) {
                out.write("\u001B[?2004h\u001B[1;32mtermux@local\u001B[0m:~$ ".toByteArray(Charsets.UTF_8))
                out.flush()
            }

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
            synchronized(outputLock) {
                out.write("ACK:$cmd\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
            return true
        }

        override fun destroy(channel: ChannelSession?) {
            running = false
            runCatching { input?.close() }
            worker?.interrupt()
        }
    }

    private class FakeExecCommand(
        private val command: String,
        private val fakeHome: String,
        private val fakeDirectories: Map<String, List<String>>,
        private val agySessionsProvider: () -> List<AgentSessionSummary> = { emptyList() },
        private val copilotSessionsProvider: () -> List<AgentSessionSummary> = { emptyList() },
        private val agyTranscriptsProvider: Map<String, String> = emptyMap(),
        private val copilotTranscriptsProvider: Map<String, String> = emptyMap(),
        private val legacyTranscriptProvider: () -> String = { "" }
    ) : Command {
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var exitCallback: ExitCallback? = null
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
            val inp = input ?: return
            val out = output ?: return

            worker = thread(start = true, isDaemon = true, name = "fake-ssh-exec") {
                try {
                    if (command == AgentSessionBrowser.LIST_AGY_SESSIONS_COMMAND ||
                        command == AgentSessionBrowser.LIST_COPILOT_SESSIONS_COMMAND
                    ) {
                        val isAgy = command == AgentSessionBrowser.LIST_AGY_SESSIONS_COMMAND
                        val sessions = if (isAgy) agySessionsProvider() else copilotSessionsProvider()
                        val payload = buildString {
                            append(AgentSessionBrowser.MARKER)
                            for (session in sessions) {
                                val wsField = if (isAgy) {
                                    if (session.workspacePath.isNotBlank()) "[\"file://${session.workspacePath}\"]" else "[]"
                                } else {
                                    session.workspacePath
                                }
                                append(session.id).append('\t')
                                append(session.title).append('\t')
                                append(wsField).append('\t')
                                append(session.lastModifiedIso).append('\t')
                                append(session.stepCount).append('\t')
                                append(if (session.isRunning) "1" else "0").append('\n')
                            }
                        }
                        out.write(payload.toByteArray(Charsets.UTF_8))
                        out.flush()
                        exitCallback?.onExit(0)
                        return@thread
                    }

                    if (command == AgentSessionBrowser.LIST_AGY_IDS_COMMAND) {
                        val ids = linkedSetOf<String>()
                        ids.addAll(agyTranscriptsProvider.keys)
                        if (legacyTranscriptProvider().isNotEmpty()) {
                            ids.add("default-live-session")
                        }
                        if (ids.isNotEmpty()) {
                            out.write((ids.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                        exitCallback?.onExit(0)
                        return@thread
                    }

                    if (command == AgentSessionBrowser.LIST_COPILOT_IDS_COMMAND) {
                        val ids = copilotTranscriptsProvider.keys.toList()
                        if (ids.isNotEmpty()) {
                            out.write((ids.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                        exitCallback?.onExit(0)
                        return@thread
                    }

                    val lineBytes = ByteArrayOutputStream()
                    while (true) {
                        val b = inp.read()
                        if (b < 0 || b == '\n'.code) break
                        if (b != '\r'.code) {
                            lineBytes.write(b)
                        }
                    }
                    val requested = lineBytes.toByteArray().toString(Charsets.UTF_8).trim()

                    if (command == com.termux.agent.infrastructure.agent.AgyTranscriptParser.READ_TRANSCRIPT_BY_ID_COMMAND) {
                        if (requested.isEmpty() || requested.contains("/") || requested.contains("..")) {
                            exitCallback?.onExit(1)
                            return@thread
                        }
                        val transcript = agyTranscriptsProvider[requested]
                            ?: if (requested == "default-live-session") legacyTranscriptProvider() else ""
                        if (transcript.isNotEmpty()) {
                            out.write(transcript.toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                        exitCallback?.onExit(0)
                        return@thread
                    }

                    if (command == com.termux.agent.infrastructure.agent.CopilotTranscriptParser.READ_TRANSCRIPT_BY_ID_COMMAND) {
                        if (requested.isEmpty() || requested.contains("/") || requested.contains("..")) {
                            exitCallback?.onExit(1)
                            return@thread
                        }
                        val transcript = copilotTranscriptsProvider[requested].orEmpty()
                        if (transcript.isNotEmpty()) {
                            out.write(transcript.toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                        exitCallback?.onExit(0)
                        return@thread
                    }

                    if (command != WorkspaceBrowser.LIST_DIRECTORIES_COMMAND) {
                        exitCallback?.onExit(127)
                        return@thread
                    }

                    val resolved = when {
                        requested.isEmpty() || requested == "~" -> fakeHome
                        requested.startsWith("~/") -> {
                            val suffix = requested.removePrefix("~/").trim('/')
                            if (suffix.isEmpty()) fakeHome else "${fakeHome.trimEnd('/')}/$suffix"
                        }
                        requested == "/" -> "/"
                        else -> requested.trimEnd('/').ifEmpty { "/" }
                    }

                    val dirs = fakeDirectories[resolved]
                    if (dirs != null) {
                        val payload = buildString {
                            append(WorkspaceBrowser.MARKER)
                            append(resolved)
                            append('\u0000')
                            for (dir in dirs) {
                                append(dir)
                                append('\u0000')
                            }
                        }
                        out.write(payload.toByteArray(Charsets.UTF_8))
                        out.flush()
                        exitCallback?.onExit(0)
                    } else {
                        exitCallback?.onExit(1)
                    }
                } catch (_: Exception) {
                    runCatching { exitCallback?.onExit(1) }
                }
            }
        }

        override fun destroy(channel: ChannelSession?) {
            runCatching { input?.close() }
            worker?.interrupt()
        }
    }

    companion object {
        const val DEFAULT_FAKE_HOME = "/home/termux"

        val DEFAULT_FAKE_DIRECTORIES: Map<String, List<String>> = mapOf(
            "/home/termux" to listOf("termux-agent-app", "notes", ".hidden-repo"),
            "/home/termux/termux-agent-app" to listOf("app", ".git"),
            "/home/termux/notes" to emptyList(),
            "/home/termux/.hidden-repo" to emptyList(),
            "/home" to listOf("termux"),
            "/" to listOf("home")
        )
    }
}
