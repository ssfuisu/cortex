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

int main(void) {
    test_tar_path_helpers();
    test_symlink_containment();
    test_pax_header_parsing();
    test_extract_tar_end_to_end();
    test_check_elf_dynamic();
    test_find_dynamic_linker();
    test_nss_fallback_semantics();
    test_sigsys_and_close_fds();

    if (g_tests_failed > 0) {
        fprintf(stderr, "FAILED: %d/%d assertions failed\n", g_tests_failed, g_tests_run);
        return 1;
    }
    printf("PASSED: all %d assertions succeeded\n", g_tests_run);
    return 0;
}
