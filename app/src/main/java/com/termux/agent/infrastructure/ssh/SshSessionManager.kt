package com.termux.agent.infrastructure.ssh

import com.termux.agent.application.config.AppConfig
import com.termux.agent.application.port.AgentAdapter
import com.termux.agent.domain.model.AdapterEvent
import com.termux.agent.domain.model.AgentState
import com.termux.agent.domain.model.ChatMessage
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide holder for the active SSH PTY session.
 */
object SshSessionManager {
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
    val agentSessions: StateFlow<List<AgentSessionSummary>> = activeSession.agentSessions
    val agentSessionsLoading: StateFlow<Boolean> = activeSession.agentSessionsLoading
    val agentSessionsError: StateFlow<String?> = activeSession.agentSessionsError
    val activeConversationId: StateFlow<String?> = activeSession.activeConversationId
    val activeConversationTitle: StateFlow<String?> = activeSession.activeConversationTitle

    /**
     * Returns true once per process so the UI only auto-connects on cold start.
     */
    fun consumeInitialAutoConnect(): Boolean = synchronized(autoConnectLock) {
        if (initialAutoConnectConsumed) return false
        initialAutoConnectConsumed = true
        true
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

    fun refreshAgentSessions(timeoutMs: Int = AppConfig.DEFAULT_CONNECT_TIMEOUT_MS): AgentSessionListingResult =
        activeSession.refreshAgentSessions(timeoutMs)

    fun refreshAgentSessionsAsync() = activeSession.refreshAgentSessionsAsync()

    fun openConversation(sessionSummary: AgentSessionSummary): Boolean =
        activeSession.openConversation(sessionSummary)

    fun openConversation(
        conversationId: String,
        workspacePath: String = "",
        title: String? = null
    ): Boolean = activeSession.openConversation(conversationId, workspacePath, title)

    fun openConversationAsync(sessionSummary: AgentSessionSummary) =
        activeSession.openConversationAsync(sessionSummary)

    fun openConversationAsync(
        conversationId: String,
        workspacePath: String = "",
        title: String? = null
    ) = activeSession.openConversationAsync(conversationId, workspacePath, title)

    fun startNewSession(
        workspacePath: String = "",
        launchAgent: Boolean = true
    ): Boolean = activeSession.startNewSession(workspacePath, launchAgent)

    fun startNewSessionAsync(
        workspacePath: String = "",
        launchAgent: Boolean = true
    ) = activeSession.startNewSessionAsync(workspacePath, launchAgent)

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

    fun sendPrompt(text: String, workspacePath: String = ""): Boolean =
        activeSession.sendPrompt(text, workspacePath)

    fun sendPromptAsync(text: String, workspacePath: String = "") =
        activeSession.sendPromptAsync(text, workspacePath)

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
