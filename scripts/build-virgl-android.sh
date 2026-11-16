#!/bin/bash
# build-virgl-android.sh JNILIBS_DIR
#
# Builds virglrenderer's vtest server for Android (arm64-v8a and armeabi-v7a) with the NDK, as
# JNILIBS_DIR/<abi>/libvirgl_test_server.so: a program, named like a library so that Android installs
# it in the app's native library directory, the one place an app may run programs from.
#
# It is the GPU side of Cortex's GPU acceleration: Linux programs draw with Mesa's virpipe driver,
# which sends OpenGL to this server over a Unix socket, and the server draws it with the phone's own
# OpenGL ES driver (EGL, surfaceless). libepoxy and virglrenderer are linked into it statically.
#
# The patches in gpu/patches are Termux's (packages/virglrenderer-android): EGL without gbm, ashmem
# when memfd_create is missing, no dual-source blending (Adreno), DXTn decompression, no memfd or
# timespec_get below API 30.
set -euo pipefail

mkdir -p "${1:?usage: build-virgl-android.sh JNILIBS_DIR}"
OUT="$(cd "$1" && pwd)"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-/usr/local/lib/android/sdk/ndk/26.1.10909125}}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API=24

VIRGL_VERSION=1.3.0
VIRGL_SHA256=56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce
VIRGL_URL="https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/virglrenderer-$VIRGL_VERSION/virglrenderer-virglrenderer-$VIRGL_VERSION.tar.gz"
EPOXY_VERSION=1.5.10
EPOXY_SHA256=a7ced37f4102b745ac86d6a70a9da399cc139ff168ba6b8002b4d8d43c900c15
EPOXY_URL="https://github.com/anholt/libepoxy/archive/refs/tags/$EPOXY_VERSION.tar.gz"

[ -x "$TC/bin/clang" ] || { echo "no NDK at $NDK" >&2; exit 1; }
command -v meson >/dev/null && command -v ninja >/dev/null || { echo "meson and ninja are needed" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fetch() { # url sha256 file
    local i
    for i in 1 2 3 4; do
        curl -fsSL --retry 3 -o "$3" "$1" && break
        sleep $((i * 5))
    done
    echo "$2  $3" | sha256sum -c - >/dev/null || { echo "SHA-256 of $1 is not $2" >&2; exit 1; }
}
fetch "$VIRGL_URL" "$VIRGL_SHA256" "$WORK/virgl.tar.gz"
fetch "$EPOXY_URL" "$EPOXY_SHA256" "$WORK/epoxy.tar.gz"

SRC="$WORK/virglrenderer-virglrenderer-$VIRGL_VERSION"
tar -xzf "$WORK/virgl.tar.gz" -C "$WORK"
tar -xzf "$WORK/epoxy.tar.gz" -C "$WORK"
mv "$WORK/libepoxy-$EPOXY_VERSION" "$SRC/libepoxy"
for p in "$HERE"/gpu/patches/*.patch; do
    echo "applying $(basename "$p")"
    patch -p1 -s -d "$SRC" < "$p"
done

build_abi() { # abi clang-triple cpu-family cpu
    local abi="$1" triple="$2" family="$3" cpu="$4"
    local prefix="$WORK/prefix-$abi" cross="$WORK/cross-$abi.txt"
    cat > "$cross" <<EOF
[binaries]
c = '$TC/bin/$triple$API-clang'
cpp = '$TC/bin/$triple$API-clang++'
ar = '$TC/bin/llvm-ar'
strip = '$TC/bin/llvm-strip'
pkg-config = 'pkg-config'

[built-in options]
c_args = ['-fPIE', '-O2', '-I$HERE/gpu/include']
c_link_args = ['-fPIE', '-pie', '-Wl,-z,max-page-size=16384', '-llog']

[host_machine]
system = 'android'
cpu_family = '$family'
cpu = '$cpu'
endian = 'little'
EOF
    # only the libraries built here, never the build machine's
    export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig"
    export PKG_CONFIG_PATH=""

    meson setup "$WORK/epoxy-$abi" "$SRC/libepoxy" --cross-file "$cross" --prefix "$prefix" --libdir lib \
        --buildtype release -Ddefault_library=static -Degl=yes -Dglx=no -Dx11=false -Dtests=false -Ddocs=false
    ninja -C "$WORK/epoxy-$abi" install

    meson setup "$WORK/virgl-$abi" "$SRC" --cross-file "$cross" --prefix "$prefix" --libdir lib \
        --buildtype release -Ddefault_library=static -Dplatforms=egl -Dvenus=false -Dtests=false -Dvideo=false
    ninja -C "$WORK/virgl-$abi" vtest/virgl_test_server

    mkdir -p "$OUT/$abi"
    "$TC/bin/llvm-strip" -o "$OUT/$abi/libvirgl_test_server.so" "$WORK/virgl-$abi/vtest/virgl_test_server"
    # a program that Android's linker starts: position independent, no libraries but the system's
    "$TC/bin/llvm-readelf" -h "$OUT/$abi/libvirgl_test_server.so" | grep -q 'DYN' || { echo "$abi: not a PIE" >&2; exit 1; }
    local needed
    needed="$("$TC/bin/llvm-readelf" -d "$OUT/$abi/libvirgl_test_server.so" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' | tr '\n' ' ')"
    echo "$abi: needs $needed"
    for lib in $needed; do
        case "$lib" in
            libc.so|libm.so|libdl.so|liblog.so|libandroid.so|libEGL.so|libGLESv2.so|libGLESv3.so) ;;
            *) echo "$abi: needs $lib, which is not a system library" >&2; exit 1 ;;
        esac
    done
    ls -l "$OUT/$abi/libvirgl_test_server.so"
}

build_abi arm64-v8a aarch64-linux-android aarch64 armv8
build_abi armeabi-v7a armv7a-linux-androideabi arm armv7

