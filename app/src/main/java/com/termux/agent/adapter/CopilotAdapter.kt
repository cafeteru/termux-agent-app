package com.termux.agent.adapter

import java.io.OutputStream

class CopilotAdapter : AgentAdapter {
    override val id: String = "copilot"
    override val name: String = "GitHub Copilot CLI"

    private var _currentState: AgentState = AgentState.IDLE
    override val currentState: AgentState
        get() = _currentState

    private var lastEmittedPermission: String? = null

    override fun launchCommand(workspacePath: String): String =
        buildWorkspaceLaunchCommand(workspacePath, "copilot")

    override fun sendPrompt(ptyOutput: OutputStream, text: String) {
        ptyOutput.write(text.toByteArray(Charsets.UTF_8))
        ptyOutput.write("\r".toByteArray(Charsets.UTF_8))
        ptyOutput.flush()
        _currentState = AgentState.WORKING
        lastEmittedPermission = null
    }

    override fun resolvePermission(ptyOutput: OutputStream, allow: Boolean, optionIndex: Int?) {
        val payload = if (allow) {
            if (optionIndex != null) "${optionIndex + 1}\r" else "y\r"
        } else {
            "n\r"
        }
        ptyOutput.write(payload.toByteArray(Charsets.UTF_8))
        ptyOutput.flush()
        _currentState = AgentState.WORKING
        lastEmittedPermission = null
    }

    override fun reset() {
        _currentState = AgentState.IDLE
        lastEmittedPermission = null
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
            val lastLine = recentLines.lastOrNull() ?: ""
            val isPrompt = isPromptLine(lastLine)

            if (isPrompt && idleMs >= 500) {
                _currentState = AgentState.COMPLETED
                val summary = extractSummary(recentLines)
                return AdapterEvent.TurnCompleted(summary)
            }
        } else if (_currentState == AgentState.COMPLETED && idleMs >= 2000) {
            _currentState = AgentState.IDLE
            return AdapterEvent.StateChanged(AgentState.IDLE)
        }

        return null
    }

    private fun detectCopilotPermission(lines: List<String>, fullText: String): AdapterEvent.PermissionRequired? {
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

    private fun extractSummary(lines: List<String>): String {
        val nonPromptLines = lines.dropLast(1).filter { it.isNotBlank() }
        return nonPromptLines.lastOrNull() ?: "Task completed"
    }
}
