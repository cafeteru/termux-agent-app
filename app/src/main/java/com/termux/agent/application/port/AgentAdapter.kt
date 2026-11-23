package com.termux.agent.application.port

import com.termux.agent.domain.model.AdapterEvent
import com.termux.agent.domain.model.AgentState
import com.termux.agent.domain.model.ChatMessage
import java.io.OutputStream

interface AgentAdapter {
    val id: String
    val name: String
    val displayName: String
        get() = name
    val currentState: AgentState
    val listSessionsCommand: String?
        get() = null
    val listStateIdsCommand: String?
        get() = null
    val readTranscriptCommand: String?
        get() = null
    val supportsSessions: Boolean
        get() = listSessionsCommand != null && listStateIdsCommand != null && readTranscriptCommand != null

    fun launchCommand(workspacePath: String = ""): String
    fun launchConversationCommand(workspacePath: String = "", conversationId: String? = null): String =
        launchCommand(workspacePath)
    fun parseTranscriptLine(jsonLine: String, fallbackStepIndex: Int = -1): ChatMessage? = null
    fun onScreenUpdated(screenLines: List<String>, idleMs: Long): AdapterEvent?
    fun sendPrompt(ptyOutput: OutputStream, text: String)
    fun resolvePermission(ptyOutput: OutputStream, allow: Boolean, optionIndex: Int? = null)
    fun isPromptReady(screenLines: List<String>): Boolean = false
    fun markCompleted() = Unit
    fun reset()
}

internal fun buildWorkspaceLaunchCommand(workspacePath: String, binary: String): String {
    val cleaned = workspacePath.replace("\r", "").replace("\n", "").trim()
    if (cleaned.isEmpty()) return binary

    val quotedPath = when {
        cleaned == "~" -> "\"\$HOME\""
        cleaned.startsWith("~/") -> "\"\$HOME/${escapeDoubleQuotedShell(cleaned.removePrefix("~/"))}\""
        else -> "\"${escapeDoubleQuotedShell(cleaned)}\""
    }
    return "cd $quotedPath && $binary"
}

private fun escapeDoubleQuotedShell(value: String): String = buildString(value.length) {
    for (ch in value) {
        when (ch) {
            '\\', '"', '$', '`' -> {
                append('\\')
                append(ch)
            }
            else -> append(ch)
        }
    }
}
