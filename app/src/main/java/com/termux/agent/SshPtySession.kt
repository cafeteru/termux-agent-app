package com.termux.agent

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Manages a persistent JSch SSH session with an interactive PTY shell channel.
 */
class SshPtySession {
    private val lock = Any()
    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "termux-ssh-pty-writer").apply { isDaemon = true }
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Checking)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _ptyOutput = MutableStateFlow("")
    val ptyOutput: StateFlow<String> = _ptyOutput.asStateFlow()
    private val _currentCommandLine = MutableStateFlow("")
    val currentCommandLine: StateFlow<String> = _currentCommandLine.asStateFlow()
    private val terminalBuffer = TerminalBuffer()

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
        disconnect(updateState = false)

        val currentGeneration = synchronized(lock) {
            terminalBuffer.clear()
            ++sessionGeneration
        }
        _ptyOutput.value = ""
        _currentCommandLine.value = ""
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
            disconnect(updateState = false)
            val error = TermuxConnectionChecker.mapSshError(e, config)
            _connectionState.value = error
            error
        }
    }

    /**
     * Writes a command line to the active PTY shell output stream.
     */
    fun sendCommand(command: String): Boolean =
        writeBytes(SshSessionManager.formatCommandPayload(command))

    /**
     * Writes raw terminal bytes (escape sequences, control chars) without appending carriage return.
     */
    fun sendRaw(raw: String): Boolean = writeBytes(raw.toByteArray(Charsets.UTF_8))

    /**
     * Enqueues a command write on a daemon writer thread without blocking UI callers.
     */
    fun sendCommandAsync(command: String) {
        writeExecutor.execute { sendCommand(command) }
    }

    /**
     * Enqueues raw terminal bytes on a daemon writer thread without blocking UI callers.
     */
    fun sendRawAsync(raw: String) {
        writeExecutor.execute { sendRaw(raw) }
    }

    private fun writeBytes(payload: ByteArray): Boolean {
        val activeOutput = synchronized(lock) {
            if (channel?.isConnected == true) outputStream else null
        } ?: return false

        return try {
            activeOutput.write(payload)
            activeOutput.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Closes the active PTY streams, shell channel, and SSH session.
     */
    fun disconnect(updateState: Boolean = true) {
        val resources = synchronized(lock) {
            terminalBuffer.clear()
            sessionGeneration++
            clearSessionRefsLocked()
        }
        closeResources(resources)
        if (updateState) {
            _ptyOutput.value = ""
            _currentCommandLine.value = ""
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private fun readPtyLoop(stream: InputStream, generation: Long) {
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val bytesRead = stream.read(buffer)
                if (bytesRead < 0) {
                    handleRemoteTermination(generation)
                    break
                }
                if (bytesRead == 0) continue

                val isCurrentSession = synchronized(lock) { sessionGeneration == generation }
                if (!isCurrentSession) break

                val chunk = String(buffer, 0, bytesRead, Charsets.UTF_8)
                val (rendered, commandLine) = synchronized(lock) {
                    terminalBuffer.feed(chunk)
                    val renderedText = terminalBuffer.render()
                    val lineText = terminalBuffer.getCurrentLineText()
                    renderedText to TerminalBuffer.extractCommandFromLine(lineText)
                }
                _ptyOutput.value = rendered
                if (commandLine != null) {
                    _currentCommandLine.value = commandLine
                }
            }
        } catch (_: Exception) {
            // Only surface an error if disconnect() has not already invalidated this generation.
            handleRemoteTermination(generation)
        }
    }

    private fun handleRemoteTermination(generation: Long) {
        val resources = synchronized(lock) {
            if (sessionGeneration != generation) return
            sessionGeneration++
            clearSessionRefsLocked()
        }
        closeResources(resources)
        _connectionState.value = ConnectionState.Error("Sesión PTY cerrada por Termux.")
    }

    private fun clearSessionRefsLocked(): SessionResources {
        val resources = SessionResources(inputStream, outputStream, channel, session)
        inputStream = null
        outputStream = null
        channel = null
        session = null
        readerThread = null
        return resources
    }

    private fun closeResources(resources: SessionResources) {
        runCatching { resources.input?.close() }
        runCatching { resources.output?.close() }
        runCatching { resources.channel?.disconnect() }
        runCatching { resources.session?.disconnect() }
    }

    private data class SessionResources(
        val input: InputStream?,
        val output: OutputStream?,
        val channel: ChannelShell?,
        val session: Session?
    )
}

/**
 * Process-wide holder for the active SSH PTY session and bounded terminal output helpers.
 */
object SshSessionManager {
    const val MAX_OUTPUT_CHARS = 8_192

    private val activeSession = SshPtySession()
    private val autoConnectLock = Any()
    private var initialAutoConnectConsumed = false

    val connectionState: StateFlow<ConnectionState> = activeSession.connectionState
    val ptyOutput: StateFlow<String> = activeSession.ptyOutput
    val currentCommandLine: StateFlow<String> = activeSession.currentCommandLine

    /**
     * Returns true once per process so the UI only auto-connects on cold start.
     */
    fun consumeInitialAutoConnect(): Boolean = synchronized(autoConnectLock) {
        if (initialAutoConnectConsumed) return false
        initialAutoConnectConsumed = true
        true
    }

    /**
     * Normalizes carriage returns and appends a chunk while bounding output to [maxChars].
     */
    fun appendBoundedOutput(
        current: String,
        chunk: String,
        maxChars: Int = MAX_OUTPUT_CHARS
    ): String {
        if (maxChars <= 0) return ""
        val buffer = TerminalBuffer()
        if (current.isNotEmpty()) {
            buffer.feed(current)
        }
        buffer.feed(chunk)
        val rendered = buffer.render()
        return if (rendered.length <= maxChars) rendered else rendered.takeLast(maxChars)
    }

    /**
     * Encodes a command with a PTY carriage return (`\r`) so both shells and raw TUIs treat it as Enter.
     */
    internal fun formatCommandPayload(command: String): ByteArray =
        (command + "\r").toByteArray(Charsets.UTF_8)

    /**
     * Applies sticky CTRL and ALT modifiers to a character or terminal escape sequence.
     */
    fun applyModifiers(input: String, ctrl: Boolean, alt: Boolean): String {
        if (input.isEmpty()) return ""
        val withCtrl = if (ctrl) {
            val ctrlChar = ((input[0].uppercaseChar().code) and 0x1F).toChar()
            ctrlChar + input.substring(1)
        } else {
            input
        }
        return if (alt) "\u001B$withCtrl" else withCtrl
    }

    fun connect(
        isTermuxInstalled: () -> Boolean,
        config: SshConfig,
        timeoutMs: Int = AppConfig.DEFAULT_CONNECT_TIMEOUT_MS
    ): ConnectionState = activeSession.connect(isTermuxInstalled, config, timeoutMs)

    fun sendCommand(command: String): Boolean = activeSession.sendCommand(command)

    fun sendCommandAsync(command: String) = activeSession.sendCommandAsync(command)

    fun sendRaw(raw: String): Boolean = activeSession.sendRaw(raw)

    fun sendRawAsync(raw: String) = activeSession.sendRawAsync(raw)

    fun disconnect() = activeSession.disconnect(updateState = true)
}
