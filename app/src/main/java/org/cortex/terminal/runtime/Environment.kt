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
            "SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt",
            "CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt"
        )

        if (preloadStr.isNotEmpty()) {
            envList.add("LD_PRELOAD=$preloadStr")
        }

        return envList.toTypedArray()
    }
}
