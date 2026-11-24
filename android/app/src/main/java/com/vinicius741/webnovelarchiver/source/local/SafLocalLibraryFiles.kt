package com.vinicius741.webnovelarchiver.source.local

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.vinicius741.webnovelarchiver.domain.sha256Hex
import java.io.File

/** Where local novels' covers are saved, under the app's files directory. */
const val LOCAL_COVERS_DIR = "local_source_covers"

/** Where scanned PDFs' recognized page text is saved ([PdfOcrCache]), under the app's files directory. */
const val LOCAL_OCR_DIR = "local_source_ocr"

/**
 * The local folder through the Storage Access Framework. The folder's tree URI and its persisted
 * read grant belong to this device, so they live in this device's preferences rather than in the
 * library JSON or backups; a restored library asks for the folder again.
 */
class SafLocalLibraryFiles(
    context: Context,
) : LocalLibraryFiles {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val copies = File(this.context.cacheDir, "local_source")
    private val covers = File(this.context.filesDir, COVERS_DIR)

    /** Remembers [tree] as the local folder, keeping read access across restarts and releasing the previous folder. */
    override fun setFolder(treeUri: String) {
        val tree = Uri.parse(treeUri)
        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        treeUri()?.takeIf { it != tree }?.let { previous ->
            runCatching { resolver.releasePersistableUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        preferences.edit().putString(TREE_URI, tree.toString()).apply()
    }

    override fun folderName(): String? {
        val tree = accessibleTree() ?: return null
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return runCatching {
            context.contentResolver.query(document, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/')
    }

    override fun listRoot(): List<LocalDocument> {
        val tree = accessibleTree() ?: throw LocalFolderUnavailableException()
        return children(tree, DocumentsContract.getTreeDocumentId(tree))
    }

    override fun listChildren(directory: LocalDocument): List<LocalDocument> {
        val tree = accessibleTree() ?: throw LocalFolderUnavailableException()
        return children(tree, directory.id)
    }

    override fun readBytes(document: LocalDocument): ByteArray {
        val tree = accessibleTree() ?: throw LocalFolderUnavailableException()
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, document.id)
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw LocalEntryMissingException(document.name)
    }

    @Synchronized
    override fun cachedCopy(document: LocalDocument): File {
        val tree = accessibleTree() ?: throw LocalFolderUnavailableException()
        val stem = sha256Hex(document.id).take(COPY_KEY_LENGTH)
        val copy = File(copies, "$stem-${document.size}-${document.lastModified}.bin")
        if (copy.isFile && (document.size <= 0 || copy.length() == document.size)) {
            // Touched so the trim below keeps the books in use (least recently used goes first).
            copy.setLastModified(System.currentTimeMillis())
            return copy
        }
        copies.mkdirs()
        copies.listFiles()?.filter { it.name.startsWith("$stem-") }?.forEach(File::delete)
        val partial = File(copies, "${copy.name}.part")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, document.id)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> partial.outputStream().use { input.copyTo(it) } }
                ?: throw LocalEntryMissingException(document.name)
            if (!partial.renameTo(copy)) throw LocalSourceException("Could not cache ${document.name}")
        } finally {
            partial.delete()
        }
        trimCopies(keep = copy)
        return copy
    }

    @Synchronized
    override fun saveCover(
        storyId: String,
        bytes: ByteArray,
        extension: String,
    ): String {
        covers.mkdirs()
        val stem = sha256Hex(storyId).take(COPY_KEY_LENGTH)
        // The content hash in the name gives a changed cover a new URL, so no stale cached image shows.
        val file = File(covers, "$stem-${sha256Hex(bytes).take(COVER_HASH_LENGTH)}.$extension")
        if (!file.isFile) {
            covers.listFiles()?.filter { it.name.startsWith("$stem-") }?.forEach(File::delete)
            val partial = File(covers, "${file.name}.part")
            partial.writeBytes(bytes)
            if (!partial.renameTo(file)) throw java.io.IOException("Could not save the cover")
        }
        return Uri.fromFile(file).toString()
    }

    private fun treeUri(): Uri? = preferences.getString(TREE_URI, null)?.let(Uri::parse)

    /** The chosen folder while the app still holds read access to it. */
    private fun accessibleTree(): Uri? {
        val tree = treeUri() ?: return null
        return tree.takeIf { context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission } }
    }

    private fun children(
        tree: Uri,
        parentId: String,
    ): List<LocalDocument> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cursor =
            runCatching { context.contentResolver.query(uri, PROJECTION, null, null, null) }.getOrNull()
                ?: throw LocalFolderUnavailableException()
        return cursor.use { rows -> generateSequence { if (rows.moveToNext()) rows.document() else null }.toList() }
    }

    private fun Cursor.document(): LocalDocument =
        LocalDocument(
            id = getString(0),
            name = getString(1).orEmpty(),
            isDirectory = getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
            size = if (isNull(3)) 0L else getLong(3),
            lastModified = if (isNull(4)) 0L else getLong(4),
        )

    private fun trimCopies(keep: File) {
        val all =
            copies
                .listFiles()
                ?.filter { it.isFile && it != keep }
                .orEmpty()
                .sortedByDescending { it.lastModified() }
        all.drop(MAX_COPIES - 1).forEach(File::delete)
    }

    private companion object {
        const val COVERS_DIR = LOCAL_COVERS_DIR
        const val PREFERENCES = "local_source"
        const val TREE_URI = "tree_uri"
        const val COPY_KEY_LENGTH = 16
        const val COVER_HASH_LENGTH = 12
        const val MAX_COPIES = 6
        val PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
    }
}
