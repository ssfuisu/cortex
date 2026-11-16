# GPU acceleration

`scripts/build-virgl-android.sh` builds [virglrenderer](https://gitlab.freedesktop.org/virgl/virglrenderer)'s
vtest server for Android with the NDK. The app ships it as `libvirgl_test_server.so` and starts it when
the GPU setting is on. Linux programs then draw with Mesa's `virpipe` driver, which sends their OpenGL to
the server over `/tmp/.virgl_test`, and the server draws it with the phone's OpenGL ES driver.

The patches in `patches/` come from Termux (`packages/virglrenderer-android`, MIT like virglrenderer).
`COPYING-gl4es` is the license of the DXTn decompression code that patch 0008 adds.
