cmake_minimum_required(VERSION 3.22.1)

project("cortex")

# Main PTY JNI bridge
add_library(
    cortex-pty
    SHARED
    cortex-pty.c
)

find_library(
    log-lib
    log
)

target_link_libraries(
    cortex-pty
    ${log-lib}
)

# Rootless Zero-Overhead LD_PRELOAD path redirection hook
add_library(
    cortex-hook
    SHARED
    cortex-hook.c
)

target_link_libraries(
    cortex-hook
    ${log-lib}
    dl
)
