package com.termux.agent

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import com.termux.agent.adapter.AdapterEvent
import com.termux.agent.adapter.AdapterRegistry
import com.termux.agent.adapter.AgentAdapter
import com.termux.agent.adapter.AgentState
import com.termux.agent.adapter.AgyTranscriptParser
import com.termux.agent.adapter.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
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

    private val _activeAdapter = MutableStateFlow<AgentAdapter>(AdapterRegistry.getAdapter("agy"))
    val activeAdapter: StateFlow<AgentAdapter> = _activeAdapter.asStateFlow()

    private val _adapterEvent = MutableStateFlow<AdapterEvent?>(null)
    val adapterEvent: StateFlow<AdapterEvent?> = _adapterEvent.asStateFlow()

    private val _agentState = MutableStateFlow<AgentState>(AgentState.IDLE)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private var lastOutputTimestamp: Long = 0L

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

    fun setActiveAdapter(adapter: AgentAdapter) {
        _activeAdapter.value = adapter
        _agentState.value = adapter.currentState
    }

    fun setActiveAdapterById(id: String) {
        val adapter = AdapterRegistry.getAdapter(id)
        setActiveAdapter(adapter)
    }

    fun addChatMessage(message: ChatMessage) {
        _chatMessages.update { it + message }
    }

    fun clearChat() {
        _chatMessages.value = emptyList()
    }

    fun appendTranscriptLine(jsonLine: String) {
        val parsed = AgyTranscriptParser.parseLine(jsonLine)
        if (parsed != null) {
            _chatMessages.update { list ->
                if (list.none { it.stepIndex == parsed.stepIndex && it.sender == parsed.sender }) {
                    list + parsed
                } else {
                    list
                }
            }
        }
    }

    fun launchActiveAgent(workspacePath: String = ""): Boolean {
        val command = _activeAdapter.value.launchCommand(workspacePath)
        return sendCommand(command)
    }

    fun launchActiveAgentAsync(workspacePath: String = "") {
        writeExecutor.execute { launchActiveAgent(workspacePath) }
    }

    /**
     * Lists subdirectories of [path] (or remote `$HOME` when blank) over a short-lived SSH exec channel.
     */
    fun listDirectories(
        path: String = "",
        timeoutMs: Int = AppConfig.DEFAULT_CONNECT_TIMEOUT_MS
    ): DirectoryListingResult {
        val activeSession = synchronized(lock) {
            session?.takeIf { it.isConnected }
        } ?: return DirectoryListingResult.Error("No hay una sesión SSH activa.")

        var exec: ChannelExec? = null
        return try {
            exec = activeSession.openChannel("exec") as ChannelExec
            exec.setCommand(WorkspaceBrowser.LIST_DIRECTORIES_COMMAND)
            val stdout = exec.inputStream
            val stdin = exec.outputStream
            exec.connect(timeoutMs)

            stdin.write((WorkspaceBrowser.sanitizeRequestedPath(path) + "\n").toByteArray(Charsets.UTF_8))
            stdin.flush()
            stdin.close()

            val outBuffer = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            val maxBytes = 256 * 1024
            while (true) {
                val read = stdout.read(chunk)
                if (read < 0) break
                if (read == 0) continue
                val remaining = maxBytes - outBuffer.size()
                if (remaining <= 0) break
                outBuffer.write(chunk, 0, minOf(read, remaining))
            }

            val deadline = System.currentTimeMillis() + 1_000L
            while (!exec.isClosed && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }

            val rawOutput = String(outBuffer.toByteArray(), Charsets.UTF_8)
            WorkspaceBrowser.parseDirectoryListingOutput(rawOutput, exec.exitStatus)
        } catch (e: Exception) {
            DirectoryListingResult.Error(
                "Error SSH al explorar directorios: ${e.message ?: e.javaClass.simpleName}"
            )
        } finally {
            runCatching { exec?.disconnect() }
        }
    }

    internal fun emitAdapterEventForTesting(event: AdapterEvent) {
        _adapterEvent.value = event
        if (event is AdapterEvent.TurnCompleted && event.summary.isNotBlank()) {
            val agentMsg = ChatMessage(
                stepIndex = _chatMessages.value.size + 1,
                sender = "agent",
                content = event.summary,
                timestamp = currentTimestamp()
            )
            _chatMessages.update { it + agentMsg }
        }
        _agentState.value = _activeAdapter.value.currentState
    }

    /**
     * Sends a user prompt via the active adapter to the PTY.
     */
    fun sendPrompt(text: String): Boolean {
        val activeOutput = synchronized(lock) {
            if (channel?.isConnected == true) outputStream else null
        } ?: return false

        return try {
            _activeAdapter.value.sendPrompt(activeOutput, text)
            _agentState.value = _activeAdapter.value.currentState
            val userMsg = ChatMessage(
                stepIndex = _chatMessages.value.size + 1,
                sender = "user",
                content = text,
                timestamp = currentTimestamp()
            )
            _chatMessages.update { it + userMsg }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resolves a pending permission request via the active adapter.
     */
    fun resolvePermission(allow: Boolean, optionIndex: Int? = null): Boolean {
        val activeOutput = synchronized(lock) {
            if (channel?.isConnected == true) outputStream else null
        } ?: return false

        return try {
            _activeAdapter.value.resolvePermission(activeOutput, allow, optionIndex)
            _agentState.value = _activeAdapter.value.currentState
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Enqueues prompt delivery on a daemon writer thread without blocking UI callers.
     */
    fun sendPromptAsync(text: String) {
        writeExecutor.execute { sendPrompt(text) }
    }

    /**
     * Enqueues permission resolution on a daemon writer thread without blocking UI callers.
     */
    fun resolvePermissionAsync(allow: Boolean, optionIndex: Int? = null) {
        writeExecutor.execute { resolvePermission(allow, optionIndex) }
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
            _activeAdapter.value.reset()
            _activeAdapter.value = AdapterRegistry.getAdapter("agy")
            _adapterEvent.value = null
            _agentState.value = AgentState.IDLE
            _chatMessages.value = emptyList()
            lastOutputTimestamp = 0L
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
                val now = System.currentTimeMillis()
                val idleMs = if (lastOutputTimestamp > 0L) now - lastOutputTimestamp else 0L
                lastOutputTimestamp = now

                val (rendered, commandLine, screenLines) = synchronized(lock) {
                    terminalBuffer.feed(chunk)
                    val renderedText = terminalBuffer.render()
                    val lineText = terminalBuffer.getCurrentLineText()
                    val lines = terminalBuffer.getLines()
                    Triple(renderedText, TerminalBuffer.extractCommandFromLine(lineText), lines)
                }
                _ptyOutput.value = rendered
                if (commandLine != null) {
                    _currentCommandLine.value = commandLine
                }

                val event = _activeAdapter.value.onScreenUpdated(screenLines, idleMs)
                if (event != null) {
                    _adapterEvent.value = event
                    if (event is AdapterEvent.TurnCompleted && event.summary.isNotBlank()) {
                        val agentMsg = ChatMessage(
                            stepIndex = _chatMessages.value.size + 1,
                            sender = "agent",
                            content = event.summary,
                            timestamp = currentTimestamp()
                        )
                        _chatMessages.update { it + agentMsg }
                    }
                }
                _agentState.value = _activeAdapter.value.currentState
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

    private fun currentTimestamp(): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
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
    val activeAdapter: StateFlow<AgentAdapter> = activeSession.activeAdapter
    val adapterEvent: StateFlow<AdapterEvent?> = activeSession.adapterEvent
    val agentState: StateFlow<AgentState> = activeSession.agentState
    val chatMessages: StateFlow<List<ChatMessage>> = activeSession.chatMessages

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

    fun setActiveAdapter(adapter: AgentAdapter) = activeSession.setActiveAdapter(adapter)

    fun setActiveAdapterById(id: String) = activeSession.setActiveAdapterById(id)

    fun addChatMessage(message: ChatMessage) = activeSession.addChatMessage(message)

    fun clearChat() = activeSession.clearChat()

    fun appendTranscriptLine(jsonLine: String) = activeSession.appendTranscriptLine(jsonLine)

    fun launchActiveAgent(workspacePath: String = ""): Boolean =
        activeSession.launchActiveAgent(workspacePath)

    fun launchActiveAgentAsync(workspacePath: String = "") =
        activeSession.launchActiveAgentAsync(workspacePath)

    fun listDirectories(
        path: String = "",
        timeoutMs: Int = AppConfig.DEFAULT_CONNECT_TIMEOUT_MS
    ): DirectoryListingResult = activeSession.listDirectories(path, timeoutMs)

    internal fun emitAdapterEventForTesting(event: AdapterEvent) =
        activeSession.emitAdapterEventForTesting(event)

    fun sendPrompt(text: String): Boolean = activeSession.sendPrompt(text)

    fun sendPromptAsync(text: String) = activeSession.sendPromptAsync(text)

    fun resolvePermission(allow: Boolean, optionIndex: Int? = null): Boolean =
        activeSession.resolvePermission(allow, optionIndex)

    fun resolvePermissionAsync(allow: Boolean, optionIndex: Int? = null) =
        activeSession.resolvePermissionAsync(allow, optionIndex)

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
