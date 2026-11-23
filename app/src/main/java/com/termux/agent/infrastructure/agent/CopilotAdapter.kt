package com.termux.agent.infrastructure.agent

import com.termux.agent.infrastructure.ssh.AgentSessionBrowser
import com.termux.agent.application.port.AgentAdapter
import com.termux.agent.application.port.buildWorkspaceLaunchCommand
import com.termux.agent.domain.model.AdapterEvent
import com.termux.agent.domain.model.AgentState
import com.termux.agent.domain.model.ChatMessage
import java.io.OutputStream

class CopilotAdapter : AgentAdapter {
    override val id: String = "copilot"
    override val name: String = "GitHub Copilot CLI"
    override val displayName: String = "Copilot"
    override val listSessionsCommand: String = AgentSessionBrowser.LIST_COPILOT_SESSIONS_COMMAND
    override val listStateIdsCommand: String = AgentSessionBrowser.LIST_COPILOT_IDS_COMMAND
    override val readTranscriptCommand: String = CopilotTranscriptParser.READ_TRANSCRIPT_BY_ID_COMMAND

    private var _currentState: AgentState = AgentState.IDLE
    override val currentState: AgentState
        get() = _currentState

    private var lastEmittedPermission: String? = null
    private var activePermissionOptions: List<String> = emptyList()
    private var activePermissionSelectedIndex: Int = 0

    override fun launchCommand(workspacePath: String): String =
        buildWorkspaceLaunchCommand(workspacePath, "copilot")

    override fun launchConversationCommand(workspacePath: String, conversationId: String?): String {
        val cleanId = conversationId?.trim()?.takeIf { AgentSessionBrowser.isValidConversationId(it) }
        val binary = if (cleanId != null) "copilot --resume=$cleanId" else "copilot"
        return buildWorkspaceLaunchCommand(workspacePath, binary)
    }

    override fun parseTranscriptLine(jsonLine: String, fallbackStepIndex: Int): ChatMessage? =
        CopilotTranscriptParser.parseLine(jsonLine, fallbackStepIndex)

    override fun sendPrompt(ptyOutput: OutputStream, text: String) {
        ptyOutput.write(text.toByteArray(Charsets.UTF_8))
        ptyOutput.write("\r".toByteArray(Charsets.UTF_8))
        ptyOutput.flush()
        _currentState = AgentState.WORKING
        lastEmittedPermission = null
    }

    override fun resolvePermission(ptyOutput: OutputStream, allow: Boolean, optionIndex: Int?) {
        val payload = if (activePermissionOptions.isNotEmpty()) {
            val target = (optionIndex ?: if (allow) 0 else activePermissionOptions.lastIndex)
                .coerceIn(0, activePermissionOptions.lastIndex)
            val delta = target - activePermissionSelectedIndex
            val arrow = (if (delta >= 0) "\u001B[B" else "\u001B[A").toByteArray(Charsets.UTF_8)
            repeat(kotlin.math.abs(delta)) {
                ptyOutput.write(arrow)
                ptyOutput.flush()
            }
            activePermissionOptions = emptyList()
            activePermissionSelectedIndex = 0
            "\r"
        } else {
            if (allow) "y\r" else "n\r"
        }
        ptyOutput.write(payload.toByteArray(Charsets.UTF_8))
        ptyOutput.flush()
        _currentState = AgentState.WORKING
        lastEmittedPermission = null
    }

    override fun markCompleted() {
        _currentState = AgentState.COMPLETED
        lastEmittedPermission = null
    }

    override fun reset() {
        _currentState = AgentState.IDLE
        lastEmittedPermission = null
        activePermissionOptions = emptyList()
        activePermissionSelectedIndex = 0
    }

    override fun isPromptReady(screenLines: List<String>): Boolean =
        screenLines.takeLast(10).any {
            val line = it.trim()
            isPromptLine(line) || line.contains(":~$ ") || line.endsWith("$ ")
        }

