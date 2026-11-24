package com.termux.agent.infrastructure.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdapterRegistryTest {

    @Test
    fun testGetSupportedAdapters() {
        val supported = AdapterRegistry.supportedAgentIds()
        assertTrue(supported.contains("agy"))
        assertTrue(supported.contains("copilot"))

        val agy = AdapterRegistry.getAdapter("agy")
        assertEquals("agy", agy.id)

        val copilot = AdapterRegistry.getAdapter("copilot")
        assertEquals("copilot", copilot.id)

        assertEquals(listOf(agy.id, copilot.id), AdapterRegistry.availableAdapters().map { it.id })
        assertTrue(agy.supportsSessions)
        assertTrue(copilot.supportsSessions)
        assertEquals(
            "user",
            agy.parseTranscriptLine("""{"type":"USER_INPUT","content":"hello","step_index":0}""")?.sender
        )
        assertEquals(
            "user",
            copilot.parseTranscriptLine("""{"type":"user.message","content":"hello"}""", 4)?.sender
        )
        assertEquals(agy.id, AdapterRegistry.getDefaultAdapter().id)
        assertEquals(null, AdapterRegistry.findAdapter("unknown"))

        // Unknown defaults to agy
        val unknown = AdapterRegistry.getAdapter("unknown")
        assertEquals("agy", unknown.id)
    }
}
