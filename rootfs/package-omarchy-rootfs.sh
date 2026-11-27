#!/bin/bash
# Builds the Omarchy guest rootfs asset for the APK.
#
# Source: the upstream Omarchy-Android release bundle (MIT), sha256-pinned in
# docs/omarchy/payload-findings.md. Its rootfs.tar.xz is repacked to zstd
# (OMDROID_IMAGE_CODEC=gzip switches back) with this project's overlay applied;
# AssetExtractor picks the decoder from the stream's magic bytes.
#
# Output: app/src/main/assets/rootfs.bin
#
# Usage: ./rootfs/package-omarchy-rootfs.sh [--bundle PATH]
# Requires ~6 GB free (staging copy + compressed output).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT="$PROJECT_DIR/app/src/main/assets/rootfs.bin"
OVERLAY_DIR="$SCRIPT_DIR/overlay"
CACHE_DIR="${OMARCHY_PAYLOAD_DIR:-$HOME/omarchy-payload}"
BUNDLE="$CACHE_DIR/omarchy-android-aarch64-0.1.1.bundle.tar"
BUNDLE_SHA256="7e9f1cd67533bc0d3988b5cb3831aef52f1527dd90391d9d868ed9345021cdb2"

# Archive codec and level for the guest image. AssetExtractor picks the decoder
# from the stream's own magic bytes, so a gzip image keeps working (the
# bootstrap stays gzip either way). zstd -19 --long=26 measured 28% smaller than
# gzip -6 and 3x faster to decode here; the 64 MiB window captures 6.9% of the
# 7.4% the maximum window buys while needing ~70 MB to decode instead of ~140 MB
# (docs/omarchy/r10-image-size.md).
IMAGE_CODEC="${OMDROID_IMAGE_CODEC:-zstd}"
ZSTD_LEVEL="${OMDROID_ZSTD_LEVEL:-19}"
ZSTD_EXTRA="${OMDROID_ZSTD_EXTRA:---long=26}"

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
# UI mode: the switch reader/installer plus the Omamobile plugin trees it links
# into ~/.config/omarchy/plugins at session start (docs/omarchy/r5-mobile-ui-report.md).
cp -a "$OVERLAY_DIR/etc/phoshdroid/omdroid-ui.sh" "$WORK/stage/etc/phoshdroid/omdroid-ui.sh"
cp -a "$OVERLAY_DIR/etc/phoshdroid/omdroid-app-defaults.py" "$WORK/stage/etc/phoshdroid/omdroid-app-defaults.py"
cp -a "$OVERLAY_DIR/etc/phoshdroid/omamobile" "$WORK/stage/etc/phoshdroid/omamobile"
# Fake root for Omarchy's scripts: the broker the host's second proot runs and
# the sudo client (docs/omarchy/launch-contract.md section 7).
cp -a "$OVERLAY_DIR/etc/phoshdroid/omdroid-rootd.py" "$WORK/stage/etc/phoshdroid/omdroid-rootd.py"
cp -a "$OVERLAY_DIR/etc/phoshdroid/sudo" "$WORK/stage/etc/phoshdroid/sudo"
# Build tools: the client half of the broker protocol, the fakeroot shim makepkg
# calls for packaging, and the yay shim that installs yay-bin on first use
# (docs/omarchy/r6b-yay-report.md).
cp -a "$OVERLAY_DIR/etc/phoshdroid/omdroid_root.py" "$WORK/stage/etc/phoshdroid/omdroid_root.py"
cp -a "$OVERLAY_DIR/etc/phoshdroid/fakeroot" "$WORK/stage/etc/phoshdroid/fakeroot"
cp -a "$OVERLAY_DIR/etc/phoshdroid/yay" "$WORK/stage/etc/phoshdroid/yay"
# Hard-link repair for guests an older extractor left without their hard-link
# members (docs/omarchy/r6-sudo-report.md). hardlinks.txt lists them as
# member<TAB>target and must match the image this script builds.
cp -a "$OVERLAY_DIR/etc/phoshdroid/omdroid-repair-links.sh" "$WORK/stage/etc/phoshdroid/omdroid-repair-links.sh"
cp -a "$OVERLAY_DIR/etc/phoshdroid/hardlinks.txt" "$WORK/stage/etc/phoshdroid/hardlinks.txt"
# aquamarine with wl_touch (lib/README.md). start.sh puts /etc/phoshdroid/lib first
# in LD_LIBRARY_PATH; the image copy in /opt/omarchy-android/aquamarine stays as is.
cp -a "$OVERLAY_DIR/etc/phoshdroid/lib" "$WORK/stage/etc/phoshdroid/lib"
# Per-feature trees (mem, share, complete), each optional; start.sh runs the
# session-start.sh hook of every tree that has one.
for tree in mem share complete; do
    if [ -d "$OVERLAY_DIR/etc/phoshdroid/$tree" ]; then
        cp -a "$OVERLAY_DIR/etc/phoshdroid/$tree" "$WORK/stage/etc/phoshdroid/$tree"
    fi
