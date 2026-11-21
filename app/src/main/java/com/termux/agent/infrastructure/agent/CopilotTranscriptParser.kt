package com.termux.agent.infrastructure.agent

import com.termux.agent.domain.model.ChatMessage

object CopilotTranscriptParser {

    // No single quotes allowed so wrapping in `sh -c '...'` works across login shells.
    internal const val READ_TRANSCRIPT_BY_ID_COMMAND =
        "sh -c 'IFS= read -r id || exit 0; " +
            "case \"\$id\" in \"\"|*/*|*..*|*\" \"*) exit 1 ;; esac; " +
            "f=\"\$HOME/.copilot/session-state/\$id/events.jsonl\"; " +
            "db=\"\$HOME/.copilot/session-store.db\"; " +
            "if command -v python3 >/dev/null 2>&1; then " +
            "python3 - \"\$f\" \"\$db\" \"\$id\" << \"PYEOF\"\n" +
            "import json, os, sqlite3, sys\n" +
            "events_path, db_path, sid = sys.argv[1], sys.argv[2], sys.argv[3]\n" +
            "entries = []\n" +
            "if os.path.isfile(events_path) and os.access(events_path, os.R_OK):\n" +
            "    try:\n" +
            "        idx = 0\n" +
            "        with open(events_path, \"r\", encoding=\"utf-8\", errors=\"replace\") as fh:\n" +
            "            for line in fh:\n" +
            "                line = line.strip()\n" +
            "                if not line:\n" +
            "                    continue\n" +
            "                try:\n" +
            "                    d = json.loads(line)\n" +
            "                except Exception:\n" +
            "                    continue\n" +
            "                tp = d.get(\"type\", \"\")\n" +
            "                if tp not in (\"user.message\", \"assistant.message\"):\n" +
            "                    continue\n" +
            "                data = d.get(\"data\") or {}\n" +
            "                ts = d.get(\"timestamp\", \"\") or \"\"\n" +
            "                content = data.get(\"content\", \"\") or \"\"\n" +
            "                tools = []\n" +
            "                if tp == \"assistant.message\":\n" +
            "                    for tr in data.get(\"toolRequests\") or []:\n" +
            "                        if not isinstance(tr, dict):\n" +
            "                            continue\n" +
            "                        name = tr.get(\"name\", \"\") or \"\"\n" +
            "                        if not name:\n" +
            "                            continue\n" +
            "                        args = tr.get(\"arguments\") or {}\n" +
            "                        detail = \"\"\n" +
            "                        if isinstance(args, dict):\n" +
            "                            detail = args.get(\"command\") or args.get(\"path\") or \"\"\n" +
            "                        if isinstance(detail, str) and detail.strip():\n" +
            "                            short = detail.strip().replace(\"\\n\", \" \")[:160]\n" +
            "                            tools.append(f\"{name}: {short}\")\n" +
            "                        else:\n" +
            "                            tools.append(name)\n" +
            "                    if not str(content).strip() and not tools:\n" +
            "                        continue\n" +
            "                elif not str(content).strip():\n" +
            "                    continue\n" +
            "                entries.append({\n" +
            "                    \"type\": tp,\n" +
            "                    \"step_index\": idx,\n" +
            "                    \"timestamp\": ts,\n" +
            "                    \"content\": str(content),\n" +
            "                    \"tools\": tools,\n" +
            "                })\n" +
            "                idx += 1\n" +
            "    except Exception:\n" +
            "        entries = []\n" +
            "if not entries and os.path.isfile(db_path) and os.access(db_path, os.R_OK):\n" +
            "    try:\n" +
            "        con = sqlite3.connect(f\"file:{db_path}?mode=ro\", uri=True, timeout=2.0)\n" +
            "        try:\n" +
            "            rows = con.execute(\n" +
            "                \"SELECT turn_index, user_message, assistant_response, timestamp \"\n" +
            "                \"FROM turns WHERE session_id = ? ORDER BY turn_index ASC\",\n" +
            "                (sid,),\n" +
            "            ).fetchall()\n" +
            "            for turn_idx, umsg, amsg, ts in rows:\n" +
            "                tbase = int(turn_idx or 0) * 2\n" +
            "                ts_str = ts or \"\"\n" +
            "                if umsg and str(umsg).strip():\n" +
            "                    entries.append({\"type\": \"user.message\", \"step_index\": tbase, \"timestamp\": ts_str, \"content\": str(umsg), \"tools\": []})\n" +
            "                if amsg and str(amsg).strip():\n" +
            "                    entries.append({\"type\": \"assistant.message\", \"step_index\": tbase + 1, \"timestamp\": ts_str, \"content\": str(amsg), \"tools\": []})\n" +
            "        finally:\n" +
            "            con.close()\n" +
            "    except Exception:\n" +
            "        pass\n" +
            "for item in entries[-200:]:\n" +
            "    print(json.dumps(item, ensure_ascii=False))\n" +
            "PYEOF\n" +
            "elif [ -r \"\$f\" ]; then " +
            "grep -E \"\\\"type\\\":\\\"(user|assistant)\\\\.message\\\"\" \"\$f\" | tail -n 120 | tail -c 450000; " +
            "fi; " +
            "exit 0'"

