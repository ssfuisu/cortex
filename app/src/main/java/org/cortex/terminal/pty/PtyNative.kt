package org.cortex.terminal.pty

object PtyNative {
    init {
        System.loadLibrary("cortex-pty")
    }

    @JvmStatic
    external fun createPty(
        cmd: String,
        args: Array<String>,
        envVars: Array<String>,
        cwd: String?,
        rows: Int,
        cols: Int,
        widthPx: Int,
        heightPx: Int
    ): IntArray?

    @JvmStatic
    external fun setPtyWindowSize(
        masterFd: Int,
        rows: Int,
        cols: Int,
        widthPx: Int,
        heightPx: Int
    )

    @JvmStatic
    external fun waitForProcess(pid: Int): Int

    @JvmStatic
    external fun killProcess(pid: Int, sig: Int)

    @JvmStatic
    external fun closeFd(fd: Int)

    @JvmStatic
    external fun extractTar(tarPath: String, destDir: String): Int
}
