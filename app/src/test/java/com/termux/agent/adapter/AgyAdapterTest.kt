package com.termux.agent.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class AgyAdapterTest {

    private val adapter = AgyAdapter()

    @Test
    fun testLaunchCommandWithAndWithoutWorkspace() {
        assertEquals("agy", adapter.launchCommand(""))
        assertEquals("cd \"/sdcard/project\" && agy", adapter.launchCommand("/sdcard/project"))
    }

    @Test
    fun testSendPromptWritesTextAndCarriageReturnAndSetsWorking() {
        val out = ByteArrayOutputStream()
        adapter.sendPrompt(out, "explain this function")

        assertEquals("explain this function\r", out.toString(Charsets.UTF_8.name()))
        assertEquals(AgentState.WORKING, adapter.currentState)
    }

    @Test
    fun testResolvePermissionAllowAndDeny() {
        val allowOut = ByteArrayOutputStream()
        adapter.resolvePermission(allowOut, allow = true)
        assertEquals("y\r", allowOut.toString(Charsets.UTF_8.name()))
        assertEquals(AgentState.WORKING, adapter.currentState)

        val customOptionOut = ByteArrayOutputStream()
        adapter.resolvePermission(customOptionOut, allow = true, optionIndex = 1)
        assertEquals("2\r", customOptionOut.toString(Charsets.UTF_8.name()))

        val denyOut = ByteArrayOutputStream()
        adapter.resolvePermission(denyOut, allow = false)
        assertEquals("n\r", denyOut.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun testDetectPermissionRequestFromScreen() {
        adapter.sendPrompt(ByteArrayOutputStream(), "run test")

        val screenLines = listOf(
            "Agent is about to run a command:",
            "Tool: run_command",
            "Command: git status",
            "Do you want to allow this execution? [y/n]"
        )

        val event = adapter.onScreenUpdated(screenLines, idleMs = 100)
        assertNotNull(event)
        assertTrue(event is AdapterEvent.PermissionRequired)

        val perm = event as AdapterEvent.PermissionRequired
        assertEquals("run_command", perm.tool)
        assertEquals("git status", perm.command)
        assertEquals(AgentState.WAITING_PERMISSION, adapter.currentState)

        // Calling again with same screen should not emit duplicate event
        val duplicate = adapter.onScreenUpdated(screenLines, idleMs = 200)
        assertNull(duplicate)
    }

    @Test
    fun testDetectTurnCompletedWhenWorkingAndPromptIdle() {
        adapter.sendPrompt(ByteArrayOutputStream(), "summarize git log")
        assertEquals(AgentState.WORKING, adapter.currentState)

        val screenLines = listOf(
            "Fetching commit history...",
            "Summary: 3 commits added, branch is clean.",
            "agy> "
        )

        // idleMs under threshold -> no completion yet
        val notYet = adapter.onScreenUpdated(screenLines, idleMs = 300)
        assertNull(notYet)

        // idleMs >= 500ms -> TurnCompleted emitted
        val completed = adapter.onScreenUpdated(screenLines, idleMs = 600)
        assertNotNull(completed)
        assertTrue(completed is AdapterEvent.TurnCompleted)
        assertEquals("Summary: 3 commits added, branch is clean.", (completed as AdapterEvent.TurnCompleted).summary)
        assertEquals(AgentState.COMPLETED, adapter.currentState)

        // idleMs >= 2000ms -> returns to IDLE
        val idleEvent = adapter.onScreenUpdated(screenLines, idleMs = 2100)
        assertNotNull(idleEvent)
        assertTrue(idleEvent is AdapterEvent.StateChanged)
        assertEquals(AgentState.IDLE, (idleEvent as AdapterEvent.StateChanged).state)
        assertEquals(AgentState.IDLE, adapter.currentState)
    }
}
