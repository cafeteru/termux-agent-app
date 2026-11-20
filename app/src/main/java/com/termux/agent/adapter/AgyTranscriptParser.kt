package com.termux.agent.adapter

data class ChatMessage(
    val stepIndex: Int,
    val sender: String, // "user", "agent", "tool"
    val content: String,
    val timestamp: String = "",
    val toolCalls: List<String> = emptyList()
)

object AgyTranscriptParser {

    internal const val READ_LATEST_TRANSCRIPT_COMMAND =
        "sh -c 'f=\$(ls -t \"\$HOME\"/.gemini/antigravity-cli/brain/*/.system_generated/logs/transcript.jsonl 2>/dev/null | head -n 1); " +
            "if [ -n \"\$f\" ] && [ -r \"\$f\" ]; then tail -n 120 \"\$f\"; fi'"

    fun parseLine(jsonLine: String): ChatMessage? {
        val trimmed = jsonLine.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{") || !trimmed.endsWith("}")) return null

        val stepIndex = extractJsonInt(trimmed, "step_index")
        val type = extractJsonString(trimmed, "type") ?: ""
        val source = extractJsonString(trimmed, "source") ?: ""
        val createdAt = extractJsonString(trimmed, "created_at") ?: ""
        val rawContent = extractJsonString(trimmed, "content") ?: ""

        if (type.isEmpty() && source.isEmpty()) {
            return null
        }

        return when {
            type == "USER_INPUT" || source == "USER_EXPLICIT" -> {
                val cleanPrompt = extractUserPrompt(rawContent)
                if (cleanPrompt.isBlank()) return null
                ChatMessage(
                    stepIndex = stepIndex,
                    sender = "user",
                    content = cleanPrompt,
                    timestamp = createdAt
                )
            }
            type == "PLANNER_RESPONSE" || (source == "MODEL" && type.isEmpty()) -> {
                val toolCallsList = extractToolCalls(trimmed)
                val thinking = extractJsonString(trimmed, "thinking") ?: ""

                val messageContent = if (rawContent.isNotBlank()) {
                    rawContent
                } else if (toolCallsList.isNotEmpty()) {
                    "Executing ${toolCallsList.joinToString(", ")}"
                } else {
                    thinking
                }
                if (messageContent.isBlank() && toolCallsList.isEmpty()) return null

                ChatMessage(
                    stepIndex = stepIndex,
                    sender = "agent",
                    content = messageContent,
                    timestamp = createdAt,
                    toolCalls = toolCallsList
                )
            }
            else -> null
        }
    }

    private fun extractUserPrompt(raw: String): String {
        val userReqStart = raw.indexOf("<USER_REQUEST>")
        val userReqEnd = raw.indexOf("</USER_REQUEST>")
        if (userReqStart >= 0 && userReqEnd > userReqStart) {
            return raw.substring(userReqStart + "<USER_REQUEST>".length, userReqEnd).trim()
        }
        return raw.trim()
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = Regex("""\"$key\"\s*:\s*\"((?:\\.|[^\"\\])*)\"""")
        val match = pattern.find(json) ?: return null
        return unescapeJson(match.groupValues[1])
    }

    private fun extractJsonInt(json: String, key: String): Int {
        val pattern = Regex("""\"$key\"\s*:\s*(-?\d+)""")
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    private fun extractToolCalls(json: String): List<String> {
        val toolCalls = mutableListOf<String>()
        val callPattern = Regex("""\{\s*\"name\"\s*:\s*\"([^\"]+)\"(?:.*?\"CommandLine\"\s*:\s*\"((?:\\.|[^\"\\])*)\")?""")
        for (match in callPattern.findAll(json)) {
            val name = match.groupValues[1]
            val cmd = if (match.groupValues.size > 2) unescapeJson(match.groupValues[2]) else ""
            if (cmd.isNotBlank()) {
                toolCalls.add("$name: $cmd")
            } else {
                toolCalls.add(name)
            }
        }
        return toolCalls
    }

    private fun unescapeJson(str: String): String {
        val sb = StringBuilder(str.length)
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (c == '\\' && i + 1 < str.length) {
                when (val next = str[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> {
                        sb.append(c)
                        sb.append(next)
                    }
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
