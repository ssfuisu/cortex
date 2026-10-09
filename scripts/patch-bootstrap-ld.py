#!/usr/bin/env python3
import io
import os
import struct
import sys
import tarfile

def patch_elf_bytes(data, filename):
    if len(data) < 52 or data[:4] != b"\x7fELF":
        return data, 0
    ei_class = data[4]
    ei_data = data[5]
    if ei_data != 1:  # Only Little Endian is supported
        return data, 0
    e_machine = struct.unpack_from("<H", data, 18)[0]
    EM_ARM = 40
    EM_AARCH64 = 183
    if not ((ei_class == 2 and e_machine == EM_AARCH64) or (ei_class == 1 and e_machine == EM_ARM)):
        return data, 0

    segments = []
    if ei_class == 2:  # 64-bit ELF
        if len(data) >= 64:
            e_phoff = struct.unpack_from("<Q", data, 32)[0]
            e_phentsize = struct.unpack_from("<H", data, 54)[0]
            e_phnum = struct.unpack_from("<H", data, 56)[0]
            if e_phentsize >= 56:
                for i in range(e_phnum):
                    off = e_phoff + i * e_phentsize
                    if off + 40 > len(data): break
                    p_type, p_flags = struct.unpack_from("<II", data, off)
                    p_offset = struct.unpack_from("<Q", data, off + 8)[0]
                    p_filesz = struct.unpack_from("<Q", data, off + 32)[0]
                    if p_type == 1 and (p_flags & 1):  # PT_LOAD and PF_X
                        segments.append((p_offset, p_offset + p_filesz))
            if not segments:
                e_shoff = struct.unpack_from("<Q", data, 40)[0]
                e_shentsize = struct.unpack_from("<H", data, 58)[0]
                e_shnum = struct.unpack_from("<H", data, 60)[0]
                if e_shentsize >= 64:
                    for i in range(e_shnum):
                        off = e_shoff + i * e_shentsize
                        if off + 48 > len(data): break
                        sh_flags = struct.unpack_from("<Q", data, off + 8)[0]
                        sh_offset = struct.unpack_from("<Q", data, off + 24)[0]
                        sh_size = struct.unpack_from("<Q", data, off + 32)[0]
                        if sh_flags & 4:  # SHF_EXECINSTR
                            segments.append((sh_offset, sh_offset + sh_size))
    elif ei_class == 1:  # 32-bit ELF
        if len(data) >= 52:
            e_phoff = struct.unpack_from("<I", data, 28)[0]
            e_phentsize = struct.unpack_from("<H", data, 42)[0]
            e_phnum = struct.unpack_from("<H", data, 44)[0]
            if e_phentsize >= 32:
                for i in range(e_phnum):
                    off = e_phoff + i * e_phentsize
                    if off + 28 > len(data): break
                    p_type = struct.unpack_from("<I", data, off)[0]
                    p_offset = struct.unpack_from("<I", data, off + 4)[0]
                    p_filesz = struct.unpack_from("<I", data, off + 16)[0]
                    p_flags = struct.unpack_from("<I", data, off + 24)[0]
                    if p_type == 1 and (p_flags & 1):  # PT_LOAD and PF_X
                        segments.append((p_offset, p_offset + p_filesz))
            if not segments:
                e_shoff = struct.unpack_from("<I", data, 32)[0]
                e_shentsize = struct.unpack_from("<H", data, 46)[0]
                e_shnum = struct.unpack_from("<H", data, 48)[0]
                if e_shentsize >= 40:
                    for i in range(e_shnum):
                        off = e_shoff + i * e_shentsize
                        if off + 24 > len(data): break
                        sh_flags = struct.unpack_from("<I", data, off + 8)[0]
                        sh_offset = struct.unpack_from("<I", data, off + 16)[0]
                        sh_size = struct.unpack_from("<I", data, off + 20)[0]
                        if sh_flags & 4:  # SHF_EXECINSTR
                            segments.append((sh_offset, sh_offset + sh_size))

    if not segments:
        return data, 0

    patched_count = 0
    if e_machine == EM_AARCH64:
        svc = b"\x01\x00\x00\xd4"          # svc #0
        nop = b"\x1f\x20\x03\xd5"          # nop
        mov_enosys = b"\xa0\x04\x80\x92"   # mov x0, #-38
        patches = [
            ("set_robust_list", b"\x68\x0c\x80\xd2", nop),        # mov x8, #0x63 (99) -> nop
            ("clone3",          b"\x68\x36\x80\xd2", mov_enosys), # mov x8, #0x1b3 (435) -> mov x0, #-38
            ("rseq",            b"\xa8\x24\x80\xd2", mov_enosys), # mov x8, #0x125 (293) -> mov x0, #-38
        ]
        for seg_start, seg_end in segments:
            seg_start = max(0, seg_start)
            seg_end = min(len(data), seg_end)
            start_aligned = (seg_start + 3) & ~3
            end_aligned = seg_end & ~3
            for pos in range(start_aligned, end_aligned - 4, 4):
                for sc_name, pat, repl in patches:
                    if data[pos:pos+4] == pat:
                        for s_pos in range(pos + 4, min(end_aligned, pos + 68), 4):
                            if data[s_pos:s_pos+4] == svc:
                                data[s_pos:s_pos+4] = repl
                                patched_count += 1
                                print(f"Patched AArch64 {sc_name} at 0x{s_pos:x} in {filename}")
                                break
    elif e_machine == EM_ARM:
        svc_arm = b"\x00\x00\x00\xef"        # svc #0 in ARM mode
        nop_arm = b"\x00\xf0\x20\xe3"        # nop in ARM mode
        mov_enosys_arm = b"\x25\x00\xe0\xe3" # mvn r0, #37 (-38)
        patches_arm = [
            ("set_robust_list", b"\x52\x71\x00\xe3", nop_arm),        # movw r7, #0x152 (338)
            ("clone3",          b"\xb3\x71\x00\xe3", mov_enosys_arm), # movw r7, #0x1b3 (435)
            ("rseq",            b"\x8e\x71\x00\xe3", mov_enosys_arm), # movw r7, #0x18e (398)
        ]
        svc_thumb = b"\x00\xdf"              # svc #0 in Thumb mode
        nop_thumb16 = b"\x00\xbf"            # 16-bit nop in Thumb mode
        mov_r0_zero_thumb = b"\x4f\xf0\x00\x00" # mov.w r0, #0
        mvn_enosys_thumb = b"\x6f\xf0\x25\x00"  # mvn.w r0, #37 (-38)
        patches_thumb_r7 = [
            ("set_robust_list", b"\x40\xf2\x52\x17", True),  # movw r7, #0x152 (338)
            ("clone3",          b"\x40\xf2\xb3\x17", False), # movw r7, #0x1b3 (435)
            ("rseq",            b"\x40\xf2\x8e\x17", False), # movw r7, #0x18e (398)
        ]
        patches_thumb_r12 = [
            ("set_robust_list", b"\x4f\xf4\xa9\x7c", mov_r0_zero_thumb), # mov.w r12, #0x152 (338)
            ("rseq",            b"\x4f\xf4\xc7\x7c", mvn_enosys_thumb),  # mov.w r12, #0x18e (398)
            ("clone3",          b"\x40\xf2\xb3\x1c", mvn_enosys_thumb),  # movw r12, #0x1b3 (435)
        ]
        libc_do_syscall_stub = b"\x80\xb5\x67\x46\x00\xdf" # push {r7, lr}; mov r7, r12; svc #0

        def decode_thumb_bl(buf, off):
            if off < 0 or off + 4 > len(buf):
                return None
            hw1, hw2 = struct.unpack_from("<HH", buf, off)
            if (hw1 & 0xf800) != 0xf000 or (hw2 & 0xd000) != 0xd000:
                return None
            s = (hw1 >> 10) & 1
            imm10 = hw1 & 0x3ff
            j1 = (hw2 >> 13) & 1
            j2 = (hw2 >> 11) & 1
            imm11 = hw2 & 0x7ff
            i1 = (j1 ^ s) ^ 1
            i2 = (j2 ^ s) ^ 1
            imm25 = (s << 24) | (i1 << 23) | (i2 << 22) | (imm10 << 12) | (imm11 << 1)
            if s:
                imm25 -= (1 << 25)
            return off + 4 + imm25

        for seg_start, seg_end in segments:
            seg_start = max(0, seg_start)
            seg_end = min(len(data), seg_end)
            start_aligned4 = (seg_start + 3) & ~3
            end_aligned4 = seg_end & ~3
            for pos in range(start_aligned4, end_aligned4 - 4, 4):
                for sc_name, pat, repl in patches_arm:
                    if data[pos:pos+4] == pat:
                        for s_pos in range(pos + 4, min(end_aligned4, pos + 68), 4):
                            if data[s_pos:s_pos+4] == svc_arm:
                                data[s_pos:s_pos+4] = repl
                                patched_count += 1
                                print(f"Patched ARM {sc_name} at 0x{s_pos:x} in {filename}")
                                break

            start_aligned2 = (seg_start + 1) & ~1
            end_aligned2 = seg_end & ~1
            for t_pos in range(start_aligned2, end_aligned2 - 4, 2):
                matched_r7 = False
                for sc_name, pat, is_nop_only in patches_thumb_r7:
                    if data[t_pos:t_pos+4] == pat:
                        matched_r7 = True
                        for s_pos in range(t_pos + 4, min(end_aligned2 - 2, t_pos + 64) + 2, 2):
                            if data[s_pos:s_pos+2] == svc_thumb:
                                if is_nop_only:
                                    data[s_pos:s_pos+2] = nop_thumb16
                                else:
                                    data[t_pos:t_pos+4] = mvn_enosys_thumb
                                    data[s_pos:s_pos+2] = nop_thumb16
                                patched_count += 1
                                print(f"Patched Thumb-2 {sc_name} svc #0 at 0x{s_pos:x} in {filename}")
                                break
                        break
                if not matched_r7:
                    for sc_name, pat, repl in patches_thumb_r12:
                        if data[t_pos:t_pos+4] == pat:
                            for s_pos in range(t_pos + 4, min(end_aligned2 - 4, t_pos + 48) + 2, 2):
                                bl_target = decode_thumb_bl(data, s_pos)
                                if bl_target is not None and seg_start <= bl_target <= seg_end - 6:
                                    if data[bl_target:bl_target+6] == libc_do_syscall_stub:
                                        data[s_pos:s_pos+4] = repl
                                        patched_count += 1
                                        print(f"Patched Thumb-2 {sc_name} bl __libc_do_syscall at 0x{s_pos:x} in {filename}")
                                        break
                            break

    return data, patched_count

