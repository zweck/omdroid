#!/bin/bash
# Builds the Omarchy guest rootfs asset for the APK.
#
# Source: the upstream Omarchy-Android release bundle (MIT), sha256-pinned in
# docs/omarchy/payload-findings.md. Its rootfs.tar.xz is repacked to gzip with
# this project's overlay applied, because AssetExtractor reads GZIP + tar.
#
# Output: app/src/main/assets/rootfs.bin
#
# Usage: ./rootfs/package-omarchy-rootfs.sh [--bundle PATH]
# Requires ~6 GB free (staging copy + gzip output).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT="$PROJECT_DIR/app/src/main/assets/rootfs.bin"
OVERLAY_DIR="$SCRIPT_DIR/overlay"
CACHE_DIR="${OMARCHY_PAYLOAD_DIR:-$HOME/omarchy-payload}"
BUNDLE="$CACHE_DIR/omarchy-android-aarch64-0.1.1.bundle.tar"
BUNDLE_SHA256="7e9f1cd67533bc0d3988b5cb3831aef52f1527dd90391d9d868ed9345021cdb2"

if [ "${1:-}" = "--bundle" ]; then
    BUNDLE="$2"
fi

echo "=== Omarchy rootfs packager ==="

if [ ! -f "$BUNDLE" ]; then
    mkdir -p "$CACHE_DIR"
    echo "Downloading Omarchy release bundle (~1.2 GB)..."
    curl -L --fail --retry 3 \
        "https://github.com/BlackFireAlex/omarchy-android/releases/download/v0.1.1/omarchy-android-aarch64-0.1.1.bundle.tar" \
        -o "$BUNDLE"
fi

actual="$(sha256sum "$BUNDLE" | awk '{print $1}')"
if [ "$actual" != "$BUNDLE_SHA256" ]; then
    echo "ERROR: bundle checksum mismatch:" >&2
    echo "  expected $BUNDLE_SHA256" >&2
    echo "  actual   $actual" >&2
    exit 1
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/omarchy-rootfs.XXXXXX")"
cleanup() {
    # The rootfs contains read-only directories and setuid binaries; make the
    # staging tree writable for the current user before deleting it.
    chmod -R u+rwX "$WORK" 2>/dev/null || true
    rm -rf "$WORK"
}
trap cleanup EXIT

echo "Extracting bundle rootfs.tar.xz..."
tar -xf "$BUNDLE" -C "$WORK" ./rootfs.tar.xz
mkdir -p "$WORK/stage"
xz -dc "$WORK/rootfs.tar.xz" | tar -xf - -C "$WORK/stage"
rm -f "$WORK/rootfs.tar.xz"

# The archive's top-level directory entry is mode 0555 and carries root-only
# paths and setuid binaries. As an unprivileged user, tar cannot write into a
# read-only root and skips some entries, so make the whole tree writable first.
chmod -R u+rwX "$WORK/stage"
chmod 755 "$WORK/stage"

echo "Applying overlay (session script, resize helper + fake /proc files only)..."
mkdir -p "$WORK/stage/etc/phoshdroid"
cp -a "$OVERLAY_DIR/etc/phoshdroid/start.sh" "$WORK/stage/etc/phoshdroid/start.sh"
cp -a "$OVERLAY_DIR/etc/phoshdroid/session-child.sh" "$WORK/stage/etc/phoshdroid/session-child.sh"
cp -a "$OVERLAY_DIR/etc/phoshdroid/omarchy-plugin-index.sh" "$WORK/stage/etc/phoshdroid/omarchy-plugin-index.sh"
cp -a "$OVERLAY_DIR/etc/phoshdroid/fit-x11.py" "$WORK/stage/etc/phoshdroid/fit-x11.py"
cp -a "$OVERLAY_DIR/etc/phoshdroid/omarchy-watch.py" "$WORK/stage/etc/phoshdroid/omarchy-watch.py"
cp -a "$OVERLAY_DIR/etc/phoshdroid/fake-proc" "$WORK/stage/etc/phoshdroid/fake-proc"
chmod 755 "$WORK/stage/etc/phoshdroid/start.sh" "$WORK/stage/etc/phoshdroid/session-child.sh" \
    "$WORK/stage/etc/phoshdroid/omarchy-plugin-index.sh" \
    "$WORK/stage/etc/phoshdroid/omarchy-watch.py"

