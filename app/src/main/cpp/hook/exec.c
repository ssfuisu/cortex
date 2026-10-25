// Part of libcortex-hook.so, included by cortex-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// execve() and posix_spawn(): rootfs programs through ld.so with the hook, scripts through their
// interpreter, Android programs with Android's environment.

#ifndef CORTEX_HOST_TEST

static void resolve_symlinks(const char *path, char *out, size_t out_size, int depth) {
    if (!path || !out || out_size == 0) return;
    strncpy(out, path, out_size - 1);
    out[out_size - 1] = '\0';
    if (depth > 8) return;

    char link_target[PATH_MAX];
    ssize_t len = readlink(path, link_target, sizeof(link_target) - 1);
    if (len < 0) return;
    link_target[len] = '\0';

    char next_path[PATH_MAX];
    if (link_target[0] == '/') {
        char rw_buf[PATH_MAX];
        const char *rewritten = rewrite_path(link_target, rw_buf, sizeof(rw_buf));
        strncpy(next_path, rewritten, sizeof(next_path) - 1);
        next_path[sizeof(next_path) - 1] = '\0';
    } else {
        char dir_buf[PATH_MAX];
        strncpy(dir_buf, path, sizeof(dir_buf) - 1);
        dir_buf[sizeof(dir_buf) - 1] = '\0';
        char *slash = strrchr(dir_buf, '/');
        if (slash) {
            *slash = '\0';
            snprintf(next_path, sizeof(next_path), "%s/%s", dir_buf, link_target);
        } else {
            strncpy(next_path, link_target, sizeof(next_path) - 1);
            next_path[sizeof(next_path) - 1] = '\0';
        }
    }
    resolve_symlinks(next_path, out, out_size, depth + 1);
}

static char **ensure_hook_in_envp(char *const envp[], const char *real_exe) {
    return prepare_cortex_env(envp, real_exe);
}

static void build_ld_library_path(char *const envp[], char *out_buf, size_t out_size) {
    if (!out_buf || out_size == 0) return;
    out_buf[0] = '\0';

    const char *caller_ld = NULL;
    if (envp) {
        for (int i = 0; envp[i] != NULL; i++) {
            if (envp[i][0] == 'L' && strncmp(envp[i], "LD_LIBRARY_PATH=", 16) == 0) {
                caller_ld = envp[i] + 16;
                break;
            }
        }
    }
    if (!caller_ld) {
        caller_ld = getenv("LD_LIBRARY_PATH");
    }

    // No "= {0}": zero-filling these buffers touched 8 KB of fresh stack in every forked child.
    char rewritten_caller[PATH_MAX * 2];
    rewritten_caller[0] = '\0';
    if (caller_ld && caller_ld[0] != '\0') {
        char copy[PATH_MAX * 2];
        strncpy(copy, caller_ld, sizeof(copy) - 1);
        copy[sizeof(copy) - 1] = '\0';
        size_t used = 0;
        char *saveptr = NULL;
        char *tok = strtok_r(copy, ":", &saveptr);
        while (tok) {
            if (tok[0] != '\0') {
                char rw_buf[PATH_MAX];
                const char *rw = (tok[0] == '/') ? rewrite_path(tok, rw_buf, sizeof(rw_buf)) : tok;
                int n = snprintf(rewritten_caller + used, sizeof(rewritten_caller) - used,
                                 "%s%s", (used > 0 ? ":" : ""), rw);
                if (n > 0 && used + (size_t)n < sizeof(rewritten_caller)) {
                    used += (size_t)n;
                }
            }
            tok = strtok_r(NULL, ":", &saveptr);
        }
    }

    size_t used = cortex_append(out_buf, out_size, 0, rewritten_caller, strlen(rewritten_caller));
    append_rootfs_lib_dirs_missing_from(out_buf, out_size, used, used > 0, rewritten_caller);
}

// The file whose header decides how to start `target`: the symlink-resolved ELF (so ld.so and
// /proc/self/exe see the real program, as with the kernel), else `target` itself (scripts,
// links to ld.so). `probe` describes the returned file.
static const char *select_exec_target(const char *target, char *resolved, size_t resolved_size, struct exec_probe *probe) {
    resolve_symlinks(target, resolved, resolved_size, 0);
    probe_exec_file(resolved, probe);
    if (probe->is_elf && !is_ld_linux(resolved)) return resolved;
    if (strcmp(resolved, target) != 0) probe_exec_file(target, probe);
    return target;
}

