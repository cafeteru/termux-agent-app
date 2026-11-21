package com.termux.agent

import com.termux.agent.presentation.*
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentIconsTest {

    @Test
    fun brandIconsParseAndBuildNonEmptyVectorGraphs() {
        assertEquals(24.dp, AntigravityIcon.defaultWidth)
        assertEquals(24.dp, AntigravityIcon.defaultHeight)
        assertTrue(AntigravityIcon.root.size > 0)

        assertEquals(24.dp, CopilotIcon.defaultWidth)
        assertEquals(24.dp, CopilotIcon.defaultHeight)
        assertTrue(CopilotIcon.root.size > 0)
    }

    @Test
    fun agentIconForIdSelectsExpectedBrandAndFallsBackToAntigravity() {
        assertSame(AntigravityIcon, agentIconForId("agy"))
        assertSame(CopilotIcon, agentIconForId("copilot"))
        assertSame(CopilotIcon, agentIconForId("COPILOT"))
        assertSame(AntigravityIcon, agentIconForId("unknown"))
    }
}
