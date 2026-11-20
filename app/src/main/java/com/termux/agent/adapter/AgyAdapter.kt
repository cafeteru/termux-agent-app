package com.termux.agent.adapter

import java.io.OutputStream

class AgyAdapter(private val interKeyDelayMs: Long = 35L) : AgentAdapter {
    override val id: String = "agy"
    override val name: String = "Google Antigravity CLI"

    private enum class PermissionInputMode { YES_NO, ARROW_MENU }

    private data class ActivePermissionMenu(
        val key: String,
        val mode: PermissionInputMode,
        val options: List<String>,
        val selectedIndex: Int
    )

    private data class DetectedPermission(
        val event: AdapterEvent.PermissionRequired,
        val mode: PermissionInputMode,
        val selectedIndex: Int,
        val key: String
    )

    private var _currentState: AgentState = AgentState.IDLE
    override val currentState: AgentState
        @Synchronized get() = _currentState

    private var activeMenu: ActivePermissionMenu? = null
    private var lastEmittedPermission: String? = null
    private var resolvedPermissionKey: String? = null

    override fun launchCommand(workspacePath: String): String =
        buildWorkspaceLaunchCommand(workspacePath, "agy")

    @Synchronized
    override fun sendPrompt(ptyOutput: OutputStream, text: String) {
        ptyOutput.write(text.toByteArray(Charsets.UTF_8))
        ptyOutput.write("\r".toByteArray(Charsets.UTF_8))
        ptyOutput.flush()
        _currentState = AgentState.WORKING
        activeMenu = null
        lastEmittedPermission = null
        resolvedPermissionKey = null
    }

