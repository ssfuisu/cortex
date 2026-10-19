#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <limits.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <pwd.h>
#include <grp.h>
#include <assert.h>

#define CORTEX_HOST_TEST 1
#include "../../app/src/main/cpp/cortex-pty.c"
#include "../../app/src/main/cpp/cortex-hook.c"

static int g_tests_run = 0;
static int g_tests_failed = 0;

#define ASSERT_TRUE(cond, msg) do { \
    g_tests_run++; \
    if (!(cond)) { \
        fprintf(stderr, "FAIL [%s:%d]: %s (%s)\n", __func__, __LINE__, msg, #cond); \
        g_tests_failed++; \
        return; \
    } \
} while (0)

#define ASSERT_EQ(actual, expected, msg) do { \
    g_tests_run++; \
    long long _a = (long long)(actual); \
    long long _e = (long long)(expected); \
    if (_a != _e) { \
        fprintf(stderr, "FAIL [%s:%d]: %s (expected %lld, got %lld)\n", __func__, __LINE__, msg, _e, _a); \
        g_tests_failed++; \
        return; \
    } \
} while (0)

#define ASSERT_STR_EQ(actual, expected, msg) do { \
    g_tests_run++; \
    const char *_a = (actual); \
    const char *_e = (expected); \
    if (!_a || !_e || strcmp(_a, _e) != 0) { \
        fprintf(stderr, "FAIL [%s:%d]: %s (expected '%s', got '%s')\n", __func__, __LINE__, msg, _e ? _e : "NULL", _a ? _a : "NULL"); \
        g_tests_failed++; \
        return; \
    } \
} while (0)

/* Helper: locate writable temporary directory across host Linux, CI, and Android/Termux */
static const char *get_test_tmp_dir(void) {
    const char *tmp = getenv("TMPDIR");
    if (tmp && tmp[0] != '\0' && access(tmp, W_OK) == 0) return tmp;
    if (access("/tmp", W_OK) == 0) return "/tmp";
    return ".";
}

/* Helper: recursively remove a temporary directory */
static void rm_rf(const char *path) {
    if (!path || path[0] == '\0' || strcmp(path, "/") == 0) return;
    char cmd[PATH_MAX + 32];
    snprintf(cmd, sizeof(cmd), "rm -rf '%s'", path);
    int r = system(cmd);
    (void)r;
}

/* Helper: populate a 512-byte POSIX ustar header with valid checksum */
static void init_tar_header(char block[512], const char *name, const char *prefix,
                            unsigned long mode, unsigned long long size,
                            char typeflag, const char *linkname) {
    memset(block, 0, 512);
    if (name) {
        strncpy(block, name, 100);
    }
    snprintf(block + 100, 8, "%07lo", mode & 07777UL);
    snprintf(block + 108, 8, "%07o", 0);
    snprintf(block + 116, 8, "%07o", 0);
    snprintf(block + 124, 12, "%011llo", size);
    snprintf(block + 136, 12, "%011o", 1700000000);
    memset(block + 148, ' ', 8);
    block[156] = typeflag;
    if (linkname) {
        strncpy(block + 157, linkname, 100);
    }
    memcpy(block + 257, "ustar", 5);
    block[262] = '\0';
    memcpy(block + 263, "00", 2);
    if (prefix) {
        strncpy(block + 345, prefix, 155);
    }
    unsigned long chksum = 0;
    for (int i = 0; i < 512; i++) {
        chksum += (unsigned char)block[i];
    }
    snprintf(block + 148, 7, "%06lo", chksum);
    block[154] = '\0';
    block[155] = ' ';
}

static void write_padded_data(int fd, const void *data, size_t len) {
    if (len == 0) return;
    ssize_t w = write(fd, data, len);
    (void)w;
    size_t rem = len % 512;
    if (rem != 0) {
        char pad[512];
        memset(pad, 0, sizeof(pad));
        w = write(fd, pad, 512 - rem);
        (void)w;
    }
}

static void write_eof_blocks(int fd) {
    char zero[1024];
    memset(zero, 0, sizeof(zero));
    ssize_t w = write(fd, zero, sizeof(zero));
    (void)w;
}

/* 1. Test has_dotdot_component & strip_tar_prefix */
static void test_tar_path_helpers(void) {
    ASSERT_EQ(has_dotdot_component(".."), 1, "bare '..' must be rejected");
    ASSERT_EQ(has_dotdot_component("../a"), 1, "leading '../a' must be rejected");
    ASSERT_EQ(has_dotdot_component("a/../b"), 1, "middle 'a/../b' must be rejected");
    ASSERT_EQ(has_dotdot_component("a/.."), 1, "trailing 'a/..' must be rejected");
    ASSERT_EQ(has_dotdot_component("a/.../b"), 0, "triple dot 'a/.../b' must be allowed");
    ASSERT_EQ(has_dotdot_component("foo..bar"), 0, "'foo..bar' must be allowed");
    ASSERT_EQ(has_dotdot_component(".bashrc"), 0, "'.bashrc' must be allowed");

    ASSERT_STR_EQ(strip_tar_prefix("./.bashrc"), ".bashrc", "leading './' stripped, dotfile '.bashrc' preserved");
    ASSERT_STR_EQ(strip_tar_prefix(".bashrc"), ".bashrc", "dotfile '.bashrc' preserved directly");
    ASSERT_STR_EQ(strip_tar_prefix("./.profile"), ".profile", "dotfile '.profile' preserved");
    ASSERT_STR_EQ(strip_tar_prefix("./root/.bashrc"), "root/.bashrc", "nested dotfile preserved");
    ASSERT_STR_EQ(strip_tar_prefix("///./usr/bin/env"), "usr/bin/env", "leading slashes and ./ stripped");
    ASSERT_STR_EQ(strip_tar_prefix("./"), "", "root directory entry './' becomes empty");
    ASSERT_STR_EQ(strip_tar_prefix("."), "", "root directory entry '.' becomes empty");
}

/* 2. Test symlink target containment */
static void test_symlink_containment(void) {
    ASSERT_EQ(is_symlink_target_safe("etc/alternatives/awk", "../../usr/bin/mawk"), 1,
              "relative symlink staying within root must be allowed");
    ASSERT_EQ(is_symlink_target_safe("usr/bin/awk", "../../etc/alternatives/awk"), 1,
              "usr/bin/awk -> ../../etc/alternatives/awk must be allowed");
    ASSERT_EQ(is_symlink_target_safe("etc/alternatives/awk", "../../../etc/passwd"), 0,
              "relative symlink escaping root must be rejected");
    ASSERT_EQ(is_symlink_target_safe("top_link", "../outside"), 0,
              "top-level symlink escaping root must be rejected");
    ASSERT_EQ(is_symlink_target_safe("etc/alternatives/awk", "/etc/passwd"), 0,
              "absolute symlink target must be rejected");
}

/* 3. Test PAX extended header ('x') parsing for path= and linkpath= > 100 chars */
static void test_pax_header_parsing(void) {
    char long_p[256];
    memset(long_p, 'a', 140);
    long_p[140] = '\0';
    memcpy(long_p, "usr/share/doc/", 14);

    char long_l[256];
    memset(long_l, 'b', 130);
    long_l[130] = '\0';
    memcpy(long_l, "../lib/", 7);

    char pax_data[1024];
    char rec1[512], rec2[512];
    /* Format PAX record: "<len> key=value\n" */
    int body1_len = snprintf(NULL, 0, " path=%s\n", long_p);
    int total1 = body1_len + 3; /* 3 digits for length */
    snprintf(rec1, sizeof(rec1), "%d path=%s\n", total1, long_p);

    int body2_len = snprintf(NULL, 0, " linkpath=%s\n", long_l);
    int total2 = body2_len + 3;
    snprintf(rec2, sizeof(rec2), "%d linkpath=%s\n", total2, long_l);

    int offset = snprintf(pax_data, sizeof(pax_data), "%s%s11 size=42\n", rec1, rec2);

    char parsed_path[PATH_MAX] = {0};
    char parsed_link[PATH_MAX] = {0};
    unsigned long long parsed_size = 0;
    int has_size = 0;

    int rc = parse_pax_headers(pax_data, (size_t)offset,
                               parsed_path, sizeof(parsed_path),
                               parsed_link, sizeof(parsed_link),
                               &parsed_size, &has_size);
    ASSERT_EQ(rc, 0, "valid PAX header must parse cleanly");
    ASSERT_STR_EQ(parsed_path, long_p, "PAX path > 100 chars must match");
    ASSERT_STR_EQ(parsed_link, long_l, "PAX linkpath > 100 chars must match");
    ASSERT_EQ(has_size, 1, "PAX size must be flagged present");
    ASSERT_EQ(parsed_size, 42ULL, "PAX size must equal 42");

    /* Oversized path >= PATH_MAX must be rejected */
    size_t huge_len = PATH_MAX + 64;
    char *huge_pax = malloc(huge_len + 64);
    ASSERT_TRUE(huge_pax != NULL, "alloc huge_pax");
    char *huge_val = malloc(huge_len + 1);
    ASSERT_TRUE(huge_val != NULL, "alloc huge_val");
    memset(huge_val, 'x', huge_len);
    huge_val[huge_len] = '\0';
    int body_huge = (int)huge_len + 7; /* " path=\n" */
    int digits = snprintf(NULL, 0, "%d", body_huge + 4);
    int total_huge = body_huge + digits;
    int written = snprintf(huge_pax, huge_len + 64, "%d path=%s\n", total_huge, huge_val);
    int rc_huge = parse_pax_headers(huge_pax, (size_t)written,
                                    parsed_path, sizeof(parsed_path),
                                    parsed_link, sizeof(parsed_link),
                                    &parsed_size, &has_size);
    free(huge_val);
    free(huge_pax);
    ASSERT_EQ(rc_huge, -1, "oversized PAX path >= PATH_MAX must be rejected");
}

