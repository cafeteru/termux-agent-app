package com.termux.agent.adapter

object AdapterRegistry {
    private val adapters = mapOf(
        "agy" to { AgyAdapter() },
        "copilot" to { CopilotAdapter() }
    )

    fun getAdapter(id: String): AgentAdapter {
        val factory = adapters[id.lowercase()] ?: adapters["agy"]!!
        return factory()
    }

    fun supportedAgentIds(): List<String> = listOf("agy", "copilot")
}