done
cp -a "$OVERLAY_DIR/etc/phoshdroid/guiutils" "$WORK/stage/etc/phoshdroid/guiutils"
cp -a "$OVERLAY_DIR/etc/phoshdroid/terminal" "$WORK/stage/etc/phoshdroid/terminal"
chmod 755 "$WORK/stage/etc/phoshdroid/start.sh" "$WORK/stage/etc/phoshdroid/session-child.sh" \
    "$WORK/stage/etc/phoshdroid/omarchy-plugin-index.sh" \
    "$WORK/stage/etc/phoshdroid/omarchy-watch.py" \
    "$WORK/stage/etc/phoshdroid/omdroid-ui.sh" \
    "$WORK/stage/etc/phoshdroid/omdroid-rootd.py" \
    "$WORK/stage/etc/phoshdroid/omdroid_root.py" \
    "$WORK/stage/etc/phoshdroid/sudo" \
    "$WORK/stage/etc/phoshdroid/fakeroot" \
    "$WORK/stage/etc/phoshdroid/yay" \
    "$WORK/stage/etc/phoshdroid/omdroid-repair-links.sh"
# The vendored helpers go on the session PATH through /usr/local/bin, so the
# bar and the launcher find them by bare name. The symlinks are image-time; the
# scripts they point at are APK-synced into /etc/phoshdroid every start.
chmod 755 "$WORK/stage/etc/phoshdroid/omamobile/bin"/*
mkdir -p "$WORK/stage/usr/local/bin"
for helper in omamobile-launch omamobile-home omamobile-browser-setup \
    omamobile-cell-watch omamobile-call-watch; do
    ln -sfn "/etc/phoshdroid/omamobile/bin/$helper" "$WORK/stage/usr/local/bin/$helper"
done
# sudo: image-time link; start.sh relinks it every session so an updated
# install whose overlay sync only rewrote /etc/phoshdroid picks it up too.
ln -sfn "/etc/phoshdroid/sudo" "$WORK/stage/usr/local/bin/sudo"
# fakeroot and yay: same pattern. /usr/local/bin comes first in the session
# PATH, so the shims win over the real fakeroot that base-devel installs and
# over any /usr/bin/yay the AUR package leaves behind.
ln -sfn "/etc/phoshdroid/fakeroot" "$WORK/stage/usr/local/bin/fakeroot"
ln -sfn "/etc/phoshdroid/yay" "$WORK/stage/usr/local/bin/yay"

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

# Prune what no process in the session reads (docs/omarchy/r10-image-size.md).
# The session runs with LC_ALL=C.UTF-8, so no gettext catalog is ever loaded; it
# reads no man/info/doc pages; and Qt apps here install no translator. NEVER add
# usr/include or *.a here: yay builds AUR packages against the headers and static
# libs, and libc_nonshared.a is named by the libc.so linker script.
# OMDROID_PRUNE=0 packs the unpruned tree, which is the baseline the equivalence
# check in docs/omarchy/r10-image-size.md compares against.
if [ "${OMDROID_PRUNE:-1}" = "0" ]; then
    echo "Prune skipped (OMDROID_PRUNE=0)"
else
    echo "Pruning docs, non-English translations and the Python test suite..."
    for prune_dir in usr/share/doc usr/share/man usr/share/info usr/share/gtk-doc usr/share/help; do
        rm -rf "${WORK:?}/stage/$prune_dir"
    done
    # gettext catalogs: keep en*, drop every other language (and their now-empty
    # LC_MESSAGES directories).
    find "$WORK/stage/usr/share/locale" -type f -name '*.mo' \
        ! -path '*/locale/en/*' ! -path '*/locale/en_*/*' -delete 2>/dev/null || true
    find "$WORK/stage/usr/share/locale" -mindepth 2 -type d -empty -delete 2>/dev/null || true
    # Qt's own translations (usr/share/qt6/translations); English is compiled in, the
    # *_en.qm files are kept because they are free.
    find "$WORK/stage/usr/share/qt6/translations" -maxdepth 1 -type f -name '*.qm' \
        ! -name '*_en.qm' -delete 2>/dev/null || true
    # The Python standard library's test suite; nothing imports it.
    rm -rf "$WORK/stage"/usr/lib/python3*/test
