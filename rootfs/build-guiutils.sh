#!/bin/bash
# Cross-build Hyprland Qt utilities against the pinned guest image, without
# upgrading its compositor libraries. Requires clang, cmake, ninja, host Qt 6 development tools and LLVM
# lld/strip on the host. The Android NDK also provides lld and llvm-strip.
# Output: $OMDROID_GUIUTILS_WORK/out-qt (default ~/.cache/omdroid/guiutils/out-qt).
# Copy out-qt/ into rootfs/overlay/etc/phoshdroid/guiutils/libexec after device testing.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${OMDROID_GUIUTILS_WORK:-${XDG_CACHE_HOME:-$HOME/.cache}/omdroid/guiutils}"
ROOTFS="${OMDROID_GUIUTILS_ROOTFS:-$PROJECT_DIR/app/src/main/assets/rootfs.bin}"
LLD="${OMDROID_GUIUTILS_LLD:-ld.lld}"
STRIP="${OMDROID_GUIUTILS_STRIP:-llvm-strip}"
NINJA="${OMDROID_GUIUTILS_NINJA:-ninja}"
LLD="$(command -v "$LLD")"
STRIP="$(command -v "$STRIP")"
NINJA="$(command -v "$NINJA")"
SOURCE_SHA=c02e47c9f720c20a5adca1baebbcfee0db5d3d36ce9595779563a2e5d27bafca
GCC_SHA=067a2aa64f5c7e626e3910fed6fd1f173a1fd9eb6bebaebdd7a7f5be58e170c4
mkdir -p "$WORK"
WORK="$(cd "$WORK" && pwd)"
fetch() {
    local url="$1" file="$2" sha="$3"
    [ -f "$file" ] || curl -fL --retry 3 "$url" -o "$file"
    echo "$sha  $file" | sha256sum --check --status
}
fetch https://codeload.github.com/hyprwm/hyprland-qtutils/tar.gz/refs/tags/v0.1.5 \
    "$WORK/qt-source.tar.gz" "$SOURCE_SHA"
# Only the headers and GCC support objects enter the sysroot; no ARM compiler
# is executed. These match the GCC 16 runtime in the pinned guest image.
fetch 'https://mi.us.mirror.archlinuxarm.org/aarch64/core/gcc-16.1.1%2Br12%2Bg301eb08fa2c5-1-aarch64.pkg.tar.xz' \
    "$WORK/gcc.pkg.tar.xz" "$GCC_SHA"
image_sha="$(sha256sum "$ROOTFS" | cut -d ' ' -f 1)"
if [ "$(cat "$WORK/sysroot.sha256" 2>/dev/null || true)" != "$image_sha" ]; then
    # Use a fresh staging directory; never overlay newer libraries on old ones.
    stage="$(mktemp -d "$WORK/sysroot.XXXXXX")"
    tar -xzf "$ROOTFS" -C "$stage" ./usr/include ./usr/lib
    tar -xJf "$WORK/gcc.pkg.tar.xz" -C "$stage" usr/include/c++ usr/lib/gcc
    ln -s usr/lib "$stage/lib"
    if [ -e "$WORK/sysroot" ]; then
        mv "$WORK/sysroot" "$WORK/sysroot.previous.$(date +%s)"
    fi
    mv "$stage" "$WORK/sysroot"
    printf '%s\n' "$image_sha" > "$WORK/sysroot.sha256"
fi
tar -xzf "$WORK/qt-source.tar.gz" -C "$WORK"
cat > "$WORK/cross.cmake" <<EOF
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)
set(CMAKE_SYSROOT "$WORK/sysroot")
set(CMAKE_C_COMPILER clang)
set(CMAKE_CXX_COMPILER clang++)
set(CMAKE_C_COMPILER_TARGET aarch64-unknown-linux-gnu)
set(CMAKE_CXX_COMPILER_TARGET aarch64-unknown-linux-gnu)
set(CMAKE_C_COMPILER_EXTERNAL_TOOLCHAIN "$WORK/sysroot/usr")
set(CMAKE_CXX_COMPILER_EXTERNAL_TOOLCHAIN "$WORK/sysroot/usr")
set(CMAKE_EXE_LINKER_FLAGS "-fuse-ld=$LLD")
set(CMAKE_CXX_FLAGS "-ffile-prefix-map=$WORK=/build/guiutils")
set(CMAKE_FIND_ROOT_PATH "$WORK/sysroot")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
EOF
# Qt 6.11 separates its private Wayland CMake target. Avoid host QML AOT
# bytecode because the host and guest Qt patch/minor versions can differ.
sed -i 's/QuickControls2 WaylandClient)/QuickControls2 WaylandClient WaylandClientPrivate)/' \
    "$WORK/hyprland-qtutils-0.1.5/CMakeLists.txt"
sed -i '/    URI /i\    NO_CACHEGEN' "$WORK/hyprland-qtutils-0.1.5/utils/"*/CMakeLists.txt
export PKG_CONFIG_SYSROOT_DIR="$WORK/sysroot"
export PKG_CONFIG_LIBDIR="$WORK/sysroot/usr/lib/pkgconfig:$WORK/sysroot/usr/share/pkgconfig"
cmake -S "$WORK/hyprland-qtutils-0.1.5" -B "$WORK/qt-build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$WORK/cross.cmake" -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_BUILD_TYPE=Release -DQT_HOST_PATH="${OMDROID_GUIUTILS_QT_HOST:-/usr}"
cmake --build "$WORK/qt-build" --parallel "${OMDROID_GUIUTILS_JOBS:-4}"
mkdir -p "$WORK/out-qt"
for utility in dialog donate-screen update-screen; do
    install -m 755 "$WORK/qt-build/utils/$utility/hyprland-$utility" "$WORK/out-qt/"
    "$STRIP" --strip-unneeded "$WORK/out-qt/hyprland-$utility"
done
sha256sum "$WORK/out-qt/"*
