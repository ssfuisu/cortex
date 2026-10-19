package org.cortex.terminal.runtime

/**
 * Library directories of the rootfs, in the order ld.so searches them (LD_LIBRARY_PATH).
 *
 * ld.so tries every directory for every library a program needs, so the multiarch directories
 * that hold nearly all of them come first, as in glibc's own default order: the usual library is
 * then found on the first try instead of after two failed lookups. Keep in sync with
 * k_rootfs_lib_dirs in hook/paths.c and CORTEX_LD in su.sh (LibraryPathTest checks both).
 */
object LibraryPath {
    val DIRS = listOf(
        "/lib/aarch64-linux-gnu",
        "/usr/lib/aarch64-linux-gnu",
        "/lib/arm-linux-gnueabihf",
        "/usr/lib/arm-linux-gnueabihf",
        "/lib",
        "/usr/lib",
        "/usr/local/lib",
        "/usr/lib/systemd",
        "/lib/systemd",
        "/usr/lib/aarch64-linux-gnu/systemd",
        "/lib/aarch64-linux-gnu/systemd",
        "/usr/lib/arm-linux-gnueabihf/systemd",
        "/lib/arm-linux-gnueabihf/systemd"
    )

    /** The search path under [root], which may also be a shell expression such as `$CORTEX_ROOT`. */
    fun forRoot(root: String): String = DIRS.joinToString(":") { root + it }
}
