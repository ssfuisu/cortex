package org.cortex.terminal.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ElfLinkerPatcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun buildElf64(
        eMachine: Int = 183, // EM_AARCH64
        ePhoff: Long = 64L,
        ePhentsize: Int = 56,
        ePhnum: Int = 1,
        segments: List<Triple<Int, Int, ByteArray>> // (pFlags, fileOffset, payload)
    ): ByteArray {
        val maxEnd = segments.maxOfOrNull { it.second + it.third.size } ?: 256
        val totalSize = maxOf(256, maxEnd)
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // e_ident
        buf.put(0, 0x7f.toByte())
        buf.put(1, 'E'.code.toByte())
        buf.put(2, 'L'.code.toByte())
        buf.put(3, 'F'.code.toByte())
        buf.put(4, 2.toByte()) // ELFCLASS64
        buf.put(5, 1.toByte()) // ELFDATA2LSB
        buf.put(6, 1.toByte()) // EV_CURRENT

        buf.putShort(16, 3.toShort()) // ET_DYN
        buf.putShort(18, eMachine.toShort())
        buf.putInt(20, 1) // EV_CURRENT
        buf.putLong(32, ePhoff) // e_phoff
        buf.putShort(52, 64.toShort()) // e_ehsize
        buf.putShort(54, ePhentsize.toShort())
        buf.putShort(56, ePhnum.toShort())

        if (ePhoff >= 0 && ePhentsize >= 56 && ePhoff + ePhnum.toLong() * ePhentsize.toLong() <= totalSize) {
            segments.forEachIndexed { idx, (pFlags, offset, payload) ->
                if (idx < ePhnum) {
                    val phPos = (ePhoff + idx * ePhentsize).toInt()
                    buf.putInt(phPos + 0, 1) // PT_LOAD
                    buf.putInt(phPos + 4, pFlags)
                    buf.putLong(phPos + 8, offset.toLong()) // p_offset
                    buf.putLong(phPos + 16, offset.toLong()) // p_vaddr
                    buf.putLong(phPos + 24, offset.toLong()) // p_paddr
                    buf.putLong(phPos + 32, payload.size.toLong()) // p_filesz
                    buf.putLong(phPos + 40, payload.size.toLong()) // p_memsz
                    buf.putLong(phPos + 48, 0x1000L) // p_align
                }
                val arr = buf.array()
                payload.copyInto(arr, destinationOffset = offset)
            }
        }
        return buf.array()
    }

    @Test
    fun patchDynamicLinker_patchesAarch64SyscallsInExecutablePtLoadSegment() {
        val movX8_99 = byteArrayOf(0x68, 0x0c, 0x80.toByte(), 0xd2.toByte())
        val movX8_435 = byteArrayOf(0x68, 0x36, 0x80.toByte(), 0xd2.toByte())
        val movX8_293 = byteArrayOf(0xa8.toByte(), 0x24, 0x80.toByte(), 0xd2.toByte())
        val svc0 = byteArrayOf(0x01, 0x00, 0x00, 0xd4.toByte())
        val nop = byteArrayOf(0x1f, 0x20, 0x03, 0xd5.toByte())
        val movnX0_37 = byteArrayOf(0xa0.toByte(), 0x04, 0x80.toByte(), 0x92.toByte())

        val codePayload = movX8_99 + svc0 + movX8_435 + svc0 + movX8_293 + svc0
        val elfBytes = buildElf64(
            segments = listOf(Triple(0x5 /* PF_R | PF_X */, 0x80, codePayload))
        )

        val file = File(tempFolder.root, "ld-2.39.so")
        file.writeBytes(elfBytes)

        ElfLinkerPatcher.patchDynamicLinker(file)

        val patched = file.readBytes()
        assertArrayEquals(
            "set_robust_list svc #0 must be patched to nop",
            nop,
            patched.sliceArray(0x84 until 0x88)
        )
        assertArrayEquals(
            "clone3 svc #0 must be patched to movn x0, #37",
            movnX0_37,
            patched.sliceArray(0x8c until 0x90)
        )
        assertArrayEquals(
            "rseq svc #0 must be patched to movn x0, #37",
            movnX0_37,
            patched.sliceArray(0x94 until 0x98)
        )
    }

    @Test
    fun patchDynamicLinker_patchesArm32SyscallsInExecutablePtLoadSegment() {
        val totalSize = 256
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0, 0x7f.toByte())
        buf.put(1, 'E'.code.toByte())
        buf.put(2, 'L'.code.toByte())
        buf.put(3, 'F'.code.toByte())
        buf.put(4, 1.toByte()) // ELFCLASS32
        buf.put(5, 1.toByte()) // ELFDATA2LSB
        buf.put(6, 1.toByte()) // EV_CURRENT
        buf.putShort(16, 3.toShort()) // ET_DYN
        buf.putShort(18, 40.toShort()) // EM_ARM
        buf.putInt(20, 1) // EV_CURRENT
        buf.putInt(28, 52) // e_phoff
        buf.putShort(40, 52.toShort()) // e_ehsize
        buf.putShort(42, 32.toShort()) // e_phentsize
        buf.putShort(44, 1.toShort()) // e_phnum

        val movwR7_338 = byteArrayOf(0x52, 0x71, 0x00, 0xe3.toByte())
        val movwR7_435 = byteArrayOf(0xb3.toByte(), 0x71, 0x00, 0xe3.toByte())
        val svcArm = byteArrayOf(0x00, 0x00, 0x00, 0xef.toByte())
        val nopArm = byteArrayOf(0x00, 0xf0.toByte(), 0x20, 0xe3.toByte())
        val mvnR0_37 = byteArrayOf(0x25, 0x00, 0xe0.toByte(), 0xe3.toByte())
        val payload = movwR7_338 + svcArm + movwR7_435 + svcArm

        buf.putInt(52 + 0, 1) // PT_LOAD
        buf.putInt(52 + 4, 0x80) // p_offset
        buf.putInt(52 + 8, 0x80) // p_vaddr
        buf.putInt(52 + 12, 0x80) // p_paddr
        buf.putInt(52 + 16, payload.size) // p_filesz
        buf.putInt(52 + 20, payload.size) // p_memsz
        buf.putInt(52 + 24, 0x5) // PF_R | PF_X
        buf.putInt(52 + 28, 0x1000) // p_align
        payload.copyInto(buf.array(), destinationOffset = 0x80)

        val file = File(tempFolder.root, "ld-linux-armhf.so.3")
        file.writeBytes(buf.array())

        ElfLinkerPatcher.patchDynamicLinker(file)

        val patched = file.readBytes()
        assertArrayEquals(nopArm, patched.sliceArray(0x84 until 0x88))
        assertArrayEquals(mvnR0_37, patched.sliceArray(0x8c until 0x90))
    }

    @Test
    fun patchDynamicLinker_doesNotModifyNonExecutablePtLoadSegment() {
        val movX8_435 = byteArrayOf(0x68, 0x36, 0x80.toByte(), 0xd2.toByte())
        val svc0 = byteArrayOf(0x01, 0x00, 0x00, 0xd4.toByte())
        val nop = byteArrayOf(0x1f, 0x20, 0x03, 0xd5.toByte())

        val execPayload = nop + nop
        val rodataPayload = movX8_435 + svc0

        val elfBytes = buildElf64(
            ePhnum = 2,
            segments = listOf(
                Triple(0x5 /* PF_R | PF_X */, 0xc0, execPayload),
                Triple(0x4 /* PF_R only (non-executable) */, 0xe0, rodataPayload)
            )
        )

        val file = File(tempFolder.root, "libc.so.6")
        file.writeBytes(elfBytes)

        ElfLinkerPatcher.patchDynamicLinker(file)

        val after = file.readBytes()
        assertArrayEquals(
            "Bytes in non-executable PT_LOAD segment must remain untouched",
            rodataPayload,
            after.sliceArray(0xe0 until 0xe8)
        )
        assertArrayEquals(elfBytes, after)
    }

    @Test
    fun patchDynamicLinker_ignoresLinkerTextScriptAndWrongArchitecture() {
        val ldScript = File(tempFolder.root, "libc.so")
        val scriptBytes = (
            "/* GNU ld script */\n" +
                "OUTPUT_FORMAT(elf64-littleaarch64)\n" +
                "GROUP ( /lib/aarch64-linux-gnu/libc.so.6 /usr/lib/aarch64-linux-gnu/libc_nonshared.a )\n"
            ).toByteArray(Charsets.UTF_8)
        ldScript.writeBytes(scriptBytes)

        ElfLinkerPatcher.patchDynamicLinker(ldScript)
        assertArrayEquals("GNU ld text script must not be modified", scriptBytes, ldScript.readBytes())

        // EM_X86_64 = 62
        val movX8_435 = byteArrayOf(0x68, 0x36, 0x80.toByte(), 0xd2.toByte())
        val svc0 = byteArrayOf(0x01, 0x00, 0x00, 0xd4.toByte())
        val x86Elf = buildElf64(
            eMachine = 62,
            segments = listOf(Triple(0x5, 0x80, movX8_435 + svc0))
        )
        val x86File = File(tempFolder.root, "libc-x86_64.so")
        x86File.writeBytes(x86Elf)

        ElfLinkerPatcher.patchDynamicLinker(x86File)
        assertArrayEquals("Wrong e_machine ELF must not be modified", x86Elf, x86File.readBytes())
    }

    @Test
    fun patchDynamicLinker_rejectsMalformedProgramHeaderTable() {
        val movX8_435 = byteArrayOf(0x68, 0x36, 0x80.toByte(), 0xd2.toByte())
        val svc0 = byteArrayOf(0x01, 0x00, 0x00, 0xd4.toByte())

        // Malformed e_phentsize < 56
        val badPhentsizeElf = buildElf64(
            ePhentsize = 16,
            segments = listOf(Triple(0x5, 0x80, movX8_435 + svc0))
        )
        val badPhentsizeFile = File(tempFolder.root, "ld-bad-phentsize.so")
        badPhentsizeFile.writeBytes(badPhentsizeElf)
        ElfLinkerPatcher.patchDynamicLinker(badPhentsizeFile)
        assertArrayEquals(badPhentsizeElf, badPhentsizeFile.readBytes())

        // Malformed e_phoff beyond file size
        val badPhoffElf = buildElf64(
            ePhoff = 4096L,
            segments = listOf(Triple(0x5, 0x80, movX8_435 + svc0))
        )
        val badPhoffFile = File(tempFolder.root, "ld-bad-phoff.so")
        badPhoffFile.writeBytes(badPhoffElf)
        ElfLinkerPatcher.patchDynamicLinker(badPhoffFile)
        assertArrayEquals(badPhoffElf, badPhoffFile.readBytes())
    }

    @Test
    fun patchAllDynamicLinkers_enforcesCanonicalRootContainment() {
        val rootDir = tempFolder.newFolder("cortex_root")
        val outsideDir = tempFolder.newFolder("outside_dir")

        val movX8_435 = byteArrayOf(0x68, 0x36, 0x80.toByte(), 0xd2.toByte())
        val svc0 = byteArrayOf(0x01, 0x00, 0x00, 0xd4.toByte())
        val unpatchedElf = buildElf64(
            segments = listOf(Triple(0x5, 0x80, movX8_435 + svc0))
        )

        val outsideTarget = File(outsideDir, "libc-2.39.so")
        outsideTarget.writeBytes(unpatchedElf)

        val rootCanonical = rootDir.canonicalFile.path
        assertFalse(
            "Outside file must not be considered contained in root",
            ElfLinkerPatcher.isContainedInRoot(outsideTarget, rootCanonical)
        )

        val insideDir = File(rootDir, "usr/lib").apply { mkdirs() }
        val insideFile = File(insideDir, "ld-linux-aarch64.so.1")
        assertTrue(
            "Inside file must be considered contained in root",
            ElfLinkerPatcher.isContainedInRoot(insideFile, rootCanonical)
        )

        val symlinkCreated = ElfLinkerPatcher.createSymlink(
            outsideTarget.absolutePath,
            File(insideDir, "libc.so.6")
        )
        if (symlinkCreated) {
            ElfLinkerPatcher.patchAllDynamicLinkers(rootDir)
            assertArrayEquals(
                "Symlink target outside root must never be patched",
                unpatchedElf,
                outsideTarget.readBytes()
            )
        }
    }
}
