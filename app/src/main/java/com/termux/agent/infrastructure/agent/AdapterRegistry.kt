package com.termux.agent.infrastructure.agent

import com.termux.agent.application.port.AgentAdapter

object AdapterRegistry {
    private const val DEFAULT_AGENT_ID = "agy"
    private val adapters = linkedMapOf(
        "agy" to { AgyAdapter() },
        "copilot" to { CopilotAdapter() }
    )

    fun getAdapter(id: String): AgentAdapter {
        val factory = adapters[id.lowercase()] ?: adapters.getValue(DEFAULT_AGENT_ID)
        return factory()
    }

    fun getDefaultAdapter(): AgentAdapter = adapters.getValue(DEFAULT_AGENT_ID)()

    fun findAdapter(id: String): AgentAdapter? = adapters[id.lowercase()]?.invoke()

    fun availableAdapters(): List<AgentAdapter> = adapters.values.map { it() }

    fun supportedAgentIds(): List<String> = adapters.keys.toList()
}