def patch_tar_ld(tar_path):
    print(f"Checking and patching dynamic linkers and libc in {tar_path}...")
    count = 0
    with tarfile.open(tar_path, "r") as tin:
        members = tin.getmembers()
        with tarfile.open(tar_path + ".tmp", "w") as tout:
            for m in members:
                if m.name == "home/ubuntu" or m.name.startswith("home/ubuntu/"):
                    continue
                if m.name in ["home/bashrc", "home/profile"]:
                    continue
                if m.issym() and m.linkname.startswith("/"):
                    parent = os.path.dirname(m.name)
                    target = m.linkname.lstrip("/")
                    rel = os.path.relpath(target, parent)
                    m.linkname = rel
                f = tin.extractfile(m) if m.isreg() else None
                if f and m.name == "var/lib/dpkg/status":
                    status_data = f.read().decode("utf-8", errors="replace")
                    packages_to_hold = {"libc6", "libc6:arm64", "libc6:armhf", "libc-bin", "libc-dev-bin", "locales"}
                    blocks = status_data.split("\n\n")
                    new_blocks = []
                    for b in blocks:
                        lines = b.split("\n")
                        pkg_name = None
                        for l in lines:
                            if l.startswith("Package: "):
                                pkg_name = l.split(": ", 1)[1].strip()
                                break
                        if pkg_name in packages_to_hold:
                            new_lines = []
                            has_status = False
                            for l in lines:
                                if l.startswith("Status: "):
                                    new_lines.append("Status: hold ok installed")
                                    has_status = True
                                else:
                                    new_lines.append(l)
                            if not has_status:
                                new_lines.insert(1, "Status: hold ok installed")
                            new_blocks.append("\n".join(new_lines))
                        else:
                            new_blocks.append(b)
                    mod_bytes = ("\n\n".join(new_blocks)).encode("utf-8")
                    m.size = len(mod_bytes)
                    tout.addfile(m, io.BytesIO(mod_bytes))
                    print(f"Set dpkg hold in {m.name}")
                    continue
                if f and ("ld-linux" in m.name or "libc.so" in m.name or "libc-" in m.name):
                    data = bytearray(f.read())
                    data, file_patched = patch_elf_bytes(data, m.name)
                    if file_patched > 0:
                        count += file_patched
                        print(f"Total patched in {m.name}: {file_patched}")
                    m.size = len(data)
                    tout.addfile(m, io.BytesIO(data))
                elif f:
                    tout.addfile(m, f)
                else:
                    tout.addfile(m)
    os.replace(tar_path + ".tmp", tar_path)
    print(f"Finished patching {tar_path}: {count} occurrences patched.")
    if count <= 0:
        print(f"ERROR: Zero seccomp syscall occurrences patched in {tar_path}!")
        sys.exit(1)

def main():
    if os.path.exists("app/src/main/assets/bootstrap-arm64.tar"):
        patch_tar_ld("app/src/main/assets/bootstrap-arm64.tar")
    if os.path.exists("app/src/main/assets/bootstrap-arm.tar"):
        patch_tar_ld("app/src/main/assets/bootstrap-arm.tar")

if __name__ == "__main__":
    main()