// The library children get with --preload: the one this process runs from, so that a program
// always starts with the same build as its parent, else the first of usr/lib and lib that exists;
// NULL if neither does.
static const char *cortex_hook_library(char *buf, size_t bufsz) {
    static const char usr_lib[] = "/usr/lib/libcortex-hook.so";
    static const char lib[] = "/lib/libcortex-hook.so";
    Dl_info info;
    if (dladdr((void *)cortex_hook_library, &info) && info.dli_fname && info.dli_fname[0] == '/' &&
        strlen(info.dli_fname) < bufsz) {
        return strcpy(buf, info.dli_fname);  // loaded from there, so it exists
    }
    size_t n = cortex_append(buf, bufsz, 0, g_cortex_root, g_cortex_root_len);
    cortex_append(buf, bufsz, n, usr_lib, sizeof(usr_lib) - 1);
    if (access(buf, F_OK) == 0) return buf;
    n = cortex_append(buf, bufsz, 0, g_cortex_root, g_cortex_root_len);
    cortex_append(buf, bufsz, n, lib, sizeof(lib) - 1);
    return access(buf, F_OK) == 0 ? buf : NULL;
}

// ld.so does not need the exec bit on the program it loads, but other tools look at it: set
// it as the old access(X_OK)/chmod() pair did, using the mode the probe already has.
static void ensure_exec_bit(const char *path, mode_t mode) {
    if (S_ISDIR(mode) || (mode & 0111) != 0) return;
    if (strstr(path, "/bin/") || strstr(path, "/sbin/")) return;
    chmod(path, 0755);
}

// Rootfs path of a "#!" interpreter; a missing /bin/sh falls back to Android's shell.
static const char *shebang_interpreter(const char *interp, char *buf, size_t bufsz) {
    const char *rewritten = rewrite_path(interp, buf, bufsz);
    if (strcmp(interp, "/bin/sh") == 0 && access(rewritten, F_OK) != 0 && access("/system/bin/sh", X_OK) == 0) {
        return "/system/bin/sh";
    }
    return rewritten;
}

static const char *exec_prog_name(const char *target) {
    const char *slash = strrchr(target, '/');
    return slash ? slash + 1 : target;
}

// Hook execve
typedef int (*orig_execve_f_type)(const char *filename, char *const argv[], char *const envp[]);

