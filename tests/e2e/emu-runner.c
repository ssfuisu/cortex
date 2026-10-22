// binfmt_misc interpreter for arm64 programs on the x86_64 test emulator (run-emulator-test.sh
// registers it in place of the image's own entries). Not part of the app.
//
// The emulator runs arm64 programs through ndk_translation's program runner, an x86_64 Android
// program whose own (bionic) linker reads LD_PRELOAD and LD_LIBRARY_PATH. Cortex sets both for
// Ubuntu's glibc, so the runner tried to load Ubuntu's libc.so and the arm64 hook and failed before
// the program started. This removes them from the environment and, when the program is glibc's
// ld.so, passes them on as its --preload and --library-path options instead. (Cortex's hook adds
// both to the environment again for every program it starts, and starts rootfs programs through
// ld.so with those options already.)
//
// Without a C library: a static glibc program would itself be stopped by Android's seccomp filter.
//
//     argv (binfmt_misc flag P): this program, the arm64 file, its argv[0], its arguments...

#ifndef RUNNER
#define RUNNER "/system/bin/ndk_translation_program_runner_binfmt_misc_arm64"
#endif

#define MAX_ARGS 4096
#define MAX_ENV 4096

static long sys3(long n, long a, long b, long c) {
    long r;
    __asm__ volatile("syscall" : "=a"(r) : "a"(n), "D"(a), "S"(b), "d"(c) : "rcx", "r11", "memory");
    return r;
}

static void say(const char *s) {
    long n = 0;
    while (s[n]) n++;
    sys3(1, 2, (long)s, n); // write(2, ...)
}

static int starts_with(const char *s, const char *prefix) {
    while (*prefix)
        if (*s++ != *prefix++) return 0;
    return 1;
}

static int equal(const char *a, const char *b) {
    while (*a && *a == *b) a++, b++;
    return *a == *b;
}

// ld.so's options that are followed by a value.
static int takes_value(const char *option) {
    static const char *const with_value[] = {
        "--preload", "--library-path", "--argv0", "--audit", "--inhibit-rpath",
        "--glibc-hwcaps-prepend", "--glibc-hwcaps-mask",
    };
    for (unsigned i = 0; i < sizeof(with_value) / sizeof(with_value[0]); i++)
        if (equal(option, with_value[i])) return 1;
    return 0;
}

static const char *base_name(const char *path) {
    const char *b = path;
    for (; *path; path++)
        if (*path == '/') b = path + 1;
    return b;
}

static char *new_argv[MAX_ARGS];
static char *new_envp[MAX_ENV];

__attribute__((used)) void emu_main(long *sp) {
    long argc = sp[0];
    char **argv = (char **)(sp + 1);
    char **envp = argv + argc + 1;
    if (argc < 3 || argc + 8 > MAX_ARGS) {
        say("cortex-emu-runner: unexpected arguments\n");
        sys3(60, 126, 0, 0);
    }

    char *preload = 0, *lib_path = 0;
    long ne = 0;
    for (char **e = envp; *e && ne < MAX_ENV - 1; e++) {
        if (starts_with(*e, "LD_PRELOAD=")) preload = *e + 11;
        else if (starts_with(*e, "LD_LIBRARY_PATH=")) lib_path = *e + 16;
        else new_envp[ne++] = *e;
    }
    new_envp[ne] = 0;

    // ld.so's own options come first; the ones it already has are left as they are.
    const char *name = base_name(argv[1]);
    int is_ldso = starts_with(name, "ld-linux") || starts_with(name, "ld-2.");
    if (is_ldso) {
        for (long i = 3; i < argc && starts_with(argv[i], "--"); i++) {
            if (equal(argv[i], "--preload")) preload = 0;
            if (equal(argv[i], "--library-path")) lib_path = 0;
            if (takes_value(argv[i])) i++;
        }
    }

    long na = 0;
    new_argv[na++] = RUNNER;
    new_argv[na++] = argv[1];
    new_argv[na++] = argv[2];
    if (is_ldso && preload && *preload) {
        new_argv[na++] = "--preload";
        new_argv[na++] = preload;
    }
    if (is_ldso && lib_path && *lib_path) {
        new_argv[na++] = "--library-path";
        new_argv[na++] = lib_path;
    }
    for (long i = 3; i < argc; i++) new_argv[na++] = argv[i];
    new_argv[na] = 0;

    sys3(59, (long)RUNNER, (long)new_argv, (long)new_envp); // execve
    say("cortex-emu-runner: cannot start " RUNNER "\n");
    sys3(60, 127, 0, 0);
}

__asm__(".text\n"
        ".global _start\n"
        "_start:\n"
        "    xor %rbp, %rbp\n"
        "    mov %rsp, %rdi\n"
        "    and $-16, %rsp\n"
        "    call emu_main\n"
        "    hlt\n");
