package com.termux.agent.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

        // Unknown defaults to agy
        val unknown = AdapterRegistry.getAdapter("unknown")
        assertEquals("agy", unknown.id)
    }
}