static int cortex_execve(const char *filename, char *const argv[], char *const envp[], int depth) {
    static orig_execve_f_type orig_execve = NULL;
    if (!orig_execve) orig_execve = (orig_execve_f_type)dlsym(RTLD_NEXT, "execve");

    // Scripts get the path exactly as the caller wrote it ("./x.sh" stays "./x.sh" in $0),
    // like the kernel would pass it; routing below uses the absolute form.
    const char *caller_path = filename;
    char abs_buf[PATH_MAX];
    filename = absolutize_exec_path(filename, abs_buf, sizeof(abs_buf));

    char buf[PATH_MAX];
    const char *target = rewrite_path(filename, buf, sizeof(buf));

    init_cortex_hook();

    const char *f_base = (filename != NULL) ? strrchr(filename, '/') : NULL;
    f_base = (f_base != NULL) ? f_base + 1 : (filename != NULL ? filename : "");

    const char *prog_name = strrchr(target, '/');
    prog_name = (prog_name != NULL) ? prog_name + 1 : target;

    // Intercept utilities that fail or cause issues in unprivileged Android environment
    if (strcmp(f_base, "ldconfig") == 0 ||
        strcmp(f_base, "ldconfig.real") == 0 ||
        strcmp(f_base, "systemd-machine-id-setup") == 0 ||
        strcmp(f_base, "systemd-sysusers") == 0 ||
        strcmp(f_base, "systemd-tmpfiles") == 0 ||
        strcmp(f_base, "start-stop-daemon") == 0 ||
        strcmp(f_base, "mandb") == 0 ||
        strcmp(f_base, "mandb.real") == 0 ||
        strcmp(f_base, "update-mime-database") == 0 ||
        strcmp(f_base, "update-desktop-database") == 0 ||
        strcmp(f_base, "install-info") == 0 ||
        strcmp(f_base, "install-sgmlcatalog") == 0 ||
        strcmp(prog_name, "ldconfig") == 0 ||
        strcmp(prog_name, "ldconfig.real") == 0 ||
        strcmp(prog_name, "systemd-machine-id-setup") == 0 ||
        strcmp(prog_name, "systemd-sysusers") == 0 ||
        strcmp(prog_name, "systemd-tmpfiles") == 0 ||
        strcmp(prog_name, "start-stop-daemon") == 0 ||
        strcmp(prog_name, "mandb") == 0 ||
        strcmp(prog_name, "mandb.real") == 0 ||
        strcmp(prog_name, "update-mime-database") == 0 ||
        strcmp(prog_name, "update-desktop-database") == 0 ||
        strcmp(prog_name, "install-info") == 0 ||
        strcmp(prog_name, "install-sgmlcatalog") == 0) {
        _exit(0);
    }
    if (strcmp(f_base, "mountpoint") == 0 || strcmp(prog_name, "mountpoint") == 0) {
        int is_virtual_fs = 0;
        for (int i = 1; argv && argv[i]; i++) {
            if (strcmp(argv[i], "/proc") == 0 || strcmp(argv[i], "/proc/") == 0 ||
                strcmp(argv[i], "/sys") == 0 || strcmp(argv[i], "/sys/") == 0 ||
                strcmp(argv[i], "/dev") == 0 || strcmp(argv[i], "/dev/") == 0 ||
                strcmp(argv[i], "/dev/pts") == 0) {
                is_virtual_fs = 1;
                break;
            }
        }
        if (is_virtual_fs) {
            _exit(0);
        }
    }
    if (strcmp(f_base, "policy-rc.d") == 0 || strcmp(prog_name, "policy-rc.d") == 0) {
        _exit(101);
    }

    // A static program inside the rootfs, to be made safe to start (prepare_static_program)
    const char *static_elf = NULL;
    char resolved_target[PATH_MAX];

    // Only intercept binaries/scripts within CORTEX_ROOT
    if (g_cortex_root[0] != '\0' && strncmp(target, g_cortex_root, g_cortex_root_len) == 0) {
        struct exec_probe probe;
        const char *elf_target = select_exec_target(target, resolved_target, sizeof(resolved_target), &probe);
        if (probe.is_elf && !probe.is_dynamic) static_elf = elf_target;

        // 1. Transparently route dynamically linked glibc ELF binaries through ld.so
        // Note: Statically linked binaries (PT_INTERP absent, e.g. Meta Muse Code) must execute directly!
        if (probe.is_dynamic) {
            char ld_so[PATH_MAX];
            ld_so[0] = '\0';
            if (find_dynamic_linker_for_machine(g_cortex_root, probe.e_machine, ld_so, sizeof(ld_so)) && strcmp(elf_target, ld_so) != 0) {
                ensure_exec_bit(elf_target, probe.mode);

                strncpy(g_real_exe, elf_target, sizeof(g_real_exe) - 1);
                g_real_exe[sizeof(g_real_exe) - 1] = '\0';

                char hook_buf[PATH_MAX];
                const char *hook_so = cortex_hook_library(hook_buf, sizeof(hook_buf));
                char ld_lib_path[PATH_MAX * 6];
                build_ld_library_path(envp, ld_lib_path, sizeof(ld_lib_path));
                char **new_argv = build_ldso_argv(ld_so, hook_so, ld_lib_path, elf_target, exec_prog_name(target), argv);
                if (!new_argv) {
                    errno = ENOMEM;
                    return -1;
                }
                char **new_env = ensure_hook_in_envp(envp, elf_target);
                int ret = orig_execve(ld_so, new_argv, new_env);
                if (errno == EACCES && chmod(ld_so, 0755) == 0) {
                    // ld.so without its exec bit (an unusual restore): fix it once and retry
                    ret = orig_execve(ld_so, new_argv, new_env);
                }
                int saved_errno = errno;
                free(new_argv);
                free_modified_env(new_env, envp);
                errno = saved_errno;
                return ret;
            }
        }

        // 2. Handle scripts with shebang lines (e.g., #!/bin/sh, #!/usr/bin/perl)
        if (!probe.is_elf && probe.n >= 2 && probe.hdr[0] == '#' && probe.hdr[1] == '!') {
            char *interp;
            char *interp_arg;
            parse_shebang(probe.hdr, &interp, &interp_arg);
            char interp_buf[PATH_MAX];
            const char *rewritten_interp = shebang_interpreter(interp, interp_buf, sizeof(interp_buf));
            const char *script = (caller_path && caller_path[0] != '/') ? caller_path : target;
            char **new_argv = build_script_argv(rewritten_interp, interp_arg, script, argv);
            if (!new_argv) {
                errno = ENOMEM;
                return -1;
            }
            int ret;
            if (is_host_interpreter(rewritten_interp)) {
                char **sys_env = clean_env_for_system(envp);
                ret = orig_execve(rewritten_interp, new_argv, sys_env);
                int saved_errno = errno;
                free_modified_env(sys_env, envp);
                errno = saved_errno;
            } else if (depth >= CORTEX_MAX_SHEBANG_DEPTH) {
                errno = ELOOP;
                ret = -1;
            } else {
                char **cortex_env = prepare_cortex_env(envp, NULL);
                ret = cortex_execve(rewritten_interp, new_argv, cortex_env, depth + 1);
                int saved_errno = errno;
                free_modified_env(cortex_env, envp);
                errno = saved_errno;
            }
            free(new_argv);
            return ret;
        }
    } else {
        // Any binary outside CORTEX_ROOT is an Android host binary (e.g. /system/bin/su, /sbin/su, /data/adb/...)
        char **sys_env = clean_env_for_system(envp);
        int ret = orig_execve(target, argv, sys_env);
        int saved_errno = errno;
        free_modified_env(sys_env, envp);
        errno = saved_errno;
        return ret;
    }

    if (static_elf) prepare_static_program(static_elf);
    char **cortex_env = prepare_cortex_env(envp, target);
    int ret = orig_execve(target, argv, cortex_env);
    int saved_errno = errno;
    free_modified_env(cortex_env, envp);
    errno = saved_errno;
    return ret;
}

