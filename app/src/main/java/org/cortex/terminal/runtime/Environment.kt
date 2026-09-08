package org.cortex.terminal.runtime

import android.content.Context
import java.io.File

object Environment {
    fun getCortexRoot(context: Context): File {
        return File(context.filesDir, "cortex")
    }

    fun getHomeDir(context: Context): File {
        return File(getCortexRoot(context), "home")
    }

    fun getUsrDir(context: Context): File {
        return File(getCortexRoot(context), "usr")
    }

    fun getTmpDir(context: Context): File {
        return File(getCortexRoot(context), "tmp")
    }

    fun getNativeLibDir(context: Context): File {
        return File(context.applicationInfo.nativeLibraryDir)
    }

    fun buildEnvironment(context: Context): Array<String> {
        val root = getCortexRoot(context).absolutePath
        val home = getHomeDir(context).absolutePath
        val tmp = getTmpDir(context).absolutePath
        val nativeLibs = getNativeLibDir(context).absolutePath

        val pathList = listOf(
            "$home/.local/bin",
            "$root/bin",
            "$root/usr/bin",
            "$root/usr/local/bin",
            "$root/usr/sbin",
            "$root/sbin",
            "/system/bin",
            "/system/xbin"
        )
        val pathStr = pathList.joinToString(":")

        val ldLibraryPathList = listOf(
            "$root/lib",
            "$root/usr/lib",
            "$root/lib/aarch64-linux-gnu",
            "$root/usr/lib/aarch64-linux-gnu",
            "$root/lib/arm-linux-gnueabihf",
            "$root/usr/lib/arm-linux-gnueabihf",
            "$root/usr/local/lib"
        )
        val ldPathStr = ldLibraryPathList.joinToString(":")

        val glibcHooks = listOf(
            File(root, "usr/lib/libcortex-hook.so"),
            File(root, "lib/libcortex-hook.so")
        )
        val hookLib = glibcHooks.firstOrNull { it.exists() }
        val preloadStr = hookLib?.absolutePath ?: ""

        val tz = try {
            java.util.TimeZone.getDefault()
        } catch (e: Exception) {
            null
        }
        val tzId = try { tz?.id ?: "UTC" } catch (e: Exception) { "UTC" }
        val now = System.currentTimeMillis()
        val offsetMillis = tz?.getOffset(now) ?: 0
        val totalMinutes = offsetMillis / 60000
        val posixSign = if (totalMinutes >= 0) "-" else "+"
        val absMinutes = Math.abs(totalMinutes)
        val hours = (absMinutes / 60).toInt()
        val mins = (absMinutes % 60).toInt()
        val shortName = try {
            val name = tz?.getDisplayName(tz.inDaylightTime(java.util.Date(now)), java.util.TimeZone.SHORT, java.util.Locale.US)
            if (!name.isNullOrEmpty() && name.all { it.isLetter() }) name else "GMT"
        } catch (e: Exception) {
            "GMT"
        }
        val stdName = when {
            shortName.length >= 3 && shortName.all { it.isLetter() } -> shortName
            totalMinutes >= 0 -> "<+%02d>".format(hours)
            else -> "<-%02d>".format(hours)
        }
        val posixTz = if (mins != 0) {
            "%s%s%d:%02d".format(stdName, posixSign, hours, mins)
        } else {
            "%s%s%d".format(stdName, posixSign, hours)
        }

        val certFile = File(root, "etc/ssl/certs/ca-certificates.crt").absolutePath
        val certDir = "${File(root, "etc/ssl/certs").absolutePath}:/system/etc/security/cacerts"

        val envList = mutableListOf(
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "HOME=$home",
            "CORTEX_ROOT=$root",
            "PREFIX=$root/usr",
            "TMPDIR=$tmp",
            "PATH=$pathStr",
            "LD_LIBRARY_PATH=$ldPathStr",
            "GLIBC_TUNABLES=glibc.pthread.rseq=0",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "LOCPATH=$root/usr/lib/locale",
            "USER=cortex",
            "LOGNAME=cortex",
            "HOSTNAME=cortex-android",
            "PS1=~ $ ",
            "ENV=$home/.profile",
            "DPKG_DEB_THREADS_MAX=1",
            "XZ_OPT=-T1",
            "XZ_DEFAULTS=-T1",
            "DEBIAN_FRONTEND=noninteractive",
            "DEBCONF_FRONTEND=noninteractive",
            "DEBCONF_NONINTERACTIVE_SEEN=true",
            "SSL_CERT_FILE=$certFile",
            "SSL_CERT_DIR=$certDir",
            "CURL_CA_BUNDLE=$certFile",
            "NODE_EXTRA_CA_CERTS=$certFile",
            "REQUESTS_CA_BUNDLE=$certFile",
            "TZDIR=$root/usr/share/zoneinfo",
            "TZ=$posixTz"
        )

        if (preloadStr.isNotEmpty()) {
            envList.add("LD_PRELOAD=$preloadStr")
        }

        return envList.toTypedArray()
    }
}
