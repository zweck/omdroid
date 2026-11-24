#!/bin/bash
# Builds the Termux bootstrap for Phoshdroid, including the Omarchy host
# payload (weston + its full library closure).
#
# Outputs:
#   - app/src/main/jniLibs/arm64-v8a/libbash.so       (executable via nativeLibDir)
#   - app/src/main/jniLibs/arm64-v8a/libproot.so       (executable via nativeLibDir)
#   - app/src/main/assets/bootstrap-omarchy.bin        (gzipped tar: bootstrap + weston)
#
# `bootstrap.bin` (the plain upstream bootstrap) is NOT touched by this script.
#
# Native binaries go into jniLibs/ so Android extracts them with exec permission.
# Everything else (scripts, shared libs, config) goes into the assets tarball.
#
# Prerequisites: curl, tar, unzip, python3, binutils (readelf)
# Usage: ./rootfs/build-bootstrap.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JNILIBS_DIR="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
ASSET_OUTPUT="$PROJECT_DIR/app/src/main/assets/bootstrap.bin"
ARCH="aarch64"

# Termux package cache (reused across builds; never part of the repo).
PKG_CACHE="${TERMUX_PKG_CACHE:-$HOME/.cache/phoshdroid/termux-packages}"
PKG_MANIFEST="$PKG_CACHE/closure-manifest.json"

STAGING_DIR=$(mktemp -d)
trap "rm -rf $STAGING_DIR" EXIT

echo "=== Phoshdroid bootstrap builder ==="

# Download official Termux bootstrap
BOOTSTRAP_URL="https://github.com/termux/termux-packages/releases/latest/download/bootstrap-${ARCH}.zip"
echo "Downloading Termux bootstrap..."
curl -L "$BOOTSTRAP_URL" -o "$STAGING_DIR/bootstrap.zip"

echo "Extracting..."
cd "$STAGING_DIR"
unzip -q bootstrap.zip -d prefix

# Fix symlinks (Termux stores them in SYMLINKS.txt)
# Format: target←link_path (link_path points to target)
if [ -f prefix/SYMLINKS.txt ]; then
    while IFS='←' read -r target linkpath; do
        # linkpath is relative to prefix (e.g., ./lib/libfoo.so)
        rm -f "prefix/$linkpath"
        mkdir -p "$(dirname "prefix/$linkpath")"
        ln -sf "$target" "prefix/$linkpath"
    done < prefix/SYMLINKS.txt
    rm -f prefix/SYMLINKS.txt
fi

# Verify essential files exist
echo "Verifying bootstrap contents..."
MISSING=0
for required in bin/bash bin/proot bin/proot-distro; do
    if [ ! -f "prefix/$required" ] && [ ! -L "prefix/$required" ]; then
        echo "ERROR: $required not found in bootstrap"
        MISSING=1
    fi
done

if [ "$MISSING" -eq 1 ]; then
    echo ""
    echo "The Termux bootstrap may not include proot/proot-distro by default."
    echo "You may need to install them into the prefix manually:"
    echo "  1. Install Termux on an Android device"
    echo "  2. Run: pkg install proot proot-distro"
    echo "  3. Copy the prefix from the device"
    echo ""
    echo "Continuing anyway — binaries may need to be added manually."
fi

# === Install the Omarchy host payload: weston + transitive Depends closure ===
echo ""
echo "Installing weston (with library closure) into the staged prefix..."
python3 "$SCRIPT_DIR/fetch-termux-packages.py" \
    --prefix "$STAGING_DIR/prefix" \
    --cache "$PKG_CACHE" \
    --manifest "$PKG_MANIFEST" \
    weston

# === Static verification of the weston payload (host is x86_64; bionic ELFs
# cannot run here, so these checks are structural only) ===
echo ""
echo "Verifying weston payload..."
VERIFY_FAIL=0

for required in bin/weston lib/libweston-16.so lib/libweston-16/x11-backend.so; do
    if [ -f "prefix/$required" ] || [ -L "prefix/$required" ]; then
        echo "  OK   $required exists"
    else
        echo "  FAIL $required missing"
        VERIFY_FAIL=1
    fi
done

