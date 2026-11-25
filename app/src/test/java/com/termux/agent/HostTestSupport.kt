package com.termux.agent

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

object HostTestSupport {
    fun assumeShellContract(
        command: String,
        home: File,
        requiresPython: Boolean = false
    ) {
        val shellReady = processSucceeds(
            command = listOf("sh", "-c", command),
            environment = mapOf("HOME" to home.absolutePath)
        )
        assumeTrue(
            shellReady,
            "This shell contract requires a host runtime that executes the production command"
        )

        if (requiresPython) {
            assumeTrue(
                processSucceeds(listOf("python3", "--version")),
                "Shell contract tests requiring SQLite fixtures need python3"
            )
        }
    }

    fun assumeSymlinkSupport(root: File) {
        val target = File(root, "symlink-capability-target").apply { mkdirs() }
        val link = File(root, "symlink-capability-link")
        val supported = runCatching {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        }.getOrDefault(false)
        link.delete()
        target.delete()
        assumeTrue(supported, "Shell directory listing test needs symbolic-link support")
    }

    private fun processSucceeds(
        command: List<String>,
        environment: Map<String, String> = emptyMap()
    ): Boolean = runCatching {
        val process = ProcessBuilder(command).apply {
            environment().putAll(environment)
            redirectErrorStream(true)
        }.start()
        val completed = process.waitFor(5, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            false
        } else {
            process.inputStream.readBytes()
            process.exitValue() == 0
        }
    }.getOrDefault(false)
}
