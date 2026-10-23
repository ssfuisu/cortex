#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
#include <string.h>
#include <stdint.h>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdarg.h>
#include <spawn.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <dirent.h>
#include <limits.h>
#include <errno.h>
#include <signal.h>
#include <ucontext.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <resolv.h>
#include <utime.h>
#include <sys/time.h>
#include <pwd.h>
#include <grp.h>
#include <pthread.h>
#include <sys/sendfile.h>
#include <sys/mman.h>
#include <sched.h>
#include <time.h>
#include <sys/xattr.h>
#include <sys/sysmacros.h>
#include <net/if.h>

#ifndef RENAME_EXCHANGE
#define RENAME_EXCHANGE (1 << 1)
#endif

// The hook is one translation unit split into parts by topic, in this order (later parts use
// the static functions of earlier ones).
#include "hook/syscalls.c"
#include "hook/paths.c"
#include "hook/hardlinks.c"
#include "hook/signals.c"
#include "hook/setup.c"
#include "hook/files.c"
#include "hook/fileops.c"
#include "hook/identity.c"
#include "hook/privileged.c"
#include "hook/compression.c"
#include "hook/env.c"
#include "hook/exec_probe.c"
#include "hook/static_programs.c"
#include "hook/exec.c"
#include "hook/net.c"
#include "hook/sockets.c"