# The nested parent compositor. The bundle ships weston 14's patched x11 module
# for the Termux host, but this project runs the parent INSIDE the guest, and
# stock weston's x11 backend dies against Xlorie ("x11shm: failed to allocate
# SHM segment") because Android blocks SysV SHM. cage/wlroots presents through
# the same X11 path phoc used, so it is the parent here.
echo "Installing guest compositor packages (cage + wlroots + libliftoff)..."
GUEST_PKG_CACHE="$CACHE_DIR/guest-packages"
mkdir -p "$GUEST_PKG_CACHE"
install_guest_package() {
    local url="$1" sha="$2" file actual
    file="$GUEST_PKG_CACHE/${url##*/}"
    if [ ! -f "$file" ]; then
        curl -L --fail --retry 3 "$url" -o "$file"
    fi
    actual="$(sha256sum "$file" | awk '{print $1}')"
    if [ "$actual" != "$sha" ]; then
        echo "ERROR: checksum mismatch for $file" >&2
        echo "  expected $sha" >&2
        echo "  actual   $actual" >&2
        exit 1
    fi
    tar -xJf "$file" -C "$WORK/stage" usr
}
# cage 0.3.1-1 and libliftoff 0.5.0-1 are the versions the device bring-up used.
install_guest_package \
    "http://mirror.archlinuxarm.org/aarch64/extra/cage-0.3.1-1-aarch64.pkg.tar.xz" \
    "7487c2d69503f6b0384181231902b02f0b18469ef61894cd316d8bf167d4e4e0"
install_guest_package \
    "http://mirror.archlinuxarm.org/aarch64/extra/wlroots0.20-0.20.2-1-aarch64.pkg.tar.xz" \
    "31bf5f2ea29001643fffd3df21930e4254e51f30bc1a1684c476449d4ebdedc3"
install_guest_package \
    "http://mirror.archlinuxarm.org/aarch64/extra/libliftoff-0.5.0-1-aarch64.pkg.tar.xz" \
    "77c8cf79c256c0b118ee14250ba16b7e322525566daa16a9fcf048153a1214df"

# These are extracted, not registered with pacman (an unprivileged repack cannot
# run pacman -U), so record them for anyone auditing the image later.
cat > "$WORK/stage/etc/phoshdroid/guest-packages.txt" <<'EOF'
Extracted from Arch Linux ARM extra; not registered with the guest pacman db.
cage 0.3.1-1            http://mirror.archlinuxarm.org/aarch64/extra/cage-0.3.1-1-aarch64.pkg.tar.xz
wlroots0.20 0.20.2-1    http://mirror.archlinuxarm.org/aarch64/extra/wlroots0.20-0.20.2-1-aarch64.pkg.tar.xz
libliftoff 0.5.0-1      http://mirror.archlinuxarm.org/aarch64/extra/libliftoff-0.5.0-1-aarch64.pkg.tar.xz
EOF

# The pmOS-era overlay pieces (bwrap shim, pmroot, gschema override, Firefox
# prefs) are Phosh-specific and are deliberately not installed here.
echo "Repacking to gzip (this takes a few minutes)..."
mkdir -p "$(dirname "$OUTPUT")"
( cd "$WORK/stage" && tar -cf - . ) | gzip > "$OUTPUT"

echo "Wrote $OUTPUT"
ls -lh "$OUTPUT"
sha256sum "$OUTPUT"
echo "Members: $(tar -tzf "$OUTPUT" | wc -l)"
