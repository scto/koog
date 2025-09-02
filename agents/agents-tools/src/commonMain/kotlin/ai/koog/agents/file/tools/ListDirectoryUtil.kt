package ai.koog.agents.file.tools

import ai.koog.agents.file.tools.filter.GlobPattern
import ai.koog.agents.file.tools.model.FileSystemEntry
import ai.koog.rag.base.files.FileMetadata
import ai.koog.rag.base.files.FileSystemProvider

/**
 * Build a directory tree with support for glob patterns.
 *
 * How it works:
 * - Matching: files are checked against their path **relative to the starting directory**.
 * - Normalization: all separators are converted to '/', so globs like "src/**/*.kt" work on any OS.
 * - Directories: always traversed to the given depth; if a filter is active,
 *   directories that contain no matching files are omitted.
 * - Depth:
 *   depth = 1 → list only the immediate children of the start directory.
 *   depth > 1 → recurse deeper. A chain of single-child directories is "unwrapped"
 *                (does not consume depth), so long paths collapse cleanly.
 *
 * @param fs File system provider for accessing files and directories
 * @param start Starting path for tree traversal
 * @param startMetadata Metadata for the starting path
 * @param maxDepth Maximum depth to traverse (must be > 0)
 * @param filter Optional glob pattern to filter files by their relative path
 * @return Root FileSystemEntry representing the tree, or null if filtered out
 */
internal suspend fun <Path> buildDirectoryTree(
    fs: FileSystemProvider.ReadOnly<Path>,
    start: Path,
    startMetadata: FileMetadata,
    maxDepth: Int,
    filter: GlobPattern? = null
): FileSystemEntry? {
    require(maxDepth > 0) { "maxDepth must be > 0" }

    return buildNode(
        fs = fs,
        rootPath = start,
        currentPath = start,
        metadata = startMetadata,
        depth = maxDepth,
        filter = filter
    )
}

/**
 * Recursively builds a node in the directory tree.
 */
private suspend fun <Path> buildNode(
    fs: FileSystemProvider.ReadOnly<Path>,
    rootPath: Path,
    currentPath: Path,
    metadata: FileMetadata,
    depth: Int,
    filter: GlobPattern?
): FileSystemEntry? {
    // Handle file nodes
    if (metadata.type == FileMetadata.FileType.File) {
        return if (matchesFilter(fs, rootPath, currentPath, filter)) {
            buildFileEntry(fs, currentPath, metadata)
        } else {
            null
        }
    }

    // Handle directory nodes
    val children = fs.list(currentPath).mapNotNull { child ->
        fs.metadata(child)?.let { child to it }
    }

    // Decide which children to keep:
    // - Always keep directories (so we can keep traversing into them)
    // - Keep files only if they match the filter (or if no filter is set)
    val visibleChildren = children.filter { (childPath, childMeta) ->
        childMeta.type == FileMetadata.FileType.Directory ||
            matchesFilter(fs, rootPath, childPath, filter)
    }

    // At max depth with multiple children: return folder entry without children (collapsed)
    if (depth == 1 && visibleChildren.size > 1) {
        return buildFolderEntry(fs, currentPath, metadata, entries = null)
    }

    // Build entries for visible children
    val entries = mutableListOf<FileSystemEntry>()

    for ((childPath, childMeta) in visibleChildren) {
        when (childMeta.type) {
            FileMetadata.FileType.File -> {
                // File already passed filter check in visibleChildren
                entries += buildFileEntry(fs, childPath, childMeta)
            }

            FileMetadata.FileType.Directory -> {
                // Next depth: if this folder has only one child directory, keep depth the same (unwrap chain)
                val nextDepth = if (visibleChildren.size == 1) depth else depth - 1

                if (nextDepth > 0) {
                    buildNode(fs, rootPath, childPath, childMeta, nextDepth, filter)
                        ?.let { entries += it }
                }
            }
        }
    }

    // Prune empty directories when filtering
    if (filter != null && entries.isEmpty()) {
        return null
    }

    return buildFolderEntry(fs, currentPath, metadata, entries)
}

/**
 * Converts a path to a normalized relative path string for glob matching.
 * Falls back to the filename if relativization fails.
 */
private fun <Path> getRelativePath(
    fs: FileSystemProvider.ReadOnly<Path>,
    rootPath: Path,
    path: Path
): String {
    return (fs.relativize(rootPath, path) ?: fs.name(path)).replace('\\', '/')
}

/**
 * Checks if a file path matches the filter pattern.
 * Always returns true if no filter is specified.
 */
private fun <Path> matchesFilter(
    fs: FileSystemProvider.ReadOnly<Path>,
    rootPath: Path,
    path: Path,
    filter: GlobPattern?
): Boolean {
    return filter == null || filter.matches(getRelativePath(fs, rootPath, path))
}

/**
 * Creates a FileSystemEntry.File from path and metadata.
 */
private suspend fun <Path> buildFileEntry(
    fs: FileSystemProvider.ReadOnly<Path>,
    path: Path,
    metadata: FileMetadata
): FileSystemEntry.File {
    return FileSystemEntry.File(
        name = fs.name(path),
        extension = fs.extension(path),
        path = fs.toAbsolutePathString(path),
        hidden = metadata.hidden,
        size = buildFileSize(fs, path),
        contentType = fs.getFileContentType(path),
        content = FileSystemEntry.File.Content.None,
    )
}

/**
 * Creates a FileSystemEntry.Folder from the path, metadata, and optional entries.
 */
private fun <Path> buildFolderEntry(
    fs: FileSystemProvider.ReadOnly<Path>,
    path: Path,
    metadata: FileMetadata,
    entries: List<FileSystemEntry>?
): FileSystemEntry.Folder {
    return FileSystemEntry.Folder(
        name = fs.name(path),
        path = fs.toAbsolutePathString(path),
        hidden = metadata.hidden,
        entries = entries
    )
}