int execve(const char *filename, char *const argv[], char *const envp[]) {
    return cortex_execve(filename, argv, envp, 0);
}

extern char **environ;

int execv(const char *path, char *const argv[]) {
    return execve(path, argv, environ);
}

int execl(const char *path, const char *arg0, ...) {
    va_list args;
    va_start(args, arg0);
    int count = (arg0 != NULL) ? 1 : 0;
    if (arg0 != NULL) {
        while (va_arg(args, const char *) != NULL) {
            count++;
        }
    }
    va_end(args);

    char **argv = (char **)calloc(count + 1, sizeof(char *));
    if (!argv) {
        errno = ENOMEM;
        return -1;
    }
    if (count > 0) {
        argv[0] = (char *)arg0;
        va_start(args, arg0);
        for (int i = 1; i < count; i++) {
            argv[i] = va_arg(args, char *);
        }
        va_end(args);
    }
    argv[count] = NULL;
    int ret = execv(path, argv);
    free(argv);
    return ret;
}

int execlp(const char *file, const char *arg0, ...) {
    va_list args;
    va_start(args, arg0);
    int count = (arg0 != NULL) ? 1 : 0;
    if (arg0 != NULL) {
        while (va_arg(args, const char *) != NULL) {
            count++;
        }
    }
    va_end(args);

    char **argv = (char **)calloc(count + 1, sizeof(char *));
    if (!argv) {
        errno = ENOMEM;
        return -1;
    }
    if (count > 0) {
        argv[0] = (char *)arg0;
        va_start(args, arg0);
        for (int i = 1; i < count; i++) {
            argv[i] = va_arg(args, char *);
        }
        va_end(args);
    }
    argv[count] = NULL;
    int ret = execvp(file, argv);
    free(argv);
    return ret;
}

int execle(const char *path, const char *arg0, ...) {
    va_list args;
    va_start(args, arg0);
    int count = (arg0 != NULL) ? 1 : 0;
    if (arg0 != NULL) {
        while (va_arg(args, const char *) != NULL) {
            count++;
        }
    }
    char *const *envp = va_arg(args, char *const *);
    va_end(args);

    char **argv = (char **)calloc(count + 1, sizeof(char *));
    if (!argv) {
        errno = ENOMEM;
        return -1;
    }
    if (count > 0) {
        argv[0] = (char *)arg0;
        va_start(args, arg0);
        for (int i = 1; i < count; i++) {
            argv[i] = va_arg(args, char *);
        }
        va_end(args);
    }
    argv[count] = NULL;
    int ret = execve(path, argv, (char *const *)envp);
    free(argv);
    return ret;
}

