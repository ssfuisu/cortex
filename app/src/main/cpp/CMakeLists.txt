cmake_minimum_required(VERSION 3.22.1)

project("cortex" LANGUAGES C)

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

target_link_options(
    cortex-pty
    PRIVATE
    "-Wl,-z,max-page-size=16384"
)

