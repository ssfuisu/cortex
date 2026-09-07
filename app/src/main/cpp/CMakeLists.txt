cmake_minimum_required(VERSION 3.22.1)

project("cortex")

# Main PTY JNI bridge & native tar extractor
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
