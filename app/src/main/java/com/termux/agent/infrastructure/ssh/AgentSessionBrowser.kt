package com.termux.agent.infrastructure.ssh

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class AgentSessionSummary(
    val id: String,
    val title: String,
    val workspacePath: String,
    val lastModifiedIso: String,
    val stepCount: Int,
    val isRunning: Boolean
)

data class AgentWorkspaceGroup(
    val workspacePath: String,
    val displayName: String,
    val sessions: List<AgentSessionSummary>
)

sealed interface AgentSessionListingResult {
    data class Success(val sessions: List<AgentSessionSummary>) : AgentSessionListingResult
    data class Error(val message: String) : AgentSessionListingResult
}

object AgentSessionBrowser {
    internal const val MARKER = "__TERMUX_AGENT_SESSIONS__\n"

    // No single quotes allowed so wrapping in `sh -c '...'` works across login shells; ends with `exit 0`.
    internal const val LIST_AGY_SESSIONS_COMMAND =
        "sh -c 'printf \"__TERMUX_AGENT_SESSIONS__\\n\"; " +
            "db=\"\$HOME/.gemini/antigravity-cli/conversation_summaries.db\"; " +
            "used_db=0; " +
            "if [ -r \"\$db\" ] && command -v python3 >/dev/null 2>&1; then " +
            "python3 - \"\$db\" << \"PYEOF\"\n" +
            "import sqlite3, sys\n" +
            "db_path = sys.argv[1]\n" +
            "try:\n" +
            "    con = sqlite3.connect(f\"file:{db_path}?mode=ro\", uri=True, timeout=2.0)\n" +
            "    try:\n" +
            "        rows = con.execute(\n" +
            "            \"SELECT conversation_id, title, workspace_uris, last_modified_time, step_count, status \"\n" +
            "            \"FROM conversation_summaries \"\n" +
            "            \"WHERE step_count > 0 AND parent_conversation_id = \\\"\\\" AND nesting_depth = 0 AND killed = 0 \"\n" +
            "            \"ORDER BY last_modified_time DESC LIMIT 200\"\n" +
            "        ).fetchall()\n" +
            "        for cid, title, ws, mtime, steps, status in rows:\n" +
            "            clean = lambda s: (s or \"\").replace(\"\\t\", \" \").replace(\"\\r\", \" \").replace(\"\\n\", \" \")\n" +
            "            running = \"1\" if status == \"CASCADE_RUN_STATUS_RUNNING\" else \"0\"\n" +
            "            print(f\"{clean(cid)}\\t{clean(title)}\\t{clean(ws)}\\t{clean(mtime)}\\t{int(steps or 0)}\\t{running}\")\n" +
            "    finally:\n" +
            "        con.close()\n" +
            "except Exception:\n" +
            "    sys.exit(1)\n" +
            "PYEOF\n" +
            "if [ \$? -eq 0 ]; then used_db=1; fi; " +
            "fi; " +
            "if [ \"\$used_db\" -eq 0 ]; then " +
            "ls -t \"\$HOME\"/.gemini/antigravity-cli/brain/*/.system_generated/logs/transcript.jsonl 2>/dev/null | head -n 200 | " +
            "while IFS= read -r f; do " +
            "[ -r \"\$f\" ] || continue; " +
            "d=\${f%/.system_generated/logs/transcript.jsonl}; " +
            "id=\${d##*/}; " +
            "mt=\$(stat -c %Y \"\$f\" 2>/dev/null || echo \"\"); " +
            "printf \"%s\\t\\t\\t%s\\t1\\t0\\n\" \"\$id\" \"\$mt\"; " +
            "done; " +
            "fi; " +
            "exit 0'"

    internal const val LIST_AGY_IDS_COMMAND =
        "sh -c 'ls -t \"\$HOME\"/.gemini/antigravity-cli/brain/*/.system_generated/logs/transcript.jsonl 2>/dev/null | " +
            "while IFS= read -r f; do [ -r \"\$f\" ] || continue; d=\${f%/.system_generated/logs/transcript.jsonl}; printf \"%s\\n\" \"\${d##*/}\"; done; exit 0'"

