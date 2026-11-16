package org.cortex.terminal.runtime

import java.io.File

/** The package managers of the distributions Cortex installs, and the program each one is */
enum class PackageManager(val id: String, val program: String) {
    APT("apt", "usr/bin/apt"),
    PACMAN("pacman", "usr/bin/pacman"),
    DNF("dnf", "usr/bin/dnf"),
    ZYPPER("zypper", "usr/bin/zypper"),
    XBPS("xbps", "usr/bin/xbps-install");

    companion object {
        fun of(id: String?): PackageManager? = values().firstOrNull { it.id == id }
    }
}

/**
 * The distribution in a rootfs. The images Cortex downloads say what they are in /etc/cortex/distro
 * (scripts/build-distro-rootfs.py); a rootfs without that file is the app's own Ubuntu 24.04, which
 * every installation before 1.28.1 has.
 */
data class InstalledDistro(val id: String, val name: String, val version: String, val pm: PackageManager) {
    /** The Ubuntu inside the APK: its apt sources, mirror choice and C development overlay are Cortex's own */
    val isBundled: Boolean get() = id == Distro.BUNDLED_ID
    val usesApt: Boolean get() = pm == PackageManager.APT
    val label: String get() = if (version.isEmpty() || version == "rolling") name else "$name $version"
}

object Distro {
    const val BUNDLED_ID = "ubuntu-24.04"
    const val MARKER = "etc/cortex/distro"

    val BUNDLED = InstalledDistro(BUNDLED_ID, "Ubuntu", "24.04 LTS", PackageManager.APT)

    @Volatile private var cached: Pair<String, InstalledDistro>? = null

    fun of(root: File): InstalledDistro {
        val marker = File(root, MARKER)
        val key = "${marker.path}:${marker.lastModified()}:${marker.length()}"
        cached?.let { if (it.first == key) return it.second }
        val parsed = parse(try { marker.takeIf { it.isFile }?.readText() } catch (e: Exception) { null })
        cached = key to parsed
        return parsed
    }

    /** The KEY=value lines of /etc/cortex/distro; a missing or unreadable file is the bundled Ubuntu */
    fun parse(text: String?): InstalledDistro {
        if (text.isNullOrBlank()) return BUNDLED
        val values = text.lineSequence()
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() } }
            .toMap()
        val id = values["ID"]?.takeIf { it.isNotEmpty() } ?: return BUNDLED
        val pm = PackageManager.of(values["PM"]) ?: PackageManager.APT
        return InstalledDistro(id, values["NAME"] ?: id, values["VERSION"] ?: "", pm)
    }

    /** /etc/cortex/distro for the bundled Ubuntu, so the file says what every rootfs is */
    fun markerText(d: InstalledDistro): String =
        "ID=${d.id}\nNAME=${d.name}\nVERSION=${d.version}\nPM=${d.pm.id}\n"
}
