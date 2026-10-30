// Part of libcortex-hook.so, included by cortex-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// What execve() and posix_spawn() need to know about a program: its ld.so, whether it is a
// dynamic ELF, a script and its interpreter, and the argument lists built from that.

// Relative exec paths ("./configure", "bin/tool", "../x.sh") must go through the same
// CORTEX_ROOT routing as absolute ones. Without this they were mistaken for Android host
// binaries and handed to the kernel unchanged, which then resolved "#!/bin/bash" (or a glibc
// PT_INTERP) literally and failed with "bad interpreter: No such file or directory".
// getcwd() is not virtualised, so cwd + path is a real path that starts with CORTEX_ROOT.
static const char *absolutize_exec_path(const char *path, char *buffer, size_t bufsize) {
    if (!path || path[0] == '\0' || path[0] == '/') return path;
    char cwd[PATH_MAX];
    static char *(*real_getcwd)(char *, size_t) = NULL;
    if (!real_getcwd) real_getcwd = (char *(*)(char *, size_t))dlsym(RTLD_NEXT, "getcwd");
    if (!real_getcwd || !real_getcwd(cwd, sizeof(cwd))) return path;

    while (path[0] == '.' && path[1] == '/') {
        path += 2;
        while (path[0] == '/') path++;
    }
    if (path[0] == '\0' || strcmp(path, ".") == 0) return path;

    size_t cwd_len = strlen(cwd);
    while (cwd_len > 1 && cwd[cwd_len - 1] == '/') cwd[--cwd_len] = '\0';
    int n = snprintf(buffer, bufsize, "%s%s%s", cwd, (cwd_len == 1 && cwd[0] == '/') ? "" : "/", path);
    if (n < 0 || (size_t)n >= bufsize) return path;
    return buffer;
}

static char s_cached_ld_so[PATH_MAX] = {0};
static char s_cached_ld_root[PATH_MAX] = {0};
static uint16_t s_cached_e_machine = 0;

