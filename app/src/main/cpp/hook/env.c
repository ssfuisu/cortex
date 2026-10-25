// Part of libcortex-hook.so, included by cortex-hook.c (one translation unit, so the
// parts share their static functions and are built with the same command as before).
//
// The environment of started programs: Android programs get it without the hook, rootfs
// programs with it.

#ifndef CORTEX_HOST_TEST

static char **clean_env_for_system(char *const envp[]) {
    int count = 0;
    while (envp && envp[count]) count++;
    char **new_env = calloc(count + 3, sizeof(char *));
    int dst = 0;
    int has_path = 0;
    for (int i = 0; i < count; i++) {
        char first = envp[i][0];
        if (!((first == 'L' && (strncmp(envp[i], "LD_PRELOAD=", 11) == 0 ||
                                strncmp(envp[i], "LD_LIBRARY_PATH=", 16) == 0)) ||
              (first == 'G' && strncmp(envp[i], "GLIBC_TUNABLES=", 15) == 0))) {
            if (first == 'P' && strncmp(envp[i], "PATH=", 5) == 0) {
                has_path = 1;
                size_t plen = strlen(envp[i]);
                char *new_path = malloc(plen + 64);
                if (new_path) {
                    snprintf(new_path, plen + 64, "PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:%s", envp[i] + 5);
                    new_env[dst++] = new_path;
                } else {
                    new_env[dst++] = envp[i];
                }
            } else {
                new_env[dst++] = envp[i];
            }
        }
    }
    if (!has_path) {
        new_env[dst++] = strdup("PATH=/system/bin:/system/xbin:/sbin:/vendor/bin");
    }
    new_env[dst] = NULL;
    return new_env;
}

