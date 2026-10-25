package org.cortex.terminal.runtime

/**
 * The system users and groups of Debian's base-passwd: daemon, man, www-data, nobody and the groups
 * adm, tty, shadow, staff, nogroup... Ubuntu's packages take them for granted: their scripts run
 * `chown redis:adm`, `setpriv --reuid=man` or `adduser --system --group www-data`. Cortex's first
 * image kept only root, daemon and cortex in /etc/passwd and /etc/group, so installing redis, nginx or
 * PostgreSQL (or any package with a man page, whose man-db trigger runs as man) failed in the middle
 * of dpkg's work.
 *
 * The lists the image was made from are in the image itself (/usr/share/base-passwd/*.master). What
 * is missing is added after what is there; nothing that is there is changed.
 */
internal object BaseUsers {

    /** [text] with the missing entries added, and the names of the entries that were. */
    class Merged(val text: String, val added: List<String>)

    private fun names(text: String): Set<String> = text.lineSequence()
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.substringBefore(':') }
        .toSet()

    /** The master list's entries as (name, fields after the password), the ones with [fields] fields or more. */
    private fun masterEntries(master: String, fields: Int): List<Pair<String, List<String>>> = master.lineSequence()
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.split(':') }
        .filter { it.size >= fields }
        .map { it[0] to it.drop(2) }
        .toList()

    private fun append(text: String, lines: List<String>): String {
        if (lines.isEmpty()) return text
        val base = if (text.isEmpty() || text.endsWith("\n")) text else text + "\n"
        return base + lines.joinToString("\n", postfix = "\n")
    }

    /** /etc/passwd with the master's users that are not in it (as `name:x:uid:gid:gecos:dir:shell`). */
    fun mergePasswd(passwd: String, master: String): Merged {
        val have = names(passwd)
        val missing = masterEntries(master, 7).filter { it.first !in have }
        return Merged(
            append(passwd, missing.map { (name, rest) -> "$name:x:" + rest.joinToString(":") }),
            missing.map { it.first }
        )
    }

    /** /etc/group with the master's groups that are not in it (as `name:x:gid:members`). */
    fun mergeGroup(group: String, master: String): Merged {
        val have = names(group)
        val missing = masterEntries(master, 4).filter { it.first !in have }
        return Merged(
            append(group, missing.map { (name, rest) -> "$name:x:" + rest.joinToString(":") }),
            missing.map { it.first }
        )
    }

    /** [text] (/etc/shadow or /etc/gshadow) with a locked entry made by [entry] for each of [users] without one. */
    fun mergeShadow(text: String, users: List<String>, entry: (String) -> String): String {
        val have = names(text)
        return append(text, users.filter { it !in have }.map(entry))
    }

    fun shadowEntry(name: String) = "$name:*:19700:0:99999:7:::"

    fun gshadowEntry(name: String) = "$name:*::"
}
