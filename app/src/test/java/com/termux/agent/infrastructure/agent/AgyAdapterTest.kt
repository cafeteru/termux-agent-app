package com.termux.agent.infrastructure.agent

import com.termux.agent.domain.model.AdapterEvent
import com.termux.agent.domain.model.AgentState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class AgyAdapterTest {

    private val adapter = AgyAdapter()

    @Test
    fun testLaunchCommandWithAndWithoutWorkspace() {
        assertEquals("agy", adapter.launchCommand(""))
        assertEquals("agy", adapter.launchCommand("   \r\n  "))
        assertEquals("cd \"/sdcard/project\" && agy", adapter.launchCommand("/sdcard/project"))
        assertEquals("cd \"/sdcard/My Project\" && agy", adapter.launchCommand("/sdcard/My Project"))
        assertEquals("cd \"\$HOME\" && agy", adapter.launchCommand("~"))
        assertEquals("cd \"\$HOME/my-repo\" && agy", adapter.launchCommand("~/my-repo"))
        assertEquals(
            "cd \"/tmp/dir\\\"\\\$\\`\\\\name\" && agy",
            adapter.launchCommand("/tmp/dir\"\$`\\name")
        )
        assertEquals(
            "cd \"/sdcard/injected; rm -rf /\" && agy",
            adapter.launchCommand("/sdcard/injected\r\n; rm -rf /")
        )
    }

    @Test
    fun testLaunchConversationCommandWithValidBlankAndInvalidIds() {
        assertEquals(
            "agy --conversation c063592d-5610-4f16-8041-4ffd9a2447ff",
            adapter.launchConversationCommand("", "c063592d-5610-4f16-8041-4ffd9a2447ff")
        )
        assertEquals(
            "cd \"/sdcard/project\" && agy --conversation sess_1",
            adapter.launchConversationCommand("/sdcard/project", " sess_1 ")
        )
        assertEquals("agy", adapter.launchConversationCommand("", null))
        assertEquals("agy", adapter.launchConversationCommand("", "   "))
        assertEquals(
            "cd \"/sdcard/project\" && agy",
            adapter.launchConversationCommand("/sdcard/project", "../bad;rm -rf /")
        )
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

    @Test
    fun testDetectTurnCompletedWhenPromptHasStatusFooterBelowIt() {
        adapter.sendPrompt(ByteArrayOutputStream(), "hola")
        assertEquals(AgentState.WORKING, adapter.currentState)

        val screenLines = listOf(
            "Sí, puedo escribir y responder en el chat sin problemas.",
            "────────────────────────────────────────",
            "❯ ",
            "────────────────────────────────────────",
            "? for shortcuts"
        )

        val completed = adapter.onScreenUpdated(screenLines, idleMs = 600)
        assertNotNull(completed)
        assertTrue(completed is AdapterEvent.TurnCompleted)
        assertEquals(
            "Sí, puedo escribir y responder en el chat sin problemas.",
            (completed as AdapterEvent.TurnCompleted).summary
        )
        assertEquals(AgentState.COMPLETED, adapter.currentState)
    }

    @Test
    fun testDetectsAgyFourOptionMenuWithWrappedLinesAndIgnoresTurnCompletion() {
        val testAdapter = AgyAdapter(interKeyDelayMs = 0L)
        testAdapter.sendPrompt(ByteArrayOutputStream(), "check git")

        val screenLines = agyFourOptionScreen()
        val event = testAdapter.onScreenUpdated(screenLines, idleMs = 1000)
        assertTrue(event is AdapterEvent.PermissionRequired)

        val perm = event as AdapterEvent.PermissionRequired
        assertEquals("Command", perm.tool)
        assertTrue(perm.command.contains("git -C"))
        assertTrue(perm.command.contains("branch -a -vv && git -C..."))
        assertEquals(4, perm.options.size)
        assertEquals("Yes, run command", perm.options[0])
        assertEquals(
            "Yes, and always allow in this conversation for commands that start with 'git -C /data/data/com.termux/files/home/balearia-ia status -sb'",
            perm.options[1]
        )
        assertEquals(
            "Yes, and always allow for commands that start with 'git -C /data/data/com.termux/files/home/balearia-ia status -sb' (Persist to settings.json)",
            perm.options[2]
        )
        assertEquals("No, cancel", perm.options[3])
        assertEquals(AgentState.WAITING_PERMISSION, testAdapter.currentState)

        // Duplicate screen update does not re-emit
        assertNull(testAdapter.onScreenUpdated(screenLines, idleMs = 1500))

        // Single cursor-prefixed numbered line is not treated as idle shell prompt
        val workingAdapter = AgyAdapter(interKeyDelayMs = 0L)
        workingAdapter.sendPrompt(ByteArrayOutputStream(), "list")
        assertNull(workingAdapter.onScreenUpdated(listOf("> 1. Some list item"), idleMs = 1000))
        assertEquals(AgentState.WORKING, workingAdapter.currentState)
    }

    @Test
    fun testResolvePermissionSendsExactArrowKeysAndEnterForMenuOptions() {
        val screenLines = agyFourOptionScreen()

        val cases = listOf(
            Triple(true, 0, "\r"),
            Triple(true, 1, "\u001B[B\r"),
            Triple(true, 2, "\u001B[B\u001B[B\r"),
            Triple(false, 3, "\u001B[B\u001B[B\u001B[B\r"),
            Triple(false, null, "\u001B[B\u001B[B\u001B[B\r")
        )

        for ((allow, optionIdx, expectedSeq) in cases) {
            val testAdapter = AgyAdapter(interKeyDelayMs = 0L)
            testAdapter.onScreenUpdated(screenLines, idleMs = 100)
            val out = ByteArrayOutputStream()
            testAdapter.resolvePermission(out, allow = allow, optionIndex = optionIdx)
            assertEquals(expectedSeq, out.toString(Charsets.UTF_8.name()))
            assertEquals(AgentState.WORKING, testAdapter.currentState)
        }

        // When cursor is already on option 3 (index 2), picking option 1 (index 0) sends two Up arrows + CR
        val movedCursorScreen = listOf(
            "Command",
            "Requesting permission for:",
            "ls",
            "Run this command?",
            "  1. Yes, run command",
            "  2. Yes, and always allow in this conversation",
            "> 3. Yes, and always allow",
            "  4. No, cancel"
        )
        val upAdapter = AgyAdapter(interKeyDelayMs = 0L)
        upAdapter.onScreenUpdated(movedCursorScreen, idleMs = 100)
        val upOut = ByteArrayOutputStream()
        upAdapter.resolvePermission(upOut, allow = true, optionIndex = 0)
        assertEquals("\u001B[A\u001B[A\r", upOut.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun testStaleScreenSuppressionAndExternalDismissal() {
        val testAdapter = AgyAdapter(interKeyDelayMs = 0L)
        val screenLines = agyFourOptionScreen()

        assertTrue(testAdapter.onScreenUpdated(screenLines, idleMs = 100) is AdapterEvent.PermissionRequired)
        testAdapter.resolvePermission(ByteArrayOutputStream(), allow = true, optionIndex = 1)
        assertEquals(AgentState.WORKING, testAdapter.currentState)

        // Stale frame right after resolving does not re-trigger WAITING_PERMISSION
        assertNull(testAdapter.onScreenUpdated(screenLines, idleMs = 200))
        assertEquals(AgentState.WORKING, testAdapter.currentState)

        // Screen clears, resetting suppression
        assertNull(testAdapter.onScreenUpdated(listOf("Running command..."), idleMs = 100))

        // Next permission request can emit again
        assertTrue(testAdapter.onScreenUpdated(screenLines, idleMs = 100) is AdapterEvent.PermissionRequired)
        assertEquals(AgentState.WAITING_PERMISSION, testAdapter.currentState)

        // External dismissal from Terminal emits StateChanged(WORKING)
        val dismissedEvent = testAdapter.onScreenUpdated(listOf("Running command..."), idleMs = 100)
        assertEquals(AdapterEvent.StateChanged(AgentState.WORKING), dismissedEvent)
        assertEquals(AgentState.WORKING, testAdapter.currentState)
    }

    @Test
    fun testDoesNotMisdetectPlainNumberedListAsPermission() {
        val testAdapter = AgyAdapter(interKeyDelayMs = 0L)
        testAdapter.sendPrompt(ByteArrayOutputStream(), "steps")

        val listScreen = listOf(
            "Pasos recomendados:",
            "1. Paso uno",
            "2. Paso dos",
            "3. Paso tres"
        )
        assertNull(testAdapter.onScreenUpdated(listScreen, idleMs = 500))
        assertEquals(AgentState.WORKING, testAdapter.currentState)
    }

    private fun agyFourOptionScreen(): List<String> = listOf(
        "ought for 8s, 716 tokens",
        "",
        "  You are already inside agy in",
        "  /data/data/com.termux/files/home/balearia-",
        "  ia. That looks like a pasted launch command",
        "Command",
        "────────────────────────────────────────────────",
        "",
        "Requesting permission for:",
        "   git -C",
        "/data/data/com.termux/files/home/balearia-",
        "ia",
        "status -sb && git -C",
        "/data/data/com.termux/files/home/balearia-",
        "ia",
        "branch -a -vv && git -C...",
        "",
        "Run this command?",
        "> 1. Yes, run command",
        "  2. Yes, and always allow in this conversation",
        "for commands that start with 'git -C",
        "/data/data/com.termux/files/home/balearia-ia",
        "status -sb'",
        "  3. Yes, and always allow for commands that",
        "start with 'git -C",
        "/data/data/com.termux/files/home/balearia-ia",
        "status -sb' (Persist to settings.json)",
        "  4. No, cancel"
    )
}
