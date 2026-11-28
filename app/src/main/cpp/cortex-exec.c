// Freestanding userland ELF runner packaged in the APK's nativeLibraryDir (apk_data_file).
// On Android 10+ (targetSdk >= 29), SELinux denies execute_no_trans on writable app storage
// (app_data_file), blocking direct kernel execve() of Ubuntu's dynamic linker and static binaries.
// However, appdomain holds execute_no_trans on apk_data_file and (mmap map+execute) + execmem for
// app_data_file, so kernel execve() runs this runner, which maps the target ELF in user space,
// builds its auxiliary vector on the real main stack, and transfers control to its _start.

#include <elf.h>
#include <stdint.h>
#include <stddef.h>

#if defined(__aarch64__)
typedef Elf64_Ehdr Elf_Ehdr;
typedef Elf64_Phdr Elf_Phdr;
typedef Elf64_auxv_t Elf_auxv;
typedef uint64_t uword;
#define ARCH_ELFCLASS ELFCLASS64
#define ARCH_EM EM_AARCH64
#elif defined(__arm__)
typedef Elf32_Ehdr Elf_Ehdr;
typedef Elf32_Phdr Elf_Phdr;
typedef Elf32_auxv_t Elf_auxv;
typedef uint32_t uword;
#define ARCH_ELFCLASS ELFCLASS32
#define ARCH_EM EM_ARM
#else
#error "Unsupported architecture for cortex-exec"
#endif

#ifndef AT_FDCWD
#define AT_FDCWD (-100)
#endif
#ifndef O_RDONLY
#define O_RDONLY 0
#endif
#ifndef PROT_NONE
#define PROT_NONE 0
#define PROT_READ 1
#define PROT_WRITE 2
#define PROT_EXEC 4
#endif
#ifndef MAP_PRIVATE
#define MAP_PRIVATE 0x02
#define MAP_FIXED 0x10
#define MAP_ANONYMOUS 0x20
#define MAP_FAILED ((void *)(long)-1)
#endif
#ifndef PR_SET_NAME
#define PR_SET_NAME 15
#endif
#ifndef SEEK_SET
#define SEEK_SET 0
#endif
#ifndef AT_MINSIGSTKSZ
#define AT_MINSIGSTKSZ 51
#endif

#if defined(__aarch64__)
static inline long sc6(long nr, long a0, long a1, long a2, long a3, long a4, long a5) {
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a0;
    register long x1 __asm__("x1") = a1;
    register long x2 __asm__("x2") = a2;
    register long x3 __asm__("x3") = a3;
    register long x4 __asm__("x4") = a4;
    register long x5 __asm__("x5") = a5;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
    return x0;
}
#define SYS_read 63
#define SYS_write 64
#define SYS_openat 56
#define SYS_close 57
#define SYS_lseek 62
#define SYS_pread64 67
#define SYS_exit_group 94
#define SYS_prctl 167
#define SYS_munmap 215
#define SYS_mmap 222
#define SYS_mprotect 226
#elif defined(__arm__)
static inline long sc6(long nr, long a0, long a1, long a2, long a3, long a4, long a5) {
    register long r0 __asm__("r0") = a0;
    register long r1 __asm__("r1") = a1;
    register long r2 __asm__("r2") = a2;
    register long r3 __asm__("r3") = a3;
    register long r4 __asm__("r4") = a4;
    register long r5 __asm__("r5") = a5;
    register long ip __asm__("r12") = nr;
    __asm__ volatile(
        "push {r7}\n\t"
        "mov r7, %1\n\t"
        "svc #0\n\t"
        "pop {r7}"
        : "+r"(r0)
        : "r"(ip), "r"(r1), "r"(r2), "r"(r3), "r"(r4), "r"(r5)
        : "memory", "cc");
    return r0;
}
#define SYS_read 3
#define SYS_write 4
#define SYS_open 5
#define SYS_close 6
#define SYS_munmap 91
#define SYS_mprotect 125
#define SYS_prctl 172
#define SYS_pread64 180
#define SYS_mmap2 192
#define SYS_exit_group 248
#define SYS_openat 322
#endif