    private val TYPE_REGEX = Regex("""(?:^|[,{\s])\"type\"\s*:\s*\"((?:\\.|[^\"\\])*)\"""")
    private val TIMESTAMP_REGEX = Regex("""(?:^|[,{\s])\"timestamp\"\s*:\s*\"((?:\\.|[^\"\\])*)\"""")
    private val CONTENT_REGEX = Regex("""(?:^|[,{\s])\"content\"\s*:\s*\"((?:\\.|[^\"\\])*)\"""")
    private val STEP_INDEX_REGEX = Regex("""(?:^|[,{\s])\"step_index\"\s*:\s*(-?\d+)""")
    private val TOOLS_ARRAY_REGEX = Regex("""(?:^|[,{\s])\"tools\"\s*:\s*\[(.*?)\]""")
    private val JSON_STRING_LITERAL_REGEX = Regex("""\"((?:\\.|[^\"\\])*)\"""")
    private val TOOL_REQUEST_CALL_REGEX =
        Regex("""\{\s*(?:\"toolCallId\"\s*:\s*\"[^\"]*\"\s*,\s*)?\"name\"\s*:\s*\"((?:\\.|[^\"\\])*)\"(?:.*?\"(?:command|path)\"\s*:\s*\"((?:\\.|[^\"\\])*)\")?""")

    fun parseLine(jsonLine: String, fallbackStepIndex: Int = -1): ChatMessage? {
        val trimmed = jsonLine.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{") || !trimmed.endsWith("}")) return null

        val type = extractJsonString(trimmed, TYPE_REGEX) ?: return null
        if (type != "user.message" && type != "assistant.message") return null

        val extractedStep = extractJsonInt(trimmed, STEP_INDEX_REGEX)
        val stepIndex = if (extractedStep >= 0) extractedStep else fallbackStepIndex
        val timestamp = extractJsonString(trimmed, TIMESTAMP_REGEX).orEmpty()
        val rawContent = extractJsonString(trimmed, CONTENT_REGEX).orEmpty()

        return when (type) {
            "user.message" -> {
                val cleanPrompt = rawContent.trim()
                if (cleanPrompt.isEmpty()) return null
                ChatMessage(
                    stepIndex = stepIndex,
                    sender = "user",
                    content = cleanPrompt,
                    timestamp = timestamp
                )
            }
            "assistant.message" -> {
                val toolCalls = extractTools(trimmed)
                val messageContent = when {
                    rawContent.isNotBlank() -> rawContent
                    toolCalls.isNotEmpty() -> "Executing ${toolCalls.joinToString(", ")}"
                    else -> ""
                }
                if (messageContent.isBlank() && toolCalls.isEmpty()) return null

                ChatMessage(
                    stepIndex = stepIndex,
                    sender = "agent",
                    content = messageContent,
                    timestamp = timestamp,
                    toolCalls = toolCalls
                )
            }
            else -> null
        }
    }

    private fun extractJsonString(json: String, pattern: Regex): String? {
        val match = pattern.find(json) ?: return null
        return unescapeJson(match.groupValues[1])
    }

    private fun extractJsonInt(json: String, pattern: Regex): Int =
        pattern.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1

    private fun extractTools(json: String): List<String> {
        val compactMatch = TOOLS_ARRAY_REGEX.find(json)
        if (compactMatch != null) {
            return JSON_STRING_LITERAL_REGEX.findAll(compactMatch.groupValues[1])
                .map { unescapeJson(it.groupValues[1]).trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }
        val requestCalls = mutableListOf<String>()
        for (match in TOOL_REQUEST_CALL_REGEX.findAll(json)) {
            val name = unescapeJson(match.groupValues[1]).trim()
            if (name.isEmpty()) continue
            val detail = unescapeJson(match.groupValues.getOrElse(2) { "" }).trim()
            if (detail.isNotEmpty()) {
                requestCalls.add("$name: $detail")
            } else {
                requestCalls.add(name)
            }
        }
        return requestCalls
    }

    private fun unescapeJson(str: String): String {
        val sb = StringBuilder(str.length)
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (c == '\\' && i + 1 < str.length) {
                when (val next = str[i + 1]) {
                    'n' -> {
                        sb.append('\n')
                        i += 2
                    }
                    'r' -> {
                        sb.append('\r')
                        i += 2
                    }
                    't' -> {
                        sb.append('\t')
                        i += 2
                    }
                    'b' -> {
                        sb.append('\b')
                        i += 2
                    }
                    'f' -> {
                        sb.append('\u000C')
                        i += 2
                    }
                    '"' -> {
                        sb.append('"')
                        i += 2
                    }
                    '\\' -> {
                        sb.append('\\')
                        i += 2
                    }
                    '/' -> {
                        sb.append('/')
                        i += 2
                    }
                    'u' -> {
                        if (i + 5 < str.length) {
                            val codeUnit = str.substring(i + 2, i + 6).toIntOrNull(16)
                            if (codeUnit != null) {
                                sb.append(codeUnit.toChar())
                                i += 6
                            } else {
                                sb.append("\\u")
                                i += 2
                            }
                        } else {
                            sb.append("\\u")
                            i += 2
                        }
                    }
                    else -> {
                        sb.append(c)
                        sb.append(next)
                        i += 2
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
