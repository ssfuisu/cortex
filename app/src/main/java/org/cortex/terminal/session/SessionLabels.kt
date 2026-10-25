package org.cortex.terminal.session

import android.content.Context
import org.cortex.terminal.runtime.Environment

/**
 * What a session is called where it is listed (the tabs, the drawer): the directory its shell is
 * in, as the shell sees it ("~/project", not the app's data directory the rootfs lives in).
 */
object SessionLabels {

    class Where(
        /** The directory as a prompt shows it: "~" for the home directory, "~/project", "/etc". */
        val path: String,
        /** The last part of it, for a tab: "~", "project", "/". */
        val tab: String
    )

    /** [real] (a directory as Android sees it) as the shell sees it, with [rootPath] being "/". */
    fun virtualPath(real: String?, rootPath: String): String? {
        if (real == null) return null
        val root = rootPath.trimEnd('/')
        return when {
            real == root -> "/"
            real.startsWith("$root/") -> real.substring(root.length)
            else -> real // outside the rootfs, such as /sdcard
        }
    }

    /** A path as a prompt shows it: the home directory is "~". */
    fun shortPath(path: String, home: String): String {
        val h = home.trimEnd('/')
        return when {
            h.isNotEmpty() && path == h -> "~"
            h.isNotEmpty() && path.startsWith("$h/") -> "~" + path.substring(h.length)
            else -> path
        }
    }

    /** The name of a tab: the last directory of the path, "~" for home and "/" for the root. */
    fun tabLabel(path: String, home: String): String {
        val short = shortPath(path, home)
        if (short == "~" || short == "/") return short
        val last = short.trimEnd('/').substringAfterLast('/')
        return last.ifEmpty { "/" }
    }

    /** Where [session]'s shell is now, or null when that is not known (a root session, a stopped one). */
    fun where(context: Context, session: TerminalSession): Where? {
        val real = session.currentDirectory() ?: return null
        val root = Environment.getCortexRoot(context).path
        val home = virtualPath(Environment.getHomeDir(context).path, root) ?: return null
        val path = virtualPath(real, root) ?: return null
        return Where(shortPath(path, home), tabLabel(path, home))
    }
}