fi

# A later `pacman -S` would put the pruned bulk straight back, so the image's
# pacman.conf gets matching NoExtract globs. pacman's globs cannot express
# "everything except en", so a new install skips all catalogs; that is fine
# because nothing in the session reads one (LC_ALL=C.UTF-8). `pacman -Qk` will
# now report the pruned paths as missing (docs/omarchy/r10-image-size.md).
NOEXTRACT="NoExtract   = usr/share/doc/* usr/share/man/* usr/share/info/* usr/share/gtk-doc/* usr/share/help/* usr/share/locale/*/LC_MESSAGES/*.mo usr/share/qt6/translations/*.qm usr/lib/python3*/test/*"
awk -v line="$NOEXTRACT" '
    $0 == "#NoExtract   =" {
        print "# Omdroid image: docs and non-English translations are pruned at packaging"
        print "# time (rootfs/package-omarchy-rootfs.sh); these globs keep a later"
        print "# pacman -S from extracting the bulk again."
        print line
        next
    }
    { print }
' "$WORK/stage/etc/pacman.conf" > "$WORK/stage/etc/pacman.conf.new"
mv "$WORK/stage/etc/pacman.conf.new" "$WORK/stage/etc/pacman.conf"

# Numeric root ownership in every tar header: a plain tar -cf records the
# user and group name of the builder on each of the ~70,000 members, and the
# extractor never applies owners anyway (an Android app cannot chown).
if [ "$IMAGE_CODEC" = "zstd" ]; then
    echo "Repacking to zstd -$ZSTD_LEVEL (this takes a few minutes)..."
    mkdir -p "$(dirname "$OUTPUT")"
    ( cd "$WORK/stage" && tar --owner=0 --group=0 --numeric-owner -cf - . ) | zstd -"$ZSTD_LEVEL" $ZSTD_EXTRA -T8 -q --force -o "$OUTPUT"
    TAR_CODEC="--zstd"
else
    echo "Repacking to gzip (this takes a few minutes)..."
    mkdir -p "$(dirname "$OUTPUT")"
    ( cd "$WORK/stage" && tar --owner=0 --group=0 --numeric-owner -cf - . ) | gzip > "$OUTPUT"
    TAR_CODEC="-z"
fi

echo "Wrote $OUTPUT"
ls -lh "$OUTPUT"
sha256sum "$OUTPUT"
echo "Members: $(tar "$TAR_CODEC" -tf "$OUTPUT" | wc -l)"