    // No single quotes allowed so wrapping in `sh -c '...'` works across login shells; ends with `exit 0`.
    internal const val LIST_COPILOT_SESSIONS_COMMAND =
        "sh -c 'printf \"__TERMUX_AGENT_SESSIONS__\\n\"; " +
            "db=\"\$HOME/.copilot/session-store.db\"; " +
            "used_db=0; " +
            "if [ -r \"\$db\" ] && command -v python3 >/dev/null 2>&1; then " +
            "python3 - \"\$db\" << \"PYEOF\"\n" +
            "import sqlite3, sys\n" +
            "db_path = sys.argv[1]\n" +
            "try:\n" +
            "    con = sqlite3.connect(f\"file:{db_path}?mode=ro\", uri=True, timeout=2.0)\n" +
            "    try:\n" +
            "        rows = con.execute(\n" +
            "            \"SELECT s.id, COALESCE(s.summary, \\\"\\\"), COALESCE(s.cwd, \\\"\\\"), \"\n" +
            "            \"COALESCE(s.updated_at, s.created_at, \\\"\\\"), COUNT(t.id) AS turn_count \"\n" +
            "            \"FROM sessions s \"\n" +
            "            \"LEFT JOIN turns t ON t.session_id = s.id \"\n" +
            "            \"GROUP BY s.id \"\n" +
            "            \"HAVING turn_count > 0 OR COALESCE(s.summary, \\\"\\\") != \\\"\\\" \"\n" +
            "            \"ORDER BY COALESCE(s.updated_at, s.created_at, \\\"\\\") DESC LIMIT 200\"\n" +
            "        ).fetchall()\n" +
            "        for sid, title, cwd, mtime, turns in rows:\n" +
            "            clean = lambda s: (s or \"\").replace(\"\\t\", \" \").replace(\"\\r\", \" \").replace(\"\\n\", \" \")\n" +
            "            print(f\"{clean(sid)}\\t{clean(title)}\\t{clean(cwd)}\\t{clean(mtime)}\\t{int(turns or 0)}\\t0\")\n" +
            "    finally:\n" +
            "        con.close()\n" +
            "except Exception:\n" +
            "    sys.exit(1)\n" +
            "PYEOF\n" +
            "if [ \$? -eq 0 ]; then used_db=1; fi; " +
            "fi; " +
            "if [ \"\$used_db\" -eq 0 ]; then " +
            "ls -t \"\$HOME\"/.copilot/session-state/*/events.jsonl 2>/dev/null | head -n 200 | " +
            "while IFS= read -r f; do " +
            "[ -r \"\$f\" ] || continue; " +
            "d=\${f%/events.jsonl}; " +
            "id=\${d##*/}; " +
            "wy=\"\$d/workspace.yaml\"; " +
            "title=\"\"; cwd=\"\"; ts=\"\"; " +
            "if [ -r \"\$wy\" ]; then " +
            "while IFS= read -r line; do " +
            "case \"\$line\" in " +
            "\"name: \"*) title=\${line#\"name: \"} ;; " +
            "\"cwd: \"*) cwd=\${line#\"cwd: \"} ;; " +
            "\"updated_at: \"*) ts=\${line#\"updated_at: \"} ;; " +
            "esac; " +
            "done < \"\$wy\"; " +
            "fi; " +
            "if [ -z \"\$ts\" ]; then ts=\$(stat -c %Y \"\$f\" 2>/dev/null || echo \"\"); fi; " +
            "printf \"%s\\t%s\\t%s\\t%s\\t1\\t0\\n\" \"\$id\" \"\$title\" \"\$cwd\" \"\$ts\"; " +
            "done; " +
            "fi; " +
            "exit 0'"

    internal const val LIST_COPILOT_IDS_COMMAND =
        "sh -c 'db=\"\$HOME/.copilot/session-store.db\"; " +
            "if [ -r \"\$db\" ] && command -v python3 >/dev/null 2>&1; then " +
            "python3 - \"\$db\" << \"PYEOF\"\n" +
            "import sqlite3, sys\n" +
            "try:\n" +
            "    con = sqlite3.connect(f\"file:{sys.argv[1]}?mode=ro\", uri=True, timeout=2.0)\n" +
            "    rows = con.execute(\"SELECT id FROM sessions WHERE COALESCE(summary, \\\"\\\") != \\\"\\\" OR id IN (SELECT DISTINCT session_id FROM turns) ORDER BY COALESCE(updated_at, created_at, \\\"\\\") DESC LIMIT 200\").fetchall()\n" +
            "    for row in rows:\n" +
            "        print(row[0])\n" +
            "    con.close()\n" +
            "except Exception:\n" +
            "    sys.exit(1)\n" +
            "PYEOF\n" +
            "if [ \$? -eq 0 ]; then exit 0; fi; " +
            "fi; " +
            "ls -t \"\$HOME\"/.copilot/session-state/*/events.jsonl 2>/dev/null | " +
            "while IFS= read -r f; do [ -r \"\$f\" ] || continue; d=\${f%/events.jsonl}; printf \"%s\\n\" \"\${d##*/}\"; done; exit 0'"

    private val CONVERSATION_ID_REGEX = Regex("""^[A-Za-z0-9_-]{1,128}$""")
    private val FIRST_JSON_STRING_REGEX = Regex("""\"((?:\\.|[^\"\\])*)\"""")
    private val SHORT_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM HH:mm")

    fun isValidConversationId(id: String): Boolean =
        CONVERSATION_ID_REGEX.matches(id.trim())

    fun parseWorkspaceUri(rawWorkspace: String): String {
        val trimmed = rawWorkspace.trim()
        if (trimmed.isEmpty()) return ""

        val firstEntry = if (trimmed.startsWith("[")) {
            val rawJsonStr = FIRST_JSON_STRING_REGEX.find(trimmed)?.groupValues?.getOrNull(1) ?: return ""
            rawJsonStr
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .trim()
        } else {
            trimmed
        }
        if (firstEntry.isEmpty()) return ""

        val resolved = if (firstEntry.startsWith("file://")) {
            val withoutScheme = firstEntry.removePrefix("file://")
            runCatching {
                URLDecoder.decode(withoutScheme.replace("+", "%2B"), StandardCharsets.UTF_8.name())
            }.getOrDefault(withoutScheme)
        } else {
            firstEntry
        }

        return normalizePath(resolved)
    }