static char **prepare_cortex_env(char *const envp[], const char *real_exe) {
    init_cortex_hook();
    int count = 0;
    int has_preload = 0;
    int has_ld_library_path = 0;
    int has_root = 0;
    int has_tunables = 0;
    int has_path = 0;
    int has_tmp = 0;
    int has_threads_max = 0;
    int has_xz_opt = 0;
    int has_xz_defaults = 0;
    int has_frontend = 0;
    int has_debconf_frontend = 0;
    int has_debconf_seen = 0;

    const char *chosen_real_exe = (real_exe && real_exe[0] != '\0') ? real_exe : (g_real_exe[0] != '\0' ? g_real_exe : NULL);

    // Every variable is looked at on every exec, so only the names that start with its first letter
    // are compared (this was 13 strncmp() calls for each of the 30 or so variables)
    int has_nt = 0;
    while (envp && envp[count]) {
        const char *e = envp[count];
        switch (e[0]) {
        case 'L':
            if (strncmp(e, "LD_PRELOAD=", 11) == 0) has_preload = 1;
            else if (strncmp(e, "LD_LIBRARY_PATH=", 16) == 0) has_ld_library_path = 1;
            break;
        case 'C':
            if (strncmp(e, "CORTEX_ROOT=", 12) == 0) has_root = 1;
            else if (strncmp(e, "CORTEX_NT=", 10) == 0) has_nt = 1;
            break;
        case 'G':
            if (strncmp(e, "GLIBC_TUNABLES=", 15) == 0) has_tunables = 1;
            break;
        case 'P':
            if (strncmp(e, "PATH=", 5) == 0) has_path = 1;
            break;
        case 'T':
            if (strncmp(e, "TMPDIR=", 7) == 0) has_tmp = 1;
            break;
        case 'X':
            if (strncmp(e, "XZ_OPT=", 7) == 0) has_xz_opt = 1;
            else if (strncmp(e, "XZ_DEFAULTS=", 12) == 0) has_xz_defaults = 1;
            break;
        case 'D':
            if (strncmp(e, "DPKG_DEB_THREADS_MAX=", 21) == 0) has_threads_max = 1;
            else if (strncmp(e, "DEBIAN_FRONTEND=", 16) == 0) has_frontend = 1;
            else if (strncmp(e, "DEBCONF_FRONTEND=", 17) == 0) has_debconf_frontend = 1;
            else if (strncmp(e, "DEBCONF_NONINTERACTIVE_SEEN=", 28) == 0) has_debconf_seen = 1;
            break;
        default:
            break;
        }
        count++;
    }

    char **new_env = calloc(count + 20, sizeof(char *));
    int dst = 0;
    for (int i = 0; i < count; i++) {
        // If we have an explicit real executable, drop the inherited entry to replace with the fresh target
        if (chosen_real_exe && envp[i][0] == 'C' && strncmp(envp[i], "CORTEX_REAL_EXE=", 16) == 0) {
            continue;
        }
        new_env[dst++] = envp[i];
    }

    // Exact-size strings: this runs before every exec, often in a freshly forked child where each
    // newly touched page is a copy-on-write fault.
    if (chosen_real_exe) {
        size_t len = strlen(chosen_real_exe);
        char *str = malloc(16 + len + 1);
        if (str) {
            memcpy(str, "CORTEX_REAL_EXE=", 16);
            memcpy(str + 16, chosen_real_exe, len + 1);
            new_env[dst++] = str;
        }
    }

    if (!has_preload && g_cortex_root[0] != '\0') {
        static const char hook_rel[] = "/usr/lib/libcortex-hook.so";
        size_t size = 11 + g_cortex_root_len + sizeof(hook_rel);
        char *str = malloc(size);
        if (str) {
            size_t n = cortex_append(str, size, 0, "LD_PRELOAD=", 11);
            n = cortex_append(str, size, n, g_cortex_root, g_cortex_root_len);
            cortex_append(str, size, n, hook_rel, sizeof(hook_rel) - 1);
            new_env[dst++] = str;
        }
    }
    if (!has_ld_library_path && g_cortex_root[0] != '\0') {
        size_t size = 16 + 1;
        for (size_t i = 0; i < sizeof(k_rootfs_lib_dirs) / sizeof(k_rootfs_lib_dirs[0]); i++) {
            size += 1 + g_cortex_root_len + strlen(k_rootfs_lib_dirs[i]);
        }
        char *str = malloc(size);
        if (str) {
            size_t n = cortex_append(str, size, 0, "LD_LIBRARY_PATH=", 16);
            append_rootfs_lib_dirs(str, size, n, 0);
            new_env[dst++] = str;
        }
    }
    if (!has_root && g_cortex_root[0] != '\0') {
        char *str = malloc(PATH_MAX + 16);
        if (str) {
            snprintf(str, PATH_MAX + 16, "CORTEX_ROOT=%s", g_cortex_root);
            new_env[dst++] = str;
        }
    }
    // This process looked for the ARM translator's files and found none: the programs it starts
    // need not look again (it takes a look at /proc/self/fd in each of them, see privileged.c)
    if (!has_nt && g_no_translator) {
        new_env[dst++] = strdup("CORTEX_NT=0");
    }
    if (!has_tunables) {
        new_env[dst++] = strdup("GLIBC_TUNABLES=glibc.pthread.rseq=0");
    }
    if (!has_path) {
        new_env[dst++] = strdup("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
    }
    if (!has_tmp && g_cortex_root[0] != '\0') {
        char *str = malloc(PATH_MAX + 16);
        if (str) {
            snprintf(str, PATH_MAX + 16, "TMPDIR=%s/tmp", g_cortex_root);
            new_env[dst++] = str;
        }
    }
    if (!has_threads_max) {
        new_env[dst++] = strdup("DPKG_DEB_THREADS_MAX=1");
    }
    if (!has_xz_opt) {
        new_env[dst++] = strdup("XZ_OPT=-T1");
    }
    if (!has_xz_defaults) {
        new_env[dst++] = strdup("XZ_DEFAULTS=-T1");
    }
    if (!has_frontend) {
        new_env[dst++] = strdup("DEBIAN_FRONTEND=noninteractive");
    }
    if (!has_debconf_frontend) {
        new_env[dst++] = strdup("DEBCONF_FRONTEND=noninteractive");
    }
    if (!has_debconf_seen) {
        new_env[dst++] = strdup("DEBCONF_NONINTERACTIVE_SEEN=true");
    }
    new_env[dst] = NULL;
    return new_env;
}

static void free_modified_env(char **modified_env, char *const orig_env[]) {
    if (!modified_env) return;
    for (int i = 0; modified_env[i] != NULL; i++) {
        int is_orig = 0;
        if (orig_env) {
            for (int j = 0; orig_env[j] != NULL; j++) {
                if (modified_env[i] == orig_env[j]) {
                    is_orig = 1;
                    break;
                }
            }
        }
        if (!is_orig) {
            free(modified_env[i]);
        }
    }
    free(modified_env);
}

#endif /* !CORTEX_HOST_TEST */