int execvp(const char *file, char *const argv[]) {
    if (!file || !*file) {
        errno = ENOENT;
        return -1;
    }
    if (strchr(file, '/')) {
        return execve(file, argv, environ);
    }
    const char *path_env = getenv("PATH");
    if (!path_env) path_env = "/usr/bin:/bin";
    char path_copy[4096];
    strncpy(path_copy, path_env, sizeof(path_copy) - 1);
    path_copy[sizeof(path_copy) - 1] = '\0';
    char *saveptr = NULL;
    char *token = strtok_r(path_copy, ":", &saveptr);
    while (token) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", token, file);
        if (access(candidate, F_OK) == 0) {
            return execve(candidate, argv, environ);
        }
        token = strtok_r(NULL, ":", &saveptr);
    }
    const char *standard_paths[] = {"/usr/bin", "/bin", "/usr/sbin", "/sbin", "/usr/local/bin", NULL};
    for (int i = 0; standard_paths[i] != NULL; i++) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", standard_paths[i], file);
        if (access(candidate, F_OK) == 0) {
            return execve(candidate, argv, environ);
        }
    }
    return execve(file, argv, environ);
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    if (!file || !*file) {
        errno = ENOENT;
        return -1;
    }
    if (strchr(file, '/')) {
        return execve(file, argv, envp);
    }
    const char *path_env = NULL;
    if (envp) {
        for (char *const *ep = envp; *ep; ep++) {
            if (strncmp(*ep, "PATH=", 5) == 0) {
                path_env = *ep + 5;
                break;
            }
        }
    }
    if (!path_env) path_env = getenv("PATH");
    if (!path_env) path_env = "/usr/bin:/bin";
    char path_copy[4096];
    strncpy(path_copy, path_env, sizeof(path_copy) - 1);
    path_copy[sizeof(path_copy) - 1] = '\0';
    char *saveptr = NULL;
    char *token = strtok_r(path_copy, ":", &saveptr);
    while (token) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", token, file);
        if (access(candidate, F_OK) == 0) {
            return execve(candidate, argv, envp);
        }
        token = strtok_r(NULL, ":", &saveptr);
    }
    const char *standard_paths[] = {"/usr/bin", "/bin", "/usr/sbin", "/sbin", "/usr/local/bin", NULL};
    for (int i = 0; standard_paths[i] != NULL; i++) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", standard_paths[i], file);
        if (access(candidate, F_OK) == 0) {
            return execve(candidate, argv, envp);
        }
    }
    return execve(file, argv, envp);
}

typedef int (*orig_posix_spawn_f_type)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                                      const posix_spawnattr_t *, char *const [], char *const []);

static orig_posix_spawn_f_type real_posix_spawn(void) {
    static orig_posix_spawn_f_type orig_posix_spawn = NULL;
    if (!orig_posix_spawn) orig_posix_spawn = (orig_posix_spawn_f_type)dlsym(RTLD_NEXT, "posix_spawn");
    return orig_posix_spawn;
}

static int cortex_posix_spawn(pid_t *pid, const char *path,
                              const posix_spawn_file_actions_t *file_actions,
                              const posix_spawnattr_t *attrp,
                              char *const argv[], char *const envp[], int depth);

// A rootfs script: start its interpreter with the same file actions and attributes. Handing the
// script to the kernel resolves "#!/bin/sh" to Android's own shell, which crashed on the glibc
// hook in LD_PRELOAD (and "#!/usr/bin/env" etc. used to fall back to fork() + execve(), which
// dropped the file actions).
static int spawn_script(pid_t *pid, const char *script, struct exec_probe *probe,
                        const posix_spawn_file_actions_t *file_actions, const posix_spawnattr_t *attrp,
                        char *const argv[], char *const envp[], int depth) {
    char *interp;
    char *interp_arg;
    parse_shebang(probe->hdr, &interp, &interp_arg);
    char interp_buf[PATH_MAX];
    const char *rewritten_interp = shebang_interpreter(interp, interp_buf, sizeof(interp_buf));
    char **new_argv = build_script_argv(rewritten_interp, interp_arg, script, argv);
    if (!new_argv) return ENOMEM;
    int ret;
    if (is_host_interpreter(rewritten_interp)) {
        orig_posix_spawn_f_type orig_posix_spawn = real_posix_spawn();
        char **sys_env = clean_env_for_system(envp);
        ret = orig_posix_spawn ? orig_posix_spawn(pid, rewritten_interp, file_actions, attrp, new_argv, sys_env) : ENOSYS;
        free_modified_env(sys_env, envp);
    } else {
        ret = cortex_posix_spawn(pid, rewritten_interp, file_actions, attrp, new_argv, envp, depth + 1);
    }
    free(new_argv);
    return ret;
}

