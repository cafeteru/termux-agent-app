package com.termux.agent.domain.model

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

data class ChatMessage(
    val stepIndex: Int,
    val sender: String,
    val content: String,
    val timestamp: String = "",
    val toolCalls: List<String> = emptyList()
)
