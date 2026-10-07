package org.cortex.terminal.runtime

import android.annotation.SuppressLint
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File

object ElfLinkerPatcher {
    private const val TAG = "ElfLinkerPatcher"

    private const val ELFCLASS32 = 1
    private const val ELFCLASS64 = 2
    private const val ELFDATA2LSB = 1
    private const val EM_ARM = 40
    private const val EM_AARCH64 = 183
    private const val PT_LOAD = 1L
    private const val PF_X = 0x1L

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

    @SuppressLint("NewApi")
    internal fun isSymlink(file: File): Boolean {
        if (Build.VERSION.SDK_INT > 0) {
            return try {
                val st = Os.lstat(file.absolutePath)
                if (st != null) {
                    OsConstants.S_ISLNK(st.st_mode)
                } else {
                    java.nio.file.Files.isSymbolicLink(file.toPath())
                }
            } catch (_: ErrnoException) {
                false
            } catch (_: Throwable) {
                try {
                    java.nio.file.Files.isSymbolicLink(file.toPath())
                } catch (_: Throwable) {
                    false
                }
            }
        }
        return try {
            java.nio.file.Files.isSymbolicLink(file.toPath())
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("NewApi")
    internal fun readSymlink(file: File): String? {
        if (Build.VERSION.SDK_INT > 0) {
            return try {
                val target = Os.readlink(file.absolutePath)
                target ?: java.nio.file.Files.readSymbolicLink(file.toPath()).toString()
            } catch (_: ErrnoException) {
                null
            } catch (_: Throwable) {
                try {
                    java.nio.file.Files.readSymbolicLink(file.toPath()).toString()
                } catch (_: Throwable) {
                    null
                }
            }
        }
        return try {
            java.nio.file.Files.readSymbolicLink(file.toPath()).toString()
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("NewApi")
    internal fun createSymlink(target: String, linkFile: File): Boolean {
        if (Build.VERSION.SDK_INT > 0) {
            return try {
                Os.symlink(target, linkFile.absolutePath)
                true
            } catch (_: ErrnoException) {
                false
            } catch (_: Throwable) {
                try {
                    java.nio.file.Files.createSymbolicLink(
                        linkFile.toPath(),
                        java.nio.file.Paths.get(target)
                    )
                    true
                } catch (_: Throwable) {
                    false
                }
            }
        }
        return try {
            java.nio.file.Files.createSymbolicLink(
                linkFile.toPath(),
                java.nio.file.Paths.get(target)
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("NewApi")
    internal fun deleteIfExists(file: File): Boolean {
        if (Build.VERSION.SDK_INT > 0) {
            return try {
                Os.remove(file.absolutePath)
                true
            } catch (_: ErrnoException) {
                false
            } catch (_: Throwable) {
                try {
                    java.nio.file.Files.deleteIfExists(file.toPath())
                } catch (_: Throwable) {
                    file.delete()
                }
            }
        }
        return try {
            java.nio.file.Files.deleteIfExists(file.toPath())
        } catch (_: Throwable) {
            file.delete()
        }
    }

    @SuppressLint("NewApi")
    internal fun atomicReplace(src: File, dst: File): Boolean {
        if (Build.VERSION.SDK_INT > 0) {
            return try {
                Os.rename(src.absolutePath, dst.absolutePath)
                true
            } catch (_: ErrnoException) {
                try {
                    deleteIfExists(dst)
                    if (src.renameTo(dst)) {
                        true
                    } else {
                        src.copyTo(dst, overwrite = true)
                        src.delete()
                        true
                    }
                } catch (_: Throwable) {
                    false
                }
            } catch (_: Throwable) {
                moveOnHostFallback(src, dst)
            }
        }
        return moveOnHostFallback(src, dst)
    }

    @SuppressLint("NewApi")
    private fun moveOnHostFallback(src: File, dst: File): Boolean {
        return try {
            try {
                java.nio.file.Files.move(
                    src.toPath(),
                    dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                java.nio.file.Files.move(
                    src.toPath(),
                    dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun isContainedInRoot(candidate: File, rootCanonicalPath: String): Boolean {
        return try {
            val candidateCanonical = candidate.canonicalFile.path
            val normalizedRoot = rootCanonicalPath.trimEnd(File.separatorChar, '/', '\\')
            val normalizedCand = candidateCanonical.trimEnd(File.separatorChar, '/', '\\')
            normalizedCand == normalizedRoot ||
                normalizedCand.startsWith(normalizedRoot + File.separator) ||
                normalizedCand.startsWith("$normalizedRoot/")
        } catch (_: Exception) {
            false
        }
    }

    fun hasDynamicLinker(root: File): Boolean {
        return LINKER_CANDIDATES.any { File(root, it).exists() }
    }

    fun findDynamicLinker(root: File): File? {
        for (cand in LINKER_CANDIDATES) {
            val f = File(root, cand)
            if (f.exists() && (f.isFile || isSymlink(f))) {
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
                try { Os.chmod(ld64Real.absolutePath, 448) } catch (_: Exception) {}
                if (!ld64Link.exists() && !isSymlink(ld64Link)) {
                    if (!createSymlink("ld-2.39.so", ld64Link)) {
                        try { ld64Real.copyTo(ld64Link, overwrite = true) } catch (_: Exception) {}
                    }
                }
                ld64Link.setExecutable(true, true)
                ld64Link.setReadable(true, true)
                ld64Link.setWritable(true, true)
                try { Os.chmod(ld64Link.absolutePath, 448) } catch (_: Exception) {}
            }

            // Check arm32
            val ld32Real = File(root, "usr/lib/arm-linux-gnueabihf/ld-2.39.so")
            val ld32Link = File(root, "usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3")
            if (ld32Real.exists()) {
                ld32Real.setExecutable(true, true)
                ld32Real.setReadable(true, true)
                ld32Real.setWritable(true, true)
                try { Os.chmod(ld32Real.absolutePath, 448) } catch (_: Exception) {}
                if (!ld32Link.exists() && !isSymlink(ld32Link)) {
                    if (!createSymlink("ld-2.39.so", ld32Link)) {
                        try { ld32Real.copyTo(ld32Link, overwrite = true) } catch (_: Exception) {}
                    }
                }
                ld32Link.setExecutable(true, true)
                ld32Link.setReadable(true, true)
                ld32Link.setWritable(true, true)
                try { Os.chmod(ld32Link.absolutePath, 448) } catch (_: Exception) {}
            }

            // Ensure /lib has a direct link or copy to dynamic linker if /lib is not already symlinked
            val libDir = File(root, "lib")
            if (libDir.exists() && !isSymlink(libDir)) {
                val direct64 = File(libDir, "ld-linux-aarch64.so.1")
                if (!direct64.exists() && !isSymlink(direct64)) {
                    val src = if (ld64Link.exists()) ld64Link else if (ld64Real.exists()) ld64Real else null
                    if (src != null) {
                        if (!createSymlink(src.absolutePath, direct64)) {
                            try { src.copyTo(direct64, overwrite = true) } catch (_: Exception) {}
                        }
                        direct64.setExecutable(true, true)
                        direct64.setReadable(true, true)
                        direct64.setWritable(true, true)
                        try { Os.chmod(direct64.absolutePath, 448) } catch (_: Exception) {}
                    }
                }
                val direct32 = File(libDir, "ld-linux-armhf.so.3")
                if (!direct32.exists() && !isSymlink(direct32)) {
                    val src = if (ld32Link.exists()) ld32Link else if (ld32Real.exists()) ld32Real else null
                    if (src != null) {
                        if (!createSymlink(src.absolutePath, direct32)) {
                            try { src.copyTo(direct32, overwrite = true) } catch (_: Exception) {}
                        }
                        direct32.setExecutable(true, true)
                        direct32.setReadable(true, true)
                        direct32.setWritable(true, true)
                        try { Os.chmod(direct32.absolutePath, 448) } catch (_: Exception) {}
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
            val rootCanonicalPath = root.canonicalFile.path
            root.walkTopDown()
                .onEnter { dir ->
                    dir == root || isContainedInRoot(dir, rootCanonicalPath)
                }
                .forEach { file ->
                    val name = file.name
                    if (name.startsWith("ld-linux") || name.startsWith("ld-2.") ||
                        name.startsWith("libc.so") || name.startsWith("libc-")
                    ) {
                        val target = try {
                            file.canonicalFile
                        } catch (_: Exception) {
                            null
                        }
                        if (target != null &&
                            isContainedInRoot(target, rootCanonicalPath) &&
                            target.exists() &&
                            target.isFile &&
                            !isSymlink(target)
                        ) {
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
            val rootCanonicalPath = root.canonicalFile.path
            val candidateDirs = listOf(
                File(root, "etc/alternatives"),
                File(root, "usr/bin"),
                File(root, "usr/sbin"),
                File(root, "bin"),
                File(root, "sbin")
            )
            for (dir in candidateDirs) {
                if (!dir.exists() || !dir.isDirectory) continue
                // Skip directories that are themselves symlinks or resolve outside root
                if (isSymlink(dir) || !isContainedInRoot(dir, rootCanonicalPath)) continue

                dir.listFiles()?.forEach { file ->
                    try {
                        if (isSymlink(file)) {
                            val target = readSymlink(file)
                            if (target != null && target.startsWith("/")) {
                                val targetClean = target.trimStart('/')
                                val targetInRoot = File(root, targetClean)
                                if (!isContainedInRoot(File(rootCanonicalPath, targetClean), rootCanonicalPath)) {
                                    return@forEach
                                }
                                val parentDir = file.parentFile ?: return@forEach
                                val relTarget = targetInRoot.absoluteFile
                                    .toRelativeString(parentDir.absoluteFile)
                                    .replace('\\', '/')
                                if (relTarget.isNotEmpty()) {
                                    val tmpLink = File(parentDir, "${file.name}.ctx_link_tmp")
                                    deleteIfExists(tmpLink)
                                    if (createSymlink(relTarget, tmpLink)) {
                                        if (!atomicReplace(tmpLink, file)) {
                                            deleteIfExists(tmpLink)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // ignore individual link error
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in fixAbsoluteSymlinks", e)
        }
    }

    private fun readU16Le(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU32Le(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }

    private fun readU64Le(bytes: ByteArray, offset: Int): Long {
        return readU32Le(bytes, offset) or (readU32Le(bytes, offset + 4) shl 32)
    }

    private fun matchesWord(bytes: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        return bytes[offset] == pattern[0] &&
            bytes[offset + 1] == pattern[1] &&
            bytes[offset + 2] == pattern[2] &&
            bytes[offset + 3] == pattern[3]
    }

    private fun matchesHalfword(bytes: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        return bytes[offset] == pattern[0] &&
            bytes[offset + 1] == pattern[1]
    }

    private fun decodeThumbBlTarget(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        val hw1 = readU16Le(bytes, offset)
        val hw2 = readU16Le(bytes, offset + 2)
        if ((hw1 and 0xf800) != 0xf000 || (hw2 and 0xd000) != 0xd000) return null
        val s = (hw1 ushr 10) and 1
        val imm10 = hw1 and 0x3ff
        val j1 = (hw2 ushr 13) and 1
        val j2 = (hw2 ushr 11) and 1
        val imm11 = hw2 and 0x7ff
        val i1 = (j1 xor s) xor 1
        val i2 = (j2 xor s) xor 1
        var imm25 = (s shl 24) or (i1 shl 23) or (i2 shl 22) or (imm10 shl 12) or (imm11 shl 1)
        if (s != 0) {
            imm25 -= (1 shl 25)
        }
        return offset + 4 + imm25
    }

    private fun isThumbLibcDoSyscallStub(
        bytes: ByteArray,
        targetOffset: Int,
        segStart: Int,
        segEnd: Int
    ): Boolean {
        if (targetOffset < segStart || targetOffset + 6 > segEnd) return false
        // push {r7, lr} (80 b5); mov r7, r12 (67 46); svc #0 (00 df)
        return bytes[targetOffset] == 0x80.toByte() &&
            bytes[targetOffset + 1] == 0xb5.toByte() &&
            bytes[targetOffset + 2] == 0x67.toByte() &&
            bytes[targetOffset + 3] == 0x46.toByte() &&
            bytes[targetOffset + 4] == 0x00.toByte() &&
            bytes[targetOffset + 5] == 0xdf.toByte()
    }

    fun patchDynamicLinker(file: File) {
        if (!file.exists() || !file.isFile || isSymlink(file)) {
            return
        }
        try {
            val bytes = file.readBytes()
            val fileSize = bytes.size.toLong()
            if (bytes.size < 52) return

            // Validate ELF magic: 0x7F 'E' 'L' 'F'
            if (bytes[0] != 0x7f.toByte() ||
                bytes[1] != 'E'.code.toByte() ||
                bytes[2] != 'L'.code.toByte() ||
                bytes[3] != 'F'.code.toByte()
            ) {
                return
            }

            val eiClass = bytes[4].toInt() and 0xFF
            val eiData = bytes[5].toInt() and 0xFF
            if (eiData != ELFDATA2LSB) return
            if (eiClass != ELFCLASS64 && eiClass != ELFCLASS32) return
            if (eiClass == ELFCLASS64 && bytes.size < 64) return

            val eMachine = readU16Le(bytes, 18)
            if (eiClass == ELFCLASS64 && eMachine != EM_AARCH64) return
            if (eiClass == ELFCLASS32 && eMachine != EM_ARM) return

            val execSegments = mutableListOf<Pair<Int, Int>>()

            if (eiClass == ELFCLASS64) {
                val ePhoff = readU64Le(bytes, 32)
                val ePhentsize = readU16Le(bytes, 54)
                val ePhnum = readU16Le(bytes, 56)

                if (ePhoff <= 0L || ePhentsize !in 56..4096 || ePhnum !in 1..1024) return
                val tableBytes = ePhnum.toLong() * ePhentsize.toLong()
                if (ePhoff > fileSize - tableBytes) return

                for (idx in 0 until ePhnum) {
                    val phOff = (ePhoff + idx.toLong() * ePhentsize.toLong()).toInt()
                    val pType = readU32Le(bytes, phOff)
                    val pFlags = readU32Le(bytes, phOff + 4)
                    val pOffset = readU64Le(bytes, phOff + 8)
                    val pFilesz = readU64Le(bytes, phOff + 32)

                    if (pType == PT_LOAD) {
                        if (pOffset < 0L || pFilesz < 0L || pOffset > fileSize - pFilesz) return
                        if ((pFlags and PF_X) != 0L && pFilesz > 0L) {
                            execSegments.add(Pair(pOffset.toInt(), (pOffset + pFilesz).toInt()))
                        }
                    }
                }
            } else {
                val ePhoff = readU32Le(bytes, 28)
                val ePhentsize = readU16Le(bytes, 42)
                val ePhnum = readU16Le(bytes, 44)

                if (ePhoff <= 0L || ePhentsize !in 32..4096 || ePhnum !in 1..1024) return
                val tableBytes = ePhnum.toLong() * ePhentsize.toLong()
                if (ePhoff > fileSize - tableBytes) return

                for (idx in 0 until ePhnum) {
                    val phOff = (ePhoff + idx.toLong() * ePhentsize.toLong()).toInt()
                    val pType = readU32Le(bytes, phOff)
                    val pOffset = readU32Le(bytes, phOff + 4)
                    val pFilesz = readU32Le(bytes, phOff + 16)
                    val pFlags = readU32Le(bytes, phOff + 24)

                    if (pType == PT_LOAD) {
                        if (pOffset < 0L || pFilesz < 0L || pOffset > fileSize - pFilesz) return
                        if ((pFlags and PF_X) != 0L && pFilesz > 0L) {
                            execSegments.add(Pair(pOffset.toInt(), (pOffset + pFilesz).toInt()))
                        }
                    }
                }
            }

            if (execSegments.isEmpty()) return

            var modified = false

            if (eMachine == EM_AARCH64) {
                val svcAarch64 = byteArrayOf(0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0xd4.toByte())
                val nopAarch64 = byteArrayOf(0x1f.toByte(), 0x20.toByte(), 0x03.toByte(), 0xd5.toByte())
                val movEnosysAarch64 = byteArrayOf(0xa0.toByte(), 0x04.toByte(), 0x80.toByte(), 0x92.toByte()) // movn x0, #37 (-38)

                // mov x8, #0x63 (syscall 99 set_robust_list)
                val movX8Syscall99 = byteArrayOf(0x68.toByte(), 0x0c.toByte(), 0x80.toByte(), 0xd2.toByte())
                // mov x8, #0x1b3 (syscall 435 clone3)
                val movX8Syscall435 = byteArrayOf(0x68.toByte(), 0x36.toByte(), 0x80.toByte(), 0xd2.toByte())
                // mov x8, #0x125 (syscall 293 rseq)
                val movX8Syscall293 = byteArrayOf(0xa8.toByte(), 0x24.toByte(), 0x80.toByte(), 0xd2.toByte())

                for ((segStartRaw, segEndRaw) in execSegments) {
                    val segStart = maxOf(0, segStartRaw)
                    val segEnd = minOf(bytes.size, segEndRaw)
                    val startAligned = (segStart + 3) and 3.inv()
                    val endAligned = segEnd and 3.inv()

                    var pos = startAligned
                    while (pos <= endAligned - 4) {
                        val isSyscall99 = matchesWord(bytes, pos, movX8Syscall99)
                        val isSyscall435 = matchesWord(bytes, pos, movX8Syscall435)
                        val isSyscall293 = matchesWord(bytes, pos, movX8Syscall293)

                        if (isSyscall99 || isSyscall435 || isSyscall293) {
                            val replacement = if (isSyscall99) nopAarch64 else movEnosysAarch64
                            val scName = when {
                                isSyscall99 -> "99 set_robust_list"
                                isSyscall435 -> "435 clone3"
                                else -> "293 rseq"
                            }
                            val searchEnd = minOf(endAligned - 4, pos + 64)
                            for (i in (pos + 4)..searchEnd step 4) {
                                if (matchesWord(bytes, i, svcAarch64)) {
                                    replacement.copyInto(bytes, destinationOffset = i)
                                    modified = true
                                    Log.i(TAG, "Patched AArch64 syscall $scName svc #0 at 0x${Integer.toHexString(i)} in ${file.name}")
                                    break
                                }
                            }
                        }
                        pos += 4
                    }
                }
            } else if (eMachine == EM_ARM) {
                val svcArm = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0xef.toByte())
                val nopArm = byteArrayOf(0x00.toByte(), 0xf0.toByte(), 0x20.toByte(), 0xe3.toByte())
                val mvnEnosysArm = byteArrayOf(0x25.toByte(), 0x00.toByte(), 0xe0.toByte(), 0xe3.toByte()) // mvn r0, #37 (-38)

                // A32: movw r7, #0x152 (syscall 338 set_robust_list)
                val movR7Syscall338 = byteArrayOf(0x52.toByte(), 0x71.toByte(), 0x00.toByte(), 0xe3.toByte())
                // A32: movw r7, #0x1b3 (syscall 435 clone3)
                val movR7Syscall435 = byteArrayOf(0xb3.toByte(), 0x71.toByte(), 0x00.toByte(), 0xe3.toByte())
                // A32: movw r7, #0x18e (syscall 398 rseq)
                val movR7Syscall398 = byteArrayOf(0x8e.toByte(), 0x71.toByte(), 0x00.toByte(), 0xe3.toByte())

                // Thumb-2: svc #0 (00 df), 16-bit nop (00 bf)
                val svcThumb = byteArrayOf(0x00.toByte(), 0xdf.toByte())
                val nopThumb16 = byteArrayOf(0x00.toByte(), 0xbf.toByte())
                // Thumb-2: mov.w r0, #0 (4f f0 00 00) and mvn.w r0, #37 (-38 = -ENOSYS: 6f f0 25 00)
                val movR0ZeroThumb = byteArrayOf(0x4f.toByte(), 0xf0.toByte(), 0x00.toByte(), 0x00.toByte())
                val mvnEnosysThumb = byteArrayOf(0x6f.toByte(), 0xf0.toByte(), 0x25.toByte(), 0x00.toByte())

                // Thumb-2: movw r7, #338 (40 f2 52 17), movw r7, #435 (40 f2 b3 17), movw r7, #398 (40 f2 8e 17)
                val thumbMovwR7Syscall338 = byteArrayOf(0x40.toByte(), 0xf2.toByte(), 0x52.toByte(), 0x17.toByte())
                val thumbMovwR7Syscall435 = byteArrayOf(0x40.toByte(), 0xf2.toByte(), 0xb3.toByte(), 0x17.toByte())
                val thumbMovwR7Syscall398 = byteArrayOf(0x40.toByte(), 0xf2.toByte(), 0x8e.toByte(), 0x17.toByte())

                // Thumb-2: mov.w r12, #338 (4f f4 a9 7c), mov.w r12, #398 (4f f4 c7 7c), movw r12, #435 (40 f2 b3 1c)
                val thumbMovR12Syscall338 = byteArrayOf(0x4f.toByte(), 0xf4.toByte(), 0xa9.toByte(), 0x7c.toByte())
                val thumbMovR12Syscall398 = byteArrayOf(0x4f.toByte(), 0xf4.toByte(), 0xc7.toByte(), 0x7c.toByte())
                val thumbMovR12Syscall435 = byteArrayOf(0x40.toByte(), 0xf2.toByte(), 0xb3.toByte(), 0x1c.toByte())

                for ((segStartRaw, segEndRaw) in execSegments) {
                    val segStart = maxOf(0, segStartRaw)
                    val segEnd = minOf(bytes.size, segEndRaw)
                    val startAligned4 = (segStart + 3) and 3.inv()
                    val endAligned4 = segEnd and 3.inv()

                    // 1. A32 4-byte aligned scan
                    var pos = startAligned4
                    while (pos <= endAligned4 - 4) {
                        val isSyscall338 = matchesWord(bytes, pos, movR7Syscall338)
                        val isSyscall435 = matchesWord(bytes, pos, movR7Syscall435)
                        val isSyscall398 = matchesWord(bytes, pos, movR7Syscall398)

                        if (isSyscall338 || isSyscall435 || isSyscall398) {
                            val replacement = if (isSyscall338) nopArm else mvnEnosysArm
                            val scName = when {
                                isSyscall338 -> "338 set_robust_list"
                                isSyscall435 -> "435 clone3"
                                else -> "398 rseq"
                            }
                            val searchEnd = minOf(endAligned4 - 4, pos + 64)
                            for (i in (pos + 4)..searchEnd step 4) {
                                if (matchesWord(bytes, i, svcArm)) {
                                    replacement.copyInto(bytes, destinationOffset = i)
                                    modified = true
                                    Log.i(TAG, "Patched ARM syscall $scName svc #0 at 0x${Integer.toHexString(i)} in ${file.name}")
                                    break
                                }
                            }
                        }
                        pos += 4
                    }

                    // 2. Thumb-2 2-byte aligned scan
                    val startAligned2 = (segStart + 1) and 1.inv()
                    val endAligned2 = segEnd and 1.inv()
                    var tPos = startAligned2
                    while (tPos <= endAligned2 - 4) {
                        val isThumbR7_338 = matchesWord(bytes, tPos, thumbMovwR7Syscall338)
                        val isThumbR7_435 = matchesWord(bytes, tPos, thumbMovwR7Syscall435)
                        val isThumbR7_398 = matchesWord(bytes, tPos, thumbMovwR7Syscall398)

                        if (isThumbR7_338 || isThumbR7_435 || isThumbR7_398) {
                            val scName = when {
                                isThumbR7_338 -> "338 set_robust_list"
                                isThumbR7_435 -> "435 clone3"
                                else -> "398 rseq"
                            }
                            val searchEnd = minOf(endAligned2 - 2, tPos + 64)
                            for (i in (tPos + 4)..searchEnd step 2) {
                                if (matchesHalfword(bytes, i, svcThumb)) {
                                    if (isThumbR7_338) {
                                        nopThumb16.copyInto(bytes, destinationOffset = i)
                                    } else {
                                        // movw r7, #imm (4B) + svc #0 (2B) -> mvn.w r0, #37 (4B) at movw, nop (2B) at svc
                                        mvnEnosysThumb.copyInto(bytes, destinationOffset = tPos)
                                        nopThumb16.copyInto(bytes, destinationOffset = i)
                                    }
                                    modified = true
                                    Log.i(TAG, "Patched Thumb-2 syscall $scName svc #0 at 0x${Integer.toHexString(i)} in ${file.name}")
                                    break
                                }
                            }
                        } else {
                            val isThumbR12_338 = matchesWord(bytes, tPos, thumbMovR12Syscall338)
                            val isThumbR12_398 = matchesWord(bytes, tPos, thumbMovR12Syscall398)
                            val isThumbR12_435 = matchesWord(bytes, tPos, thumbMovR12Syscall435)

                            if (isThumbR12_338 || isThumbR12_398 || isThumbR12_435) {
                                val replacement = if (isThumbR12_338) movR0ZeroThumb else mvnEnosysThumb
                                val scName = when {
                                    isThumbR12_338 -> "338 set_robust_list"
                                    isThumbR12_435 -> "435 clone3"
                                    else -> "398 rseq"
                                }
                                val searchEnd = minOf(endAligned2 - 4, tPos + 48)
                                for (i in (tPos + 4)..searchEnd step 2) {
                                    val blTarget = decodeThumbBlTarget(bytes, i)
                                    if (blTarget != null && isThumbLibcDoSyscallStub(bytes, blTarget, segStart, segEnd)) {
                                        replacement.copyInto(bytes, destinationOffset = i)
                                        modified = true
                                        Log.i(TAG, "Patched Thumb-2 syscall $scName bl __libc_do_syscall at 0x${Integer.toHexString(i)} in ${file.name}")
                                        break
                                    }
                                }
                            }
                        }
                        tPos += 2
                    }
                }
            }

            if (modified) {
                val parent = file.parentFile ?: return
                val tmp = File(parent, "${file.name}.ctx_patch_tmp")
                deleteIfExists(tmp)
                tmp.outputStream().use { it.write(bytes) }
                tmp.setExecutable(true, true)
                tmp.setReadable(true, true)
                tmp.setWritable(true, true)
                try { Os.chmod(tmp.absolutePath, 448) } catch (_: Exception) {}
                if (!atomicReplace(tmp, file)) {
                    deleteIfExists(tmp)
                    return
                }
                file.setExecutable(true, true)
                file.setReadable(true, true)
                file.setWritable(true, true)
                try { Os.chmod(file.absolutePath, 448) } catch (_: Exception) {}
                Log.i(TAG, "Successfully wrote patched linker atomically: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to patch dynamic linker: ${file.absolutePath}", e)
        }
    }
}
