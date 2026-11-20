package com.termux.agent.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class CopilotAdapterTest {

    private val adapter = CopilotAdapter()

    @Test
    fun testLaunchCommandWithAndWithoutWorkspace() {
        assertEquals("copilot", adapter.launchCommand(""))
        assertEquals("copilot", adapter.launchCommand("  \n\r "))
        assertEquals("cd \"/data/project\" && copilot", adapter.launchCommand("/data/project"))
        assertEquals("cd \"/data/My Project\" && copilot", adapter.launchCommand("/data/My Project"))
        assertEquals("cd \"\$HOME\" && copilot", adapter.launchCommand("~"))
        assertEquals("cd \"\$HOME/work\" && copilot", adapter.launchCommand("~/work"))
        assertEquals(
            "cd \"/tmp/a\\\"\\\$\\`\\\\b\" && copilot",
            adapter.launchCommand("/tmp/a\"\$`\\b")
        )
        assertEquals(
            "cd \"/data/projectecho pwned\" && copilot",
            adapter.launchCommand("/data/project\r\necho pwned")
        )
    }

    @Test
    fun testSendPromptWritesTextAndCarriageReturnAndSetsWorking() {
        val out = ByteArrayOutputStream()
        adapter.sendPrompt(out, "explain git rebase")

        assertEquals("explain git rebase\r", out.toString(Charsets.UTF_8.name()))
        assertEquals(AgentState.WORKING, adapter.currentState)
    }

    @Test
    fun testResolvePermissionAllowAndDeny() {
        val allowOut = ByteArrayOutputStream()
        adapter.resolvePermission(allowOut, allow = true)
        assertEquals("y\r", allowOut.toString(Charsets.UTF_8.name()))
        assertEquals(AgentState.WORKING, adapter.currentState)

        val denyOut = ByteArrayOutputStream()
        adapter.resolvePermission(denyOut, allow = false)
        assertEquals("n\r", denyOut.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun testDetectPermissionRequestFromScreen() {
        adapter.sendPrompt(ByteArrayOutputStream(), "list files")

        val screenLines = listOf(
            "Suggested command:",
            "? Confirm command: `ls -la` (y/n)"
        )

        val event = adapter.onScreenUpdated(screenLines, idleMs = 100)
        assertNotNull(event)
        assertTrue(event is AdapterEvent.PermissionRequired)

        val perm = event as AdapterEvent.PermissionRequired
        assertEquals("shell", perm.tool)
        assertEquals("ls -la", perm.command)
        assertEquals(AgentState.WAITING_PERMISSION, adapter.currentState)

        // Duplicate screen check
        val duplicate = adapter.onScreenUpdated(screenLines, idleMs = 200)
        assertNull(duplicate)
    }

    @Test
    fun testDetectTurnCompletedWhenWorkingAndPromptIdle() {
        adapter.sendPrompt(ByteArrayOutputStream(), "help")
        assertEquals(AgentState.WORKING, adapter.currentState)

        val screenLines = listOf(
            "Here is the command output:",
            "All tasks finished successfully.",
            "copilot> "
        )

        val completed = adapter.onScreenUpdated(screenLines, idleMs = 600)
        assertNotNull(completed)
        assertTrue(completed is AdapterEvent.TurnCompleted)
        assertEquals("All tasks finished successfully.", (completed as AdapterEvent.TurnCompleted).summary)
        assertEquals(AgentState.COMPLETED, adapter.currentState)
    }
}
