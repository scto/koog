package ai.koog.agents.file.tools

import ai.koog.agents.file.tools.filter.GlobPattern
import ai.koog.agents.file.tools.model.FileSystemEntry
import ai.koog.rag.base.files.FileMetadata
import ai.koog.rag.base.files.FileSystemProvider

internal suspend fun <Path> buildDirectoryTree(
    fs: FileSystemProvider.ReadOnly<Path>,
    path: Path,
    metadata: FileMetadata,
    maxDepth: Int,
    filter: GlobPattern? = null
): FileSystemEntry? {
    require(maxDepth > 0) { "The maxDepth must be greater than zero." }

    if (filter != null && !filter.matches(fs.name(path))) return null

    return when (metadata.type) {
        FileMetadata.FileType.File -> buildFileEntryForTree(fs, path, metadata)
        FileMetadata.FileType.Directory -> buildFolderWithUnwrap(fs, path, metadata, maxDepth, filter)
    }
}

private suspend fun <Path> buildFileEntryForTree(
    fs: FileSystemProvider.ReadOnly<Path>,
    path: Path,
    metadata: FileMetadata
): FileSystemEntry.File {
    val name = fs.name(path)
    return FileSystemEntry.File(
        name = name,
        extension = fs.extension(path),
        path = fs.toAbsolutePathString(path),
        content = FileSystemEntry.File.Content.None,
        size = buildFileSize(fs, path),
        hidden = metadata.hidden,
        contentType = fs.getFileContentType(path),
    )
}

private suspend fun <Path> buildFolderWithUnwrap(
    fs: FileSystemProvider.ReadOnly<Path>,
    path: Path,
    metadata: FileMetadata,
    maxDepth: Int,
    filter: GlobPattern?
): FileSystemEntry.Folder {
    val children = fs.list(path).filter { child ->
        filter == null || filter.matches(fs.name(child))
    }
    if (maxDepth == 1 && children.size > 1) {
        return FileSystemEntry.Folder(
            name = fs.name(path),
            path = fs.toAbsolutePathString(path),
            entries = null,
            hidden = metadata.hidden
        )
    }

    val entries = children.mapNotNull { child ->
        val childMeta = fs.metadata(child) ?: return@mapNotNull null

        if (childMeta.type == FileMetadata.FileType.File) {
            return@mapNotNull buildFileEntryForTree(fs, child, childMeta)
        }
        val nextDepth = if (shouldUnwrap(children, childMeta)) maxDepth else maxDepth - 1
        if (nextDepth <= 0) return@mapNotNull null
        buildDirectoryTree(
            fs = fs,
            path = child,
            metadata = childMeta,
            maxDepth = nextDepth,
            filter = filter
        )
    }

    return FileSystemEntry.Folder(
        name = fs.name(path),
        path = fs.toAbsolutePathString(path),
        entries = entries,
        hidden = metadata.hidden
    )
}

/**
 * Determines if we should unwrap (preserve depth) for single directory paths.
 * Only unwrap if there's exactly one child AND that child is a directory.
 */
private fun <Path> shouldUnwrap(children: List<Path>, childMeta: FileMetadata): Boolean {
    return children.size == 1 && childMeta.type == FileMetadata.FileType.Directory
}