static void *sys_mmap(void *addr, size_t len, int prot, int flags, int fd, uword off) {
#if defined(__aarch64__)
    long r = sc6(SYS_mmap, (long)addr, (long)len, prot, flags, fd, (long)off);
#else
    long r = sc6(SYS_mmap2, (long)addr, (long)len, prot, flags, fd, (long)(off >> 12));
#endif
    if (r < 0 && r > -4096) return MAP_FAILED;
    return (void *)r;
}

static void c_memset(void *dst, int c, size_t n) {
    volatile unsigned char *d = (volatile unsigned char *)dst;
    for (size_t i = 0; i < n; i++) d[i] = (unsigned char)c;
}

__attribute__((visibility("hidden")))
void *memset(void *dst, int c, size_t n) {
    c_memset(dst, c, n);
    return dst;
}

__attribute__((visibility("hidden")))
void *memcpy(void *dst, const void *src, size_t n) {
    volatile unsigned char *d = (volatile unsigned char *)dst;
    const unsigned char *s = (const unsigned char *)src;
    for (size_t i = 0; i < n; i++) d[i] = s[i];
    return dst;
}

static size_t c_strlen(const char *s) {
    size_t n = 0;
    while (s[n]) n++;
    return n;
}

static int c_strncmp(const char *a, const char *b, size_t n) {
    for (size_t i = 0; i < n; i++) {
        if (a[i] != b[i]) return (unsigned char)a[i] - (unsigned char)b[i];
        if (a[i] == '\0') return 0;
    }
    return 0;
}

static void die_msg(const char *msg, int code) {
    sc6(SYS_write, 2, (long)msg, (long)c_strlen(msg), 0, 0, 0);
    sc6(SYS_exit_group, code, 0, 0, 0, 0, 0);
    __builtin_unreachable();
}

#if defined(__arm__)
static long pread_all(int fd, void *buf, size_t len, uword off) {
    unsigned char *p = (unsigned char *)buf;
    size_t done = 0;
    while (done < len) {
        long n = sc6(SYS_pread64, fd, (long)(p + done), (long)(len - done), 0, (long)(off + done), 0);
        if (n <= 0) return -1;
        done += (size_t)n;
    }
    return (long)done;
}
#else
static long pread_all(int fd, void *buf, size_t len, uword off) {
    unsigned char *p = (unsigned char *)buf;
    size_t done = 0;
    while (done < len) {
        long n = sc6(SYS_pread64, fd, (long)(p + done), (long)(len - done), (long)(off + done), 0, 0);
        if (n <= 0) return -1;
        done += (size_t)n;
    }
    return (long)done;
}
#endif

static int phdr_prot(uint32_t flags) {
    return ((flags & PF_R) ? PROT_READ : 0) |
           ((flags & PF_W) ? PROT_WRITE : 0) |
           ((flags & PF_X) ? PROT_EXEC : 0);
}

static void set_proc_name(const char *path) {
    const char *base = path;
    for (const char *p = path; *p; p++) {
        if (*p == '/') base = p + 1;
    }
    char comm[16];
    size_t i = 0;
    for (; i < 15 && base[i]; i++) comm[i] = base[i];
    comm[i] = '\0';
    sc6(SYS_prctl, PR_SET_NAME, (long)comm, 0, 0, 0, 0);
}

#define MAX_PHDRS 64

struct loaded_image {
    uword entry;
    uword phdr;
    uword phent;
    uword phnum;
    uword base;
};