    fun parseSessionsOutput(rawOutput: String, exitStatus: Int): AgentSessionListingResult {
        if (exitStatus != 0) {
            return AgentSessionListingResult.Error("Error al listar sesiones (código $exitStatus).")
        }

        val markerIndex = rawOutput.indexOf(MARKER)
        if (markerIndex < 0) {
            return AgentSessionListingResult.Error("Respuesta inesperada al listar sesiones.")
        }

        val payload = rawOutput.substring(markerIndex + MARKER.length)
        val sessions = payload
            .lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val cols = line.split('\t')
                val id = cols.getOrNull(0)?.trim().orEmpty()
                if (!isValidConversationId(id)) return@mapNotNull null

                val rawTitle = cols.getOrNull(1)?.trim().orEmpty()
                val title = rawTitle.ifBlank { "Sesión ${id.take(8)}" }
                val workspacePath = parseWorkspaceUri(cols.getOrNull(2).orEmpty())
                val lastModifiedIso = cols.getOrNull(3)?.trim().orEmpty()
                val stepCount = cols.getOrNull(4)?.trim()?.toIntOrNull() ?: 0
                val isRunning = cols.getOrNull(5)?.trim() == "1"

                AgentSessionSummary(
                    id = id,
                    title = title,
                    workspacePath = workspacePath,
                    lastModifiedIso = lastModifiedIso,
                    stepCount = stepCount,
                    isRunning = isRunning
                )
            }
            .distinctBy { it.id }
            .toList()

        return AgentSessionListingResult.Success(sessions)
    }

    fun parseStateIdsOutput(rawOutput: String): List<String> =
        rawOutput
            .lineSequence()
            .map { it.trim() }
            .filter { isValidConversationId(it) }
            .distinct()
            .toList()

    fun matchesWorkspace(sessionWorkspacePath: String, selectedWorkspacePath: String): Boolean {
        val selected = normalizePath(selectedWorkspacePath)
        if (selected.isEmpty()) return true
        val session = normalizePath(sessionWorkspacePath)
        return session.isNotEmpty() && session == selected
    }

    fun groupSessionsByWorkspace(
        sessions: List<AgentSessionSummary>,
        preferredWorkspacePath: String = ""
    ): List<AgentWorkspaceGroup> {
        if (sessions.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<String, MutableList<AgentSessionSummary>>()
        for (session in sessions) {
            val key = normalizePath(session.workspacePath)
            grouped.getOrPut(key) { mutableListOf() }.add(session)
        }

        val preferredKey = normalizePath(preferredWorkspacePath)
        val orderedKeys = if (preferredKey.isNotEmpty() && grouped.containsKey(preferredKey)) {
            listOf(preferredKey) + grouped.keys.filter { it != preferredKey }
        } else {
            grouped.keys.toList()
        }

        return orderedKeys.map { key ->
            AgentWorkspaceGroup(
                workspacePath = key,
                displayName = if (key.isEmpty()) "Sin directorio" else WorkspaceBrowser.displayName(key),
                sessions = grouped.getValue(key)
            )
        }
    }

    fun defaultExpandedWorkspaceKey(
        groups: List<AgentWorkspaceGroup>,
        activeConversationId: String?,
        selectedWorkspacePath: String
    ): String? {
        if (groups.isEmpty()) return null
        if (!activeConversationId.isNullOrBlank()) {
            groups.firstOrNull { group -> group.sessions.any { it.id == activeConversationId } }
                ?.let { return it.workspacePath }
        }
        val selectedKey = normalizePath(selectedWorkspacePath)
        if (selectedKey.isNotEmpty()) {
            groups.firstOrNull { it.workspacePath == selectedKey }
                ?.let { return it.workspacePath }
        }
        return groups.first().workspacePath
    }

    fun formatShortTimestamp(raw: String, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val instant = parseInstant(trimmed) ?: return ""
        return SHORT_TIME_FORMATTER.withZone(zoneId).format(instant)
    }

    private fun parseInstant(trimmed: String): Instant? {
        if (trimmed.all { it.isDigit() }) {
            val epochSeconds = trimmed.toLongOrNull() ?: return null
            return runCatching { Instant.ofEpochSecond(epochSeconds) }.getOrNull()
        }
        val isoCandidate = if (trimmed.length > 10 && trimmed[10] == ' ') {
            trimmed.substring(0, 10) + "T" + trimmed.substring(11)
        } else {
            trimmed
        }
        val parsed = runCatching { OffsetDateTime.parse(isoCandidate) }.getOrNull()
            ?: return runCatching { Instant.parse(isoCandidate) }.getOrNull()
        if (parsed.year <= 1) return null
        return parsed.toInstant()
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed == "/") "/" else trimmed.trimEnd('/').ifEmpty { "/" }
    }
}