// ld.so inside `cortex_root` for an ELF with machine type `e_machine` (0 when unknown).
static int find_dynamic_linker_for_machine(const char *cortex_root, uint16_t e_machine, char *out_ld_so, size_t max_len) {
    if (!cortex_root || cortex_root[0] == '\0' || !out_ld_so || max_len == 0) return 0;

    if (s_cached_ld_so[0] != '\0' && strcmp(s_cached_ld_root, cortex_root) == 0 &&
        e_machine != 0 && e_machine == s_cached_e_machine) {
        strncpy(out_ld_so, s_cached_ld_so, max_len - 1);
        out_ld_so[max_len - 1] = '\0';
        return 1;
    }

    const char *aarch64_cands[] = {
        "/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        "/usr/lib/aarch64-linux-gnu/ld-2.39.so",
        "/lib/ld-linux-aarch64.so.1",
        "/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        "/usr/lib/ld-linux-aarch64.so.1",
        "/usr/lib64/ld-linux-aarch64.so.1",
        "/lib64/ld-linux-aarch64.so.1",
        "/lib/aarch64-linux-gnu/ld-2.39.so",
        NULL
    };

    const char *armhf_cands[] = {
        "/usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3",
        "/usr/lib/arm-linux-gnueabihf/ld-2.39.so",
        "/lib/ld-linux-armhf.so.3",
        "/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3",
        "/usr/lib/ld-linux-armhf.so.3",
        "/lib/arm-linux-gnueabihf/ld-2.39.so",
        "/usr/lib/arm-linux-gnueabi/ld-linux.so.3",
        "/lib/arm-linux-gnueabi/ld-linux.so.3",
        NULL
    };

    const char *x86_64_cands[] = {
        "/lib64/ld-linux-x86-64.so.2",
        "/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
        "/usr/lib/x86_64-linux-gnu/ld-2.39.so",
        "/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
        NULL
    };

    const char **primary = NULL;
    const char **secondary = NULL;

    if (e_machine == 183) { // EM_AARCH64
        primary = aarch64_cands;
        secondary = NULL;
    } else if (e_machine == 40) { // EM_ARM
        primary = armhf_cands;
        secondary = NULL;
    } else if (e_machine == 62) { // EM_X86_64
        primary = x86_64_cands;
        secondary = NULL;
    } else if (e_machine == 3) { // EM_386
        primary = NULL;
        secondary = NULL;
    } else {
        #if defined(__aarch64__)
        primary = aarch64_cands;
        secondary = armhf_cands;
        #elif defined(__arm__)
        primary = armhf_cands;
        secondary = aarch64_cands;
        #else
        primary = x86_64_cands;
        #endif
    }

    if (primary) {
        for (int i = 0; primary[i] != NULL; i++) {
            snprintf(out_ld_so, max_len, "%s%s", cortex_root, primary[i]);
            if (access(out_ld_so, F_OK) == 0) {
                if (e_machine != 0) {
                    strncpy(s_cached_ld_so, out_ld_so, sizeof(s_cached_ld_so) - 1);
                    s_cached_ld_so[sizeof(s_cached_ld_so) - 1] = '\0';
                    strncpy(s_cached_ld_root, cortex_root, sizeof(s_cached_ld_root) - 1);
                    s_cached_ld_root[sizeof(s_cached_ld_root) - 1] = '\0';
                    s_cached_e_machine = e_machine;
                }
                return 1;
            }
        }
    }
    if (secondary) {
        for (int i = 0; secondary[i] != NULL; i++) {
            snprintf(out_ld_so, max_len, "%s%s", cortex_root, secondary[i]);
            if (access(out_ld_so, F_OK) == 0) {
                if (e_machine != 0) {
                    strncpy(s_cached_ld_so, out_ld_so, sizeof(s_cached_ld_so) - 1);
                    s_cached_ld_so[sizeof(s_cached_ld_so) - 1] = '\0';
                    strncpy(s_cached_ld_root, cortex_root, sizeof(s_cached_ld_root) - 1);
                    s_cached_ld_root[sizeof(s_cached_ld_root) - 1] = '\0';
                    s_cached_e_machine = e_machine;
                }
                return 1;
            }
        }
    }

    return 0;
}

#ifdef CORTEX_HOST_TEST
// Test entry point taking a program instead of its machine type (cortex-pty.c, which the tests
// include too, has a find_dynamic_linker of its own).
static int hook_find_dynamic_linker(const char *cortex_root, const char *cmd, char *out_ld_so, size_t max_len) {
    if (!cortex_root || cortex_root[0] == '\0' || !out_ld_so || max_len == 0) return 0;

    uint16_t e_machine = 0;
    if (cmd && cmd[0] != '\0') {
        int fd = open(cmd, O_RDONLY);
        if (fd >= 0) {
            unsigned char ehdr[20];
            ssize_t n = read(fd, ehdr, sizeof(ehdr));
            close(fd);
            if (n >= 20 && ehdr[0] == 0x7f && ehdr[1] == 'E' && ehdr[2] == 'L' && ehdr[3] == 'F') {
                e_machine = (uint16_t)(ehdr[18] | (ehdr[19] << 8));
            }
        }
    }
    return find_dynamic_linker_for_machine(cortex_root, e_machine, out_ld_so, max_len);
}
#endif

