package org.cortex.terminal.runtime

import android.content.Context
import android.os.Process
import androidx.preference.PreferenceManager

/**
 * The user the terminal's sessions run as. Like a distro installed on a PC, the shell is an ordinary
 * user (cortex by default, `cortex@localhost` in purple) and root's work goes through `sudo`.
 *
 * The kernel knows one user, the app's: this user is that one, under a name of its own in the
 * rootfs' /etc/passwd, and the preloaded library tells the programs the truth about it (the
 * shell's environment has CORTEX_BECOME=app). `sudo` and `su` make a program root as the library
 * presents it. Settings › "Sessions run as root" brings back the earlier behaviour.
 */
object UserAccount {
    const val PREF_NAME = "user_name"
    const val PREF_SESSIONS_AS_ROOT = "sessions_as_root"
    const val DEFAULT_NAME = "cortex"

    private val VALID_NAME = Regex("^[a-z_][a-z0-9_-]{0,30}$")

    /** Names the passwd file already uses for something else */
    private val RESERVED = setOf(
        "root", "daemon", "bin", "sys", "sync", "games", "man", "lp", "mail", "news", "uucp", "proxy",
        "www-data", "backup", "list", "irc", "gnats", "nobody", "systemd-network", "systemd-resolve",
        "messagebus", "sshd", "_ssh", "_apt", "postgres", "mysql", "redis", "polkitd", "syslog"
    )

    fun isValidName(name: String): Boolean = VALID_NAME.matches(name) && name !in RESERVED

    fun name(context: Context): String {
        val stored = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_NAME, null)
        return stored?.takeIf { isValidName(it) } ?: DEFAULT_NAME
    }

    fun setName(context: Context, name: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREF_NAME, name).apply()
    }

    fun sessionsAsRoot(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_SESSIONS_AS_ROOT, false)

    const val PASSWORD_FILE = "etc/cortex/sudo-password"
    const val MIN_PASSWORD = 4

    /**
     * The password sudo asks for (su.sh): "salt sha256(salt + password)" in /etc/cortex/sudo-password.
     * An empty one removes it, and sudo then asks for none.
     */
    fun setSudoPassword(root: java.io.File, password: String) {
        val file = java.io.File(root, PASSWORD_FILE)
        if (password.isEmpty()) {
            file.delete()
            return
        }
        val saltBytes = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val salt = saltBytes.joinToString("") { "%02x".format(it) }
        FileInstaller.install(file, "$salt ${passwordHash(salt, password)}\n", 384) // 0600
    }

    fun passwordHash(salt: String, password: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest((salt + password).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** The app's own user and group: the kernel's ids of everything the terminal runs */
    fun uid(): Int = Process.myUid()

    fun gid(): Int = Process.myUid()

    /**
     * /etc/passwd with the user's line: the app's uid, [name], the shared home. It stays where it is
     * when it is there already, so that a second run changes nothing.
     */
    fun withPasswdEntry(passwd: String, name: String, home: String): String {
        val uid = uid()
        val line = "$name:x:$uid:${gid()}:Cortex:$home:/bin/bash"
        val out = mutableListOf<String>()
        var placed = false
        for (l in passwd.lineSequence()) {
            if (l.isEmpty()) continue
            val f = l.split(':')
            if (f.size >= 3 && f[0] == name) {
                if (!placed) out += line
                placed = true
                continue
            }
            // Earlier releases had "cortex:x:0:0:" (cortex was root); a renamed user leaves its old line
            if (f.size >= 3 && ((f[2] == uid.toString() && f[0] != "root") || (f[0] == DEFAULT_NAME && f[2] == "0"))) continue
            out += l
        }
        if (!placed) out += line
        return out.joinToString("\n", postfix = "\n")
    }

    /** /etc/group with the user's own group, and the user in sudo, wheel, adm and users (a sudo group if missing) */
    fun withGroupEntries(group: String, name: String): String {
        val gid = gid()
        val own = "$name:x:$gid:"
        val out = mutableListOf<String>()
        var placed = false
        for (l in group.lineSequence()) {
            if (l.isEmpty()) continue
            val f = l.split(':').toMutableList()
            if (f.size >= 3 && f[0] == name) {
                if (!placed) out += own
                placed = true
                continue
            }
            if (f.size >= 3 && ((f[2] == gid.toString() && f[0] != "root") || (f[0] == DEFAULT_NAME && f[2] == "0"))) continue
            if (f.size >= 4 && f[0] in setOf("sudo", "wheel", "adm", "users")) {
                val members = f[3].split(',').filter { it.isNotEmpty() && it != DEFAULT_NAME || it == name }.toMutableList()
                if (name !in members) members += name
                f[3] = members.joinToString(",")
                out += f.joinToString(":")
            } else {
                out += l
            }
        }
        if (out.none { it.startsWith("sudo:") }) out += "sudo:x:27:$name"
        if (!placed) out += own
        return out.joinToString("\n", postfix = "\n")
    }

}
