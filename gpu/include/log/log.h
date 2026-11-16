/* What Mesa's util code takes from AOSP's <log/log.h>, through the NDK's public log API */
#pragma once
#include <android/log.h>
#ifndef LOG_PRI
#define LOG_PRI(prio, tag, ...) __android_log_print(prio, tag, __VA_ARGS__)
#endif