static int check_elf_dynamic(int fd, const unsigned char *ehdr, ssize_t n) {
    if (n < 52 || ehdr[0] != 0x7f || ehdr[1] != 'E' || ehdr[2] != 'L' || ehdr[3] != 'F') {
        return 0;
    }
    if ((ehdr[4] != 1 && ehdr[4] != 2) || ehdr[5] != 1 /* ELFDATA2LSB */) {
        return 0;
    }
    int is_64 = (ehdr[4] == 2);
    uint64_t phoff = 0;
    uint16_t phentsize = 0;
    uint16_t phnum = 0;
    if (is_64) {
        if (n < 64) return 0;
        memcpy(&phoff, ehdr + 32, sizeof(uint64_t));
        memcpy(&phentsize, ehdr + 54, sizeof(uint16_t));
        memcpy(&phnum, ehdr + 56, sizeof(uint16_t));
    } else {
        uint32_t phoff32 = 0;
        memcpy(&phoff32, ehdr + 28, sizeof(uint32_t));
        phoff = phoff32;
        memcpy(&phentsize, ehdr + 42, sizeof(uint16_t));
        memcpy(&phnum, ehdr + 44, sizeof(uint16_t));
    }
    uint16_t min_phentsize = is_64 ? 56 : 32;
    if (phoff == 0 || phentsize < min_phentsize || phentsize > 4096 || phnum == 0) {
        return 0;
    }
    if ((off_t)phoff < 0 || (uint64_t)(off_t)phoff != phoff) {
        return 0;
    }
    if (lseek(fd, (off_t)phoff, SEEK_SET) < 0) {
        return 0;
    }
    for (int i = 0; i < phnum && i < 128; i++) {
        uint32_t p_type = 0;
        if (read(fd, &p_type, sizeof(p_type)) != sizeof(p_type)) break;
        if (p_type == 3 /* PT_INTERP */) {
            return 1;
        }
        if (lseek(fd, (off_t)(phentsize - sizeof(p_type)), SEEK_CUR) < 0) break;
    }
    return 0;
}

// check_elf_dynamic() on the bytes already read: 1 or 0, or -1 when a program header lies past
// the buffer and the file has to be read after all. Program headers normally follow the ELF
// header directly, so this saves the lseek()/read() pairs.
static int elf_interp_in_buffer(const unsigned char *ehdr, ssize_t n) {
    if (n < 52 || ehdr[0] != 0x7f || ehdr[1] != 'E' || ehdr[2] != 'L' || ehdr[3] != 'F') {
        return 0;
    }
    if ((ehdr[4] != 1 && ehdr[4] != 2) || ehdr[5] != 1 /* ELFDATA2LSB */) {
        return 0;
    }
    int is_64 = (ehdr[4] == 2);
    uint64_t phoff = 0;
    uint16_t phentsize = 0;
    uint16_t phnum = 0;
    if (is_64) {
        if (n < 64) return 0;
        memcpy(&phoff, ehdr + 32, sizeof(uint64_t));
        memcpy(&phentsize, ehdr + 54, sizeof(uint16_t));
        memcpy(&phnum, ehdr + 56, sizeof(uint16_t));
    } else {
        uint32_t phoff32 = 0;
        memcpy(&phoff32, ehdr + 28, sizeof(uint32_t));
        phoff = phoff32;
        memcpy(&phentsize, ehdr + 42, sizeof(uint16_t));
        memcpy(&phnum, ehdr + 44, sizeof(uint16_t));
    }
    uint16_t min_phentsize = is_64 ? 56 : 32;
    if (phoff == 0 || phentsize < min_phentsize || phentsize > 4096 || phnum == 0) {
        return 0;
    }
    if ((off_t)phoff < 0 || (uint64_t)(off_t)phoff != phoff) {
        return 0;
    }
    for (int i = 0; i < phnum && i < 128; i++) {
        uint64_t off = phoff + (uint64_t)i * phentsize;
        if (off > (uint64_t)n || (uint64_t)n - off < sizeof(uint32_t)) return -1;
        uint32_t p_type = 0;
        memcpy(&p_type, ehdr + off, sizeof(p_type));
        if (p_type == 3 /* PT_INTERP */) {
            return 1;
        }
    }
    return 0;
}

