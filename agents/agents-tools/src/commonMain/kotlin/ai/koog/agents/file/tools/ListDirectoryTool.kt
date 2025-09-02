package ai.koog.agents.file.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolArgs
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolResult
import ai.koog.agents.core.tools.validate
import ai.koog.agents.core.tools.validateNotNull
import ai.koog.agents.file.tools.filter.GlobPattern
import ai.koog.agents.file.tools.model.FileSystemEntry
import ai.koog.agents.file.tools.render.folder
import ai.koog.prompt.text.text
import ai.koog.rag.base.files.FileMetadata
import ai.koog.rag.base.files.FileSystemProvider
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Lists directory contents recursively and returns a hierarchical directory tree structure.
 *
 * This tool traverses a directory with configurable depth and optional filtering,
 * building a tree representation that includes files and subdirectories with their metadata.
 * The output is formatted as an indented tree when converted to string.
 *
 * @param Path the filesystem path type used by the provider
 * @property fs read-only filesystem provider for accessing files and directories
 */
public class ListDirectoryTool<Path>(private val fs: FileSystemProvider.ReadOnly<Path>) :
    Tool<ListDirectoryTool.Args, ListDirectoryTool.Result>() {

    /**
     * Specifies which directory to list and how to traverse its contents.
     *
     * @property path absolute filesystem path to the target directory to list
     * @property depth the maximum recursion depth for traversing subdirectories (1 = immediate children only, 2 = children and grandchildren, etc.)
     * @property filter optional glob pattern to include only matching files/directories (e.g., `*.txt`)
     */
    @Serializable
    public data class Args(
        val path: String,
        val depth: Int = 1,
        val filter: String? = null
    ) : ToolArgs

    /**
     * Contains the successfully listed directory tree structure.
     *
     * The result wraps a [FileSystemEntry.Folder] representing the root directory with:
     * - All child files and subdirectories up to the specified depth
     * - File metadata (name, extension, path, size, content type, hidden status)
     * - Directory metadata (name, path, hidden status)
     * - Single-entry directories automatically unwrapped for cleaner output
     *
     * When converted to string, it produces an indented tree representation showing the hierarchy.
     *
     * @property root the root directory entry containing the complete tree structure
     */
    @Serializable
    public data class Result(val root: FileSystemEntry.Folder) : ToolResult.JSONSerializable<Result> {
        override fun getSerializer(): KSerializer<Result> = serializer()

        /**
         * Converts the directory tree to an indented text representation.
         *
         * The output format shows:
         * - Files: `path (size, lines, hidden if applicable)`
         * - Directories: `path/` with "(hidden)" marker if applicable
         * - Each level indented with 2 spaces to show hierarchy
         * - Single-entry directories automatically collapsed/unwrapped for readability
         *
         * Example output:
         * ```
         * /home/user/project/
         *   README.md (2.0 KiB, 45 lines)
         *   src/
         *     main/
         *       kotlin/
         *         Main.kt (1.5 KiB, 30 lines)
         *         Utils.kt (0.8 KiB, 20 lines)
         *     test/kotlin/MainTest.kt (0.5 KiB, 15 lines)
         *   build.gradle.kts (1.2 KiB, 25 lines)
         *   .gitignore (0.2 KiB, 8 lines, hidden)
         * ```
         *
         * @return formatted directory tree as indented text
         */
        override fun toStringDefault(): String = text { folder(root) }
    }

    override val argsSerializer: KSerializer<Args> = Args.serializer()
    override val descriptor: ToolDescriptor = Companion.descriptor

    /**
     * Lists directory contents with optional recursion and filtering.
     *
     * Validates that the path exists and is a directory, then builds a hierarchical tree structure.
     * Applies optional glob pattern filtering to include only matching files and directories.
     *
     * @param args arguments specifying the directory path, depth, and optional filter
     * @return [Result] containing the directory with its contents and metadata
     * @throws [ToolException.ValidationFailure] if has invalid depth, the path doesn't exist, is not a directory,
     * or all contents are filtered out by the glob pattern
     */
    override suspend fun execute(args: Args): Result {
        validate(args.depth > 0) { "The maximum recursion depth must be greater than zero." }

        val path = fs.fromAbsolutePathString(args.path)
        validate(fs.exists(path)) {
            "Path ${args.path} does not exist. " +
                "Please ensure you provide an absolute path to a valid directory."
        }

        val metadata = validateNotNull(fs.metadata(path)) { "Cannot read metadata: ${args.path}" }
        validate(metadata.type == FileMetadata.FileType.Directory) {
            "Path ${args.path} must be a directory. " +
                "Provided path points to a file."
        }

        val entry = buildDirectoryTree(
            fs = fs,
            start = path,
            startMetadata = metadata,
            maxDepth = args.depth,
            filter = if (args.filter != null) {
                GlobPattern.compile(pattern = args.filter, caseSensitive = false)
            } else {
                null
            }
        )

        validate(entry != null) {
            "Directory ${args.path} is filtered out by the provided glob pattern '${args.filter}'. " +
                "No matching files or directories found."
        }

        return Result(entry as FileSystemEntry.Folder)
    }

    public companion object {
        public val descriptor: ToolDescriptor = ToolDescriptor(
            name = "__list_directory__",
            description = """
                Browse and explore directory contents without making changes.
                
                When to use:
                - To understand project structure
                - To find existing files
                - To check what is in a directory before creating new files
                
                Output:
                A hierarchical tree view with file sizes and metadata.
                
                Guarantees:
                This is a read-only operation. It never creates, modifies, or deletes anything.
            """.trimIndent(),
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    name = "path",
                    description = "Absolute filesystem path to the directory you want to explore",
                    type = ToolParameterType.String
                )
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    name = "depth",
                    description = "How deep to traverse subdirectories (1=immediate children only, 2=grandchildren too, etc.). Default: 1",
                    type = ToolParameterType.Integer
                ),
                ToolParameterDescriptor(
                    name = "filter",
                    description = "Glob pattern to show only matching files/directories (e.g., '*.kt', '*.json', 'test*')",
                    type = ToolParameterType.String
                )
            )
        )
    }
}