static int cortex_posix_spawn(pid_t *pid, const char *path,
                              const posix_spawn_file_actions_t *file_actions,
                              const posix_spawnattr_t *attrp,
                              char *const argv[], char *const envp[], int depth) {
    orig_posix_spawn_f_type orig_posix_spawn = real_posix_spawn();

    const char *caller_path = path;
    char abs_buf[PATH_MAX];
    path = absolutize_exec_path(path, abs_buf, sizeof(abs_buf));

    char buf[PATH_MAX];
    const char *target = rewrite_path(path, buf, sizeof(buf));

    init_cortex_hook();

    const char *f_base = (path != NULL) ? strrchr(path, '/') : NULL;
    f_base = (f_base != NULL) ? f_base + 1 : (path != NULL ? path : "");

    const char *prog_name = exec_prog_name(target);

    if (strcmp(f_base, "ldconfig") == 0 ||
        strcmp(f_base, "ldconfig.real") == 0 ||
        strcmp(f_base, "systemd-machine-id-setup") == 0 ||
        strcmp(f_base, "systemd-sysusers") == 0 ||
        strcmp(f_base, "systemd-tmpfiles") == 0 ||
        strcmp(f_base, "start-stop-daemon") == 0 ||
        strcmp(prog_name, "ldconfig") == 0 ||
        strcmp(prog_name, "ldconfig.real") == 0 ||
        strcmp(prog_name, "systemd-machine-id-setup") == 0 ||
        strcmp(prog_name, "systemd-sysusers") == 0 ||
        strcmp(prog_name, "systemd-tmpfiles") == 0 ||
        strcmp(prog_name, "start-stop-daemon") == 0) {
        pid_t child = fork();
        if (child == 0) {
            _exit(0);
        } else if (child > 0) {
            if (pid) *pid = child;
            return 0;
        }
        return errno;
    }
    if (strcmp(f_base, "mountpoint") == 0 || strcmp(prog_name, "mountpoint") == 0) {
        int is_virtual_fs = 0;
        for (int i = 1; argv && argv[i]; i++) {
            if (strcmp(argv[i], "/proc") == 0 || strcmp(argv[i], "/proc/") == 0 ||
                strcmp(argv[i], "/sys") == 0 || strcmp(argv[i], "/sys/") == 0 ||
                strcmp(argv[i], "/dev") == 0 || strcmp(argv[i], "/dev/") == 0 ||
                strcmp(argv[i], "/dev/pts") == 0) {
                is_virtual_fs = 1;
                break;
            }
        }
        if (is_virtual_fs) {
            pid_t child = fork();
            if (child == 0) {
                _exit(0);
            } else if (child > 0) {
                if (pid) *pid = child;
                return 0;
            }
            return errno;
        }
    }
    if (strcmp(f_base, "policy-rc.d") == 0 || strcmp(prog_name, "policy-rc.d") == 0) {
        pid_t child = fork();
        if (child == 0) {
            _exit(101);
        } else if (child > 0) {
            if (pid) *pid = child;
            return 0;
        }
        return errno;
    }

    char *const *orig_ep = envp ? envp : environ;
    int in_root = g_cortex_root[0] != '\0' && strncmp(target, g_cortex_root, g_cortex_root_len) == 0;

    struct exec_probe probe;
    char resolved_target[PATH_MAX];
    const char *elf_target = target;
    if (in_root) {
        elf_target = select_exec_target(target, resolved_target, sizeof(resolved_target), &probe);
        if (!probe.is_elf && probe.n >= 2 && probe.hdr[0] == '#' && probe.hdr[1] == '!') {
            // Like the kernel, give up after a few nested "#!" interpreters.
            if (depth >= CORTEX_MAX_SHEBANG_DEPTH) return ELOOP;
            const char *script = (caller_path && caller_path[0] != '/') ? caller_path : target;
            return spawn_script(pid, script, &probe, file_actions, attrp, argv, orig_ep, depth);
        }
    } else {
        probe.hdr[0] = '\0';
        probe.n = -1;
        probe.is_elf = 0;
        probe.is_dynamic = 0;
        probe.e_machine = 0;
        probe.mode = 0;
    }

    char ld_so[PATH_MAX];
    ld_so[0] = '\0';
    int has_ld_so = probe.is_dynamic ? find_dynamic_linker_for_machine(g_cortex_root, probe.e_machine, ld_so, sizeof(ld_so)) : 0;

    // Android programs get Android's environment, as execve() gives them: the glibc hook in
    // LD_PRELOAD crashes them.
    char **new_envp = in_root ? ensure_hook_in_envp(orig_ep, elf_target) : clean_env_for_system(orig_ep);

    int ret = -1;
    if (has_ld_so && strcmp(elf_target, ld_so) != 0) {
        ensure_exec_bit(elf_target, probe.mode);

        char hook_buf[PATH_MAX];
        const char *hook_so = cortex_hook_library(hook_buf, sizeof(hook_buf));
        char ld_lib_path[PATH_MAX * 6];
        build_ld_library_path(orig_ep, ld_lib_path, sizeof(ld_lib_path));
        char **new_argv = build_ldso_argv(ld_so, hook_so, ld_lib_path, elf_target, prog_name, argv);
        if (new_argv) {
            if (orig_posix_spawn) {
                ret = orig_posix_spawn(pid, ld_so, file_actions, attrp, new_argv, new_envp);
                if (ret == EACCES && chmod(ld_so, 0755) == 0) {
                    // ld.so without its exec bit (an unusual restore): fix it once and retry
                    ret = orig_posix_spawn(pid, ld_so, file_actions, attrp, new_argv, new_envp);
                }
            }
            free(new_argv);
        }
    } else {
        if (in_root && probe.is_elf && !probe.is_dynamic) prepare_static_program(elf_target);
        if (orig_posix_spawn) {
            ret = orig_posix_spawn(pid, target, file_actions, attrp, argv, new_envp);
        }
    }

    if (ret != 0) {
        pid_t child = fork();
        if (child < 0) {
            free_modified_env(new_envp, orig_ep);
            return errno;
        } else if (child == 0) {
            execve(target, argv, new_envp);
            _exit(127);
        } else {
            if (pid) *pid = child;
            free_modified_env(new_envp, orig_ep);
            return 0;
        }
    }
    free_modified_env(new_envp, orig_ep);
    return ret;
}

