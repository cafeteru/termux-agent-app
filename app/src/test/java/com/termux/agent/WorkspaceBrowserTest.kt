package com.termux.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspaceBrowserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parentPathHandlesRootNestedAndTrailingSlashes() {
        assertNull(WorkspaceBrowser.parentPath(""))
        assertNull(WorkspaceBrowser.parentPath("/"))
        assertEquals("/", WorkspaceBrowser.parentPath("/data"))
        assertEquals("/", WorkspaceBrowser.parentPath("/data/"))
        assertEquals("/data/data", WorkspaceBrowser.parentPath("/data/data/com.termux"))
        assertEquals("/data/data", WorkspaceBrowser.parentPath("/data/data/com.termux/"))
    }

    @Test
    fun childPathJoinsRootAndNestedPaths() {
        assertEquals("/data", WorkspaceBrowser.childPath("/", "data"))
        assertEquals("/home/termux/repo", WorkspaceBrowser.childPath("/home/termux", "repo"))
        assertEquals("/home/termux/repo", WorkspaceBrowser.childPath("/home/termux/", "repo"))
    }

    @Test
    fun displayNameExtractsFolderOrFallback() {
        assertEquals("Seleccionar proyecto", WorkspaceBrowser.displayName(""))
        assertEquals("Seleccionar proyecto", WorkspaceBrowser.displayName("   "))
        assertEquals("/", WorkspaceBrowser.displayName("/"))
        assertEquals("termux-agent-app", WorkspaceBrowser.displayName("/home/termux/termux-agent-app"))
        assertEquals("termux-agent-app", WorkspaceBrowser.displayName("/home/termux/termux-agent-app/"))
    }

    @Test
    fun parseDirectoryListingOutputParsesNoiseSortedDirsAndEmptyDirs() {
        val raw = "MOTD noise line\n" +
            WorkspaceBrowser.MARKER +
            "/home/termux\u0000zeta\u0000Alpha\u0000.\u0000..\u0000alpha\u0000beta\u0000"
        val result = WorkspaceBrowser.parseDirectoryListingOutput(raw, exitStatus = 0)
        assertTrue(result is DirectoryListingResult.Success)
        val success = result as DirectoryListingResult.Success
        assertEquals("/home/termux", success.currentPath)
        assertEquals(listOf("Alpha", "alpha", "beta", "zeta"), success.subdirectories)

        val emptyResult = WorkspaceBrowser.parseDirectoryListingOutput(
            WorkspaceBrowser.MARKER + "/home/termux/empty\u0000",
            exitStatus = 0
        )
        assertEquals(
            DirectoryListingResult.Success("/home/termux/empty", emptyList()),
            emptyResult
        )
    }

    @Test
    fun parseDirectoryListingOutputMapsErrorsAndMissingMarker() {
        assertEquals(
            DirectoryListingResult.Error("No se pudo abrir el directorio."),
            WorkspaceBrowser.parseDirectoryListingOutput("", exitStatus = 1)
        )
        assertEquals(
            DirectoryListingResult.Error("Sin permiso de lectura en este directorio."),
            WorkspaceBrowser.parseDirectoryListingOutput("", exitStatus = 2)
        )
        assertEquals(
            DirectoryListingResult.Error("Error al listar directorios (código 127)."),
            WorkspaceBrowser.parseDirectoryListingOutput("", exitStatus = 127)
        )
        assertEquals(
            DirectoryListingResult.Error("Respuesta inesperada al listar directorios."),
            WorkspaceBrowser.parseDirectoryListingOutput("random output", exitStatus = 0)
        )
    }

    @Test
    fun shellListingCommandHandlesEmptyNormalSpecialCharsHiddenAndSymlinkDirs() {
        val emptyDir = tempFolder.newFolder("empty")
        val emptyResult = runListingScript(emptyDir.absolutePath, homePath = emptyDir.absolutePath)
        assertEquals(
            DirectoryListingResult.Success(emptyDir.canonicalPath, emptyList()),
            emptyResult
        )

        val rootDir = tempFolder.newFolder("workspace")
        File(rootDir, "alpha").mkdir()
        File(rootDir, "My Project").mkdir()
        File(rootDir, "Bob's App").mkdir()
        File(rootDir, "-dash").mkdir()
        File(rootDir, ".git").mkdir()
        File(rootDir, "..double-dot").mkdir()
        File(rootDir, "readme.txt").writeText("ignore file")

        val symlinkTarget = tempFolder.newFolder("linked-target")
        Files.createSymbolicLink(
            File(rootDir, "linked-dir").toPath(),
            symlinkTarget.toPath()
        )

        val listed = runListingScript(rootDir.absolutePath, homePath = rootDir.absolutePath)
        assertTrue("Expected Success but got $listed", listed is DirectoryListingResult.Success)
        val success = listed as DirectoryListingResult.Success
        assertEquals(rootDir.canonicalPath, success.currentPath)
        assertEquals(
            listOf("-dash", "..double-dot", ".git", "alpha", "Bob's App", "linked-dir", "My Project"),
            success.subdirectories
        )

        val homeResult = runListingScript("", homePath = rootDir.absolutePath)
        assertTrue(homeResult is DirectoryListingResult.Success)
        assertEquals(rootDir.canonicalPath, (homeResult as DirectoryListingResult.Success).currentPath)

        val tildeResult = runListingScript("~", homePath = rootDir.absolutePath)
        assertTrue(tildeResult is DirectoryListingResult.Success)
        assertEquals(rootDir.canonicalPath, (tildeResult as DirectoryListingResult.Success).currentPath)

        val missingResult = runListingScript(
            File(rootDir, "does-not-exist").absolutePath,
            homePath = rootDir.absolutePath
        )
        assertTrue(missingResult is DirectoryListingResult.Error)
    }

    private fun runListingScript(requestedPath: String, homePath: String): DirectoryListingResult {
        val process = ProcessBuilder("sh", "-c", WorkspaceBrowser.LIST_DIRECTORIES_COMMAND).apply {
            environment()["HOME"] = homePath
            redirectErrorStream(false)
        }.start()

        process.outputStream.use { out ->
            out.write((WorkspaceBrowser.sanitizeRequestedPath(requestedPath) + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }

        val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val exitCode = process.waitFor()
        return WorkspaceBrowser.parseDirectoryListingOutput(stdout, exitCode)
    }
}
