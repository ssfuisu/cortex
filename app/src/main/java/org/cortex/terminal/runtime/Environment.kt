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
            nativeLibs,
            "/system/lib64",
            "/system/lib"
        )
        val ldPathStr = ldLibraryPathList.joinToString(":")

        val hookLib = File(nativeLibs, "libcortex-hook.so")
        val preloadStr = if (hookLib.exists()) hookLib.absolutePath else ""

        val envList = mutableListOf(
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "HOME=$home",
            "CORTEX_ROOT=$root",
            "PREFIX=$root/usr",
            "TMPDIR=$tmp",
            "PATH=$pathStr",
            "LD_LIBRARY_PATH=$ldPathStr",
            "LANG=en_US.UTF-8",
            "LC_ALL=en_US.UTF-8",
            "USER=cortex",
            "LOGNAME=cortex",
            "HOSTNAME=cortex-android",
            "PS1=~ $ ",
            "ENV=$home/.profile"
        )

        if (preloadStr.isNotEmpty()) {
            envList.add("LD_PRELOAD=$preloadStr")
        }

        return envList.toTypedArray()
    }
}