int posix_spawn(pid_t *pid, const char *path,
                const posix_spawn_file_actions_t *file_actions,
                const posix_spawnattr_t *attrp,
                char *const argv[], char *const envp[]) {
    posix_spawnattr_t sanitized;
    return cortex_posix_spawn(pid, path, file_actions, spawn_attr_for_android(attrp, &sanitized), argv, envp, 0);
}

int posix_spawnp(pid_t *pid, const char *file,
                 const posix_spawn_file_actions_t *file_actions,
                 const posix_spawnattr_t *attrp,
                 char *const argv[], char *const envp[]) {
    if (!file || !*file) {
        return ENOENT;
    }
    if (strchr(file, '/')) {
        return posix_spawn(pid, file, file_actions, attrp, argv, envp);
    }
    const char *path_env = NULL;
    if (envp) {
        for (char *const *ep = envp; *ep; ep++) {
            if (strncmp(*ep, "PATH=", 5) == 0) {
                path_env = *ep + 5;
                break;
            }
        }
    }
    if (!path_env) path_env = getenv("PATH");
    if (!path_env) path_env = "/usr/bin:/bin";
    char path_copy[4096];
    strncpy(path_copy, path_env, sizeof(path_copy) - 1);
    path_copy[sizeof(path_copy) - 1] = '\0';
    char *saveptr = NULL;
    char *token = strtok_r(path_copy, ":", &saveptr);
    while (token) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", token, file);
        if (access(candidate, F_OK) == 0) {
            return posix_spawn(pid, candidate, file_actions, attrp, argv, envp);
        }
        token = strtok_r(NULL, ":", &saveptr);
    }
    const char *standard_paths[] = {"/usr/bin", "/bin", "/usr/sbin", "/sbin", "/usr/local/bin", NULL};
    for (int i = 0; standard_paths[i] != NULL; i++) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/%s", standard_paths[i], file);
        if (access(candidate, F_OK) == 0) {
            return posix_spawn(pid, candidate, file_actions, attrp, argv, envp);
        }
    }
    // If not found in PATH or standard paths, return ENOENT without spawning zombie
    return ENOENT;
}

#endif /* !CORTEX_HOST_TEST */