    @Synchronized
    override fun resolvePermission(ptyOutput: OutputStream, allow: Boolean, optionIndex: Int?) {
        val menu = activeMenu
        if (menu != null && menu.mode == PermissionInputMode.ARROW_MENU && menu.options.isNotEmpty()) {
            val targetIndex = when {
                optionIndex != null -> optionIndex.coerceIn(0, menu.options.lastIndex)
                allow -> 0
                else -> findDenyOptionIndex(menu.options)
            }
            val delta = targetIndex - menu.selectedIndex
            val arrowBytes = (if (delta >= 0) "\u001B[B" else "\u001B[A").toByteArray(Charsets.UTF_8)
            repeat(kotlin.math.abs(delta)) {
                ptyOutput.write(arrowBytes)
                ptyOutput.flush()
                if (interKeyDelayMs > 0L) {
                    try {
                        Thread.sleep(interKeyDelayMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
            ptyOutput.write("\r".toByteArray(Charsets.UTF_8))
            ptyOutput.flush()
            resolvedPermissionKey = menu.key
            activeMenu = null
            lastEmittedPermission = null
            _currentState = AgentState.WORKING
            return
        }

        val payload = if (allow) {
            if (optionIndex != null) "${optionIndex + 1}\r" else "y\r"
        } else {
            "n\r"
        }
        ptyOutput.write(payload.toByteArray(Charsets.UTF_8))
        ptyOutput.flush()
        resolvedPermissionKey = menu?.key
        activeMenu = null
        _currentState = AgentState.WORKING
        lastEmittedPermission = null
    }

    @Synchronized
    override fun markCompleted() {
        _currentState = AgentState.COMPLETED
        activeMenu = null
        lastEmittedPermission = null
        resolvedPermissionKey = null
    }

    @Synchronized
    override fun reset() {
        _currentState = AgentState.IDLE
        activeMenu = null
        lastEmittedPermission = null
        resolvedPermissionKey = null
    }

    @Synchronized
    override fun onScreenUpdated(screenLines: List<String>, idleMs: Long): AdapterEvent? {
        val rawWindow = screenLines.takeLast(50)
        val recentLines = rawWindow.map { it.trim() }.filter { it.isNotEmpty() }
        if (recentLines.isEmpty()) return null

        // 1. Check for permission dialogs / confirmations
        val detected = detectNumberedMenuPermission(rawWindow)
            ?: detectYesNoPermission(recentLines.takeLast(20))
        if (detected != null) {
            activeMenu = ActivePermissionMenu(
                key = detected.key,
                mode = detected.mode,
                options = detected.event.options,
                selectedIndex = detected.selectedIndex
            )
            if (resolvedPermissionKey == detected.key) {
                return null
            }
            resolvedPermissionKey = null
            if (_currentState != AgentState.WAITING_PERMISSION || lastEmittedPermission != detected.key) {
                _currentState = AgentState.WAITING_PERMISSION
                lastEmittedPermission = detected.key
                return detected.event
            }
            return null
        }

        activeMenu = null
        lastEmittedPermission = null
        resolvedPermissionKey = null
        if (_currentState == AgentState.WAITING_PERMISSION) {
            _currentState = AgentState.WORKING
            return AdapterEvent.StateChanged(AgentState.WORKING)
        }

        // 2. Check for turn completion when working
        val completionWindow = recentLines.takeLast(20)
        if (_currentState == AgentState.WORKING) {
            val promptIndex = completionWindow.indexOfLast { isPromptLine(it) }
            val hasPromptNearBottom = promptIndex >= 0 && promptIndex >= completionWindow.size - 4

            if (hasPromptNearBottom && idleMs >= 500) {
                _currentState = AgentState.COMPLETED
                val summary = extractSummary(completionWindow, promptIndex)
                return AdapterEvent.TurnCompleted(summary)
            }
        } else if (_currentState == AgentState.COMPLETED && idleMs >= 2000) {
            _currentState = AgentState.IDLE
            return AdapterEvent.StateChanged(AgentState.IDLE)
        }

        return null
    }

    private fun detectNumberedMenuPermission(rawWindow: List<String>): DetectedPermission? {
        val firstOptIdx = rawWindow.indices.lastOrNull { idx ->
            val match = OPTION_START_REGEX.matchEntire(rawWindow[idx].trim())
            match != null && match.groupValues[2].toIntOrNull() == 1
        } ?: return null

        val options = mutableListOf<StringBuilder>()
        var hasCursorMarker = false
        var selectedIndex = 0
        var lastOptionLineIdx = firstOptIdx

        var i = firstOptIdx
        while (i < rawWindow.size) {
            val trimmed = rawWindow[i].trim()
            if (trimmed.isEmpty()) {
                if (options.size >= 2) break
                val nextNonEmpty = ((i + 1) until rawWindow.size)
                    .map { rawWindow[it].trim() }
                    .firstOrNull { it.isNotEmpty() }
                val nextMatch = nextNonEmpty?.let { OPTION_START_REGEX.matchEntire(it) }
                if (nextMatch != null && nextMatch.groupValues[2].toIntOrNull() == 2) {
                    i++
                    continue
                }
                break
            }
            if (isDividerLine(trimmed) || TUI_HINT_REGEX.containsMatchIn(trimmed)) {
                break
            }
            val optMatch = OPTION_START_REGEX.matchEntire(trimmed)
            if (optMatch != null) {
                val num = optMatch.groupValues[2].toIntOrNull()
                if (num == options.size + 1) {
                    if (optMatch.groupValues[1].isNotEmpty()) {
                        hasCursorMarker = true
                        selectedIndex = options.size
                    }
                    options.add(StringBuilder(optMatch.groupValues[3].trim()))
                    lastOptionLineIdx = i
                    i++
                    continue
                }
                break
            }
            if (options.isNotEmpty()) {
                options.last().append(' ').append(trimmed)
                lastOptionLineIdx = i
            }
            i++
        }

        if (options.size !in 2..8) return null

        val trailingNonEmpty = ((lastOptionLineIdx + 1) until rawWindow.size)
            .count { rawWindow[it].trim().isNotEmpty() }
        if (trailingNonEmpty > 6) return null

        val headerStart = maxOf(0, firstOptIdx - 25)
        val headerLines = rawWindow.subList(headerStart, firstOptIdx).map { it.trim() }
        val nonEmptyHeader = headerLines.filter { it.isNotEmpty() }
        val headerText = nonEmptyHeader.joinToString("\n")
        val parsedOptions = options.map { it.toString().trim() }

        val hasPermissionHeader = PERMISSION_HEADER_REGEX.containsMatchIn(headerText)
        val hasAllowChoice = parsedOptions.any { ALLOW_OPTION_REGEX.containsMatchIn(it) }
        val hasDenyChoice = parsedOptions.any { DENY_OPTION_REGEX.containsMatchIn(it) }
        if (!hasPermissionHeader && !(hasCursorMarker && hasAllowChoice && hasDenyChoice)) {
            return null
        }

        val reqIdx = headerLines.indexOfLast { it.contains("requesting permission", ignoreCase = true) }
        val questionIdx = headerLines.indexOfLast { it.endsWith("?") }

        val explicitTool = nonEmptyHeader.asReversed().firstNotNullOfOrNull { line ->
            TOOL_REGEX.find(line)?.groupValues?.getOrNull(1)
        }
        val boxTitleTool = if (reqIdx >= 0) {
            ((reqIdx - 1) downTo 0)
                .map { headerLines[it] }
                .firstOrNull { it.isNotEmpty() && !isDividerLine(it) }
                ?.takeIf { it.length <= 32 && !it.contains('.') && !it.contains(':') }
        } else {
            null
        }
        val tool = explicitTool ?: boxTitleTool ?: "run_command"

        val command = if (reqIdx >= 0) {
            val endIdx = if (questionIdx > reqIdx) questionIdx else headerLines.size
            val block = headerLines.subList(reqIdx + 1, endIdx).filter { it.isNotEmpty() }
            if (block.isNotEmpty()) {
                block.joinToString("\n")
            } else {
                extractFallbackCommand(nonEmptyHeader, headerText)
            }
        } else {
            extractFallbackCommand(nonEmptyHeader, headerText)
        }

        val permKey = "$tool:$command:${parsedOptions.joinToString("|")}"
        return DetectedPermission(
            event = AdapterEvent.PermissionRequired(
                id = "agy-perm-${System.currentTimeMillis()}",
                tool = tool,
                command = command,
                options = parsedOptions
            ),
            mode = PermissionInputMode.ARROW_MENU,
            selectedIndex = selectedIndex,
            key = permKey
        )
    }

    private fun detectYesNoPermission(lines: List<String>): DetectedPermission? {
        val fullText = lines.joinToString("\n")
        if (!YES_NO_PERMISSION_REGEX.containsMatchIn(fullText)) {
            return null
        }

        var tool = "run_command"
        var command = ""

        for (line in lines) {
            val tMatch = TOOL_REGEX.find(line)
            if (tMatch != null && tMatch.groupValues.size > 1) {
                tool = tMatch.groupValues[1]
            }

            val cMatch = COMMAND_REGEX.find(line)
            if (cMatch != null && cMatch.groupValues.size > 1) {
                command = cMatch.groupValues[1].trim()
            }
        }

        if (command.isEmpty()) {
            command = extractFallbackCommand(lines, fullText)
        }

        val options = listOf("Allow", "Deny")
        return DetectedPermission(
            event = AdapterEvent.PermissionRequired(
                id = "agy-perm-${System.currentTimeMillis()}",
                tool = tool,
                command = command,
                options = options
            ),
            mode = PermissionInputMode.YES_NO,
            selectedIndex = 0,
            key = "$tool:$command"
        )
    }

    private fun extractFallbackCommand(lines: List<String>, fullText: String): String {
        COMMAND_REGEX.find(fullText)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        BACKTICK_REGEX.find(fullText)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        QUOTE_REGEX.find(fullText)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        return lines.find { it.contains("?") } ?: "Confirm tool execution"
    }

    private fun findDenyOptionIndex(options: List<String>): Int {
        val idx = options.indexOfLast { DENY_OPTION_REGEX.containsMatchIn(it.trim()) }
        return if (idx >= 0) idx else options.lastIndex
    }

    private fun isPromptLine(line: String): Boolean {
        val trimmed = line.trim()
        if (OPTION_START_REGEX.matches(trimmed)) return false
        val promptSuffixes = listOf("❯", ">", "$", "agy>", "#")
        return promptSuffixes.any { trimmed == it || trimmed.endsWith(" $it") || trimmed.startsWith("$it ") }
    }

    private fun isDividerLine(line: String): Boolean =
        line.length >= 3 && line.all { ch -> ch == '─' || ch == '━' || ch == '-' || ch == '═' || ch == '=' }

    private fun extractSummary(lines: List<String>, promptIndex: Int = lines.lastIndex): String {
        val beforePrompt = if (promptIndex > 0) lines.subList(0, promptIndex) else lines.dropLast(1)
        val nonPromptLines = beforePrompt.filter { line ->
            line.isNotBlank() && !line.all { ch -> ch == '─' || ch == '━' || ch == '-' || ch == '=' }
        }
        return nonPromptLines.lastOrNull() ?: "Turn completed"
    }

    private companion object {
        val OPTION_START_REGEX = Regex("""^(?:([>❯›▸•*])\s*)?(\d+)\.\s+(.+)$""")
        val TUI_HINT_REGEX = Regex("""(?i)^(?:\?\s*for shortcuts|use\s+↑|↑/↓|enter\s+to|esc\s+to|press\s+\d|tab\s+to|ctrl\+)""")
        val PERMISSION_HEADER_REGEX = Regex("""(?i)(?:requesting permission|run this command\?|allow .*\?|approve .*\?|execute .*\?|confirm .*\?|do you want to|proceed\?|apply this edit\?|write this file\?)""")
        val ALLOW_OPTION_REGEX = Regex("""(?i)^(?:yes|allow|approve|run|accept|confirm)\b""")
        val DENY_OPTION_REGEX = Regex("""(?i)^(?:no\b|cancel|deny|reject|denegar|cancelar)""")
        val YES_NO_PERMISSION_REGEX = Regex("""(?i)(?:allow|approve|permission|run command|execute command|tool).*?\?|\[y/n\]|\(y/n\)|\[Y/n\]|\[y/N\]""")
        val TOOL_REGEX = Regex("""(?i)(?:tool|action):\s*([a-zA-Z0-9_\-]+)""")
        val COMMAND_REGEX = Regex("""(?i)(?:command|cmd):\s*(.+)""")
        val BACKTICK_REGEX = Regex("""`([^`]+)`""")
        val QUOTE_REGEX = Regex("""["']([^"']+)["']""")
    }
}
