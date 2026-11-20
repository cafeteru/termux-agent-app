package com.termux.agent

sealed interface DirectoryListingResult {
    data class Success(val currentPath: String, val subdirectories: List<String>) : DirectoryListingResult
    data class Error(val message: String) : DirectoryListingResult
}

object WorkspaceBrowser {
    internal const val MARKER = "__TERMUX_AGENT_LS__\u0000"

    // No single quotes allowed so wrapping in `sh -c '...'` works across login shells; ends with `exit 0` for empty dirs.
    internal const val LIST_DIRECTORIES_SH_SCRIPT =
        "IFS= read -r t || true; " +
            "case \"\$t\" in \"\"|\"~\") cd || exit 1 ;; \"~/\"*) cd -- \"\$HOME/\${t#~/}\" || exit 1 ;; *) cd -- \"\$t\" || exit 1 ;; esac; " +
            "[ -r . ] || exit 2; " +
            "printf \"__TERMUX_AGENT_LS__\\000%s\\000\" \"\$PWD\"; " +
            "for d in ./* ./.[!.]* ./..?*; do if [ -d \"\$d\" ]; then printf \"%s\\000\" \"\${d#./}\"; fi; done; " +
            "exit 0"

    internal const val LIST_DIRECTORIES_COMMAND = "sh -c '$LIST_DIRECTORIES_SH_SCRIPT'"

    fun sanitizeRequestedPath(path: String): String =
        path.replace("\r", "").replace("\n", "").trim()

    fun parseDirectoryListingOutput(rawOutput: String, exitStatus: Int): DirectoryListingResult {
        when (exitStatus) {
            1 -> return DirectoryListingResult.Error("No se pudo abrir el directorio.")
            2 -> return DirectoryListingResult.Error("Sin permiso de lectura en este directorio.")
            0 -> Unit
            else -> return DirectoryListingResult.Error("Error al listar directorios (código $exitStatus).")
        }

        val markerIndex = rawOutput.indexOf(MARKER)
        if (markerIndex < 0) {
            return DirectoryListingResult.Error("Respuesta inesperada al listar directorios.")
        }

        val payload = rawOutput.substring(markerIndex + MARKER.length)
        val parts = payload.split('\u0000')
        val currentPath = parts.firstOrNull()
            ?.replace("\r", "")
            ?.replace("\n", "")
            ?.trim()
            .orEmpty()
        if (currentPath.isEmpty()) {
            return DirectoryListingResult.Error("Respuesta inesperada al listar directorios.")
        }

        val subdirectories = parts
            .drop(1)
            .map { it.trim('\r', '\n') }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

        return DirectoryListingResult.Success(currentPath = currentPath, subdirectories = subdirectories)
    }

    fun parentPath(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        val normalized = if (trimmed == "/") "/" else trimmed.trimEnd('/').ifEmpty { "/" }
        if (normalized == "/") return null
        val lastSlash = normalized.lastIndexOf('/')
        return when {
            lastSlash < 0 -> null
            lastSlash == 0 -> "/"
            else -> normalized.substring(0, lastSlash)
        }
    }

    fun childPath(currentPath: String, childName: String): String {
        val cleanChild = childName.trim('/')
        val base = currentPath.trim().trimEnd('/')
        return if (base.isEmpty()) "/$cleanChild" else "$base/$cleanChild"
    }

    fun displayName(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "Seleccionar proyecto"
        val normalized = if (trimmed == "/") "/" else trimmed.trimEnd('/').ifEmpty { "/" }
        if (normalized == "/") return "/"
        return normalized.substringAfterLast('/').ifEmpty { normalized }
    }
}
