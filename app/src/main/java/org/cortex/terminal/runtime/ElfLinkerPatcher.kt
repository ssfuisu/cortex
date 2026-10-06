package org.cortex.terminal.runtime

import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

object ElfLinkerPatcher {
    private const val TAG = "ElfLinkerPatcher"

    private val LINKER_CANDIDATES = listOf(
        "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        "usr/lib/aarch64-linux-gnu/ld-2.39.so",
        "lib/ld-linux-aarch64.so.1",
        "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        "usr/lib/ld-linux-aarch64.so.1",
        "usr/lib64/ld-linux-aarch64.so.1",
        "lib64/ld-linux-aarch64.so.1",
        "usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3",
        "usr/lib/arm-linux-gnueabihf/ld-2.39.so",
        "lib/ld-linux-armhf.so.3",
        "lib/arm-linux-gnueabihf/ld-linux-armhf.so.3",
        "usr/lib/ld-linux-armhf.so.3"
    )

    fun hasDynamicLinker(root: File): Boolean {
        return LINKER_CANDIDATES.any { File(root, it).exists() }
    }

    fun findDynamicLinker(root: File): File? {
        for (cand in LINKER_CANDIDATES) {
            val f = File(root, cand)
            if (f.exists() && (f.isFile || Files.isSymbolicLink(f.toPath()))) {
                return f
            }
        }
        return null
    }