static void load_elf(const char *path, uword pagesz, int check_interp, struct loaded_image *out, char *interp_buf, size_t interp_cap) {
    int fd = (int)sc6(SYS_openat, AT_FDCWD, (long)path, O_RDONLY, 0, 0, 0);
    if (fd < 0) die_msg("cortex-exec: cannot open target ELF\n", 127);

    Elf_Ehdr eh;
    if (pread_all(fd, &eh, sizeof(eh), 0) != (long)sizeof(eh)) die_msg("cortex-exec: bad ELF header\n", 126);
    if (eh.e_ident[EI_MAG0] != ELFMAG0 || eh.e_ident[EI_MAG1] != ELFMAG1 ||
        eh.e_ident[EI_MAG2] != ELFMAG2 || eh.e_ident[EI_MAG3] != ELFMAG3 ||
        eh.e_ident[EI_CLASS] != ARCH_ELFCLASS || eh.e_ident[EI_DATA] != ELFDATA2LSB ||
        eh.e_machine != ARCH_EM || (eh.e_type != ET_DYN && eh.e_type != ET_EXEC)) {
        die_msg("cortex-exec: unsupported ELF\n", 126);
    }
    if (eh.e_phnum == 0 || eh.e_phnum > MAX_PHDRS || eh.e_phentsize != sizeof(Elf_Phdr)) {
        die_msg("cortex-exec: invalid program headers\n", 126);
    }

    Elf_Phdr ph[MAX_PHDRS];
    if (pread_all(fd, ph, (size_t)eh.e_phnum * sizeof(Elf_Phdr), (uword)eh.e_phoff) != (long)(eh.e_phnum * sizeof(Elf_Phdr))) {
        die_msg("cortex-exec: cannot read program headers\n", 126);
    }

    if (interp_buf) interp_buf[0] = '\0';
    uword min_va = (uword)-1;
    uword max_va = 0;
    int can_direct_mmap = 1;
    uword prev_seg_end = 0;

    for (uint16_t i = 0; i < eh.e_phnum; i++) {
        if (check_interp && interp_buf && ph[i].p_type == PT_INTERP &&
            ph[i].p_filesz > 1 && ph[i].p_filesz < interp_cap) {
            if (pread_all(fd, interp_buf, (size_t)ph[i].p_filesz, (uword)ph[i].p_offset) == (long)ph[i].p_filesz) {
                interp_buf[ph[i].p_filesz - 1] = '\0';
            }
        }
        if (ph[i].p_type != PT_LOAD || ph[i].p_memsz == 0) continue;
        uword start = ph[i].p_vaddr & ~(pagesz - 1);
        uword end = (ph[i].p_vaddr + ph[i].p_memsz + pagesz - 1) & ~(pagesz - 1);
        if (start < min_va) min_va = start;
        if (end > max_va) max_va = end;
        if (((ph[i].p_vaddr ^ ph[i].p_offset) & (pagesz - 1)) != 0 || start < prev_seg_end) {
            can_direct_mmap = 0;
        }
        prev_seg_end = end;
    }

    if (min_va == (uword)-1 || max_va <= min_va) die_msg("cortex-exec: no PT_LOAD segments\n", 126);

    uword bias = 0;
    if (eh.e_type == ET_DYN) {
        void *res = sys_mmap(NULL, (size_t)(max_va - min_va), PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (res == MAP_FAILED) die_msg("cortex-exec: mmap reserve failed\n", 126);
        bias = (uword)res - min_va;
    } else {
        // Reserve ET_EXEC span on fixed address if non-overlapping; direct MAP_FIXED per segment handles it
    }

    if (can_direct_mmap) {
        for (uint16_t i = 0; i < eh.e_phnum; i++) {
            if (ph[i].p_type != PT_LOAD || ph[i].p_memsz == 0) continue;
            uword seg_va = bias + ph[i].p_vaddr;
            uword map_start = seg_va & ~(pagesz - 1);
            uword map_off = ph[i].p_offset & ~(pagesz - 1);
            uword file_end = seg_va + ph[i].p_filesz;
            uword file_map_end = (file_end + pagesz - 1) & ~(pagesz - 1);
            uword mem_end = (seg_va + ph[i].p_memsz + pagesz - 1) & ~(pagesz - 1);
            int prot = phdr_prot(ph[i].p_flags);

            if (file_map_end > map_start) {
                int map_prot = prot;
                int zero_tail = (ph[i].p_memsz > ph[i].p_filesz) && ((file_end & (pagesz - 1)) != 0);
                if (zero_tail) map_prot |= PROT_WRITE;
                void *m = sys_mmap((void *)map_start, (size_t)(file_map_end - map_start), map_prot,
                                   MAP_PRIVATE | MAP_FIXED, fd, map_off);
                if (m == MAP_FAILED) die_msg("cortex-exec: segment mmap failed\n", 126);
                if (zero_tail) {
                    c_memset((void *)file_end, 0, (size_t)(file_map_end - file_end));
                    if (!(prot & PROT_WRITE)) {
                        sc6(SYS_mprotect, (long)(file_map_end - pagesz), (long)pagesz, prot, 0, 0, 0);
                    }
                }
            }
            if (mem_end > file_map_end) {
                void *b = sys_mmap((void *)file_map_end, (size_t)(mem_end - file_map_end), prot,
                                   MAP_PRIVATE | MAP_FIXED | MAP_ANONYMOUS, -1, 0);
                if (b == MAP_FAILED) die_msg("cortex-exec: bss mmap failed\n", 126);
            }
        }
    } else {
        // 4 KB aligned ELF on a 16 KB page kernel or adjacent sub-page segments: populate anonymously.
        void *mem = sys_mmap((void *)(bias + min_va), (size_t)(max_va - min_va), PROT_READ | PROT_WRITE,
                             MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
        if (mem == MAP_FAILED) die_msg("cortex-exec: fallback mmap failed\n", 126);
        for (uint16_t i = 0; i < eh.e_phnum; i++) {
            if (ph[i].p_type != PT_LOAD || ph[i].p_filesz == 0) continue;
            if (pread_all(fd, (void *)(bias + ph[i].p_vaddr), (size_t)ph[i].p_filesz, (uword)ph[i].p_offset) != (long)ph[i].p_filesz) {
                die_msg("cortex-exec: segment read failed\n", 126);
            }
        }
        // Apply per-page merged protections (if two segments share a 16 KB page, keep union of flags).
        for (uword pg = bias + min_va; pg < bias + max_va; pg += pagesz) {
            int p = 0;
            for (uint16_t i = 0; i < eh.e_phnum; i++) {
                if (ph[i].p_type != PT_LOAD || ph[i].p_memsz == 0) continue;
                uword s = (bias + ph[i].p_vaddr) & ~(pagesz - 1);
                uword e = (bias + ph[i].p_vaddr + ph[i].p_memsz + pagesz - 1) & ~(pagesz - 1);
                if (pg >= s && pg < e) p |= phdr_prot(ph[i].p_flags);
            }
            sc6(SYS_mprotect, (long)pg, (long)pagesz, p ? p : PROT_READ, 0, 0, 0);
        }
    }

    sc6(SYS_close, fd, 0, 0, 0, 0, 0);

    uword phdr_va = bias + eh.e_phoff;
    for (uint16_t i = 0; i < eh.e_phnum; i++) {
        if (ph[i].p_type == PT_LOAD && eh.e_phoff >= ph[i].p_offset &&
            eh.e_phoff < ph[i].p_offset + ph[i].p_filesz) {
            phdr_va = bias + ph[i].p_vaddr + (eh.e_phoff - ph[i].p_offset);
            break;
        }
    }
    out->entry = bias + eh.e_entry;
    out->phdr = phdr_va;
    out->phent = eh.e_phentsize;
    out->phnum = eh.e_phnum;
    out->base = bias + min_va;
}

__attribute__((used))
void cortex_exec_main(uword *orig_sp) {
    uword argc = orig_sp[0];
    char **argv = (char **)&orig_sp[1];
    char **envp = argv + argc + 1;
    char **e = envp;
    while (*e) e++;
    Elf_auxv *aux = (Elf_auxv *)(e + 1);

    uword pagesz = 4096;
    size_t n_aux = 0;
    for (Elf_auxv *a = aux; a->a_type != AT_NULL; a++) {
        if (a->a_type == AT_PAGESZ && a->a_un.a_val > 0) pagesz = (uword)a->a_un.a_val;
        n_aux++;
    }

    const char *bin_path = (argc > 0 && argv[0]) ? argv[0] : NULL;
    long drop_env_idx = -1;
    size_t envc = (size_t)(e - envp);
    for (size_t i = 0; i < envc; i++) {
        if (c_strncmp(envp[i], "CORTEX_EXEC_BIN=", 16) == 0) {
            if (envp[i][16] != '\0') bin_path = envp[i] + 16;
            drop_env_idx = (long)i;
            break;
        }
    }
    if (!bin_path || bin_path[0] == '\0') die_msg("cortex-exec: missing target path\n", 127);

    char interp_path[512];
    struct loaded_image main_img;
    load_elf(bin_path, pagesz, 1, &main_img, interp_path, sizeof(interp_path));

    struct loaded_image interp_img;
    int has_interp = 0;
    if (interp_path[0] != '\0') {
        load_elf(interp_path, pagesz, 0, &interp_img, NULL, 0);
        has_interp = 1;
    }

    set_proc_name(argc > 0 && argv[0] ? argv[0] : bin_path);

    size_t out_envc = drop_env_idx >= 0 ? envc - 1 : envc;
    // Place the target frame well below cortex_exec_main's stack frame so populating it cannot
    // overwrite our locals or saved registers before branching to target_entry.
    size_t aux_slots = n_aux + 8;
    size_t words = 1 + (size_t)argc + 1 + out_envc + 1 + aux_slots * 2;
    uword frame_bytes = (uword)((words * sizeof(uword) + 15) & ~(size_t)15);
    uword *new_sp = (uword *)(((uword)orig_sp - 16384 - frame_bytes) & ~(uword)15);

    size_t pos = 0;
    new_sp[pos++] = argc;
    for (uword i = 0; i < argc; i++) new_sp[pos++] = (uword)argv[i];
    new_sp[pos++] = 0;
    for (size_t i = 0; i < envc; i++) {
        if ((long)i == drop_env_idx) continue;
        new_sp[pos++] = (uword)envp[i];
    }
    new_sp[pos++] = 0;

    Elf_auxv *dst_aux = (Elf_auxv *)&new_sp[pos];
    size_t da = 0;
    int seen_phdr = 0, seen_phent = 0, seen_phnum = 0, seen_entry = 0, seen_base = 0, seen_execfn = 0;
    uword base_val = has_interp ? interp_img.base : 0;

    for (size_t i = 0; i < n_aux; i++) {
        Elf_auxv entry = aux[i];
        switch (entry.a_type) {
        case AT_PHDR:   entry.a_un.a_val = main_img.phdr; seen_phdr = 1; break;
        case AT_PHENT:  entry.a_un.a_val = main_img.phent; seen_phent = 1; break;
        case AT_PHNUM:  entry.a_un.a_val = main_img.phnum; seen_phnum = 1; break;
        case AT_ENTRY:  entry.a_un.a_val = main_img.entry; seen_entry = 1; break;
        case AT_BASE:   entry.a_un.a_val = base_val; seen_base = 1; break;
        case AT_EXECFN: entry.a_un.a_val = (uword)bin_path; seen_execfn = 1; break;
        default: break;
        }
        dst_aux[da++] = entry;
    }
    if (!seen_phdr)   dst_aux[da++] = (Elf_auxv){ .a_type = AT_PHDR, .a_un.a_val = main_img.phdr };
    if (!seen_phent)  dst_aux[da++] = (Elf_auxv){ .a_type = AT_PHENT, .a_un.a_val = main_img.phent };
    if (!seen_phnum)  dst_aux[da++] = (Elf_auxv){ .a_type = AT_PHNUM, .a_un.a_val = main_img.phnum };
    if (!seen_entry)  dst_aux[da++] = (Elf_auxv){ .a_type = AT_ENTRY, .a_un.a_val = main_img.entry };
    if (!seen_base)   dst_aux[da++] = (Elf_auxv){ .a_type = AT_BASE, .a_un.a_val = base_val };
    if (!seen_execfn) dst_aux[da++] = (Elf_auxv){ .a_type = AT_EXECFN, .a_un.a_val = (uword)bin_path };
    dst_aux[da] = (Elf_auxv){ .a_type = AT_NULL, .a_un.a_val = 0 };

    uword target_entry = has_interp ? interp_img.entry : main_img.entry;
#if defined(__aarch64__)
    register uword r_sp __asm__("x19") = (uword)new_sp;
    register uword r_entry __asm__("x16") = target_entry;
    __asm__ volatile(
        "mov sp, %0\n"
        "mov x0, #0\n"
        "mov x1, #0\n"
        "mov x2, #0\n"
        "mov x3, #0\n"
        "mov x4, #0\n"
        "mov x5, #0\n"
        "mov x6, #0\n"
        "mov x7, #0\n"
        "mov x8, #0\n"
        "mov x9, #0\n"
        "mov x10, #0\n"
        "mov x11, #0\n"
        "mov x12, #0\n"
        "mov x13, #0\n"
        "mov x14, #0\n"
        "mov x15, #0\n"
        "mov x17, #0\n"
        "mov x18, #0\n"
        "mov x19, #0\n"
        "mov x20, #0\n"
        "mov x21, #0\n"
        "mov x22, #0\n"
        "mov x23, #0\n"
        "mov x24, #0\n"
        "mov x25, #0\n"
        "mov x26, #0\n"
        "mov x27, #0\n"
        "mov x28, #0\n"
        "mov x29, #0\n"
        "mov x30, #0\n"
        "br x16\n"
        :
        : "r"(r_sp), "r"(r_entry)
        : "memory");
#elif defined(__arm__)
    register uword r_sp __asm__("r4") = (uword)new_sp;
    register uword r_entry __asm__("r12") = target_entry;
    __asm__ volatile(
        "mov sp, %0\n"
        "mov r0, #0\n"
        "mov r1, #0\n"
        "mov r2, #0\n"
        "mov r3, #0\n"
        "mov r4, #0\n"
        "mov r5, #0\n"
        "mov r6, #0\n"
        "mov r7, #0\n"
        "mov r8, #0\n"
        "mov r9, #0\n"
        "mov r10, #0\n"
        "mov r11, #0\n"
        "mov lr, #0\n"
        "bx r12\n"
        :
        : "r"(r_sp), "r"(r_entry)
        : "memory");
#endif
    __builtin_unreachable();
}

#if defined(__aarch64__)
__asm__(
    ".text\n"
    ".global _start\n"
    ".type _start, %function\n"
    "_start:\n"
    "  mov x29, #0\n"
    "  mov x30, #0\n"
    "  mov x0, sp\n"
    "  b cortex_exec_main\n"
);
#elif defined(__arm__)
__asm__(
    ".text\n"
    ".syntax unified\n"
#ifdef __thumb__
    ".thumb\n"
    ".thumb_func\n"
#endif
    ".global _start\n"
    ".type _start, %function\n"
    "_start:\n"
    "  mov fp, #0\n"
    "  mov lr, #0\n"
    "  mov r0, sp\n"
    "  b cortex_exec_main\n"
);
#endif