/* 4. End-to-end extract_tar_archive tests */
static void test_extract_tar_end_to_end(void) {
    char tmp_base[PATH_MAX];
    snprintf(tmp_base, sizeof(tmp_base), "%s/cortex_tar_test_XXXXXX", get_test_tmp_dir());
    ASSERT_TRUE(mkdtemp(tmp_base) != NULL, "mkdtemp base");

    /* 4a. Valid tar with dotfiles, regular files, and relative alternatives symlink */
    char tar_valid[PATH_MAX], dest_valid[PATH_MAX];
    snprintf(tar_valid, sizeof(tar_valid), "%s/valid.tar", tmp_base);
    snprintf(dest_valid, sizeof(dest_valid), "%s/dest_valid", tmp_base);
    mkdir(dest_valid, 0755);

    int fd = open(tar_valid, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    ASSERT_TRUE(fd >= 0, "open valid.tar");
    char blk[512];
    const char *bashrc_content = "export PATH=/usr/bin:/bin\n";
    init_tar_header(blk, "./.bashrc", NULL, 0644, strlen(bashrc_content), '0', NULL);
    write(fd, blk, 512);
    write_padded_data(fd, bashrc_content, strlen(bashrc_content));

    const char *mawk_content = "mawk-binary";
    init_tar_header(blk, "./usr/bin/mawk", NULL, 0755, strlen(mawk_content), '0', NULL);
    write(fd, blk, 512);
    write_padded_data(fd, mawk_content, strlen(mawk_content));

    init_tar_header(blk, "./etc/alternatives/awk", NULL, 0777, 0, '2', "../../usr/bin/mawk");
    write(fd, blk, 512);

    init_tar_header(blk, "./a/.../file.txt", NULL, 0644, 2, '0', NULL);
    write(fd, blk, 512);
    write_padded_data(fd, "ok", 2);

    write_eof_blocks(fd);
    close(fd);

    ASSERT_EQ(extract_tar_archive(tar_valid, dest_valid), 0, "valid tar must extract with 0");

    char check_path[PATH_MAX];
    struct stat st;
    snprintf(check_path, sizeof(check_path), "%s/.bashrc", dest_valid);
    ASSERT_EQ(lstat(check_path, &st), 0, ".bashrc must exist (not stripped to bashrc)");

    snprintf(check_path, sizeof(check_path), "%s/a/.../file.txt", dest_valid);
    ASSERT_EQ(lstat(check_path, &st), 0, "a/.../file.txt must exist");

    snprintf(check_path, sizeof(check_path), "%s/etc/alternatives/awk", dest_valid);
    ASSERT_EQ(lstat(check_path, &st), 0, "etc/alternatives/awk symlink must exist");
    ASSERT_TRUE(S_ISLNK(st.st_mode), "etc/alternatives/awk must be a symlink");

    /* 4b. Escaping symlink tar must return -1 */
    char tar_escape[PATH_MAX], dest_escape[PATH_MAX];
    snprintf(tar_escape, sizeof(tar_escape), "%s/escape.tar", tmp_base);
    snprintf(dest_escape, sizeof(dest_escape), "%s/dest_escape", tmp_base);
    mkdir(dest_escape, 0755);

    fd = open(tar_escape, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    ASSERT_TRUE(fd >= 0, "open escape.tar");
    init_tar_header(blk, "etc/alternatives/awk", NULL, 0777, 0, '2', "../../../etc/passwd");
    write(fd, blk, 512);
    write_eof_blocks(fd);
    close(fd);

    ASSERT_EQ(extract_tar_archive(tar_escape, dest_escape), -1, "escaping symlink tar must return -1");

    /* 4c. Symlinked-parent traversal tar must return -1 */
    char tar_symparent[PATH_MAX], dest_symparent[PATH_MAX], outside_dir[PATH_MAX];
    snprintf(tar_symparent, sizeof(tar_symparent), "%s/symparent.tar", tmp_base);
    snprintf(dest_symparent, sizeof(dest_symparent), "%s/dest_symparent", tmp_base);
    snprintf(outside_dir, sizeof(outside_dir), "%s/outside", tmp_base);
    mkdir(dest_symparent, 0755);
    mkdir(outside_dir, 0755);

    /* Create a pre-existing or in-archive symlink parent -> .. or outside_dir */
    fd = open(tar_symparent, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    ASSERT_TRUE(fd >= 0, "open symparent.tar");
    init_tar_header(blk, "a/sub", NULL, 0777, 0, '2', "..");
    write(fd, blk, 512);
    init_tar_header(blk, "a/sub/evil.txt", NULL, 0644, 4, '0', NULL);
    write(fd, blk, 512);
    write_padded_data(fd, "evil", 4);
    write_eof_blocks(fd);
    close(fd);

    ASSERT_EQ(extract_tar_archive(tar_symparent, dest_symparent), -1,
              "symlinked-parent traversal tar must return -1");

    /* 4d. Truncated tar must return -1 */
    char tar_trunc[PATH_MAX], dest_trunc[PATH_MAX];
    snprintf(tar_trunc, sizeof(tar_trunc), "%s/trunc.tar", tmp_base);
    snprintf(dest_trunc, sizeof(dest_trunc), "%s/dest_trunc", tmp_base);
    mkdir(dest_trunc, 0755);

    fd = open(tar_trunc, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    ASSERT_TRUE(fd >= 0, "open trunc.tar");
    init_tar_header(blk, "truncated.bin", NULL, 0644, 2048, '0', NULL);
    write(fd, blk, 512);
    write(fd, "short", 5); /* Truncated payload! */
    close(fd);

    ASSERT_EQ(extract_tar_archive(tar_trunc, dest_trunc), -1, "truncated tar must return -1");

    rm_rf(tmp_base);
}

/* 5. Test check_elf_dynamic */
static void test_check_elf_dynamic(void) {
    char tmp_elf[PATH_MAX];
    snprintf(tmp_elf, sizeof(tmp_elf), "%s/cortex_elf_test_XXXXXX", get_test_tmp_dir());
    int fd = mkstemp(tmp_elf);
    ASSERT_TRUE(fd >= 0, "mkstemp elf");
    unlink(tmp_elf);

    /* 5a. Valid 64-bit little-endian ELF with PT_INTERP */
    unsigned char elf64[128];
    memset(elf64, 0, sizeof(elf64));
    elf64[0] = 0x7f; elf64[1] = 'E'; elf64[2] = 'L'; elf64[3] = 'F';
    elf64[4] = 2; /* ELFCLASS64 */
    elf64[5] = 1; /* ELFDATA2LSB */
    uint64_t phoff64 = 64;
    uint16_t phentsize64 = 56;
    uint16_t phnum64 = 1;
    memcpy(elf64 + 32, &phoff64, sizeof(phoff64));
    memcpy(elf64 + 54, &phentsize64, sizeof(phentsize64));
    memcpy(elf64 + 56, &phnum64, sizeof(phnum64));
    uint32_t pt_interp = 3; /* PT_INTERP */
    memcpy(elf64 + 64, &pt_interp, sizeof(pt_interp));

    ftruncate(fd, 0);
    lseek(fd, 0, SEEK_SET);
    write(fd, elf64, sizeof(elf64));
    ASSERT_EQ(check_elf_dynamic(fd, elf64, 64), 1, "valid 64-bit dynamic ELF must return 1");

    /* 5b. Valid 32-bit little-endian ELF with PT_INTERP */
    unsigned char elf32[96];
    memset(elf32, 0, sizeof(elf32));
    elf32[0] = 0x7f; elf32[1] = 'E'; elf32[2] = 'L'; elf32[3] = 'F';
    elf32[4] = 1; /* ELFCLASS32 */
    elf32[5] = 1; /* ELFDATA2LSB */
    uint32_t phoff32 = 52;
    uint16_t phentsize32 = 32;
    uint16_t phnum32 = 1;
    memcpy(elf32 + 28, &phoff32, sizeof(phoff32));
    memcpy(elf32 + 42, &phentsize32, sizeof(phentsize32));
    memcpy(elf32 + 44, &phnum32, sizeof(phnum32));
    memcpy(elf32 + 52, &pt_interp, sizeof(pt_interp));

    ftruncate(fd, 0);
    lseek(fd, 0, SEEK_SET);
    write(fd, elf32, sizeof(elf32));
    ASSERT_EQ(check_elf_dynamic(fd, elf32, 52), 1, "valid 32-bit dynamic ELF must return 1");

    /* 5c. Malformed e_phentsize = 1 (underflow attack) */
    uint16_t bad_phentsize = 1;
    memcpy(elf64 + 54, &bad_phentsize, sizeof(bad_phentsize));
    ASSERT_EQ(check_elf_dynamic(fd, elf64, 64), 0, "malformed e_phentsize=1 must be rejected");

    /* 5d. Malformed e_phentsize = 0 */
    bad_phentsize = 0;
    memcpy(elf64 + 54, &bad_phentsize, sizeof(bad_phentsize));
    ASSERT_EQ(check_elf_dynamic(fd, elf64, 64), 0, "malformed e_phentsize=0 must be rejected");

    /* 5e. phoff overflow (negative off_t when cast) */
    memcpy(elf64 + 54, &phentsize64, sizeof(phentsize64));
    uint64_t bad_phoff = UINT64_MAX - 10;
    memcpy(elf64 + 32, &bad_phoff, sizeof(bad_phoff));
    ASSERT_EQ(check_elf_dynamic(fd, elf64, 64), 0, "overflowing phoff must be rejected");

    close(fd);
}

/* 6. Test find_dynamic_linker cache and cross-arch protection */
static void test_find_dynamic_linker(void) {
    char tmp_root[PATH_MAX];
    snprintf(tmp_root, sizeof(tmp_root), "%s/cortex_ld_test_XXXXXX", get_test_tmp_dir());
    ASSERT_TRUE(mkdtemp(tmp_root) != NULL, "mkdtemp ld root");

    char lib_dir[PATH_MAX], ld_aarch64[PATH_MAX];
    snprintf(lib_dir, sizeof(lib_dir), "%s/lib", tmp_root);
    mkdir(lib_dir, 0755);
    snprintf(ld_aarch64, sizeof(ld_aarch64), "%s/lib/ld-linux-aarch64.so.1", tmp_root);
    int fd = open(ld_aarch64, O_WRONLY | O_CREAT | O_TRUNC, 0755);
    ASSERT_TRUE(fd >= 0, "create ld-linux-aarch64.so.1");
    close(fd);

    /* Create fake arm64 binary (e_machine = 183) and arm32 binary (e_machine = 40) */
    char bin64[PATH_MAX], bin32[PATH_MAX], script[PATH_MAX];
    snprintf(bin64, sizeof(bin64), "%s/bin64", tmp_root);
    snprintf(bin32, sizeof(bin32), "%s/bin32", tmp_root);
    snprintf(script, sizeof(script), "%s/script", tmp_root);

    unsigned char ehdr[20] = {0};
    ehdr[0] = 0x7f; ehdr[1] = 'E'; ehdr[2] = 'L'; ehdr[3] = 'F';
    ehdr[18] = 183; ehdr[19] = 0; /* EM_AARCH64 */
    fd = open(bin64, O_WRONLY | O_CREAT | O_TRUNC, 0755);
    write(fd, ehdr, sizeof(ehdr));
    close(fd);

    ehdr[18] = 40; ehdr[19] = 0; /* EM_ARM */
    fd = open(bin32, O_WRONLY | O_CREAT | O_TRUNC, 0755);
    write(fd, ehdr, sizeof(ehdr));
    close(fd);

    fd = open(script, O_WRONLY | O_CREAT | O_TRUNC, 0755);
    write(fd, "#!/bin/sh\n", 10);
    close(fd);

    char out_ld[PATH_MAX] = {0};
    ASSERT_EQ(hook_find_dynamic_linker(tmp_root, bin64, out_ld, sizeof(out_ld)), 1,
              "arm64 binary must find arm64 dynamic linker");
    ASSERT_STR_EQ(out_ld, ld_aarch64, "matched ld-linux-aarch64.so.1");

    /* Must NOT fall back to 64-bit linker or hit 64-bit cache for 32-bit EM_ARM binary! */
    memset(out_ld, 0, sizeof(out_ld));
    ASSERT_EQ(hook_find_dynamic_linker(tmp_root, bin32, out_ld, sizeof(out_ld)), 0,
              "32-bit EM_ARM binary must NOT poison/hit 64-bit linker cache or fall back to opposite bitness");

    rm_rf(tmp_root);
}

/* 7. Test Issue #7 NSS passwd/group fallback semantics */
static void test_nss_fallback_semantics(void) {
    struct passwd *pw = hook_getpwnam("root");
    ASSERT_TRUE(pw != NULL, "getpwnam('root') must return fallback root entry");
    ASSERT_EQ(pw->pw_uid, 0, "root uid must be 0");
    ASSERT_STR_EQ(pw->pw_name, "root", "root pw_name must be 'root'");

    ASSERT_TRUE(hook_getpwnam("messagebus") == NULL,
                "getpwnam('messagebus') must return NULL when absent from /etc/passwd");
    ASSERT_TRUE(hook_getpwnam("_apt") == NULL,
                "getpwnam('_apt') must return NULL when absent from /etc/passwd");

    struct passwd pwd_buf;
    char str_buf[256];
    struct passwd *pw_res = &pwd_buf;
    ASSERT_EQ(hook_getpwnam_r("messagebus", &pwd_buf, str_buf, sizeof(str_buf), &pw_res), 0,
              "getpwnam_r('messagebus') must return 0 per POSIX when not found");
    ASSERT_TRUE(pw_res == NULL, "getpwnam_r('messagebus') *result must be NULL");

    ASSERT_TRUE(hook_getpwuid(0) != NULL, "getpwuid(0) must return root fallback");
    ASSERT_TRUE(hook_getpwuid(100) == NULL,
                "getpwuid(100) must return NULL so adduser first_avail_uid succeeds");
    ASSERT_EQ(hook_getpwuid_r(100, &pwd_buf, str_buf, sizeof(str_buf), &pw_res), 0,
              "getpwuid_r(100) must return 0");
    ASSERT_TRUE(pw_res == NULL, "getpwuid_r(100) *result must be NULL");

    struct group *gr = hook_getgrnam("root");
    ASSERT_TRUE(gr != NULL, "getgrnam('root') must return fallback root group");
    struct group *gr_ssh = hook_getgrnam("_ssh");
    ASSERT_TRUE(gr_ssh != NULL, "getgrnam('_ssh') must return _ssh group");
    ASSERT_EQ(gr_ssh->gr_gid, 100, "getgrnam('_ssh')->gr_gid must be 100");
    struct group *gr_cortex = hook_getgrnam("cortex");
    ASSERT_TRUE(gr_cortex != NULL, "getgrnam('cortex') must return cortex group");
    struct passwd *pw_ssh = hook_getpwnam("_ssh");
    ASSERT_TRUE(pw_ssh != NULL, "getpwnam('_ssh') must return _ssh user");
    ASSERT_EQ(pw_ssh->pw_uid, 100, "getpwnam('_ssh')->pw_uid must be 100");
    ASSERT_EQ(pw_ssh->pw_gid, 100, "getpwnam('_ssh')->pw_gid must be 100");
    ASSERT_TRUE(hook_getgrnam("messagebus") == NULL,
                "getgrnam('messagebus') must return NULL when absent from /etc/group");
    ASSERT_TRUE(hook_getgrgid(100) == NULL,
                "getgrgid(100) must return NULL when absent from /etc/group");

    /* 7b. Real errors (ERANGE, EIO) from underlying libc *_r must propagate and NOT be masked by synthetic root or 0 */
    struct group grp_buf;
    struct group *gr_res = &grp_buf;
    g_test_orig_nss_r_ret = ERANGE;
    g_test_orig_nss_r_found = 0;

    pw_res = &pwd_buf;
    ASSERT_EQ(hook_getpwnam_r("root", &pwd_buf, str_buf, sizeof(str_buf), &pw_res), ERANGE,
              "getpwnam_r('root') must propagate ERANGE from orig_getpwnam_r instead of returning synthetic root");
    ASSERT_TRUE(pw_res == NULL, "getpwnam_r('root') *result must be NULL on ERANGE");

    pw_res = &pwd_buf;
    ASSERT_EQ(hook_getpwnam_r("messagebus", &pwd_buf, str_buf, sizeof(str_buf), &pw_res), ERANGE,
              "getpwnam_r('messagebus') must propagate ERANGE instead of masking as not-found 0");
    ASSERT_TRUE(pw_res == NULL, "getpwnam_r('messagebus') *result must be NULL on ERANGE");

    pw_res = &pwd_buf;
    ASSERT_EQ(hook_getpwuid_r(0, &pwd_buf, str_buf, sizeof(str_buf), &pw_res), ERANGE,
              "getpwuid_r(0) must propagate ERANGE from orig_getpwuid_r");
    ASSERT_TRUE(pw_res == NULL, "getpwuid_r(0) *result must be NULL on ERANGE");

    gr_res = &grp_buf;
    ASSERT_EQ(hook_getgrnam_r("root", &grp_buf, str_buf, sizeof(str_buf), &gr_res), ERANGE,
              "getgrnam_r('root') must propagate ERANGE from orig_getgrnam_r");
    ASSERT_TRUE(gr_res == NULL, "getgrnam_r('root') *result must be NULL on ERANGE");

    gr_res = &grp_buf;
    ASSERT_EQ(hook_getgrgid_r(0, &grp_buf, str_buf, sizeof(str_buf), &gr_res), ERANGE,
              "getgrgid_r(0) must propagate ERANGE from orig_getgrgid_r");
    ASSERT_TRUE(gr_res == NULL, "getgrgid_r(0) *result must be NULL on ERANGE");

    g_test_orig_nss_r_ret = EIO;
    pw_res = &pwd_buf;
    ASSERT_EQ(hook_getpwnam_r("root", &pwd_buf, str_buf, sizeof(str_buf), &pw_res), EIO,
              "getpwnam_r('root') must propagate EIO from orig_getpwnam_r");
    ASSERT_TRUE(pw_res == NULL, "getpwnam_r('root') *result must be NULL on EIO");

    /* Restore ENOENT (not-found) and verify root fallback still works */
    g_test_orig_nss_r_ret = ENOENT;
    ASSERT_EQ(hook_getpwnam_r("root", &pwd_buf, str_buf, sizeof(str_buf), &pw_res), 0,
              "getpwnam_r('root') must fall back to synthetic root on ENOENT");
    ASSERT_TRUE(pw_res == &pwd_buf, "getpwnam_r('root') *result must point to pwd_buf on ENOENT");
    g_test_orig_nss_r_ret = 0;
}

/* 8. Test SIGSYS Linux 5.1+ range (424..511) and close_all_inherited_fds */
static void test_sigsys_and_close_fds(void) {
    ASSERT_EQ(is_handled_sigsys_syscall(424), 1, "pidfd_send_signal (424) must be handled");
    ASSERT_EQ(is_handled_sigsys_syscall(435), 1, "clone3 (435) must be handled");
    ASSERT_EQ(is_handled_sigsys_syscall(439), 1, "faccessat2 (439) must be handled");
    ASSERT_EQ(is_handled_sigsys_syscall(449), 1, "futex_waitv (449) must be handled");
    ASSERT_EQ(is_handled_sigsys_syscall(462), 1, "mseal (462) must be handled");
    ASSERT_EQ(is_handled_sigsys_syscall(511), 1, "upper bound 511 must be handled");
    ASSERT_EQ(is_handled_sigsys_syscall(512), 0, "512 must not be handled");
    ASSERT_EQ(is_handled_sigsys_syscall(423), 0, "423 must not be handled");
    ASSERT_EQ(is_handled_sigsys_syscall(1), 0, "syscall 1 must not be handled");

#if defined(__aarch64__)
    ASSERT_EQ(is_handled_sigsys_syscall(99), 1, "set_robust_list (99) must be handled on aarch64");
    ASSERT_EQ(is_handled_sigsys_syscall(146), 1, "setuid (146) must be handled on aarch64");
    ASSERT_EQ(is_sigsys_synthetic_success_syscall(99), 1, "set_robust_list (99) must succeed synthetically");
    ASSERT_EQ(is_sigsys_synthetic_success_syscall(146), 1, "setuid (146) must succeed synthetically");
    ASSERT_EQ(is_sigsys_synthetic_success_syscall(435), 0, "clone3 (435) must not succeed synthetically (-ENOSYS)");
#endif

    int dup_fd = dup(2);
    ASSERT_TRUE(dup_fd >= 3, "dup(2) must return fd >= 3");
    close_all_inherited_fds(dup_fd + 1);
    ASSERT_EQ(fcntl(dup_fd, F_GETFD), -1, "dup_fd must be closed by close_all_inherited_fds");
    ASSERT_TRUE(fcntl(2, F_GETFD) != -1, "stderr (fd 2) must remain open");
}

/* 9. Test emulated hardlink and st_nlink reporting (fixing groupadd/shadow /etc/group.lock failure) */
static void test_hardlink_emulation_and_st_nlink(void) {
    const char *tmp = get_test_tmp_dir();
    char file_a[PATH_MAX], file_b[PATH_MAX];
    snprintf(file_a, sizeof(file_a), "%s/cortex_hl_test_a_%d.tmp", tmp, getpid());
    snprintf(file_b, sizeof(file_b), "%s/cortex_hl_test_b_%d.lock", tmp, getpid());

    hook_unlink(file_a);
    hook_unlink(file_b);

    int fd = open(file_a, O_CREAT | O_WRONLY | O_TRUNC, 0600);
    ASSERT_TRUE(fd >= 0, "open file_a must succeed");
    const char *content = "12345\n";
    ssize_t nw = write(fd, content, strlen(content));
    ASSERT_EQ(nw, (ssize_t)strlen(content), "write to file_a must match");
    close(fd);

    // Call hook_link (which uses cortex_emulate_hardlink on Android/EACCES)
    int lres = hook_link(file_a, file_b);
    ASSERT_EQ(lres, 0, "hook_link(file_a, file_b) must succeed");

    // Existing link destination must fail with EEXIST
    int lres_exist = hook_link(file_a, file_b);
    ASSERT_EQ(lres_exist, -1, "linking over existing file_b must fail");
    ASSERT_EQ(errno, EEXIST, "linking over existing file_b must set errno=EEXIST");

    // hook_stat(file_a) must report st_nlink == 2 (critical for shadow check_link_count!)
    struct stat st_a;
    memset(&st_a, 0, sizeof(st_a));
    ASSERT_EQ(hook_stat(file_a, &st_a), 0, "hook_stat(file_a) must succeed");
    ASSERT_EQ(st_a.st_nlink, 2, "hook_stat(file_a)->st_nlink must be 2 for shadow link count check");

    // hook_stat(file_b) must report st_nlink == 2
    struct stat st_b;
    memset(&st_b, 0, sizeof(st_b));
    ASSERT_EQ(hook_stat(file_b, &st_b), 0, "hook_stat(file_b) must succeed");
    ASSERT_EQ(st_b.st_nlink, 2, "hook_stat(file_b)->st_nlink must be 2");

    // Repeated stat calls must NEVER deadlock or hang
    for (int rep = 0; rep < 5; rep++) {
        struct stat st_rep;
        ASSERT_EQ(hook_stat(file_b, &st_rep), 0, "repeated hook_stat(file_b) must succeed without deadlock");
        ASSERT_EQ(st_rep.st_nlink, 2, "repeated hook_stat(file_b)->st_nlink must remain 2");
    }

    // shadow's sequence unlinks file_a, leaving file_b intact
    ASSERT_EQ(hook_unlink(file_a), 0, "hook_unlink(file_a) must succeed");

    // file_b must still exist, be readable, and contain the original data!
    int fd_b = open(file_b, O_RDONLY);
    ASSERT_TRUE(fd_b >= 0, "open(file_b) after unlinking file_a must succeed (regular file, not broken symlink)");
    char read_buf[32] = {0};
    ssize_t nr = read(fd_b, read_buf, sizeof(read_buf) - 1);
    ASSERT_EQ(nr, (ssize_t)strlen(content), "read from file_b must match original content");
    ASSERT_STR_EQ(read_buf, content, "content in file_b must match original content");
    close(fd_b);

    ASSERT_EQ(hook_unlink(file_b), 0, "hook_unlink(file_b) must succeed");

    // Test git-clone style linkat (loose objects: tmp_obj -> real_obj, then unlink tmp_obj)
    char dir_objs[PATH_MAX], tmp_obj[PATH_MAX], real_obj[PATH_MAX];
    snprintf(dir_objs, sizeof(dir_objs), "%s/cortex_git_test_%d", tmp, getpid());
    mkdir(dir_objs, 0700);
    snprintf(tmp_obj, sizeof(tmp_obj), "%s/tmp_obj_%d", dir_objs, getpid());
    snprintf(real_obj, sizeof(real_obj), "%s/real_obj_%d", dir_objs, getpid());
    int fd_tmp = open(tmp_obj, O_CREAT | O_WRONLY | O_TRUNC, 0644);
    ASSERT_TRUE(fd_tmp >= 0, "open tmp_obj must succeed");
    const char *git_blob = "blob 14\0hello world 123";
    write(fd_tmp, git_blob, 23);
    close(fd_tmp);

    int dirfd = open(dir_objs, O_RDONLY | O_DIRECTORY);
    ASSERT_TRUE(dirfd >= 0, "open dir_objs must succeed");
    char base_tmp[64], base_real[64];
    snprintf(base_tmp, sizeof(base_tmp), "tmp_obj_%d", getpid());
    snprintf(base_real, sizeof(base_real), "real_obj_%d", getpid());

    int lres_at = hook_linkat(dirfd, base_tmp, dirfd, base_real, 0);
    ASSERT_EQ(lres_at, 0, "hook_linkat with dirfd must succeed");
    close(dirfd);

    // Git immediately unlinks the temporary object
    ASSERT_EQ(hook_unlink(tmp_obj), 0, "unlinking tmp_obj must succeed");

    // Real object must be intact, NOT a dangling symlink!
    int fd_real = open(real_obj, O_RDONLY);
    ASSERT_TRUE(fd_real >= 0, "open real_obj after unlinking tmp_obj must succeed (not dangling symlink)");
    char git_read[32] = {0};
    ssize_t gnr = read(fd_real, git_read, sizeof(git_read));
    ASSERT_EQ(gnr, 23, "read from real_obj must match git blob length");
    ASSERT_EQ(memcmp(git_read, git_blob, 23), 0, "git blob data must match perfectly");
    close(fd_real);
    hook_unlink(real_obj);
    rmdir(dir_objs);

    // Test lckpwdf and ulckpwdf hooks for shadow suite locking
    ASSERT_EQ(hook_lckpwdf(), 0, "hook_lckpwdf() must return 0");
    ASSERT_EQ(hook_ulckpwdf(), 0, "hook_ulckpwdf() must return 0");
}

/* 9b. The copy path itself (what Android takes): link counts, shared inode, relative names, removal */
static void test_emulated_hardlink_groups(void) {
    const char *tmp = get_test_tmp_dir();
    char dir[PATH_MAX], a[PATH_MAX], b[PATH_MAX], c[PATH_MAX], d[PATH_MAX];
    snprintf(dir, sizeof(dir), "%s/cortex_hlg_%d", tmp, getpid());
    rm_rf(dir);
    ASSERT_EQ(mkdir(dir, 0700), 0, "mkdir test dir");
    snprintf(a, sizeof(a), "%s/a", dir);
    snprintf(b, sizeof(b), "%s/b", dir);
    snprintf(c, sizeof(c), "%s/c", dir);
    snprintf(d, sizeof(d), "%s/d", dir);

    int fd = open(a, O_CREAT | O_WRONLY | O_TRUNC, 0640);
    ASSERT_TRUE(fd >= 0, "create a");
    ASSERT_EQ(write(fd, "data", 4), 4, "write a");
    close(fd);
    struct timespec old_times[2] = { { 1600000000, 0 }, { 1600000000, 0 } };
    ASSERT_EQ(utimensat(AT_FDCWD, a, old_times, 0), 0, "set an old mtime on a");
    struct stat plain;
    ASSERT_EQ(stat(a, &plain), 0, "stat a");

    // a -> b by absolute name, then b -> c relative to a directory descriptor
    ASSERT_EQ(cortex_emulate_hardlink(a, b), 0, "emulated link a -> b");
    int dirfd = open(dir, O_RDONLY | O_DIRECTORY);
    ASSERT_TRUE(dirfd >= 0, "open dir");
    ASSERT_EQ(cortex_emulate_hardlinkat(dirfd, "b", dirfd, "c"), 0, "emulated link b -> c (relative)");

    struct stat sa, sb, sc;
    ASSERT_EQ(hook_stat(a, &sa), 0, "stat a");
    ASSERT_EQ(hook_stat(b, &sb), 0, "stat b");
    ASSERT_EQ(hook_stat(c, &sc), 0, "stat c");
    ASSERT_EQ(sa.st_nlink, 3, "a counts all three names");
    ASSERT_EQ(sb.st_nlink, 3, "b counts all three names");
    ASSERT_EQ(sc.st_nlink, 3, "c (linked through a relative name) counts all three names");
    ASSERT_EQ(sb.st_ino, plain.st_ino, "b reports the inode of a");
    ASSERT_EQ(sc.st_ino, plain.st_ino, "c reports the inode of a");
    ASSERT_EQ(sc.st_mtime, 1600000000, "the copy keeps the modification time of the original");
    ASSERT_EQ(sc.st_mode & 0777, 0640, "the copy keeps the mode");

    // fstat of an open copy matches too
    int fdc = open(c, O_RDONLY);
    ASSERT_TRUE(fdc >= 0, "open c");
    struct stat sfd;
    ASSERT_EQ(fstat(fdc, &sfd), 0, "fstat c");
    apply_emulated_hardlink(&sfd);
    ASSERT_EQ(sfd.st_nlink, 3, "fstat of c counts all three names");
    close(fdc);

    // Removing a name lowers the count of the others
    ASSERT_EQ(hook_unlink(a), 0, "unlink a");
    ASSERT_EQ(hook_stat(b, &sb), 0, "stat b after a went away");
    ASSERT_EQ(sb.st_nlink, 2, "b and c are left");
    ASSERT_EQ(sb.st_ino, plain.st_ino, "b still reports the inode of the link group");
    dev_t hl_dev = 0;
    ino_t hl_ino = 0;
    ASSERT_EQ(lookup_emulated_hardlink_at(dirfd, "b", &hl_dev, &hl_ino), 1, "b, named relative to the directory, is found");
    ASSERT_EQ(lookup_emulated_hardlink_at(dirfd, "missing", &hl_dev, &hl_ino), 0, "a missing name is not");
    ASSERT_EQ(hook_unlink(b), 0, "unlink b");
    ASSERT_EQ(hook_stat(c, &sc), 0, "stat c");
    ASSERT_EQ(sc.st_nlink, 1, "the last copy is a plain file again");
    ASSERT_TRUE(sc.st_ino != plain.st_ino, "and reports its own inode");
    ASSERT_EQ(g_hardlinks_any, 0, "nothing is tracked any more");

    // Many links in one process (dpkg unpacking a package full of hard links) are all kept
    for (int i = 0; i < 300; i++) {
        snprintf(d, sizeof(d), "%s/many_%d", dir, i);
        ASSERT_EQ(cortex_emulate_hardlink(c, d), 0, "emulated link c -> many_i");
    }
    ASSERT_EQ(hook_stat(c, &sc), 0, "stat c");
    ASSERT_EQ(sc.st_nlink, 301, "all 300 copies and c are counted");

    close(dirfd);
    rm_rf(dir);
    pthread_mutex_lock(&g_hardlinks_lock);
    g_hardlinks_len = 0;
    g_hardlinks_any = 0;
    pthread_mutex_unlock(&g_hardlinks_lock);
}

/* 9c. DNS servers of both families, queries and replies (the hook's own resolver) */
static void test_dns_helpers(void) {
    cortex_dns_server_t s;
    ASSERT_EQ(dns_server_from_string("8.8.8.8", &s), 1, "IPv4 server");
    ASSERT_EQ(s.addr.ss_family, AF_INET, "IPv4 family");
    ASSERT_EQ(ntohs(((struct sockaddr_in *)&s.addr)->sin_port), 53, "port 53");
    ASSERT_EQ(dns_server_from_string("2001:4860:4860::8888", &s), 1, "IPv6 server");
    ASSERT_EQ(s.addr.ss_family, AF_INET6, "IPv6 family");
    ASSERT_EQ(s.len, sizeof(struct sockaddr_in6), "IPv6 length");
    cortex_dns_server_t java_form;
    ASSERT_EQ(dns_server_from_string("2001:4860:4860:0:0:0:0:8888", &java_form), 1, "Java's uncompressed IPv6 form");
    ASSERT_EQ(dns_server_equal(&s, &java_form), 1, "both spellings are the same server");
    ASSERT_EQ(dns_server_from_string("127.0.0.53", &s), 0, "IPv4 loopback is skipped");
    ASSERT_EQ(dns_server_from_string("0.0.0.0", &s), 0, "unspecified IPv4 is skipped");
    ASSERT_EQ(dns_server_from_string("::1", &s), 0, "IPv6 loopback is skipped");
    ASSERT_EQ(dns_server_from_string("::", &s), 0, "unspecified IPv6 is skipped");
    ASSERT_EQ(dns_server_from_string("::ffff:127.0.0.1", &s), 0, "IPv4-mapped loopback is skipped");
    ASSERT_EQ(dns_server_from_string("fe80::1", &s), 0, "link-local without a scope is skipped");
    ASSERT_EQ(dns_server_from_string("fe80::1%7", &s), 1, "link-local with a numeric scope");
    ASSERT_EQ(((struct sockaddr_in6 *)&s.addr)->sin6_scope_id, 7, "scope id kept");
    ASSERT_EQ(dns_server_from_string("dns.google", &s), 0, "names are not addresses");

    cortex_dns_server_t list[3];
    const char *conf =
        "# written by Cortex\n"
        "nameserver 127.0.0.53\n"
        "nameserver\t2001:4860:4860::8844 # comment\n"
        "options timeout:1 attempts:2 rotate\n"
        "  nameserver 1.1.1.1\r\n"
        "nameservers 9.9.9.9\n"
        "nameserver 8.8.8.8\n"
        "nameserver 8.8.4.4\n";
    ASSERT_EQ(parse_nameservers(conf, list, 3), 3, "three usable servers, at most three");
    ASSERT_EQ(list[0].addr.ss_family, AF_INET6, "the IPv6 server first, loopback skipped");
    ASSERT_EQ(list[1].addr.ss_family, AF_INET, "then 1.1.1.1 (leading spaces, CRLF)");
    ASSERT_EQ(((struct sockaddr_in *)&list[2].addr)->sin_addr.s_addr, inet_addr("8.8.8.8"), "\"nameservers\" is not a nameserver line");
    ASSERT_EQ(parse_nameservers("", list, 3), 0, "an empty file has none");

    unsigned char q[512];
    int qlen = dns_build_query("example.com", CORTEX_DNS_TYPE_AAAA, 0xbeef, q, sizeof(q));
    static const unsigned char expected[] = {
        0xbe, 0xef, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0, 0x00, 28, 0x00, 0x01,
    };
    ASSERT_EQ(qlen, (int)sizeof(expected), "query length");
    ASSERT_EQ(memcmp(q, expected, sizeof(expected)), 0, "query bytes");
    ASSERT_EQ(dns_build_query("example.com.", CORTEX_DNS_TYPE_AAAA, 0xbeef, q, sizeof(q)), (int)sizeof(expected), "a trailing dot is the same name");
    ASSERT_EQ(dns_build_query("a..b", CORTEX_DNS_TYPE_A, 1, q, sizeof(q)), -1, "empty label");
    ASSERT_EQ(dns_build_query("", CORTEX_DNS_TYPE_A, 1, q, sizeof(q)), -1, "empty name");
    char long_label[80];
    memset(long_label, 'a', 64);
    strcpy(long_label + 64, ".com");
    ASSERT_EQ(dns_build_query(long_label, CORTEX_DNS_TYPE_A, 1, q, sizeof(q)), -1, "label over 63 bytes");

    // Reply: www.example.com is a CNAME of edge.example.net, which has the AAAA 2001:db8::1
    unsigned char r[512];
    int n = 0;
    static const unsigned char head[] = { 0xbe, 0xef, 0x81, 0x80, 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00 };
    memcpy(r, head, sizeof(head)); n = sizeof(head);
    static const unsigned char question[] = { 3, 'w', 'w', 'w', 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0, 0, 28, 0, 1 };
    memcpy(r + n, question, sizeof(question)); n += sizeof(question);
    // answer 1: name -> question (offset 12), CNAME, rdata "edge" + "example" label + "net"
    static const unsigned char cname[] = {
        0xc0, 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 18,
        4, 'e', 'd', 'g', 'e', 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'n', 'e', 't', 0,
    };
    int cname_rdata = n + 12;
    memcpy(r + n, cname, sizeof(cname)); n += sizeof(cname);
    // answer 2: name "edge" + pointer to "example.net" inside the CNAME rdata (labels, then a pointer)
    unsigned char aaaa[] = { 4, 'e', 'd', 'g', 'e', 0xc0, 0, 0, 28, 0, 1, 0, 0, 0, 60, 0, 16,
                             0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 };
    aaaa[6] = (unsigned char)(cname_rdata + 5);
    memcpy(r + n, aaaa, sizeof(aaaa)); n += sizeof(aaaa);

    unsigned char addr[16];
    ASSERT_EQ(dns_parse_answer(r, n, 0xbeef, CORTEX_DNS_TYPE_AAAA, addr), 0, "AAAA behind a CNAME and a compressed name");
    struct in6_addr want6;
    inet_pton(AF_INET6, "2001:db8::1", &want6);
    ASSERT_EQ(memcmp(addr, &want6, 16), 0, "the AAAA address");
    ASSERT_EQ(dns_parse_answer(r, n, 0xbeef, CORTEX_DNS_TYPE_A, addr), -1, "no A record in it");
    ASSERT_EQ(dns_parse_answer(r, n, 0xbeee, CORTEX_DNS_TYPE_AAAA, addr), -1, "a reply to another query");
    ASSERT_EQ(dns_parse_answer(r, n - 4, 0xbeef, CORTEX_DNS_TYPE_AAAA, addr), -1, "a truncated reply");
    r[3] = 0x83; // NXDOMAIN
    ASSERT_EQ(dns_parse_answer(r, n, 0xbeef, CORTEX_DNS_TYPE_AAAA, addr), -1, "an error reply");
    r[3] = 0x80;
    r[2] = 0x01; // a query, not a reply
    ASSERT_EQ(dns_parse_answer(r, n, 0xbeef, CORTEX_DNS_TYPE_AAAA, addr), -1, "not a reply");
    r[2] = 0x81;
    // a pointer loop must not hang
    unsigned char loop[] = { 0xbe, 0xef, 0x81, 0x80, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 1, 'a', 1, 'b' };
    ASSERT_EQ(dns_parse_answer(loop, (int)sizeof(loop), 0xbeef, CORTEX_DNS_TYPE_A, addr), -1, "an unterminated name");
}

/* 9d. Static programs: blocked syscall sites are patched the way the hook's SIGSYS handler answers */
static void put32(unsigned char *p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }
static void put16(unsigned char *p, uint16_t v) { p[0] = v; p[1] = v >> 8; }

static void test_static_syscall_patching(void) {
    // AArch64
    static const uint32_t code[] = {
        0xd2800c68, 0xd4000001,             // mov x8, #99 (set_robust_list); svc #0      -> nop
        0xd2803668, 0xaa0203e1, 0xd4000001, // mov x8, #435 (clone3); mov x1, x2; svc     -> mov x0, #-38
        0x52801268, 0xd4000001,             // mov w8, #147 (setresuid); svc              -> mov x0, #0
        0xd2800808, 0xd4000001,             // mov x8, #64 (write); svc                   untouched
        0xd28024a8, 0x14000002, 0xd4000001, // mov x8, #293 (rseq); b; svc               untouched: a branch
        0xd2803668, 0xd2800808, 0xd4000001, // mov x8, #435; mov x8, #64; svc            untouched: x8 rewritten
    };
    unsigned char buf[sizeof(code)];
    for (size_t i = 0; i < sizeof(code) / 4; i++) put32(buf + i * 4, code[i]);
    ASSERT_EQ(patch_seccomp_syscalls(buf, 0, sizeof(buf), 183), 3, "three AArch64 sites");
    ASSERT_EQ(rd32(buf + 4), 0xd503201fu, "set_robust_list -> nop (as the script)");
    ASSERT_EQ(rd32(buf + 16), 0x928004a0u, "clone3 -> mov x0, #-38");
    ASSERT_EQ(rd32(buf + 24), 0xd2800000u, "setresuid (w8 form) -> mov x0, #0");
    ASSERT_EQ(rd32(buf + 32), 0xd4000001u, "write is left alone");
    ASSERT_EQ(rd32(buf + 44), 0xd4000001u, "no patch across a branch");
    ASSERT_EQ(rd32(buf + 56), 0xd4000001u, "no patch after x8 is rewritten");
    ASSERT_EQ(patch_seccomp_syscalls(buf, 0, sizeof(buf), 183), 0, "patching twice changes nothing");

    unsigned char far[4 * 19];
    put32(far, 0xd2803668);
    for (int i = 1; i < 18; i++) put32(far + i * 4, 0xd503201f); // 17 nops
    put32(far + 18 * 4, 0xd4000001);
    ASSERT_EQ(patch_seccomp_syscalls(far, 0, sizeof(far), 183), 0, "an svc beyond 16 instructions is not the call");
    ASSERT_EQ(patch_seccomp_syscalls(buf, 0, sizeof(buf), 62 /* x86-64 */), 0, "other machines are left alone");

    // 32-bit ARM, the script's patterns
    unsigned char arm[16];
    put32(arm, 0xe30071b3); put32(arm + 4, 0xef000000); put32(arm + 8, 0xe1a00000); put32(arm + 12, 0xe1a00000);
    ASSERT_EQ(patch_seccomp_syscalls(arm, 0, sizeof(arm), 40), 1, "ARM clone3");
    ASSERT_EQ(rd32(arm + 4), 0xe3e00025u, "ARM svc -> mvn r0, #37");
    unsigned char th[24];
    put16(th, 0xf240); put16(th + 2, 0x1752); put16(th + 4, 0x2000); put16(th + 6, 0xdf00); // movw r7, #338; movs r0, #0; svc
    put16(th + 8, 0xf240); put16(th + 10, 0x17b3); put16(th + 12, 0xdf00);               // movw r7, #435; svc
    for (int i = 14; i < 24; i += 2) put16(th + i, 0xbf00);
    ASSERT_EQ(patch_seccomp_syscalls(th, 0, sizeof(th), 40), 2, "two Thumb sites");
    ASSERT_EQ(rd16(th + 6), 0xbf00, "Thumb set_robust_list svc -> nop");
    ASSERT_EQ(rd16(th + 0), 0xf240, "and its movw stays");
    ASSERT_EQ(rd16(th + 8), 0xf06f, "Thumb clone3 movw -> mvn.w r0, #37");
    ASSERT_EQ(rd16(th + 10), 0x0025, "(second half)");
    ASSERT_EQ(rd16(th + 12), 0xbf00, "and its svc -> nop");

    // A whole static AArch64 program on disk
    const char *tmp = get_test_tmp_dir();
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/cortex_static_%d", tmp, getpid());
    unsigned char elf[0x1000 + sizeof(code)];
    memset(elf, 0, sizeof(elf));
    memcpy(elf, "\x7f" "ELF", 4);
    elf[4] = 2; elf[5] = 1; elf[6] = 1;
    put16(elf + 16, 2); put16(elf + 18, 183);                 // ET_EXEC, EM_AARCH64
    put32(elf + 32, 64);                                      // e_phoff
    put16(elf + 52, 64); put16(elf + 54, 56); put16(elf + 56, 1);
    unsigned char *ph = elf + 64;
    put32(ph, 1); put32(ph + 4, 5);                           // PT_LOAD, R+X
    put32(ph + 8, 0x1000);                                    // p_offset
    put32(ph + 32, sizeof(code)); put32(ph + 40, sizeof(code)); // p_filesz, p_memsz
    for (size_t i = 0; i < sizeof(code) / 4; i++) put32(elf + 0x1000 + i * 4, code[i]);
    int fd = open(path, O_CREAT | O_WRONLY | O_TRUNC, 0755);
    ASSERT_TRUE(fd >= 0, "create static program");
    ASSERT_EQ(write(fd, elf, sizeof(elf)), (ssize_t)sizeof(elf), "write static program");
    close(fd);
    struct timespec old_times[2] = { { 1500000000, 0 }, { 1500000000, 0 } };
    utimensat(AT_FDCWD, path, old_times, 0);
    ASSERT_EQ(chmod(path, 0555), 0, "make it read-only");

    ASSERT_EQ(prepare_static_program(path), 3, "three sites patched in the file");
    unsigned char back[sizeof(elf)];
    fd = open(path, O_RDONLY);
    ASSERT_EQ(read(fd, back, sizeof(back)), (ssize_t)sizeof(back), "read it back");
    close(fd);
    ASSERT_EQ(rd32(back + 0x1000 + 16), 0x928004a0u, "clone3 patched on disk");
    ASSERT_EQ(memcmp(back, elf, 0x1000), 0, "headers untouched");
    struct stat st;
    ASSERT_EQ(stat(path, &st), 0, "stat");
    ASSERT_EQ(st.st_mtime, 1500000000, "modification time kept");
    ASSERT_EQ(st.st_mode & 07777, 0555, "mode restored after making it writable");
    ASSERT_EQ(prepare_static_program(path), 0, "the second start has nothing to do");

    // A Go program (build-ID note) is left alone
    put16(elf + 56, 2);
    unsigned char *note_ph = elf + 64 + 56;
    put32(note_ph, 4); put32(note_ph + 4, 4);                 // PT_NOTE
    put32(note_ph + 8, 0x200); put32(note_ph + 32, 16);
    put32(elf + 0x200, 4); put32(elf + 0x204, 0); put32(elf + 0x208, 4); memcpy(elf + 0x20c, "Go\0\0", 4);
    chmod(path, 0755);
    fd = open(path, O_WRONLY | O_TRUNC);
    ASSERT_TRUE(fd >= 0, "rewrite as a Go program");
    ASSERT_EQ(write(fd, elf, sizeof(elf)), (ssize_t)sizeof(elf), "write Go program");
    close(fd);
    ASSERT_EQ(prepare_static_program(path), 0, "Go programs are not patched");
    fd = open(path, O_RDONLY);
    ASSERT_EQ(read(fd, back, sizeof(back)), (ssize_t)sizeof(back), "read Go program back");
    close(fd);
    ASSERT_EQ(rd32(back + 0x1000 + 16), 0xd4000001u, "its code is untouched");
    unlink(path);
    ASSERT_EQ(prepare_static_program(path), -1, "a missing file is reported");
}

/* 9. Relative exec paths ("./script.sh") must be routed like absolute ones */
static void test_absolutize_exec_path(void) {
    char tmp_root[PATH_MAX];
    snprintf(tmp_root, sizeof(tmp_root), "%s/cortex_abs_test_XXXXXX", get_test_tmp_dir());
    ASSERT_TRUE(mkdtemp(tmp_root) != NULL, "mkdtemp abs root");

    char old_cwd[PATH_MAX];
    ASSERT_TRUE(getcwd(old_cwd, sizeof(old_cwd)) != NULL, "save cwd");
    ASSERT_EQ(chdir(tmp_root), 0, "chdir into temp root");
    char cwd[PATH_MAX];
    ASSERT_TRUE(getcwd(cwd, sizeof(cwd)) != NULL, "read cwd");

    char buf[PATH_MAX], expect[PATH_MAX];
    snprintf(expect, sizeof(expect), "%s/script.sh", cwd);
    ASSERT_STR_EQ(absolutize_exec_path("./script.sh", buf, sizeof(buf)), expect, "./script.sh -> cwd/script.sh");
    ASSERT_STR_EQ(absolutize_exec_path("script.sh", buf, sizeof(buf)), expect, "bare name -> cwd/name");
    ASSERT_STR_EQ(absolutize_exec_path(".//script.sh", buf, sizeof(buf)), expect, "repeated slashes after ./ collapse");

    snprintf(expect, sizeof(expect), "%s/bin/tool", cwd);
    ASSERT_STR_EQ(absolutize_exec_path("bin/tool", buf, sizeof(buf)), expect, "sub/path -> cwd/sub/path");
    snprintf(expect, sizeof(expect), "%s/../x.sh", cwd);
    ASSERT_STR_EQ(absolutize_exec_path("../x.sh", buf, sizeof(buf)), expect, "../x.sh keeps the ..");

    const char *abs_in = "/usr/bin/env";
    ASSERT_TRUE(absolutize_exec_path(abs_in, buf, sizeof(buf)) == abs_in, "absolute paths are returned untouched");
    ASSERT_TRUE(absolutize_exec_path(NULL, buf, sizeof(buf)) == NULL, "NULL stays NULL");

    char tiny[8];
    ASSERT_STR_EQ(absolutize_exec_path("script.sh", tiny, sizeof(tiny)), "script.sh",
                  "a result that does not fit is not truncated");

    ASSERT_EQ(chdir(old_cwd), 0, "restore cwd");
    rm_rf(tmp_root);
}

/* rewrite_path() as it was before the fast path (v1.26.29), kept to prove that the rewrite gives
 * the same result for every path, rootfs prefix and buffer size. */
static int ref_is_path_prefix(const char *path, const char *prefix, size_t prefix_len) {
    if (strncmp(path, prefix, prefix_len) != 0) return 0;
    return path[prefix_len] == '/' || path[prefix_len] == '\0';
}

static const char *ref_rewrite_path(const char *root, const char *path, char *buffer, size_t bufsize) {
    if (!path) return NULL;
    if (path[0] != '/') return path;
    if (root[0] == '\0') return path;
    while (path[0] == '/' && path[1] == '/') path++;
    while (path[0] == '/' && path[1] == '.' && (path[2] == '/' || path[2] == '\0')) {
        if (path[2] == '/') {
            path += 2;
        } else {
            path = "/";
            break;
        }
    }
    if (strcmp(path, "/") == 0) {
        snprintf(buffer, bufsize, "%s", root);
        return buffer;
    }
    size_t root_len = strlen(root);
    if (strncmp(path, root, root_len) == 0 && (path[root_len] == '/' || path[root_len] == '\0')) {
        return path;
    }
    if (strncmp(path, "/dev/shm", 8) == 0 && (path[8] == '/' || path[8] == '\0')) {
        snprintf(buffer, bufsize, "%s/tmp/shm%s", root, path + 8);
        return buffer;
    }
    if (strcmp(path, "/sbin/su") == 0 || strcmp(path, "/su") == 0 ||
        ref_is_path_prefix(path, "/proc", 5) || ref_is_path_prefix(path, "/dev", 4) ||
        ref_is_path_prefix(path, "/sys", 4) || ref_is_path_prefix(path, "/system", 7) ||
        ref_is_path_prefix(path, "/vendor", 7) || ref_is_path_prefix(path, "/apex", 5) ||
        ref_is_path_prefix(path, "/product", 8) || ref_is_path_prefix(path, "/system_ext", 11) ||
        ref_is_path_prefix(path, "/odm", 4) || ref_is_path_prefix(path, "/oem", 4) ||
        ref_is_path_prefix(path, "/linkerconfig", 13) || ref_is_path_prefix(path, "/config", 7) ||
        ref_is_path_prefix(path, "/d", 2) || ref_is_path_prefix(path, "/data", 5) ||
        ref_is_path_prefix(path, "/sdcard", 7) || ref_is_path_prefix(path, "/storage", 8)) {
        return path;
    }
    snprintf(buffer, bufsize, "%s%s", root, path);
    return buffer;
}

/* Compares rewrite_path_in_root() with the reference; returns the number of differences. */
static int compare_rewrite(const char *root, const char *path, size_t bufsize, int *reported) {
    char got_buf[PATH_MAX], want_buf[PATH_MAX];
    memset(got_buf, 'G', sizeof(got_buf));
    memset(want_buf, 'W', sizeof(want_buf));
    const char *got = rewrite_path_in_root(path, got_buf, bufsize);
    const char *want = ref_rewrite_path(root, path, want_buf, bufsize);
    int same = (got == NULL && want == NULL) || (got && want && strcmp(got, want) == 0);
    /* the buffer must never be written past bufsize */
    if (bufsize < sizeof(got_buf) && got_buf[bufsize] != 'G') same = 0;
    if (!same && !*reported) {
        fprintf(stderr, "rewrite mismatch: root='%s' path='%s' bufsize=%zu got='%s' want='%s'\n",
                root, path ? path : "(null)", bufsize, got ? got : "(null)", want ? want : "(null)");
        *reported = 1;
    }
    return same ? 0 : 1;
}

/* 10. The path translation fast path must behave exactly like the old rewrite_path() */
static void test_rewrite_path_matches_reference(void) {
    static const char *const roots[] = {
        "/data/data/org.cortex.terminal/files/cortex",
        "/data/user/0/org.cortex.terminal/files/cortex",
        "/r",
    };
    static const char *const parts[] = {
        "", ".", "..", "proc", "dev", "shm", "d", "data", "dataX", "sdcard", "storage", "system",
        "system_ext", "systemd", "vendor", "apex", "product", "odm", "oem", "linkerconfig", "config",
        "su", "sbin", "bin", "usr", "etc", "tmp", "home", "x", "procfs", "user", "0",
        "org.cortex.terminal", "files", "cortex", "cortexX", "r",
    };
    static const char *const seps[] = { "/", "//", "/./", "/../" };
    static const size_t sizes[] = { PATH_MAX, 1, 2, 3, 8, 17, 44, 45, 46, 64 };
    const size_t nparts = sizeof(parts) / sizeof(parts[0]);
    const size_t nseps = sizeof(seps) / sizeof(seps[0]);
    const size_t nsizes = sizeof(sizes) / sizeof(sizes[0]);

    int mismatches = 0, reported = 0;
    long checked = 0;
    char path[PATH_MAX];
    for (size_t r = 0; r < sizeof(roots) / sizeof(roots[0]); r++) {
        cortex_set_root(roots[r], strlen(roots[r]));
        const char *fixed[] = {
            "/", "//", "///", "/.", "/./", "/./.", "/.//proc", "//./dev/shm", "/dev/shm", "/dev/shm/x",
            "/dev/shmx", "/sbin/su", "/sbin/sux", "/su", "/su/x", "/d", "/d/x", "/data", "/database",
            "relative/path", "", "./x", "../x", roots[r], NULL,
        };
        for (int f = 0; fixed[f] != NULL; f++) {
            for (size_t s = 0; s < nsizes; s++) {
                mismatches += compare_rewrite(roots[r], fixed[f], sizes[s], &reported);
                checked++;
            }
        }
        mismatches += compare_rewrite(roots[r], NULL, PATH_MAX, &reported);
        /* the rootfs prefix itself, with and without a component boundary */
        snprintf(path, sizeof(path), "%s/etc/passwd", roots[r]);
        mismatches += compare_rewrite(roots[r], path, PATH_MAX, &reported);
        snprintf(path, sizeof(path), "%sX/etc", roots[r]);
        mismatches += compare_rewrite(roots[r], path, PATH_MAX, &reported);
        snprintf(path, sizeof(path), "/%s", roots[r]);
        mismatches += compare_rewrite(roots[r], path, PATH_MAX, &reported);

        /* every pair of components with every separator */
        for (size_t a = 0; a < nparts; a++) {
            for (size_t b = 0; b < nparts; b++) {
                for (size_t sa = 0; sa < nseps; sa++) {
                    for (size_t sb = 0; sb < nseps; sb++) {
                        snprintf(path, sizeof(path), "%s%s%s%s", seps[sa], parts[a], seps[sb], parts[b]);
                        for (size_t s = 0; s < nsizes; s++) {
                            mismatches += compare_rewrite(roots[r], path, sizes[s], &reported);
                            checked++;
                        }
                    }
                }
            }
        }
        /* longer pseudo-random paths (fixed seed, reproducible) */
        uint32_t seed = 12345u + (uint32_t)r;
        for (int i = 0; i < 20000; i++) {
            size_t len = 0;
            int depth = 1 + (int)((seed = seed * 1103515245u + 12345u) >> 16) % 6;
            path[0] = '\0';
            for (int k = 0; k < depth; k++) {
                const char *sep = seps[((seed = seed * 1103515245u + 12345u) >> 16) % nseps];
                const char *part = parts[((seed = seed * 1103515245u + 12345u) >> 16) % nparts];
                len += (size_t)snprintf(path + len, sizeof(path) - len, "%s%s", sep, part);
            }
            size_t bs = sizes[((seed = seed * 1103515245u + 12345u) >> 16) % nsizes];
            mismatches += compare_rewrite(roots[r], path, bs, &reported);
            checked++;
        }
    }
    cortex_set_root("", 0);
    ASSERT_EQ(mismatches, 0, "rewrite_path_in_root must match the previous rewrite_path for every input");
    ASSERT_TRUE(checked > 100000, "the comparison must cover a large input set");

    const char *rel = "relative/x";
    char buf[64];
    cortex_set_root("/r", 2);
    ASSERT_TRUE(rewrite_path_in_root(rel, buf, sizeof(buf)) == rel, "relative paths are returned untouched");
    ASSERT_STR_EQ(rewrite_path_in_root("/etc/passwd", buf, sizeof(buf)), "/r/etc/passwd", "rootfs path");
    ASSERT_STR_EQ(rewrite_path_in_root("/proc/self/exe", buf, sizeof(buf)), "/proc/self/exe", "Android path");
    ASSERT_STR_EQ(rewrite_path_in_root("/dev/shm/sem.x", buf, sizeof(buf)), "/r/tmp/shm/sem.x", "/dev/shm");
    cortex_set_root("", 0);
    ASSERT_TRUE(rewrite_path_in_root("/etc/passwd", buf, sizeof(buf)) != buf, "no rootfs: nothing is rewritten");
}

/* 11. Helpers of the path translation and the bin <-> usr/bin retry */
static void test_path_helpers(void) {
    /* cortex_append truncates exactly like snprintf */
    const char *pieces[] = { "", "a", "abc", "0123456789" };
    int bad = 0;
    for (size_t size = 1; size < 16; size++) {
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                char got[32], want[32];
                memset(got, 'x', sizeof(got));
                size_t n = cortex_append(got, size, 0, pieces[i], strlen(pieces[i]));
                n = cortex_append(got, size, n, pieces[j], strlen(pieces[j]));
                snprintf(want, size, "%s%s", pieces[i], pieces[j]);
                if (strcmp(got, want) != 0 || n != strlen(want) || got[size] != 'x') bad++;
            }
        }
    }
    ASSERT_EQ(bad, 0, "cortex_append must truncate like snprintf and never write past the buffer");

    cortex_set_root("/data/data/org.cortex.terminal/files/cortex", 43);
    char alt[PATH_MAX];
    ASSERT_EQ(usrmerge_alt_path("/usr/bin/foo", alt, sizeof(alt)), CORTEX_USRMERGE_BIN, "/usr/bin pair");
    ASSERT_STR_EQ(alt, "/data/data/org.cortex.terminal/files/cortex/bin/foo", "/usr/bin/foo -> /bin/foo");
    ASSERT_EQ(usrmerge_alt_path("/bin/foo", alt, sizeof(alt)), CORTEX_USRMERGE_BIN, "/bin pair");
    ASSERT_STR_EQ(alt, "/data/data/org.cortex.terminal/files/cortex/usr/bin/foo", "/bin/foo -> /usr/bin/foo");
    ASSERT_EQ(usrmerge_alt_path("/usr/sbin/foo", alt, sizeof(alt)), CORTEX_USRMERGE_SBIN, "/usr/sbin pair");
    ASSERT_STR_EQ(alt, "/data/data/org.cortex.terminal/files/cortex/sbin/foo", "/usr/sbin/foo -> /sbin/foo");
    ASSERT_EQ(usrmerge_alt_path("/sbin/foo", alt, sizeof(alt)), CORTEX_USRMERGE_SBIN, "/sbin pair");
    ASSERT_STR_EQ(alt, "/data/data/org.cortex.terminal/files/cortex/usr/sbin/foo", "/sbin/foo -> /usr/sbin/foo");
    ASSERT_EQ(usrmerge_alt_path("/usr/local/bin/foo", alt, sizeof(alt)), 0, "no pair for /usr/local/bin");
    ASSERT_EQ(usrmerge_alt_path("/usr/bin", alt, sizeof(alt)), 0, "the directory itself has no pair");

    char sub[] = "/data/data/org.cortex.terminal/files/cortex/etc/x";
    ASSERT_STR_EQ(get_rootfs_subpath(sub), "/etc/x", "subpath inside the rootfs");
    ASSERT_TRUE(get_rootfs_subpath("/data/data/org.cortex.terminal/files/cortexX/etc") == NULL,
                "a longer directory name is not inside the rootfs");

    /* library search path: multiarch first, every directory once */
    char dirs[4096];
    size_t len = append_rootfs_lib_dirs(dirs, sizeof(dirs), 0, 0);
    ASSERT_EQ(len, strlen(dirs), "returned length");
    const char *first = "/data/data/org.cortex.terminal/files/cortex/lib/aarch64-linux-gnu:"
                        "/data/data/org.cortex.terminal/files/cortex/usr/lib/aarch64-linux-gnu:";
    ASSERT_TRUE(strncmp(dirs, first, strlen(first)) == 0, "aarch64 multiarch directories come first");
    int entries = 1;
    for (const char *p = dirs; *p; p++) entries += (*p == ':');
    ASSERT_EQ(entries, 13, "13 library directories");
    char with_sep[16] = "x";
    append_rootfs_lib_dirs(with_sep, sizeof(with_sep), 1, 1);
    ASSERT_TRUE(strncmp(with_sep, "x:/data", 7) == 0, "separate=1 starts with ':'");

    /* SELinux label filtering of listxattr() results */
    char list1[] = "user.a\0security.selinux\0user.b";
    ssize_t n1 = drop_selinux_xattr(list1, (ssize_t)sizeof(list1));
    ASSERT_EQ(n1, 14, "security.selinux removed from the middle");
    ASSERT_EQ(memcmp(list1, "user.a\0user.b", 14), 0, "other names kept in order");
    char list2[] = "security.selinux";
    ASSERT_EQ(drop_selinux_xattr(list2, (ssize_t)sizeof(list2)), 0, "only security.selinux -> empty list");
    char list3[] = "security.selinuxX\0user.c";
    ASSERT_EQ(drop_selinux_xattr(list3, (ssize_t)sizeof(list3)), (ssize_t)sizeof(list3), "similar names are kept");
    char list4[] = "user.d\0security.selinux";
    ASSERT_EQ(drop_selinux_xattr(list4, 10), 10, "an entry cut off by the length is kept as is");
    ASSERT_EQ(drop_selinux_xattr(NULL, -1), -1, "errors pass through");
    ASSERT_TRUE(is_selinux_xattr("security.selinux") && !is_selinux_xattr("security.capability") && !is_selinux_xattr(NULL),
                "is_selinux_xattr");
    cortex_set_root("", 0);
}

/* Writes an ELF header with `phnum` program headers of type 1 (PT_LOAD) at `phoff`, the one at
 * `interp_index` (-1: none) being PT_INTERP. Returns the file size. */
static size_t make_elf(unsigned char *out, size_t cap, int is64, uint64_t phoff, uint16_t phentsize,
                       uint16_t phnum, int interp_index, uint16_t machine) {
    memset(out, 0, cap);
    out[0] = 0x7f; out[1] = 'E'; out[2] = 'L'; out[3] = 'F';
    out[4] = is64 ? 2 : 1;
    out[5] = 1;
    out[18] = (unsigned char)(machine & 0xff);
    out[19] = (unsigned char)(machine >> 8);
    if (is64) {
        memcpy(out + 32, &phoff, sizeof(phoff));
        memcpy(out + 54, &phentsize, sizeof(phentsize));
        memcpy(out + 56, &phnum, sizeof(phnum));
    } else {
        uint32_t off32 = (uint32_t)phoff;
        memcpy(out + 28, &off32, sizeof(off32));
        memcpy(out + 42, &phentsize, sizeof(phentsize));
        memcpy(out + 44, &phnum, sizeof(phnum));
    }
    size_t end = (size_t)phoff + (size_t)phnum * phentsize;
    if (end > cap) end = cap;
    for (uint16_t i = 0; i < phnum; i++) {
        size_t off = (size_t)phoff + (size_t)i * phentsize;
        if (off + 4 > cap) break;
        uint32_t type = (i == interp_index) ? 3 : 1;
        memcpy(out + off, &type, sizeof(type));
    }
    return end < 64 ? 64 : end;
}

/* 12. Reading PT_INTERP from the buffer must agree with check_elf_dynamic() reading the file */
static void test_exec_probe(void) {
    char tmp_dir[PATH_MAX];
    snprintf(tmp_dir, sizeof(tmp_dir), "%s/cortex_probe_test_XXXXXX", get_test_tmp_dir());
    ASSERT_TRUE(mkdtemp(tmp_dir) != NULL, "mkdtemp probe dir");
    char file[PATH_MAX + 64];
    snprintf(file, sizeof(file), "%s/prog", tmp_dir);

    static unsigned char elf[8192];
    /* 252: the first header straddles the end of the 255-byte buffer */
    const uint64_t phoffs[] = { 64, 52, 120, 200, 240, 252, 300 };
    const uint16_t phnums[] = { 1, 2, 3, 9, 200 };
    const int interps[] = { -1, 0, 1, 2, 8, 150 };
    int disagree = 0, buffered = 0, from_file = 0, cases = 0;
    for (int is64 = 0; is64 <= 1; is64++) {
        uint16_t ent = is64 ? 56 : 32;
        for (size_t a = 0; a < sizeof(phoffs) / sizeof(phoffs[0]); a++) {
            for (size_t b = 0; b < sizeof(phnums) / sizeof(phnums[0]); b++) {
                for (size_t c = 0; c < sizeof(interps) / sizeof(interps[0]); c++) {
                    size_t size = make_elf(elf, sizeof(elf), is64, phoffs[a], ent, phnums[b], interps[c], is64 ? 183 : 40);
                    int fd = open(file, O_RDWR | O_CREAT | O_TRUNC, 0644);
                    if (fd < 0) { disagree++; continue; }
                    fchmod(fd, 0644); /* independent of the umask */
                    ssize_t w = write(fd, elf, size);
                    (void)w;
                    lseek(fd, 0, SEEK_SET);
                    unsigned char head[256];
                    memset(head, 0xAA, sizeof(head)); /* anything read past n shows up */
                    ssize_t n = read(fd, head, 255);
                    int from_buffer = elf_interp_in_buffer(head, n);
                    int expected = check_elf_dynamic(fd, head, n);
                    close(fd);
                    if (from_buffer >= 0) {
                        buffered++;
                        if (from_buffer != expected) disagree++;
                    } else {
                        from_file++;
                    }
                    struct exec_probe probe;
                    probe_exec_file(file, &probe);
                    if (!probe.is_elf || probe.is_dynamic != expected || probe.e_machine != (is64 ? 183 : 40)) disagree++;
                    if (probe.is_dynamic && (probe.mode & 0777) != 0644) disagree++;
                    cases++;
                }
            }
        }
    }
    ASSERT_EQ(disagree, 0, "buffer and file reads must agree on PT_INTERP, machine and mode");
    ASSERT_TRUE(buffered > 0 && from_file > 0, "both the buffer path and the file fallback must be exercised");
    ASSERT_TRUE(cases == 420, "all generated ELF layouts were checked");

    /* malformed headers are not dynamic, whichever way they are read */
    unsigned char bad[255];
    size_t size = make_elf(elf, sizeof(elf), 1, 64, 1, 1, 0, 183);
    memcpy(bad, elf, sizeof(bad));
    ASSERT_EQ(elf_interp_in_buffer(bad, (ssize_t)size), 0, "phentsize=1 is rejected");
    make_elf(elf, sizeof(elf), 1, UINT64_MAX - 10, 56, 1, 0, 183);
    memcpy(bad, elf, sizeof(bad));
    ASSERT_EQ(elf_interp_in_buffer(bad, (ssize_t)sizeof(bad)), 0, "an overflowing phoff is rejected");
    ASSERT_EQ(elf_interp_in_buffer((const unsigned char *)"#!/bin/sh\n", 10), 0, "a script is not ELF");

    int fd = open(file, O_WRONLY | O_CREAT | O_TRUNC, 0755);
    ASSERT_TRUE(fd >= 0, "create script");
    ssize_t w = write(fd, "#!/usr/bin/env python3 -u\nprint(1)\n", 35);
    (void)w;
    close(fd);
    struct exec_probe probe;
    probe_exec_file(file, &probe);
    ASSERT_TRUE(!probe.is_elf && probe.n == 35 && probe.hdr[0] == '#' && probe.hdr[1] == '!', "script header is read");
    ASSERT_EQ(probe.hdr[35], '\0', "header is NUL-terminated");
    char missing[PATH_MAX + 64];
    snprintf(missing, sizeof(missing), "%s/missing", tmp_dir);
    probe_exec_file(missing, &probe);
    ASSERT_TRUE(probe.n == -1 && !probe.is_elf && !probe.is_dynamic, "missing file");

    rm_rf(tmp_dir);
}

/* 13. "#!" parsing and the argv given to interpreters and ld.so */
static void test_exec_argv(void) {
    char *interp, *arg;
    char h1[] = "#!/bin/sh\necho hi\n";
    parse_shebang(h1, &interp, &arg);
    ASSERT_STR_EQ(interp, "/bin/sh", "plain interpreter");
    ASSERT_TRUE(arg == NULL, "no argument");
    char h2[] = "#! /usr/bin/env python3\n";
    parse_shebang(h2, &interp, &arg);
    ASSERT_STR_EQ(interp, "/usr/bin/env", "space after #!");
    ASSERT_STR_EQ(arg, "python3", "one argument");
    char h3[] = "#!\t/bin/bash  -e -x";
    parse_shebang(h3, &interp, &arg);
    ASSERT_STR_EQ(interp, "/bin/bash", "tab after #!, no newline");
    ASSERT_STR_EQ(arg, "-e -x", "the rest of the line is one argument, as with the kernel");

    char *argv1[] = { "ignored-argv0", "a", "b", NULL };
    char **v = build_script_argv("/r/usr/bin/python3", "-u", "./x.py", argv1);
    ASSERT_TRUE(v != NULL, "script argv");
    ASSERT_STR_EQ(v[0], "/r/usr/bin/python3", "interpreter first");
    ASSERT_STR_EQ(v[1], "-u", "then its argument");
    ASSERT_STR_EQ(v[2], "./x.py", "then the script as the caller named it");
    ASSERT_STR_EQ(v[3], "a", "then the arguments");
    ASSERT_TRUE(v[4] != NULL && strcmp(v[4], "b") == 0 && v[5] == NULL, "argv0 of the script is dropped");
    free(v);
    v = build_script_argv("/r/bin/sh", NULL, "/r/tmp/s.sh", argv1);
    ASSERT_TRUE(v && strcmp(v[1], "/r/tmp/s.sh") == 0 && strcmp(v[2], "a") == 0, "no interpreter argument");
    free(v);

    v = build_ldso_argv("/r/lib/ld.so", "/r/usr/lib/libcortex-hook.so", "/r/lib", "/r/usr/bin/true", "true", argv1);
    ASSERT_TRUE(v != NULL, "ld.so argv");
    const char *want[] = { "/r/lib/ld.so", "--preload", "/r/usr/lib/libcortex-hook.so", "--library-path", "/r/lib",
                           "--argv0", "ignored-argv0", "/r/usr/bin/true", "a", "b", NULL };
    int ok = 1;
    for (int i = 0; want[i] || v[i]; i++) {
        if (!want[i] || !v[i] || strcmp(want[i], v[i]) != 0) { ok = 0; break; }
    }
    ASSERT_TRUE(ok, "ld.so [--preload hook] --library-path dirs --argv0 argv0 program args");
    free(v);
    char *argv0[] = { NULL };
    v = build_ldso_argv("/r/lib/ld.so", NULL, "/r/lib", "/r/usr/bin/true", "true", argv0);
    ASSERT_TRUE(v && strcmp(v[1], "--library-path") == 0 && strcmp(v[4], "true") == 0 && v[6] == NULL,
                "no hook, empty argv: argv0 falls back to the program name");
    free(v);

    cortex_set_root("/r", 2);
    ASSERT_TRUE(is_host_interpreter("/system/bin/sh"), "Android shell");
    ASSERT_TRUE(is_host_interpreter("/data/local/tmp/x"), "outside the rootfs");
    ASSERT_TRUE(!is_host_interpreter("/r/usr/bin/python3"), "rootfs interpreter");
    cortex_set_root("", 0);
}

/* 14. ld.so lookup by machine type (what execve()/posix_spawn() use) */
static void test_find_dynamic_linker_for_machine(void) {
    char tmp_root[PATH_MAX];
    snprintf(tmp_root, sizeof(tmp_root), "%s/cortex_ldm_test_XXXXXX", get_test_tmp_dir());
    ASSERT_TRUE(mkdtemp(tmp_root) != NULL, "mkdtemp ld root");
    char dir[PATH_MAX + 64], ld[PATH_MAX + 64];
    snprintf(dir, sizeof(dir), "%s/lib", tmp_root);
    mkdir(dir, 0755);
    snprintf(ld, sizeof(ld), "%s/lib/ld-linux-aarch64.so.1", tmp_root);
    int fd = open(ld, O_WRONLY | O_CREAT | O_TRUNC, 0755);
    ASSERT_TRUE(fd >= 0, "create ld.so");
    close(fd);

    char out[PATH_MAX];
    ASSERT_EQ(find_dynamic_linker_for_machine(tmp_root, 183, out, sizeof(out)), 1, "aarch64 program");
    ASSERT_STR_EQ(out, ld, "aarch64 ld.so");
    ASSERT_EQ(find_dynamic_linker_for_machine(tmp_root, 183, out, sizeof(out)), 1, "second lookup (cached)");
    ASSERT_STR_EQ(out, ld, "cached aarch64 ld.so");
    ASSERT_EQ(find_dynamic_linker_for_machine(tmp_root, 40, out, sizeof(out)), 0, "no armhf ld.so in this root");
    ASSERT_EQ(find_dynamic_linker_for_machine("", 183, out, sizeof(out)), 0, "no root");
    rm_rf(tmp_root);
}

/* 15. posix_spawn() must not hand POSIX_SPAWN_RESETIDS to glibc (its child would die of SIGSYS) */
static void test_spawn_attr_without_resetids(void) {
    posix_spawnattr_t attr, copy;
    ASSERT_EQ(posix_spawnattr_init(&attr), 0, "init attr");
    ASSERT_TRUE(spawn_attr_for_android(NULL, &copy) == NULL, "no attributes stay none");

    posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETPGROUP);
    ASSERT_TRUE(spawn_attr_for_android(&attr, &copy) == &attr, "attributes without RESETIDS are passed on as they are");

    sigset_t defaults;
    sigemptyset(&defaults);
    sigaddset(&defaults, SIGPIPE);
    posix_spawnattr_setsigdefault(&attr, &defaults);
    posix_spawnattr_setpgroup(&attr, 1234);
    posix_spawnattr_setflags(&attr, POSIX_SPAWN_RESETIDS | POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_SETPGROUP);

    const posix_spawnattr_t *got = spawn_attr_for_android(&attr, &copy);
    ASSERT_TRUE(got == &copy, "RESETIDS attributes are replaced by a copy");
    short flags = 0;
    posix_spawnattr_getflags(got, &flags);
    ASSERT_EQ(flags, POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_SETPGROUP, "RESETIDS dropped, the other flags kept");
    sigset_t got_defaults;
    posix_spawnattr_getsigdefault(got, &got_defaults);
    ASSERT_TRUE(sigismember(&got_defaults, SIGPIPE) == 1, "signal set kept");
    pid_t group = 0;
    posix_spawnattr_getpgroup(got, &group);
    ASSERT_EQ(group, 1234, "process group kept");

    posix_spawnattr_getflags(&attr, &flags);
    ASSERT_TRUE((flags & POSIX_SPAWN_RESETIDS) != 0, "the caller's attributes are not modified");
    posix_spawnattr_destroy(&attr);
    posix_spawnattr_destroy(&copy);
}

int main(void) {
    test_tar_path_helpers();
    test_symlink_containment();
    test_pax_header_parsing();
    test_extract_tar_end_to_end();
    test_check_elf_dynamic();
    test_find_dynamic_linker();
    test_nss_fallback_semantics();
    test_sigsys_and_close_fds();
    test_hardlink_emulation_and_st_nlink();
    test_emulated_hardlink_groups();
    test_dns_helpers();
    test_static_syscall_patching();
    test_absolutize_exec_path();
    test_rewrite_path_matches_reference();
    test_path_helpers();
    test_exec_probe();
    test_exec_argv();
    test_find_dynamic_linker_for_machine();
    test_spawn_attr_without_resetids();

    if (g_tests_failed > 0) {
        fprintf(stderr, "FAILED: %d/%d assertions failed\n", g_tests_failed, g_tests_run);
        return 1;
    }
    printf("PASSED: all %d assertions succeeded\n", g_tests_run);
    return 0;
}