    fun ensureDynamicLinkerSymlinks(root: File) {
        try {
            // Check arm64
            val ld64Real = File(root, "usr/lib/aarch64-linux-gnu/ld-2.39.so")
            val ld64Link = File(root, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
            if (ld64Real.exists()) {
                ld64Real.setExecutable(true, true)
                ld64Real.setReadable(true, true)
                ld64Real.setWritable(true, true)
                try { android.system.Os.chmod(ld64Real.absolutePath, 448) } catch (e: Exception) {}
                if (!ld64Link.exists()) {
                    try {
                        android.system.Os.symlink("ld-2.39.so", ld64Link.absolutePath)
                    } catch (e: Exception) {
                        try { ld64Real.copyTo(ld64Link, overwrite = true) } catch (e2: Exception) {}
                    }
                }
                ld64Link.setExecutable(true, true)
                ld64Link.setReadable(true, true)
                ld64Link.setWritable(true, true)
                try { android.system.Os.chmod(ld64Link.absolutePath, 448) } catch (e: Exception) {}
            }

            // Check arm32
            val ld32Real = File(root, "usr/lib/arm-linux-gnueabihf/ld-2.39.so")
            val ld32Link = File(root, "usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3")
            if (ld32Real.exists()) {
                ld32Real.setExecutable(true, true)
                ld32Real.setReadable(true, true)
                ld32Real.setWritable(true, true)
                try { android.system.Os.chmod(ld32Real.absolutePath, 448) } catch (e: Exception) {}
                if (!ld32Link.exists()) {
                    try {
                        android.system.Os.symlink("ld-2.39.so", ld32Link.absolutePath)
                    } catch (e: Exception) {
                        try { ld32Real.copyTo(ld32Link, overwrite = true) } catch (e2: Exception) {}
                    }
                }
                ld32Link.setExecutable(true, true)
                ld32Link.setReadable(true, true)
                ld32Link.setWritable(true, true)
                try { android.system.Os.chmod(ld32Link.absolutePath, 448) } catch (e: Exception) {}
            }

            // Ensure /lib has a direct link or copy to dynamic linker if /lib is not already symlinked
            val libDir = File(root, "lib")
            if (libDir.exists() && !Files.isSymbolicLink(libDir.toPath())) {
                val direct64 = File(libDir, "ld-linux-aarch64.so.1")
                if (!direct64.exists()) {
                    val src = if (ld64Link.exists()) ld64Link else if (ld64Real.exists()) ld64Real else null
                    if (src != null) {
                        try {
                            android.system.Os.symlink(src.absolutePath, direct64.absolutePath)
                        } catch (e: Exception) {
                            try { src.copyTo(direct64, overwrite = true) } catch (e2: Exception) {}
                        }
                        direct64.setExecutable(true, true)
                        direct64.setReadable(true, true)
                        direct64.setWritable(true, true)
                        try { android.system.Os.chmod(direct64.absolutePath, 448) } catch (e: Exception) {}
                    }
                }
                val direct32 = File(libDir, "ld-linux-armhf.so.3")
                if (!direct32.exists()) {
                    val src = if (ld32Link.exists()) ld32Link else if (ld32Real.exists()) ld32Real else null
                    if (src != null) {
                        try {
                            android.system.Os.symlink(src.absolutePath, direct32.absolutePath)
                        } catch (e: Exception) {
                            try { src.copyTo(direct32, overwrite = true) } catch (e2: Exception) {}
                        }
                        direct32.setExecutable(true, true)
                        direct32.setReadable(true, true)
                        direct32.setWritable(true, true)
                        try { android.system.Os.chmod(direct32.absolutePath, 448) } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure dynamic linker symlinks", e)
        }
    }

    fun patchAllDynamicLinkers(root: File) {
        if (!root.exists() || !root.isDirectory) return
        try {
            root.walkTopDown().forEach { file ->
                val name = file.name
                if (file.isFile && (name.startsWith("ld-linux") || name.startsWith("libc.so") || name.startsWith("libc-"))) {
                    val target = if (Files.isSymbolicLink(file.toPath())) {
                        try { file.canonicalFile } catch (e: Exception) { file }
                    } else {
                        file
                    }
                    if (target.exists() && target.isFile) {
                        patchDynamicLinker(target)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error walking root to patch dynamic linkers and libc", e)
        }
    }

    fun fixAbsoluteSymlinks(root: File) {
        if (!root.exists() || !root.isDirectory) return
        try {
            val candidateDirs = listOf(
                File(root, "etc/alternatives"),
                File(root, "usr/bin"),
                File(root, "usr/sbin"),
                File(root, "bin"),
                File(root, "sbin")
            )
            for (dir in candidateDirs) {
                if (!dir.exists() || !dir.isDirectory) continue
                // Crucial: Skip directories that are themselves symlinks
                if (Files.isSymbolicLink(dir.toPath())) continue

                dir.listFiles()?.forEach { file ->
                    try {
                        val path = file.toPath()
                        if (Files.isSymbolicLink(path)) {
                            val target = Files.readSymbolicLink(path).toString()
                            if (target.startsWith("/")) {
                                val targetClean = target.trimStart('/')
                                val targetInRoot = File(root, targetClean)
                                val relTarget = file.parentFile?.toPath()?.relativize(targetInRoot.toPath())?.toString()
                                if (relTarget != null) {
                                    val tmpLink = File(file.parentFile, "${file.name}.ctx_link_tmp")
                                    val tmpPath = tmpLink.toPath()
                                    Files.deleteIfExists(tmpPath)
                                    Files.createSymbolicLink(tmpPath, Paths.get(relTarget))
                                    try {
                                        Files.move(
                                            tmpPath,
                                            path,
                                            StandardCopyOption.REPLACE_EXISTING,
                                            StandardCopyOption.ATOMIC_MOVE
                                        )
                                    } catch (e: Exception) {
                                        Files.move(
                                            tmpPath,
                                            path,
                                            StandardCopyOption.REPLACE_EXISTING
                                        )
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // ignore individual link error
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in fixAbsoluteSymlinks", e)
        }
    }

    fun patchDynamicLinker(file: File) {
        if (!file.exists() || !file.isFile) {
            return
        }
        try {
            val bytes = file.readBytes()
            var modified = false

            // AArch64 syscall patterns
            val svcAarch64 = byteArrayOf(0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0xd4.toByte())
            val nopAarch64 = byteArrayOf(0x1f.toByte(), 0x20.toByte(), 0x03.toByte(), 0xd5.toByte())
            val movEnosysAarch64 = byteArrayOf(0xa0.toByte(), 0x04.toByte(), 0x80.toByte(), 0x92.toByte()) // mov x0, #-38

            // mov x8, #0x63 (syscall 99 set_robust_list)
            val movX8Syscall99 = byteArrayOf(0x68.toByte(), 0x0c.toByte(), 0x80.toByte(), 0xd2.toByte())
            // mov x8, #0x1b3 (syscall 435 clone3)
            val movX8Syscall435 = byteArrayOf(0x68.toByte(), 0x36.toByte(), 0x80.toByte(), 0xd2.toByte())
            // mov x8, #0x125 (syscall 293 rseq)
            val movX8Syscall293 = byteArrayOf(0xa8.toByte(), 0x24.toByte(), 0x80.toByte(), 0xd2.toByte())

            var pos = 0
            while (pos <= bytes.size - 4) {
                val isSyscall99 = (bytes[pos] == movX8Syscall99[0] && bytes[pos + 1] == movX8Syscall99[1] &&
                                   bytes[pos + 2] == movX8Syscall99[2] && bytes[pos + 3] == movX8Syscall99[3])
                val isSyscall435 = (bytes[pos] == movX8Syscall435[0] && bytes[pos + 1] == movX8Syscall435[1] &&
                                    bytes[pos + 2] == movX8Syscall435[2] && bytes[pos + 3] == movX8Syscall435[3])
                val isSyscall293 = (bytes[pos] == movX8Syscall293[0] && bytes[pos + 1] == movX8Syscall293[1] &&
                                    bytes[pos + 2] == movX8Syscall293[2] && bytes[pos + 3] == movX8Syscall293[3])

                if (isSyscall99 || isSyscall435 || isSyscall293) {
                    val replacement = if (isSyscall99) nopAarch64 else movEnosysAarch64
                    val scName = if (isSyscall99) "99 set_robust_list" else if (isSyscall435) "435 clone3" else "293 rseq"
                    val searchEnd = minOf(bytes.size - 4, pos + 64)
                    for (i in (pos + 4)..searchEnd step 4) {
                        if (bytes[i] == svcAarch64[0] &&
                            bytes[i + 1] == svcAarch64[1] &&
                            bytes[i + 2] == svcAarch64[2] &&
                            bytes[i + 3] == svcAarch64[3]) {

                            replacement.copyInto(bytes, destinationOffset = i)
                            modified = true
                            Log.i(TAG, "Patched syscall $scName svc #0 at 0x${Integer.toHexString(i)} in ${file.name}")
                            break
                        }
                    }
                }
                pos += 4
            }

            if (modified) {
                val parent = file.parentFile ?: return
                val tmp = File(parent, "${file.name}.ctx_patch_tmp")
                tmp.outputStream().use { it.write(bytes) }
                tmp.setExecutable(true, true)
                tmp.setReadable(true, true)
                tmp.setWritable(true, true)
                try { android.system.Os.chmod(tmp.absolutePath, 448) } catch (e: Exception) {}
                try {
                    Files.move(
                        tmp.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (e: Exception) {
                    Files.move(
                        tmp.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
                file.setExecutable(true, true)
                file.setReadable(true, true)
                file.setWritable(true, true)
                try { android.system.Os.chmod(file.absolutePath, 448) } catch (e: Exception) {}
                Log.i(TAG, "Successfully wrote patched linker atomically: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to patch dynamic linker: ${file.absolutePath}", e)
        }
    }
}
