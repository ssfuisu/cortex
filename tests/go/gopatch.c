// Patches the static Go programs given as arguments the way the hook does before it starts them
// (hook/go_programs.c), for the rootfs given first. Built for the machine this runs on:
// tests/scripts/test_go_programs.sh uses it to patch AArch64 programs it then runs under qemu.
//
//   gopatch ROOTFS PROGRAM...      prints "<program>: prepared N" or "failed"
#define CORTEX_HOST_TEST 1
#include "../../app/src/main/cpp/cortex-hook.c"

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s ROOTFS PROGRAM...\n", argv[0]);
        return 2;
    }
    cortex_set_root(argv[1], strlen(argv[1]));
    int bad = 0;
    for (int i = 2; i < argc; i++) {
        int rc = prepare_static_program(argv[i]);
        printf("%s: %s %d\n", argv[i], rc < 0 ? "failed" : "prepared", rc);
        if (rc < 0) bad = 1;
    }
    return bad;
}
