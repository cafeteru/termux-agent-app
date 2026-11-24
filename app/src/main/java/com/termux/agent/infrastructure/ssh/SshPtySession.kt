package com.termux.agent.infrastructure.ssh

import android.os.Handler
import android.os.Looper
import com.termux.agent.application.config.AppConfig
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import com.termux.agent.application.port.AgentAdapter
import com.termux.agent.domain.model.AdapterEvent
import com.termux.agent.domain.model.AgentState
import com.termux.agent.domain.model.ChatMessage
import com.termux.agent.infrastructure.agent.AdapterRegistry
import com.termux.agent.infrastructure.terminal.TerminalBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
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

    private val _activeAdapter = MutableStateFlow<AgentAdapter>(AdapterRegistry.getDefaultAdapter())
    val activeAdapter: StateFlow<AgentAdapter> = _activeAdapter.asStateFlow()

    private val _adapterEvent = MutableStateFlow<AdapterEvent?>(null)
    val adapterEvent: StateFlow<AdapterEvent?> = _adapterEvent.asStateFlow()

    private val _agentState = MutableStateFlow<AgentState>(AgentState.IDLE)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _agentSessions = MutableStateFlow<List<AgentSessionSummary>>(emptyList())
    val agentSessions: StateFlow<List<AgentSessionSummary>> = _agentSessions.asStateFlow()

    private val _agentSessionsLoading = MutableStateFlow(false)
    val agentSessionsLoading: StateFlow<Boolean> = _agentSessionsLoading.asStateFlow()

    private val _agentSessionsError = MutableStateFlow<String?>(null)
    val agentSessionsError: StateFlow<String?> = _agentSessionsError.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _activeConversationTitle = MutableStateFlow<String?>(null)
    val activeConversationTitle: StateFlow<String?> = _activeConversationTitle.asStateFlow()

    @Volatile
    private var lastOutputTimestamp: Long = 0L
    @Volatile
    private var ptyCols: Int = 80
    @Volatile
    private var ptyRows: Int = 24
    private var completedTurnCounter: Long = 0L

    @Volatile
    private var shellDirty: Boolean = false

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readerThread: Thread? = null
    private var monitorThread: Thread? = null
    private var sessionGeneration: Long = 0L
    private var shellGeneration: Long = 0L
    private var conversationGeneration: Long = 0L
    private var awaitingNewConversation: Boolean = false
    private var agentProcessRunning: Boolean = false
    private var baselineConversationIds: Set<String> = emptySet()
    private val knownIdsByAgent: MutableMap<String, Set<String>> = mutableMapOf()

    /**
     * Connects to Termux SSH, opens an xterm-256color PTY shell, and starts reading PTY output.
     */
    fun connect(
        isTermuxInstalled: () -> Boolean,
        config: SshConfig,
        timeoutMs: Int = AppConfig.DEFAULT_CONNECT_TIMEOUT_MS
    ): ConnectionState {
        disconnect(updateState = false)

        val (currentGeneration, currentShellGeneration) = synchronized(lock) {
            val nextSessionGen = ++sessionGeneration
            val nextShellGen = ++shellGeneration
            conversationGeneration++
            shellDirty = false
            awaitingNewConversation = false
            agentProcessRunning = false
            baselineConversationIds = emptySet()
            knownIdsByAgent.clear()
            agentProcessRunning = false
            _agentSessions.value = emptyList()
            _agentSessionsError.value = null
            _agentSessionsLoading.value = false
            nextSessionGen to nextShellGen
        }
        lastOutputTimestamp = 0L
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
            shellChannel.setPtyType("xterm-256color", ptyCols, ptyRows, ptyCols * 8, ptyRows * 16)

            val shellInput = shellChannel.inputStream
            val shellOutput = shellChannel.outputStream
            shellChannel.connect(timeoutMs)

            val initialIdsByAgent = AdapterRegistry.availableAdapters()
                .associate { adapter ->
                    adapter.id.lowercase() to fetchStateIds(adapter, openedSession, currentGeneration).toSet()
                }

            synchronized(lock) {
                session = openedSession
                channel = shellChannel
                inputStream = shellInput
                outputStream = shellOutput
                knownIdsByAgent.putAll(initialIdsByAgent)
                baselineConversationIds = knownIdsByAgent[_activeAdapter.value.id.lowercase()].orEmpty()
                readerThread = thread(
                    start = true,
                    isDaemon = true,
                    name = "termux-ssh-pty-reader"
                ) {
                    readPtyLoop(shellInput, currentGeneration, currentShellGeneration)
                }
                monitorThread = thread(
                    start = true,
                    isDaemon = true,
                    name = "termux-ssh-pty-monitor"
                ) {
                    monitorIdleAndTranscriptLoop(currentGeneration)
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
        val switched: Boolean
        val isConnected: Boolean
        synchronized(lock) {
            switched = !adapter.id.equals(_activeAdapter.value.id, ignoreCase = true)
            if (switched) {
                conversationGeneration++
                awaitingNewConversation = false
                agentProcessRunning = false
                baselineConversationIds = knownIdsByAgent[adapter.id.lowercase()].orEmpty()
                _activeConversationId.value = null
                _activeConversationTitle.value = null
                _chatMessages.value = emptyList()
                _agentSessions.value = emptyList()
                _agentSessionsError.value = null
                _agentSessionsLoading.value = false
            }
            isConnected = session?.isConnected == true
        }
        _activeAdapter.value = adapter
        _agentState.value = adapter.currentState
        if (switched && isConnected && adapter.supportsSessions) {
            refreshAgentSessionsAsync()
        }
    }

    fun setActiveAdapterById(id: String) {
        val adapter = AdapterRegistry.getAdapter(id)
        setActiveAdapter(adapter)
    }

    fun addChatMessage(message: ChatMessage) {
        synchronized(lock) {
            _chatMessages.update { it + message }
        }
    }

    fun clearChat() {
        startNewSession(workspacePath = "", launchAgent = true)
    }

    fun appendTranscriptLine(jsonLine: String): ChatMessage? {
        val parsed = _activeAdapter.value.parseTranscriptLine(jsonLine) ?: return null
        return synchronized(lock) {
            val (updated, _) = mergeTranscriptMessages(_chatMessages.value, listOf(parsed))
            if (updated !== _chatMessages.value) {
                _chatMessages.value = updated
                parsed
            } else {
                null
            }
        }
    }

    private fun mergeTranscriptMessages(
        existing: List<ChatMessage>,
        incoming: List<ChatMessage>
    ): Pair<List<ChatMessage>, ChatMessage?> {
        var result: MutableList<ChatMessage>? = null
        var latestNewAgentReply: ChatMessage? = null

        for (parsed in incoming) {
            val current = result ?: existing
            val existingByStep = if (parsed.stepIndex >= 0) {
                current.indexOfFirst { it.stepIndex == parsed.stepIndex && it.sender == parsed.sender }
            } else {
                -1
            }

            when {
                existingByStep >= 0 -> {
                    if (current[existingByStep] != parsed) {
                        val mutable = result ?: existing.toMutableList().also { result = it }
                        mutable[existingByStep] = parsed
                        if (parsed.sender == "agent" && parsed.toolCalls.isEmpty() && parsed.content.isNotBlank()) {
                            latestNewAgentReply = parsed
                        }
                    }
                }
                parsed.sender == "user" -> {
                    val localDuplicateIdx = current.indexOfFirst {
                        it.stepIndex < 0 && it.sender == "user" && it.content.trim() == parsed.content.trim()
                    }
                    val mutable = result ?: existing.toMutableList().also { result = it }
                    if (localDuplicateIdx >= 0) {
                        mutable[localDuplicateIdx] = parsed
                    } else {
                        mutable.add(parsed)
                    }
                }
                else -> {
                    val mutable = result ?: existing.toMutableList().also { result = it }
                    mutable.add(parsed)
                    if (parsed.sender == "agent" && parsed.toolCalls.isEmpty() && parsed.content.isNotBlank()) {
                        latestNewAgentReply = parsed
                    }
                }
            }
        }

        return (result ?: existing) to latestNewAgentReply
    }

    fun refreshAgentSessions(
        timeoutMs: Int = AppConfig.DEFAULT_CONNECT_TIMEOUT_MS
    ): AgentSessionListingResult {
        val adapter = _activeAdapter.value
        val agentId = adapter.id
        val command = adapter.listSessionsCommand
            ?: return AgentSessionListingResult.Success(emptyList())

        val activeSession = synchronized(lock) {
            session?.takeIf { it.isConnected }
        } ?: run {
            val err = AgentSessionListingResult.Error("No hay una sesión SSH activa.")
            _agentSessionsError.value = err.message
            return err
        }

        _agentSessionsLoading.value = true
        return try {
            val (rawOutput, exitStatus) = runExecCommand(
                activeSession = activeSession,
                command = command,
                timeoutMs = timeoutMs,
                maxBytes = 512 * 1024
            )
            val result = AgentSessionBrowser.parseSessionsOutput(rawOutput, exitStatus)
            synchronized(lock) {
                if (!_activeAdapter.value.id.equals(agentId, ignoreCase = true)) {
                    return result
                }
                when (result) {
                    is AgentSessionListingResult.Success -> {
                        _agentSessions.value = result.sessions
                        _agentSessionsError.value = null
                        val currentId = _activeConversationId.value
                        if (currentId != null) {
                            result.sessions.firstOrNull { it.id == currentId }?.let { matched ->
                                _activeConversationTitle.value = matched.title
                            }
                        }
                    }
                    is AgentSessionListingResult.Error -> {
                        _agentSessionsError.value = result.message
                    }
                }
            }
            result
        } catch (e: Exception) {
            val message = "Error SSH al listar sesiones: ${e.message ?: e.javaClass.simpleName}"
            if (_activeAdapter.value.id.equals(agentId, ignoreCase = true)) {
                _agentSessionsError.value = message
            }
            AgentSessionListingResult.Error(message)
        } finally {
            _agentSessionsLoading.value = false
        }
    }

    fun refreshAgentSessionsAsync() {
        writeExecutor.execute { refreshAgentSessions() }
    }

    fun openConversation(sessionSummary: AgentSessionSummary): Boolean =
        openConversation(
            conversationId = sessionSummary.id,
            workspacePath = sessionSummary.workspacePath,
            title = sessionSummary.title
        )

    fun openConversation(
        conversationId: String,
        workspacePath: String = "",
        title: String? = null
    ): Boolean {
        val cleanId = conversationId.trim()
        if (!AgentSessionBrowser.isValidConversationId(cleanId)) return false

        val adapter = _activeAdapter.value
        val agentId = adapter.id
        if (!adapter.supportsSessions) return false

        val (sessionGen, convGen, needsReopen) = synchronized(lock) {
            if (session?.isConnected != true) return false
            val nextConvGen = ++conversationGeneration
            awaitingNewConversation = false
            val resolvedTitle = title?.takeIf { it.isNotBlank() }
                ?: _agentSessions.value.firstOrNull { it.id == cleanId }?.title
                ?: "Sesión ${cleanId.take(8)}"
            _activeConversationId.value = cleanId
            _activeConversationTitle.value = resolvedTitle
            _chatMessages.value = emptyList()
            Triple(sessionGeneration, nextConvGen, shellDirty)
        }

        syncTranscriptById(adapter, cleanId, sessionGen, convGen, allowTurnCompletion = false)

        if (needsReopen && !reopenShellChannel()) {
            return false
        }

        val command = adapter.launchConversationCommand(workspacePath, cleanId)
        val launched = sendCommand(command)
        if (launched) {
            synchronized(lock) { agentProcessRunning = true }
        }
        return launched
    }

    fun openConversationAsync(sessionSummary: AgentSessionSummary) {
        writeExecutor.execute { openConversation(sessionSummary) }
    }

    fun openConversationAsync(
        conversationId: String,
        workspacePath: String = "",
        title: String? = null
    ) {
        writeExecutor.execute { openConversation(conversationId, workspacePath, title) }
    }

    fun startNewSession(
        workspacePath: String = "",
        launchAgent: Boolean = true
    ): Boolean {
        val adapter = _activeAdapter.value
        val agentId = adapter.id
        val supportsSessions = adapter.supportsSessions

        val activeSession = synchronized(lock) { session?.takeIf { it.isConnected } }
        val capturedIds = if (supportsSessions && activeSession != null) {
            val gen = synchronized(lock) { sessionGeneration }
            fetchStateIds(adapter, activeSession, gen).toSet()
        } else {
            emptySet()
        }

        val needsReopen = synchronized(lock) {
            conversationGeneration++
            if (supportsSessions) {
                val key = agentId.lowercase()
                val mergedBaseline = knownIdsByAgent[key].orEmpty() + capturedIds
                knownIdsByAgent[key] = mergedBaseline
                baselineConversationIds = mergedBaseline
                awaitingNewConversation = true
            } else {
                awaitingNewConversation = false
            }
            _activeConversationId.value = null
            _activeConversationTitle.value = null
            _chatMessages.value = emptyList()
            shellDirty
        }

        if (!launchAgent) {
            return true
        }
        if (activeSession == null) {
            return false
        }
        if (needsReopen && !reopenShellChannel()) {
            return false
        }
        val launched = sendCommand(adapter.launchCommand(workspacePath))
        if (launched) {
            synchronized(lock) { agentProcessRunning = true }
        }
        return launched
    }

    fun startNewSessionAsync(
        workspacePath: String = "",
        launchAgent: Boolean = true
    ) {
        writeExecutor.execute { startNewSession(workspacePath, launchAgent) }
    }

    fun launchActiveAgent(workspacePath: String = ""): Boolean {
        val adapter = _activeAdapter.value
        if (adapter.supportsSessions) {
            val boundId = _activeConversationId.value
            return if (boundId != null) {
                openConversation(boundId, workspacePath, _activeConversationTitle.value)
            } else {
                startNewSession(workspacePath, launchAgent = true)
            }
        }
        return sendCommand(adapter.launchCommand(workspacePath))
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

        return try {
            val (rawOutput, exitStatus) = runExecCommand(
                activeSession = activeSession,
                command = WorkspaceBrowser.LIST_DIRECTORIES_COMMAND,
                stdinPayload = WorkspaceBrowser.sanitizeRequestedPath(path) + "\n",
                timeoutMs = timeoutMs,
                maxBytes = 256 * 1024
            )
            WorkspaceBrowser.parseDirectoryListingOutput(rawOutput, exitStatus)
        } catch (e: Exception) {
            DirectoryListingResult.Error(
                "Error SSH al explorar directorios: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun runExecCommand(
        activeSession: Session,
        command: String,
        stdinPayload: String? = null,
        timeoutMs: Int,
        maxBytes: Int = 512 * 1024
    ): Pair<String, Int> {
        var exec: ChannelExec? = null
        return try {
            exec = activeSession.openChannel("exec") as ChannelExec
            exec.setCommand(command)
            val stdout = exec.inputStream
            val stdin = if (stdinPayload != null) exec.outputStream else null
            exec.connect(timeoutMs)

            if (stdin != null && stdinPayload != null) {
                stdin.write(stdinPayload.toByteArray(Charsets.UTF_8))
                stdin.flush()
                stdin.close()
            }

            val outBuffer = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            val deadlineNanos = System.nanoTime() + timeoutMs.coerceAtLeast(1).toLong() * 1_000_000L
            while (true) {
                if (System.nanoTime() >= deadlineNanos) {
                    runCatching { exec?.disconnect() }
                    throw java.net.SocketTimeoutException("SSH exec timeout")
                }
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

            String(outBuffer.toByteArray(), Charsets.UTF_8) to exec.exitStatus
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
    fun sendPrompt(text: String, workspacePath: String = ""): Boolean {
        val needsLaunch = synchronized(lock) { !agentProcessRunning }
        if (needsLaunch) {
            if (!launchActiveAgent(workspacePath)) return false
            val readyDeadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < readyDeadline) {
                val lines = synchronized(lock) { terminalBuffer.getLines() }
                if (_activeAdapter.value.isPromptReady(lines)) break
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            // A timeout is a bounded fallback: deliver the prompt even if a CLI
            // did not expose a recognizable prompt line, rather than losing it.
        }

        val activeOutput = synchronized(lock) {
            if (channel?.isConnected == true) outputStream else null
        } ?: return false

        return try {
            _activeAdapter.value.sendPrompt(activeOutput, text)
            synchronized(lock) {
                shellDirty = true
                val agentId = _activeAdapter.value.id
                if (_activeAdapter.value.supportsSessions &&
                    _activeConversationId.value == null &&
                    !awaitingNewConversation
                ) {
                    baselineConversationIds = knownIdsByAgent[agentId.lowercase()].orEmpty()
                    awaitingNewConversation = true
                }
                val userMsg = ChatMessage(
                    stepIndex = -(_chatMessages.value.size + 1),
                    sender = "user",
                    content = text,
                    timestamp = currentTimestamp()
                )
                _chatMessages.update { it + userMsg }
            }
            _agentState.value = _activeAdapter.value.currentState
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
            if (_adapterEvent.value is AdapterEvent.PermissionRequired) {
                _adapterEvent.value = null
            }
            _agentState.value = _activeAdapter.value.currentState
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Enqueues prompt delivery on a daemon writer thread without blocking UI callers.
     */
    fun sendPromptAsync(text: String, workspacePath: String = "") {
        writeExecutor.execute { sendPrompt(text, workspacePath) }
    }

    fun sendPromptAsync(
        text: String,
        workspacePath: String = "",
        onResult: (Boolean) -> Unit
    ) {
        writeExecutor.execute {
            val sent = sendPrompt(text, workspacePath)
            Handler(Looper.getMainLooper()).post {
                onResult(sent)
            }
        }
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

    fun resizePty(cols: Int, rows: Int, pixelWidth: Int = cols * 8, pixelHeight: Int = rows * 16) {
        val safeCols = cols.coerceIn(20, 300)
        val safeRows = rows.coerceIn(5, 200)
        ptyCols = safeCols
        ptyRows = safeRows
        val activeChannel = synchronized(lock) {
            channel?.takeIf { it.isConnected }
        } ?: return
        try {
            activeChannel.setPtySize(safeCols, safeRows, pixelWidth, pixelHeight)
        } catch (_: Exception) {
            // The next shell reopen will use the stored dimensions.
        }
    }

    private fun writeBytes(payload: ByteArray): Boolean {
        val activeOutput = synchronized(lock) {
            if (channel?.isConnected == true) outputStream else null
        } ?: return false

        return try {
            activeOutput.write(payload)
            activeOutput.flush()
            shellDirty = true
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
            shellGeneration++
            conversationGeneration++
            shellDirty = false
            awaitingNewConversation = false
            baselineConversationIds = emptySet()
            knownIdsByAgent.clear()
            clearSessionRefsLocked()
        }
        if (updateState) {
            _activeAdapter.value.reset()
            _activeAdapter.value = AdapterRegistry.getDefaultAdapter()
            _adapterEvent.value = null
            _agentState.value = AgentState.IDLE
            _chatMessages.value = emptyList()
            _activeConversationId.value = null
            _activeConversationTitle.value = null
            _agentSessions.value = emptyList()
            _agentSessionsError.value = null
            _agentSessionsLoading.value = false
            lastOutputTimestamp = 0L
            _ptyOutput.value = ""
            _currentCommandLine.value = ""
            _connectionState.value = ConnectionState.Disconnected
        }
        closeResources(resources)
    }

    private fun reopenShellChannel(timeoutMs: Int = AppConfig.DEFAULT_CONNECT_TIMEOUT_MS): Boolean {
        val (activeSession, currentSessionGen, nextShellGen, oldShell) = synchronized(lock) {
            val s = session?.takeIf { it.isConnected } ?: return false
            val sGen = sessionGeneration
            val nextShGen = ++shellGeneration
            terminalBuffer.clear()
            lastOutputTimestamp = 0L
            shellDirty = false
            agentProcessRunning = false
            val detached = SessionResources(
                input = inputStream,
                output = outputStream,
                channel = channel,
                session = null,
                monitor = null
            )
            inputStream = null
            outputStream = null
            channel = null
            readerThread = null
            Quadruple(s, sGen, nextShGen, detached)
        }

        _ptyOutput.value = ""
        _currentCommandLine.value = ""
        _activeAdapter.value.reset()
        _adapterEvent.value = null
        _agentState.value = AgentState.IDLE

        closeResources(oldShell)

        var newChannel: ChannelShell? = null
        var newInput: InputStream? = null
        var newOutput: OutputStream? = null
        return try {
            newChannel = activeSession.openChannel("shell") as ChannelShell
            newChannel.setPty(true)
            newChannel.setPtyType(
                "xterm-256color",
                ptyCols,
                ptyRows,
                ptyCols * 8,
                ptyRows * 16
            )
            newInput = newChannel.inputStream
            newOutput = newChannel.outputStream
            newChannel.connect(timeoutMs)

            val installed = synchronized(lock) {
                val stillValid = sessionGeneration == currentSessionGen &&
                    shellGeneration == nextShellGen &&
                    session === activeSession &&
                    activeSession.isConnected
                if (stillValid) {
                    channel = newChannel
                    inputStream = newInput
                    outputStream = newOutput
                    readerThread = thread(
                        start = true,
                        isDaemon = true,
                        name = "termux-ssh-pty-reader"
                    ) {
                        readPtyLoop(newInput, currentSessionGen, nextShellGen)
                    }
                    true
                } else {
                    false
                }
            }
            if (!installed) {
                runCatching { newInput.close() }
                runCatching { newOutput.close() }
                runCatching { newChannel.disconnect() }
                return false
            }

            val promptDeadline = System.currentTimeMillis() + 400L
            while (System.currentTimeMillis() < promptDeadline) {
                if (lastOutputTimestamp > 0L) break
                val stillValid = synchronized(lock) {
                    sessionGeneration == currentSessionGen && shellGeneration == nextShellGen
                }
                if (!stillValid) break
                Thread.sleep(20)
            }
            true
        } catch (_: Exception) {
            runCatching { newInput?.close() }
            runCatching { newOutput?.close() }
            runCatching { newChannel?.disconnect() }
            false
        }
    }

    private fun readPtyLoop(stream: InputStream, sessionGen: Long, shellGen: Long) {
        val buffer = ByteArray(4096)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        try {
            while (true) {
                val bytesRead = stream.read(buffer)
                if (bytesRead < 0) {
                    handleRemoteTermination(sessionGen, shellGen)
                    break
                }
                if (bytesRead == 0) continue

                val isCurrentSession = synchronized(lock) {
                    sessionGeneration == sessionGen && shellGeneration == shellGen
                }
                if (!isCurrentSession) break

                val chunk = decoder.decode(ByteBuffer.wrap(buffer, 0, bytesRead)).toString()
                if (chunk.isEmpty()) continue
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
                    applyAdapterEvent(event)
                }
                _agentState.value = _activeAdapter.value.currentState
            }
        } catch (_: Exception) {
            // Only surface an error if neither session nor shell was replaced locally.
            handleRemoteTermination(sessionGen, shellGen)
        }
    }

    private fun applyAdapterEvent(event: AdapterEvent) {
        val emitted = if (event is AdapterEvent.TurnCompleted) {
            synchronized(lock) {
                val id = ++completedTurnCounter
                AdapterEvent.TurnCompleted(event.summary, id)
            }
        } else event
        _adapterEvent.value = emitted
        if (event is AdapterEvent.TurnCompleted && event.summary.isNotBlank()) {
            synchronized(lock) {
                _chatMessages.update { list ->
                    val lastAgent = list.lastOrNull { it.sender == "agent" }
                    if (lastAgent != null && lastAgent.content == event.summary) {
                        list
                    } else {
                        list + ChatMessage(
                            stepIndex = -(list.size + 1),
                            sender = "agent",
                            content = event.summary,
                            timestamp = currentTimestamp()
                        )
                    }
                }
            }
        }
    }

    private fun monitorIdleAndTranscriptLoop(generation: Long) {
        var tick = 0
        try {
            if (_activeAdapter.value.supportsSessions) {
                refreshAgentSessions()
            }
            while (true) {
                Thread.sleep(300)
                val (isCurrent, shellConnected) = synchronized(lock) {
                    (sessionGeneration == generation && session?.isConnected == true) to (channel?.isConnected == true)
                }
                if (!isCurrent) break

                val lastTs = lastOutputTimestamp
                if (lastTs > 0L && shellConnected) {
                    val idleMs = System.currentTimeMillis() - lastTs
                    val screenLines = synchronized(lock) { terminalBuffer.getLines() }
                    val event = _activeAdapter.value.onScreenUpdated(screenLines, idleMs)
                    if (event != null) {
                        applyAdapterEvent(event)
                    }
                    _agentState.value = _activeAdapter.value.currentState
                }

                tick++
                val adapter = _activeAdapter.value
                if (adapter.supportsSessions && tick % 3 == 0) {
                    val (boundId, awaiting, convGen) = synchronized(lock) {
                        Triple(_activeConversationId.value, awaitingNewConversation, conversationGeneration)
                    }
                    when {
                        boundId != null -> syncTranscriptById(
                            agentAdapter = adapter,
                            conversationId = boundId,
                            sessionGen = generation,
                            convGen = convGen,
                            allowTurnCompletion = true
                        )
                        awaiting -> discoverAndBindNewConversation(adapter, generation, convGen)
                    }
                }
            }
        } catch (_: InterruptedException) {
            // Thread interrupted on disconnect.
        } catch (_: Exception) {
            // Best-effort background polling.
        }
    }

    private fun fetchStateIds(
        adapter: AgentAdapter,
        activeSession: Session? = synchronized(lock) { session?.takeIf { it.isConnected } },
        generation: Long = synchronized(lock) { sessionGeneration },
        timeoutMs: Int = 1_500
    ): List<String> {
        val command = adapter.listStateIdsCommand ?: return emptyList()
        val target = activeSession ?: return emptyList()
        val validGen = synchronized(lock) { sessionGeneration == generation }
        if (!validGen) return emptyList()

        return try {
            val (raw, _) = runExecCommand(
                activeSession = target,
                command = command,
                timeoutMs = timeoutMs,
                maxBytes = 128 * 1024
            )
            AgentSessionBrowser.parseStateIdsOutput(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun discoverAndBindNewConversation(
        adapter: AgentAdapter,
        generation: Long,
        convGen: Long
    ) {
        val agentId = adapter.id
        val (activeSession, baselineSnapshot) = synchronized(lock) {
            if (sessionGeneration != generation ||
                conversationGeneration != convGen ||
                !_activeAdapter.value.id.equals(agentId, ignoreCase = true) ||
                !awaitingNewConversation ||
                _activeConversationId.value != null
            ) {
                return
            }
            session?.takeIf { it.isConnected } to baselineConversationIds
        }
        val targetSession = activeSession ?: return

        val currentIds = fetchStateIds(adapter, targetSession, generation)
        if (currentIds.isEmpty()) return

        val newId = currentIds.firstOrNull { it !in baselineSnapshot } ?: return
        val bound = synchronized(lock) {
            if (sessionGeneration != generation ||
                conversationGeneration != convGen ||
                !_activeAdapter.value.id.equals(agentId, ignoreCase = true) ||
                !awaitingNewConversation ||
                _activeConversationId.value != null
            ) {
                false
            } else {
                awaitingNewConversation = false
                val key = agentId.lowercase()
                knownIdsByAgent[key] = knownIdsByAgent[key].orEmpty() + currentIds
                _activeConversationId.value = newId
                if (_activeConversationTitle.value.isNullOrBlank()) {
                    val userTitle = _chatMessages.value
                        .firstOrNull { it.sender == "user" && it.content.isNotBlank() }
                        ?.content
                        ?.trim()
                        ?.replace('\n', ' ')
                        ?.take(40)
                    _activeConversationTitle.value = userTitle ?: "Sesión ${newId.take(8)}"
                }
                true
            }
        }
        if (bound) {
            syncTranscriptById(
                agentAdapter = adapter,
                conversationId = newId,
                sessionGen = generation,
                convGen = convGen,
                allowTurnCompletion = true
            )
            refreshAgentSessions()
        }
    }

    internal fun syncTranscriptById(
        agentAdapter: AgentAdapter,
        conversationId: String,
        sessionGen: Long = synchronized(lock) { sessionGeneration },
        convGen: Long = synchronized(lock) { conversationGeneration },
        allowTurnCompletion: Boolean = true,
        timeoutMs: Int = 2_000
    ) {
        if (!AgentSessionBrowser.isValidConversationId(conversationId)) return

        val readCommand = agentAdapter.readTranscriptCommand ?: return
        val agentId = agentAdapter.id

        val activeSession = synchronized(lock) {
            if (sessionGeneration == sessionGen &&
                conversationGeneration == convGen &&
                _activeAdapter.value.id.equals(agentId, ignoreCase = true) &&
                _activeConversationId.value == conversationId
            ) {
                session?.takeIf { it.isConnected }
            } else {
                null
            }
        } ?: return

        try {
            val (raw, exitStatus) = runExecCommand(
                activeSession = activeSession,
                command = readCommand,
                stdinPayload = "$conversationId\n",
                timeoutMs = timeoutMs,
                maxBytes = 512 * 1024
            )
            if (exitStatus != 0 || raw.isBlank()) return

            val parsedMessages = raw.lineSequence()
                .mapIndexedNotNull { idx, line ->
                    agentAdapter.parseTranscriptLine(line, fallbackStepIndex = idx)
                }
                .toList()
            if (parsedMessages.isEmpty()) return

            val latestNewAgentReply = synchronized(lock) {
                if (sessionGeneration != sessionGen ||
                    conversationGeneration != convGen ||
                    !_activeAdapter.value.id.equals(agentId, ignoreCase = true) ||
                    _activeConversationId.value != conversationId
                ) {
                    return
                }
                val (updated, reply) = mergeTranscriptMessages(_chatMessages.value, parsedMessages)
                if (updated !== _chatMessages.value) {
                    _chatMessages.value = updated
                }
                reply
            }

            if (allowTurnCompletion &&
                latestNewAgentReply != null &&
                _activeAdapter.value.id.equals(agentId, ignoreCase = true) &&
                _activeAdapter.value.currentState == AgentState.WORKING
            ) {
                _activeAdapter.value.markCompleted()
                _adapterEvent.value = AdapterEvent.TurnCompleted(latestNewAgentReply.content)
                _agentState.value = _activeAdapter.value.currentState
                refreshAgentSessions()
            }
        } catch (_: Exception) {
            // Ignore transient exec errors so the PTY session is never disrupted.
        }
    }

    private fun handleRemoteTermination(sessionGen: Long, shellGen: Long) {
        val resources = synchronized(lock) {
            if (sessionGeneration != sessionGen || shellGeneration != shellGen) return
            sessionGeneration++
            shellGeneration++
            clearSessionRefsLocked()
        }
        closeResources(resources)
        _connectionState.value = ConnectionState.Error("Sesión PTY cerrada por Termux.")
    }

    private fun clearSessionRefsLocked(): SessionResources {
        val resources = SessionResources(inputStream, outputStream, channel, session, monitorThread)
        inputStream = null
        outputStream = null
        channel = null
        session = null
        readerThread = null
        monitorThread = null
        return resources
    }

    private fun closeResources(resources: SessionResources) {
        runCatching { resources.monitor?.interrupt() }
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
        val session: Session?,
        val monitor: Thread? = null
    )

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}
