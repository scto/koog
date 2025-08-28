package ai.koog.agents.file.tools

import ai.koog.agents.core.tools.DirectToolCallsEnabler
import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.file.tools.model.FileSystemEntry
import ai.koog.rag.base.files.JVMFileSystemProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(InternalAgentToolsApi::class)
class ListDirectoryToolJvmTest {

    private val fs = JVMFileSystemProvider.ReadOnly

    @OptIn(InternalAgentToolsApi::class)
    private val enabler = object : DirectToolCallsEnabler {}
    private val tool = ListDirectoryTool(fs)

    @TempDir
    lateinit var tempDir: Path

    private fun createDir(name: String): Path = tempDir.resolve(name).createDirectories()

    private suspend fun list(path: Path, depth: Int = 1, filter: String? = null): ListDirectoryTool.Result =
        tool.execute(ListDirectoryTool.Args(path.toString(), depth, filter), enabler)

    @Test
    fun `Args uses correct defaults`() {
        val args = ListDirectoryTool.Args("/tmp/test")
        assertEquals("/tmp/test", args.path)
        assertEquals(1, args.depth)
        assertNull(args.filter)
    }

    @Test
    fun `descriptor is configured correctly`() {
        val descriptor = ListDirectoryTool.descriptor
        assertEquals("__list_directory__", descriptor.name)
        assertTrue(descriptor.description.isNotEmpty())
        assertEquals(listOf("path"), descriptor.requiredParameters.map { it.name })
        assertEquals(setOf("depth", "filter"), descriptor.optionalParameters.map { it.name }.toSet())
    }

    @Test
    fun `throws ValidationFailure for non-existent path`() {
        val nonExistent = tempDir.resolve("missing")
        assertThrows<ToolException.ValidationFailure> { runBlocking { list(nonExistent) } }
    }

    @Test
    fun `throws ValidationFailure when path points to a file`() {
        val file = tempDir.resolve("f.txt").createFile().apply { writeText("x") }
        assertThrows<ToolException.ValidationFailure> { runBlocking { list(file) } }
    }

    @Test
    fun `throws ValidationFailure for invalid depth`() {
        val dir = createDir("d")
        assertThrows<ToolException.ValidationFailure> { runBlocking { list(dir, depth = 0) } }
    }

    @Test
    fun `lists directory tree and renders text`() = runBlocking {
        val d = createDir("project").also {
            it.resolve("README.md").createFile().writeText("hello\nworld")
            val src = it.resolve("src").createDirectories()
            val main = src.resolve("main").createDirectories()
            val kotlin = main.resolve("kotlin").createDirectories()
            kotlin.resolve("Main.kt").createFile().writeText("fun main(){}\n")
        }

        val result = list(d, depth = 3)
        val root = result.root
        assertIs<FileSystemEntry.Folder>(root)

        val text = result.toStringDefault()
        assertTrue(text.contains(d.toAbsolutePath().toString()))
        assertTrue(text.contains("README.md"))
        assertTrue(text.contains("src"))
    }

    @Test
    fun `filter is applied`() = runBlocking {
        val d = createDir("filt.txt")
        d.resolve("a.md").createFile().writeText("a")
        d.resolve("b.txt").createFile().writeText("b")
        val result = list(d, depth = 2, filter = "*.txt")
        val names = requireNotNull(result.root.entries).map {
            when (it) {
                is FileSystemEntry.File -> it.name
                is FileSystemEntry.Folder -> it.name
            }
        }.toSet()
        assertEquals(setOf("b.txt"), names)
    }
}
