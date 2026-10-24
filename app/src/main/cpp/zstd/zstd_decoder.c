/*
 * The Zstandard decoder (https://github.com/facebook/zstd, v1.5.7, BSD license: LICENSE) in one
 * translation unit, as zstd's own build/single_file_libs/zstddeclib-in.c makes it: the bootstrap
 * archive is a .tar.zst, which the native tar extractor (cortex-pty.c) reads through it.
 * Only these files of zstd's lib/ are here; they are unchanged.
 */
#define DEBUGLEVEL 0
#define MEM_MODULE
#undef  XXH_NAMESPACE
#define XXH_NAMESPACE ZSTD_
#undef  XXH_PRIVATE_API
#define XXH_PRIVATE_API
#undef  XXH_INLINE_ALL
#define XXH_INLINE_ALL
#define ZSTD_LEGACY_SUPPORT 0
#define ZSTD_STRIP_ERROR_STRINGS
#define ZSTD_TRACE 0
/* The x86-64 assembly of Huffman decoding is not part of one translation unit */
#define ZSTD_DISABLE_ASM 1

/* zstd_deps.h first, with what is needed */
#define ZSTD_DEPS_NEED_MALLOC
#include "common/zstd_deps.h"

#include "common/debug.c"
#include "common/entropy_common.c"
#include "common/error_private.c"
#include "common/fse_decompress.c"
#include "common/zstd_common.c"

#include "decompress/huf_decompress.c"
#include "decompress/zstd_ddict.c"
#include "decompress/zstd_decompress.c"
#include "decompress/zstd_decompress_block.c"
