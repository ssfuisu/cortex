/* What Mesa's util code takes from AOSP's <cutils/properties.h>, through the NDK's system properties */
#pragma once
#include <string.h>
#include <sys/system_properties.h>
#ifndef PROPERTY_KEY_MAX
#define PROPERTY_KEY_MAX 32
#endif
#ifndef PROPERTY_VALUE_MAX
#define PROPERTY_VALUE_MAX PROP_VALUE_MAX
#endif
static inline int property_get(const char *key, char *value, const char *default_value) {
    int len = __system_property_get(key, value);
    if (len <= 0 && default_value) {
        len = (int)strlen(default_value);
        if (len >= PROPERTY_VALUE_MAX) len = PROPERTY_VALUE_MAX - 1;
        memcpy(value, default_value, (size_t)len);
        value[len] = '\0';
    }
    return len > 0 ? len : 0;
}
