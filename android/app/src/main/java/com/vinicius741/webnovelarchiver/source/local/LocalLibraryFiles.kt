package com.vinicius741.webnovelarchiver.source.local

import java.io.File
import java.io.IOException

/** A file or folder in the local folder. [id] is opaque to everything but [LocalLibraryFiles]. */
data class LocalDocument(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/** A local novel or chapter could not be read. Never retried automatically: a file does not fix itself. */
open class LocalSourceException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** The local folder is not chosen, or the app lost its access to it. */
class LocalFolderUnavailableException(
    message: String = "Choose your local folder again in Add story › Local folder.",
) : LocalSourceException(message)

/** The local folder no longer holds a novel's file or subfolder. */
class LocalEntryMissingException(
    name: String,
) : LocalSourceException("\"$name\" is no longer in your local folder.")

/** Read access to the folder the user chose for local novels (the device implementation uses the Storage Access Framework). */
interface LocalLibraryFiles {
    /** Remembers the folder at [treeUri] (a document tree the user picked) as the local folder. */
    fun setFolder(treeUri: String)

    /** The chosen folder's display name, or null when none is chosen or access was lost. */
    fun folderName(): String?

    /** The local folder's direct children. */
    fun listRoot(): List<LocalDocument>

    fun listChildren(directory: LocalDocument): List<LocalDocument>

    fun readBytes(document: LocalDocument): ByteArray

    /**
     * A local file with [document]'s bytes, for formats that need random access (EPUB, PDF). The
     * copy is reused while the document's size and modification time stay the same.
     */
    fun cachedCopy(document: LocalDocument): File

    /** Saves a novel's cover image and returns a URL the image loader can show. */
    fun saveCover(
        storyId: String,
        bytes: ByteArray,
        extension: String,
    ): String
}

/** Reads a PDF's text page by page and renders a cover from its first page. */
interface PdfPages {
    /** False when this device cannot read PDF text (it needs Android 15). */
    val supported: Boolean

    fun text(file: File): List<String>

    /** The first page as a JPEG, or null when it cannot be rendered. */
    fun firstPageImage(file: File): ByteArray?
}
