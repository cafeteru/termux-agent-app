package com.termux.agent

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * Manages a persistent JSch SSH session with an interactive PTY shell channel.
 */
class SshPtySession {
    private val lock = Any()
    private val outputLock = Any()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Checking)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _ptyOutput = MutableStateFlow("")
    val ptyOutput: StateFlow<String> = _ptyOutput.asStateFlow()

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readerThread: Thread? = null
    private var sessionGeneration: Long = 0L

    /**
     * Connects to Termux SSH, opens an xterm-256color PTY shell, and starts reading PTY output.
     */
    fun connect(
        isTermuxInstalled: () -> Boolean,
        config: SshConfig,
        timeoutMs: Int = AppConfig.DEFAULT_CONNECT_TIMEOUT_MS
    ): ConnectionState {
        disconnect()

        val currentGeneration: Long
        synchronized(lock) {
            currentGeneration = ++sessionGeneration
        }
        synchronized(outputLock) {
            _ptyOutput.value = ""
        }
        _connectionState.value = ConnectionState.Checking

        TermuxConnectionChecker.validateConfig(isTermuxInstalled, config)?.let { error ->
            _connectionState.value = error
            return error
        }

        var openedSession: Session? = null
        var openedChannel: ChannelShell? = null
        return try {
            openedSession = TermuxConnectionChecker.openConnectedSession(config, timeoutMs)
            val shellChannel = openedSession.openChannel("shell") as ChannelShell
            openedChannel = shellChannel
            shellChannel.setPty(true)
            shellChannel.setPtyType("xterm-256color", 80, 24, 640, 480)

            val shellInput = shellChannel.inputStream
            val shellOutput = shellChannel.outputStream
            shellChannel.connect(timeoutMs)

            synchronized(lock) {
                session = openedSession
                channel = shellChannel
                inputStream = shellInput
                outputStream = shellOutput
                readerThread = thread(
                    start = true,
                    isDaemon = true,
                    name = "termux-ssh-pty-reader"
                ) {
                    readPtyLoop(shellInput, currentGeneration)
                }
            }

            val serverBanner = openedSession.serverVersion ?: "SSH-2.0"
            val connected = ConnectionState.Connected(config.host, config.port, serverBanner)
            _connectionState.value = connected
            connected
        } catch (e: Exception) {
            runCatching { openedChannel?.disconnect() }
            runCatching { openedSession?.disconnect() }
            disconnect()
            val error = TermuxConnectionChecker.mapSshError(e, config)
            _connectionState.value = error
            error
        }
    }

    /**
     * Writes a command line to the active PTY shell output stream.
     */
    fun sendCommand(command: String): Boolean {
        val activeOutput = synchronized(lock) {
            if (channel?.isConnected == true) outputStream else null
        } ?: return false

        return try {
            activeOutput.write((command + "\n").toByteArray(Charsets.UTF_8))
            activeOutput.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Closes the active PTY streams, shell channel, and SSH session.
     */
    fun disconnect() {
        val toCloseInput: InputStream?
        val toCloseOutput: OutputStream?
        val toDisconnectChannel: ChannelShell?
        val toDisconnectSession: Session?

        synchronized(lock) {
            sessionGeneration++
            toCloseInput = inputStream
            toCloseOutput = outputStream
            toDisconnectChannel = channel
            toDisconnectSession = session
            inputStream = null
            outputStream = null
            channel = null
            session = null
            readerThread = null
        }

        runCatching { toCloseInput?.close() }
        runCatching { toCloseOutput?.close() }
        runCatching { toDisconnectChannel?.disconnect() }
        runCatching { toDisconnectSession?.disconnect() }
    }

    private fun readPtyLoop(stream: InputStream, generation: Long) {
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val bytesRead = stream.read(buffer)
                if (bytesRead < 0) break
                if (bytesRead == 0) continue

                val isCurrentSession = synchronized(lock) { sessionGeneration == generation }
                if (!isCurrentSession) break

                val chunk = String(buffer, 0, bytesRead, Charsets.UTF_8)
                synchronized(outputLock) {
                    _ptyOutput.value = SshSessionManager.appendBoundedOutput(_ptyOutput.value, chunk)
                }
            }
        } catch (_: Exception) {
            // Stream closed during disconnect or remote termination.
        }
    }
}

/**
 * Process-wide holder for the active SSH PTY session and bounded terminal output helpers.
 */
object SshSessionManager {
    const val MAX_OUTPUT_CHARS = 8_192

    private val activeSession = SshPtySession()

    val connectionState: StateFlow<ConnectionState> = activeSession.connectionState
    val ptyOutput: StateFlow<String> = activeSession.ptyOutput

    /**
     * Normalizes carriage returns and appends a chunk while bounding output to [maxChars].
     */
    fun appendBoundedOutput(
        current: String,
        chunk: String,
        maxChars: Int = MAX_OUTPUT_CHARS
    ): String {
        if (maxChars <= 0) return ""
        val normalized = chunk.replace("\r", "")
        val combined = current + normalized
        return if (combined.length <= maxChars) combined else combined.takeLast(maxChars)
    }

    fun connect(
        isTermuxInstalled: () -> Boolean,
        config: SshConfig,
        timeoutMs: Int = AppConfig.DEFAULT_CONNECT_TIMEOUT_MS
    ): ConnectionState = activeSession.connect(isTermuxInstalled, config, timeoutMs)

    fun sendCommand(command: String): Boolean = activeSession.sendCommand(command)

    fun disconnect() = activeSession.disconnect()
}