    override fun onScreenUpdated(screenLines: List<String>, idleMs: Long): AdapterEvent? {
        val recentLines = screenLines.takeLast(20).map { it.trim() }.filter { it.isNotEmpty() }
        if (recentLines.isEmpty()) return null

        val screenText = recentLines.joinToString("\n")

        // 1. Check for Copilot permission confirmation prompts
        val permMatch = detectCopilotPermission(recentLines, screenText)
        if (permMatch != null) {
            val permKey = "${permMatch.tool}:${permMatch.command}"
            if (_currentState != AgentState.WAITING_PERMISSION || lastEmittedPermission != permKey) {
                _currentState = AgentState.WAITING_PERMISSION
                lastEmittedPermission = permKey
                return permMatch
            }
            return null
        }

        // 2. Check for turn completion
        if (_currentState == AgentState.WORKING) {
            val promptIndex = recentLines.indexOfLast { isPromptLine(it) }
            val hasPromptNearBottom = promptIndex >= 0 && promptIndex >= recentLines.size - 4

            if (hasPromptNearBottom && idleMs >= 500) {
                _currentState = AgentState.COMPLETED
                val summary = extractSummary(recentLines, promptIndex)
                return AdapterEvent.TurnCompleted(summary)
            }
        } else if (_currentState == AgentState.COMPLETED && idleMs >= 2000) {
            _currentState = AgentState.IDLE
            return AdapterEvent.StateChanged(AgentState.IDLE)
        }

        return null
    }

    private fun detectCopilotPermission(lines: List<String>, fullText: String): AdapterEvent.PermissionRequired? {
        val trustText = fullText.lowercase()
        val asksFolderTrust = (
            trustText.contains("do you trust") &&
                (trustText.contains("folder") || trustText.contains("directory"))
            ) || (
            trustText.contains("trust") &&
                trustText.contains("files") &&
                trustText.contains("future sessions")
        )
        if (asksFolderTrust) {
            val folderLine = lines.firstOrNull {
                it.contains("do you trust", ignoreCase = true)
            } ?: lines.firstOrNull {
                it.contains("trust", ignoreCase = true)
            } ?: lines.firstOrNull {
                it.contains("folder", ignoreCase = true)
            } ?: "Do you trust the files in this folder?"

            val id = "copilot-folder-trust-${System.currentTimeMillis()}"
            activePermissionOptions = listOf(
                "Yes",
                "Yes, remember this folder for future sessions",
                "No, cancel"
            )
            activePermissionSelectedIndex = 0
            return AdapterEvent.PermissionRequired(
                id = id,
                tool = "workspace",
                command = folderLine,
                options = listOf(
                    "Yes",
                    "Yes, remember this folder for future sessions",
                    "No, cancel"
                )
            )
        }

        val copilotConfirmRegex = Regex("""(?i)(?:run this command\?|confirm command|execute\?|\? execute|do you want to run).*?|\[y/n\]|\(y/n\)""")
        if (!copilotConfirmRegex.containsMatchIn(fullText)) {
            return null
        }

        var command = ""
        val backtickRegex = Regex("""`([^`]+)`""")
        val quoteRegex = Regex("""["']([^"']+)["']""")

        val btMatch = backtickRegex.find(fullText)
        if (btMatch != null && btMatch.groupValues.size > 1) {
            command = btMatch.groupValues[1].trim()
        } else {
            val qMatch = quoteRegex.find(fullText)
            if (qMatch != null && qMatch.groupValues.size > 1) {
                command = qMatch.groupValues[1].trim()
            }
        }

        if (command.isEmpty()) {
            command = lines.find { it.contains("?") } ?: "Execute command"
        }

        val id = "copilot-perm-${System.currentTimeMillis()}"
        return AdapterEvent.PermissionRequired(
            id = id,
            tool = "shell",
            command = command,
            options = listOf("Allow", "Deny")
        )
    }

    private fun isPromptLine(line: String): Boolean {
        val trimmed = line.trim()
        val promptSuffixes = listOf("copilot>", "? ", "❯", ">", "$")
        return promptSuffixes.any { trimmed == it || trimmed.startsWith(it) || trimmed.endsWith(" $it") }
    }

    private fun extractSummary(lines: List<String>, promptIndex: Int = lines.lastIndex): String {
        val beforePrompt = if (promptIndex > 0) lines.subList(0, promptIndex) else lines.dropLast(1)
        val nonPromptLines = beforePrompt.filter { line ->
            line.isNotBlank() && !line.all { ch -> ch == '─' || ch == '━' || ch == '-' || ch == '=' }
        }
        return nonPromptLines.lastOrNull() ?: "Task completed"
    }
}
