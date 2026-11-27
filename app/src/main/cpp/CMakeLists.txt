cmake_minimum_required(VERSION 3.22.1)

project("cortex" LANGUAGES C)

# Main PTY JNI bridge & native tar extractor, with the Zstandard decoder for the bootstrap archive
add_library(
    cortex-pty
    SHARED
    cortex-pty.c
    zstd/zstd_decoder.c
)

find_library(
    log-lib
    log
)

target_link_libraries(
    cortex-pty
    ${log-lib}
)

target_link_options(
    cortex-pty
    PRIVATE
    "-Wl,-z,max-page-size=16384"
)

# Freestanding userland ELF runner packaged in nativeLibraryDir (apk_data_file) so targetSdk >= 29
# can run dynamically installed Ubuntu linkers and binaries despite W^X on app_data_file.
add_library(
    cortex-exec
    SHARED
    cortex-exec.c
)

target_compile_options(
    cortex-exec
    PRIVATE
    "-ffreestanding"
    "-fno-builtin"
    "-fno-stack-protector"
    "-fno-unwind-tables"
    "-fno-asynchronous-unwind-tables"
    "-fvisibility=hidden"
    "-O2"
)

target_link_options(
    cortex-exec
    PRIVATE
    "-nostdlib"
    "-Wl,-e,_start"
    "-Wl,-Bsymbolic"
    "-Wl,--no-undefined"
    "-Wl,-z,max-page-size=16384"
)

