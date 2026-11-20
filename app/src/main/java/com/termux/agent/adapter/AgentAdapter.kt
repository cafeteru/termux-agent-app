package com.termux.agent.adapter

import java.io.OutputStream

enum class AgentState {
    IDLE,
    WORKING,
    WAITING_PERMISSION,
    COMPLETED
}

sealed class AdapterEvent {
    data class StateChanged(val state: AgentState) : AdapterEvent()
    data class PermissionRequired(
        val id: String,
        val tool: String,
        val command: String,
        val options: List<String> = listOf("Allow", "Deny")
    ) : AdapterEvent()
    data class TurnCompleted(val summary: String) : AdapterEvent()
}

interface AgentAdapter {
    val id: String
    val name: String
    val currentState: AgentState

    fun launchCommand(workspacePath: String = ""): String
    fun onScreenUpdated(screenLines: List<String>, idleMs: Long): AdapterEvent?
    fun sendPrompt(ptyOutput: OutputStream, text: String)
    fun resolvePermission(ptyOutput: OutputStream, allow: Boolean, optionIndex: Int? = null)
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