MODULE="prefix/lib/libweston-16/x11-backend.so"
if [ -f "$MODULE" ]; then
    MODULE_NEEDED=$(readelf -d "$MODULE" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p')
    for want in libweston-16.so libandroid-shmem.so; do
        if printf '%s\n' "$MODULE_NEEDED" | grep -qxF "$want"; then
            echo "  OK   x11-backend.so NEEDED contains $want"
        else
            echo "  FAIL x11-backend.so NEEDED missing $want"
            VERIFY_FAIL=1
        fi
    done
fi

# Every NEEDED entry of bin/weston and lib/*.so* must resolve either inside the
# staged prefix or to a library the Android system provides.
SYSTEM_ALLOW="libc.so libdl.so liblog.so libm.so libandroid.so libz.so libstdc++.so libEGL.so libGLESv2.so libvulkan.so libOpenSLES.so libmediandk.so libjnigraphics.so libc++_shared.so"
PREFIX_NAMES="$STAGING_DIR/prefix-basenames.txt"
find prefix \( -type f -o -type l -o -type d \) -printf '%f\n' | sort -u > "$PREFIX_NAMES"

ELF_CHECKED=0
UNRESOLVED=""
for elf in prefix/bin/weston $(find prefix/lib -maxdepth 1 -name '*.so*'); do
    readelf -h "$elf" >/dev/null 2>&1 || continue
    ELF_CHECKED=$((ELF_CHECKED + 1))
    while read -r so; do
        [ -z "$so" ] && continue
        grep -qxF "$so" "$PREFIX_NAMES" && continue
        case " $SYSTEM_ALLOW " in *" $so "*) continue ;; esac
        UNRESOLVED="${UNRESOLVED}${elf#prefix/} -> ${so}"$'\n'
    done < <(readelf -d "$elf" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p')
done

UNRESOLVED_COUNT=$(printf '%s' "$UNRESOLVED" | grep -c . || true)
echo "  ELF files checked: $ELF_CHECKED"
if [ "$UNRESOLVED_COUNT" -eq 0 ]; then
    echo "  OK   every NEEDED entry resolves (prefix or Android system)"
else
    echo "  FAIL $UNRESOLVED_COUNT unresolved NEEDED entries:"
    printf '%s' "$UNRESOLVED" | sed 's/^/       /'
    VERIFY_FAIL=1
fi

if [ "$VERIFY_FAIL" -ne 0 ]; then
    echo "ERROR: weston payload verification failed; not packaging the asset."
    exit 1
fi

# === Extract native binaries into jniLibs (Android makes these executable) ===
mkdir -p "$JNILIBS_DIR"

# Resolve symlinks and copy actual binaries. Android refuses exec from app data
# (W^X), so every binary the app runs from the host side must live here.
for bin_spec in bash:libbash.so proot:libproot.so weston:libweston-bin.so; do
    bin_name="${bin_spec%%:*}"
    so_name="${bin_spec##*:}"
    src="prefix/bin/$bin_name"
    if [ -L "$src" ]; then
        src=$(readlink -f "$src")
    fi
    if [ -f "$src" ]; then
        cp "$src" "$JNILIBS_DIR/$so_name"
        chmod 755 "$JNILIBS_DIR/$so_name"
        echo "  Extracted $bin_name -> jniLibs/arm64-v8a/$so_name ($(du -h "$JNILIBS_DIR/$so_name" | cut -f1))"
    else
        echo "  WARNING: $bin_name binary not found, skipping"
    fi
done

# === Package everything else as an asset tarball (scripts, libs, config) ===
# Remove the binaries we already extracted to jniLibs to avoid duplication
rm -f prefix/bin/bash prefix/bin/proot 2>/dev/null || true

# Headers and manual pages come with the Termux .debs and are never used at
# runtime; dropping them saves ~45 MB uncompressed in the APK.
rm -rf prefix/include prefix/share/man prefix/share/doc prefix/share/info 2>/dev/null || true

echo ""
echo "Compressing bootstrap prefix (with weston)..."
tar -czf "$ASSET_OUTPUT" -C prefix .

MEMBER_COUNT=$(tar -tzf "$ASSET_OUTPUT" | wc -l)

echo ""
echo "=== Done ==="
echo "  Native libs: $JNILIBS_DIR/"
ls -lh "$JNILIBS_DIR/"*.so 2>/dev/null || echo "  (none)"
echo "  Prefix size (uncompressed, staged): $(du -sh prefix | cut -f1)"
echo "  Asset tarball: $ASSET_OUTPUT ($(du -h "$ASSET_OUTPUT" | cut -f1))"
echo "  Asset members: $MEMBER_COUNT"
echo "  Closure manifest: $PKG_MANIFEST"
echo ""
echo "Now rebuild the APK: ./gradlew assembleDebug"
