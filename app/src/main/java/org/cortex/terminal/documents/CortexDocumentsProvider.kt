package org.cortex.terminal.documents

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import org.cortex.terminal.LocaleHelper
import org.cortex.terminal.R
import org.cortex.terminal.runtime.ElfLinkerPatcher
import org.cortex.terminal.runtime.Environment
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/**
 * Shows the Cortex home directory in Android's Files app and in every "open/save file" picker
 * (Storage Access Framework), so files can be exchanged with other apps without copying them to
 * /sdcard first.
 *
 * Only the home directory is exposed, never the Ubuntu system tree. The provider is protected by
 * `android.permission.MANAGE_DOCUMENTS`, which only the system documents UI holds: other apps
 * reach files solely through URIs the user picked.
 */
class CortexDocumentsProvider : DocumentsProvider() {

    private fun base(): File = Environment.getHomeDir(requireNotNullContext())

    private fun requireNotNullContext(): Context =
        context ?: throw IllegalStateException("Provider is not attached")

    private fun isShareEnabled(): Boolean = try {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireNotNullContext())
            .getBoolean(PREF_DOCUMENTS_ENABLED, true)
    } catch (_: Exception) {
        true
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        if (!isShareEnabled()) return result
        val base = base()
        if (!base.isDirectory) base.mkdirs()
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, DocumentPaths.ROOT_ID)
            add(Root.COLUMN_TITLE, LocaleHelper.wrap(requireNotNullContext()).getString(R.string.app_name))
            add(Root.COLUMN_SUMMARY, LocaleHelper.wrap(requireNotNullContext()).getString(R.string.documents_root_summary))
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_DOCUMENT_ID, DocumentPaths.ROOT_ID)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, base.freeSpace)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY
            )
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, documentId, existing(documentId))
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = existing(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("$parentDocumentId is not a directory")
        val children = parent.listFiles() ?: return result
        val base = base()
        for (child in children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))) {
            // Symlinks that lead out of the home directory (e.g. "storage" -> /sdcard) stay hidden.
            if (!DocumentPaths.isInside(base, child)) continue
            includeFile(result, DocumentPaths.docIdFor(base, child), child)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = existing(documentId)
        if (file.isDirectory) throw FileNotFoundException("$documentId is a directory")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = existing(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("$parentDocumentId is not a directory")
        if (!DocumentPaths.isValidName(displayName)) throw FileNotFoundException("Invalid name")
        val target = DocumentPaths.uniqueChild(parent, displayName)
        try {
            val created = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
            if (!created) throw FileNotFoundException("Could not create $displayName")
        } catch (e: java.io.IOException) {
            throw FileNotFoundException("Could not create $displayName: ${e.message}")
        }
        return DocumentPaths.docIdFor(base(), target)
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == DocumentPaths.ROOT_ID) throw FileNotFoundException("The home directory cannot be deleted")
        val file = existing(documentId)
        if (!DocumentPaths.deleteTree(file)) throw FileNotFoundException("Could not delete $documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        if (documentId == DocumentPaths.ROOT_ID) throw FileNotFoundException("The home directory cannot be renamed")
        if (!DocumentPaths.isValidName(displayName)) throw FileNotFoundException("Invalid name")
        val file = existing(documentId)
        val target = File(file.parentFile, displayName)
        if (target.exists() || ElfLinkerPatcher.isSymlink(target)) throw FileNotFoundException("$displayName already exists")
        if (!file.renameTo(target)) throw FileNotFoundException("Could not rename $documentId")
        return DocumentPaths.docIdFor(base(), target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val base = base()
        val parent = DocumentPaths.resolve(base, parentDocumentId) ?: return false
        val child = DocumentPaths.resolve(base, documentId) ?: return false
        return try {
            val parentPath = parent.canonicalPath.trimEnd('/')
            child.canonicalPath.startsWith("$parentPath/")
        } catch (_: Exception) {
            false
        }
    }

    override fun getDocumentType(documentId: String): String = mimeTypeOf(existing(documentId))

    private fun existing(documentId: String): File {
        if (!isShareEnabled()) throw FileNotFoundException("Sharing is turned off")
        val file = DocumentPaths.resolve(base(), documentId) ?: throw FileNotFoundException("Invalid document $documentId")
        if (!file.exists()) throw FileNotFoundException("$documentId does not exist")
        return file
    }

    private fun includeFile(result: MatrixCursor, documentId: String, file: File) {
        val isRoot = documentId == DocumentPaths.ROOT_ID
        var flags = 0
        if (file.isDirectory) {
            if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (!isRoot) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, if (isRoot) LocaleHelper.wrap(requireNotNullContext()).getString(R.string.app_name) else file.name)
            add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            add(Document.COLUMN_MIME_TYPE, mimeTypeOf(file))
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeTypeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        const val PREF_DOCUMENTS_ENABLED = "documents_enabled"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE
        )

        /** Turns the integration on or off: the provider component is enabled/disabled for the whole system. */
        fun setEnabled(context: Context, enabled: Boolean) {
            try {
                val state = if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, CortexDocumentsProvider::class.java),
                    state,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {
            }
        }
    }
}