// Whether the interpreter of a 64-bit ELF is Android's own linker (/system/bin/linker64). A Go
// program built with GOOS=android is a PIE that asks for it, so it has a PT_INTERP and looks like a
// dynamic program, but it has no C library and makes its own system calls like every Go program
// (#42). `h` holds the `n` first bytes of the file; the program headers usually are among them.
static int elf_interp_is_android(int fd, const unsigned char *h, ssize_t n) {
    if (n < 64 || h[4] != 2) return 0;
    uint64_t phoff = 0;
    uint16_t phentsize = 0, phnum = 0;
    memcpy(&phoff, h + 32, sizeof(phoff));
    memcpy(&phentsize, h + 54, sizeof(phentsize));
    memcpy(&phnum, h + 56, sizeof(phnum));
    if (phoff == 0 || phoff > (1ULL << 40) || phentsize < 56 || phentsize > 4096 || phnum == 0) return 0;
    for (int i = 0; i < phnum && i < 128; i++) {
        unsigned char e[56];
        uint64_t off = phoff + (uint64_t)i * phentsize;
        if (off + sizeof(e) <= (uint64_t)n) {
            memcpy(e, h + off, sizeof(e));
        } else if (pread(fd, e, sizeof(e), (off_t)off) != (ssize_t)sizeof(e)) {
            return 0;
        }
        uint32_t p_type = 0;
        memcpy(&p_type, e, sizeof(p_type));
        if (p_type != 3 /* PT_INTERP */) continue;
        uint64_t p_offset = 0, p_filesz = 0;
        memcpy(&p_offset, e + 8, sizeof(p_offset));
        memcpy(&p_filesz, e + 32, sizeof(p_filesz));
        char interp[48];
        size_t want = p_filesz < sizeof(interp) - 1 ? (size_t)p_filesz : sizeof(interp) - 1;
        if (want == 0 || p_offset > (1ULL << 40)) return 0;
        ssize_t got = pread(fd, interp, want, (off_t)p_offset);
        if (got <= 0) return 0;
        interp[got] = '\0';
        return strncmp(interp, "/system/bin/linker", 18) == 0;
    }
    return 0;
}

// The kernel follows at most four nested "#!" interpreters, then fails with ELOOP.
#define CORTEX_MAX_SHEBANG_DEPTH 4

// What execve()/posix_spawn() need to know about the file they are about to start, from a
// single open()/read() (they used to open it up to three times).
struct exec_probe {
    char hdr[256];      // first bytes of the file, NUL-terminated for "#!" parsing
    ssize_t n;          // bytes read; -1 when the file could not be opened
    int is_elf;
    int is_dynamic;     // ELF with PT_INTERP: has to be started through ld.so
    int android_interp; // AArch64 ELF whose PT_INTERP is Android's linker (a Go program for GOOS=android)
    uint16_t e_machine;
    mode_t mode;        // st_mode, filled in for dynamic ELF files only
};

static void probe_exec_file(const char *path, struct exec_probe *p) {
    p->hdr[0] = '\0';
    p->n = -1;
    p->is_elf = 0;
    p->is_dynamic = 0;
    p->android_interp = 0;
    p->e_machine = 0;
    p->mode = 0;
    if (!path || path[0] == '\0') return;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    ssize_t n = read(fd, p->hdr, sizeof(p->hdr) - 1);
    p->n = n;
    p->hdr[n > 0 ? n : 0] = '\0';
    const unsigned char *h = (const unsigned char *)p->hdr;
    if (n >= 4 && h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F') {
        p->is_elf = 1;
        if (n >= 20) p->e_machine = (uint16_t)(h[18] | (h[19] << 8));
        int dynamic = elf_interp_in_buffer(h, n);
        if (dynamic < 0) dynamic = check_elf_dynamic(fd, h, n);
        p->is_dynamic = dynamic;
        if (dynamic && p->e_machine == 183 /* EM_AARCH64 */) p->android_interp = elf_interp_is_android(fd, h, n);
        struct stat st;
        if (dynamic && fstat(fd, &st) == 0) p->mode = st.st_mode;
    }
    close(fd);
}

