package org.cortex.terminal.documents

import org.cortex.terminal.runtime.ElfLinkerPatcher
import java.io.File

/**
 * Path logic behind the Files-app integration, kept free of Android classes so it can be unit
 * tested. A document id is `home` for the base directory and `home/<relative path>` below it.
 *
 * Everything that comes from another app (document ids, display names) is untrusted: ids that
 * contain `..`, empty segments or NUL never resolve, symlinks that lead out of the base directory
 * are invisible, and deleting never follows a symlink.
 */
internal object DocumentPaths {
    const val ROOT_ID = "home"
    private const val MAX_NAME_LENGTH = 255

    fun docIdFor(base: File, file: File): String {
        val basePath = base.absolutePath.trimEnd('/')
        val path = file.absolutePath
        if (path == basePath) return ROOT_ID
        require(path.startsWith("$basePath/")) { "$path is outside $basePath" }
        return "$ROOT_ID/${path.substring(basePath.length + 1)}"
    }

    /** The file a document id refers to, or null if the id is malformed or points outside [base]. */
    fun resolve(base: File, docId: String?): File? {
        if (docId == null) return null
        if (docId == ROOT_ID) return base
        if (!docId.startsWith("$ROOT_ID/")) return null
        val rel = docId.substring(ROOT_ID.length + 1)
        if (rel.isEmpty() || rel.indexOf('\u0000') >= 0) return null
        val segments = rel.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        val file = File(base, rel)
        return if (isInside(base, file)) file else null
    }

    /**
     * True if [file] really lives below [base] after resolving symlinks. A symlink pointing outside
     * (home/storage -> /sdcard) therefore does not count as inside.
     */
    fun isInside(base: File, file: File): Boolean {
        return try {
            val basePath = base.canonicalPath.trimEnd('/')
            val path = file.canonicalPath
            path == basePath || path.startsWith("$basePath/")
        } catch (_: Exception) {
            false
        }
    }

    /** Valid single path component for a new or renamed document. */
    fun isValidName(name: String?): Boolean {
        if (name.isNullOrEmpty() || name == "." || name == "..") return false
        if (name.length > MAX_NAME_LENGTH) return false
        return name.none { it == '/' || it == '\u0000' }
    }

    /** `name`, or `name (1)`, `name (2)` ... so that creating a document never overwrites one. */
    fun uniqueChild(parent: File, name: String): File {
        var candidate = File(parent, name)
        if (!exists(candidate)) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            candidate = File(parent, "$stem ($i)$ext")
            if (!exists(candidate)) return candidate
            i++
        }
    }

    private fun exists(f: File): Boolean = f.exists() || ElfLinkerPatcher.isSymlink(f)

    /**
     * Deletes [file]. A symlink is removed as a link and never followed, so deleting `storage`
     * cannot wipe what it points to.
     */
    fun deleteTree(file: File): Boolean {
        if (ElfLinkerPatcher.isSymlink(file)) return file.delete()
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    if (!deleteTree(child)) return false
                }
            }
        }
        return file.delete()
    }
}
