package ai.koog.agents.file.tools

import ai.koog.agents.file.tools.filter.GlobPattern
import ai.koog.agents.file.tools.model.FileSystemEntry
import ai.koog.rag.base.files.FileMetadata
import ai.koog.rag.base.files.FileSystemProvider

internal suspend fun <Path> buildDirectoryTree(
    path: Path,
    fs: FileSystemProvider.ReadOnly<Path>,
    maxDepth: Int,
    filter: GlobPattern? = null
): FileSystemEntry? {
    require(maxDepth > 0) { "The maxDepth must be greater than zero." }

    val metadata = fs.metadata(path) ?: return null

    if (filter != null && !filter.matches(fs.name(path))) return null

    return when (metadata.type) {
        FileMetadata.FileType.File -> buildFileEntryForTree(fs, path, metadata)
        FileMetadata.FileType.Directory -> buildFolderWithUnwrap(fs, path, maxDepth, filter)
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
        extension = extractExtension(name),
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
    maxDepth: Int,
    filter: GlobPattern?
): FileSystemEntry.Folder {
    val children = fs.list(path).filter { child ->
        filter == null || filter.matches(fs.name(child))
    }

    return if (maxDepth == 1 && children.size != 1) {
        FileSystemEntry.Folder(
            name = fs.name(path),
            path = fs.toAbsolutePathString(path),
            entries = null, // Do not expand further
            hidden = fs.metadata(path)?.hidden ?: false
        )
    } else {
        val entries = children.mapNotNull { child ->
            buildDirectoryTree(child, fs, if (children.size == 1) maxDepth else maxDepth - 1, filter)
        }

        FileSystemEntry.Folder(
            name = fs.name(path),
            path = fs.toAbsolutePathString(path),
            entries = entries,
            hidden = fs.metadata(path)?.hidden ?: false
        )
    }
}