static int is_ld_linux(const char *path) {
    if (!path) return 0;
    const char *base = strrchr(path, '/');
    base = base ? base + 1 : path;
    return (strncmp(base, "ld-linux", 8) == 0 || strncmp(base, "ld-2.", 5) == 0);
}

// Splits a "#!" line (NUL-terminated `hdr`, modified in place) into the interpreter and its
// optional argument, the way execve() always has.
static void parse_shebang(char *hdr, char **interp, char **interp_arg) {
    char *newline = strchr(hdr, '\n');
    if (newline) *newline = '\0';
    char *p = hdr + 2;
    while (*p == ' ' || *p == '\t') p++;
    char *arg = strchr(p, ' ');
    if (arg) {
        *arg = '\0';
        arg++;
        while (*arg == ' ' || *arg == '\t') arg++;
    }
    *interp = p;
    *interp_arg = arg;
}

// Interpreters outside the rootfs (Android's /system/bin/sh) run with Android's environment.
static int is_host_interpreter(const char *interp) {
    return strncmp(interp, "/system", 7) == 0 ||
           (g_cortex_root[0] != '\0' && strncmp(interp, g_cortex_root, g_cortex_root_len) != 0);
}

// argv for running `script` with its interpreter: interp [arg] script args... (the script's own
// argv[0] is dropped, as the kernel does). free() the result.
static char **build_script_argv(const char *interp, const char *interp_arg, const char *script, char *const argv[]) {
    int argc = 0;
    while (argv && argv[argc]) argc++;
    int has_arg = (interp_arg && *interp_arg) ? 1 : 0;
    char **v = (char **)calloc(argc + has_arg + 3, sizeof(char *));
    if (!v) return NULL;
    int n = 0;
    v[n++] = (char *)interp;
    if (has_arg) v[n++] = (char *)interp_arg;
    v[n++] = (char *)script;
    for (int i = 1; i < argc; i++) v[n++] = argv[i];
    v[n] = NULL;
    return v;
}

// argv for "ld.so [--preload hook] --library-path dirs --argv0 name program args...".
// free() the result; the strings stay owned by the caller.
static char **build_ldso_argv(const char *ld_so, const char *hook, const char *lib_path,
                              const char *program, const char *prog_name, char *const argv[]) {
    int argc = 0;
    while (argv && argv[argc]) argc++;
    char **v = (char **)calloc(argc + 16, sizeof(char *));
    if (!v) return NULL;
    int n = 0;
    v[n++] = (char *)ld_so;
    if (hook) {
        v[n++] = (char *)"--preload";
        v[n++] = (char *)hook;
    }
    v[n++] = (char *)"--library-path";
    v[n++] = (char *)lib_path;
    v[n++] = (char *)"--argv0";
    v[n++] = (char *)((argc > 0 && argv[0]) ? argv[0] : prog_name);
    v[n++] = (char *)program;
    for (int i = 1; i < argc; i++) v[n++] = argv[i];
    v[n] = NULL;
    return v;
}

// POSIX_SPAWN_RESETIDS makes glibc's spawn child call setresuid() and setresgid() after it has reset
// every signal handler to its default, which includes the SIGSYS handler that emulates the calls
// Android's seccomp filter blocks, so the child died of SIGSYS (GNU make asks for the flag on every
// command it starts). Cortex shows one fixed user id and never runs set-user-id programs: there is
// no id to reset, so the flag is left out and everything else is passed on.
static const posix_spawnattr_t *spawn_attr_for_android(const posix_spawnattr_t *attrp, posix_spawnattr_t *copy) {
    short flags = 0;
    if (!attrp || posix_spawnattr_getflags(attrp, &flags) != 0 || !(flags & POSIX_SPAWN_RESETIDS)) return attrp;
    memcpy(copy, attrp, sizeof(*copy));
    posix_spawnattr_setflags(copy, (short)(flags & ~POSIX_SPAWN_RESETIDS));
    return copy;
}
