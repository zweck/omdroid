#!/usr/bin/env python3
"""Download Termux packages plus their transitive Depends closure into a prefix.

Standard library only. The repository indexes are the Termux aarch64 ones
(main + x11); each package's ``Filename`` field is resolved against the repo
root derived from its index URL, the ``SHA256`` field is verified, and the
``data.tar.xz`` member of the .deb is unpacked with the
``data/data/com.termux/files/usr/`` prefix stripped so the result is a drop-in
Termux ``$PREFIX`` tree (as used by Phoshdroid's in-app bootstrap asset).

Usage:
    ./rootfs/fetch-termux-packages.py --prefix DIR [--cache DIR] \\
        [--index URL ...] [--manifest FILE] weston [more packages ...]

Only ``Depends`` is followed (Pre-Depends/Recommends are ignored), ``a | b``
alternatives take the first name, and version constraints are ignored when
resolving.  The highest available version of a name across all indexes wins.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import lzma
import os
import re
import shutil
import sys
import tarfile
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_INDEXES = [
    "https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz",
    "https://packages.termux.dev/apt/termux-x11/dists/x11/main/binary-aarch64/Packages.gz",
]

# Every Termux .deb stores its payload under this in-archive prefix.
TERMUX_PREFIX = "data/data/com.termux/files/usr"

USER_AGENT = "phoshdroid-fetch-termux-packages/1.0"


# --------------------------------------------------------------------------
# index parsing
# --------------------------------------------------------------------------

def read_index(url: str) -> str:
    """Fetch a Packages.gz (or decompress a local one) and return text."""
    if url.startswith(("http://", "https://")):
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=120) as resp:
            blob = resp.read()
    else:
        with open(url, "rb") as fh:
            blob = fh.read()
    if blob[:2] == b"\x1f\x8b":
        blob = gzip.decompress(blob)
    return blob.decode("utf-8", "replace")


def parse_stanzas(text: str):
    """Yield one dict per deb822 stanza in a Packages file."""
    stanza = {}
    key = None
    for line in text.splitlines():
        if not line.strip():
            if stanza:
                yield stanza
                stanza = {}
                key = None
            continue
        if line[0] in " \t" and key:  # continuation line
            stanza[key] += "\n" + line.strip()
            continue
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        stanza[key] = value.strip()
    if stanza:
        yield stanza


def load_indexes(urls):
    """Return (providers, errors).

    providers maps package name -> list of (version, stanza, repo_root).
    """
    providers = {}
    for url in urls:
        repo_root = url.split("/dists/", 1)[0].rstrip("/")
        if "/dists/" not in url:
            # Local file with no repo layout: fall back to its directory.
            repo_root = os.path.dirname(os.path.abspath(url))
        try:
            text = read_index(url)
        except (urllib.error.URLError, OSError) as exc:
            raise SystemExit(f"ERROR: cannot read index {url}: {exc}")
        for stanza in parse_stanzas(text):
            name = stanza.get("Package")
            version = stanza.get("Version")
            filename = stanza.get("Filename")
            if not (name and version and filename):
                continue
            providers.setdefault(name, []).append((version, stanza, repo_root))
    return providers


# --------------------------------------------------------------------------
# Debian version comparison (enough of dpkg's algorithm for real indexes)
# --------------------------------------------------------------------------

def _char_order(ch: str) -> int:
    if ch == "~":
        return -1
    if ch.isdigit():
        return 0
    if ch.isalpha():
        return ord(ch)
    return ord(ch) + 256


def _compare_part(a: str, b: str) -> int:
    i = j = 0
    la, lb = len(a), len(b)
    while i < la or j < lb:
        first_diff = 0
        while (i < la and not a[i].isdigit()) or (j < lb and not b[j].isdigit()):
            ac = _char_order(a[i]) if i < la else 0
            bc = _char_order(b[j]) if j < lb else 0
            if ac != bc:
                return ac - bc
            i += 1
            j += 1
        while i < la and a[i] == "0":
            i += 1
        while j < lb and b[j] == "0":
            j += 1
        while i < la and a[i].isdigit() and j < lb and b[j].isdigit():
            if not first_diff:
                first_diff = ord(a[i]) - ord(b[j])
            i += 1
            j += 1
        if i < la and a[i].isdigit():
            return 1
        if j < lb and b[j].isdigit():
            return -1
        if first_diff:
            return first_diff
    return 0


def version_cmp(left: str, right: str) -> int:
    """Return >0 if left is newer than right, <0 if older, 0 if equal."""
    def split_epoch(v):
        if ":" in v:
            epoch, rest = v.split(":", 1)
            try:
                return int(epoch), rest
            except ValueError:
                pass
        return 0, v

    le, lv = split_epoch(left)
    re_, rv = split_epoch(right)
    if le != re_:
        return 1 if le > re_ else -1
    ldash = lv.rsplit("-", 1) if "-" in lv else [lv, ""]
    rdash = rv.rsplit("-", 1) if "-" in rv else [rv, ""]
    cmp_up = _compare_part(ldash[0], rdash[0])
    if cmp_up:
        return cmp_up
    return _compare_part(ldash[1], rdash[1])


# --------------------------------------------------------------------------
# dependency resolution
# --------------------------------------------------------------------------

def dependency_names(stanza) -> list:
    names = []
    field = stanza.get("Depends", "")
    for part in field.split(","):
        part = part.strip()
        if not part:
            continue
        alternative = part.split("|", 1)[0].strip()
        # Drop version constraints and arch qualifiers: "foo (>= 1.0)".
        name = re.split(r"[\s(\[]", alternative, maxsplit=1)[0].strip()
        if name and name not in names:
            names.append(name)
    return names


def resolve(roots, providers):
    """Return (closure, missing) where closure maps name -> chosen provider."""
    closure = {}
    missing = []
    queue = list(roots)
    seen = set()
    while queue:
        name = queue.pop(0)
        if name in seen:
            continue
        seen.add(name)
        candidates = providers.get(name)
        if not candidates:
            missing.append(name)
            continue
        best = max(candidates, key=lambda item: _VersionKey(item[0]))
        closure[name] = best
        for dep in dependency_names(best[1]):
            if dep not in seen:
                queue.append(dep)
    return closure, missing


class _VersionKey:
    """Wrapper so max() uses Debian version ordering."""

    __slots__ = ("version",)

    def __init__(self, version: str):
        self.version = version

    def __lt__(self, other):
        return version_cmp(self.version, other.version) < 0

    def __eq__(self, other):
        return version_cmp(self.version, other.version) == 0


# --------------------------------------------------------------------------
# download + .deb unpacking
# --------------------------------------------------------------------------

def sanitise(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9._+-]", "_", text)


def download(stanza, repo_root, cache_dir):
    filename = stanza["Filename"]
    url = repo_root + "/" + filename.lstrip("/")
    base = os.path.basename(filename)
    dest = os.path.join(cache_dir, base)
    expected = stanza.get("SHA256", "").lower()

    def digest(path):
        h = hashlib.sha256()
        with open(path, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                h.update(chunk)
        return h.hexdigest()

    if os.path.exists(dest) and expected and digest(dest) == expected:
        return dest, url, int(stanza.get("Size", 0) or 0), True

    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=300) as resp, open(tmp, "wb") as out:
            shutil.copyfileobj(resp, out)
    except urllib.error.HTTPError as exc:
        raise SystemExit(f"ERROR: download failed {url}: HTTP {exc.code}")
    except (urllib.error.URLError, OSError) as exc:
        raise SystemExit(f"ERROR: download failed {url}: {exc}")

    actual = digest(tmp)
    if expected and actual != expected:
        os.unlink(tmp)
        raise SystemExit(
            f"ERROR: SHA256 mismatch for {base}: expected {expected}, got {actual}"
        )
    os.replace(tmp, dest)
    return dest, url, os.path.getsize(dest), False


def ar_members(data: bytes):
    """Yield (name, member bytes) from a Unix ar archive (.deb)."""
    if data[:8] != b"!<arch>\n":
        raise ValueError("not an ar archive")
    pos = 8
    while pos + 60 <= len(data):
        header = data[pos:pos + 60]
        name = header[0:16].decode("utf-8", "replace").strip()
        try:
            size = int(header[48:58].decode("ascii").strip())
        except ValueError:
            break
        start = pos + 60
        yield name, data[start:start + size]
        pos = start + size + (size & 1)


def deb_data_member(deb_path):
    with open(deb_path, "rb") as fh:
        data = fh.read()
    for name, blob in ar_members(data):
        stripped = name.rstrip("/")
        if stripped.startswith("data.tar"):
            return stripped, blob
    raise SystemExit(f"ERROR: {deb_path} has no data.tar member")


def decompress_member(name, blob):
    if name.endswith(".xz"):
        return lzma.decompress(blob)
    if name.endswith(".gz"):
        return gzip.decompress(blob)
    if name.endswith(".tar"):
        return blob
    raise SystemExit(f"ERROR: unsupported data member compression: {name}")


def strip_termux_prefix(member_name: str):
    """Map an archive path to a prefix-relative path, or None to skip it."""
    name = member_name
    while name.startswith("./"):
        name = name[2:]
    name = name.lstrip("/")
    if name in ("", "."):
        return None
    if name == TERMUX_PREFIX:
        return None
    marker = TERMUX_PREFIX + "/"
    if not name.startswith(marker):
        return None  # outside the Termux prefix, not ours to install
    rel = name[len(marker):]
    parts = [part for part in rel.split("/") if part not in ("", ".")]
    if any(part == ".." for part in parts):
        return None
    return "/".join(parts)


def extract_deb(deb_path, prefix):
    member_name, blob = deb_data_member(deb_path)
    raw = decompress_member(member_name, blob)
    added = 0
    pending_hardlinks = []
    dir_modes = []
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as tf:
        for member in tf:
            rel = strip_termux_prefix(member.name)
            if rel is None:
                continue
            dest = os.path.join(prefix, rel)
            parent = os.path.dirname(dest)
            if parent:
                os.makedirs(parent, exist_ok=True)
            if member.isdir():
                os.makedirs(dest, exist_ok=True)
                dir_modes.append((dest, member.mode & 0o7777))
                continue
            if member.issym():
                if os.path.islink(dest) or os.path.exists(dest):
                    os.unlink(dest)
                os.symlink(member.linkname, dest)
                added += 1
                continue
            if member.islnk():
                pending_hardlinks.append((dest, strip_termux_prefix(member.linkname)))
                continue
            if not member.isfile():
                print(f"  WARN: skipping non-regular member {member.name}")
                continue
            if os.path.islink(dest) or os.path.exists(dest):
                if os.path.isdir(dest) and not os.path.islink(dest):
                    shutil.rmtree(dest)
                else:
                    os.unlink(dest)
            src = tf.extractfile(member)
            if src is None:
                continue
            with src, open(dest, "wb") as out:
                shutil.copyfileobj(src, out)
            os.chmod(dest, member.mode & 0o7777)
            added += 1
    for dest, link_rel in pending_hardlinks:
        if link_rel is None:
            continue
        target = os.path.join(prefix, link_rel)
        if os.path.exists(target):
            if os.path.exists(dest) or os.path.islink(dest):
                os.unlink(dest)
            os.link(target, dest)
            added += 1
        else:
            print(f"  WARN: unresolved hardlink target {link_rel} for {dest}")
    # Directory modes last, so read-only dirs do not block their own contents.
    for path, mode in reversed(dir_modes):
        try:
            os.chmod(path, mode)
        except OSError:
            pass
    return added


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------

def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Download Termux packages and their Depends closure into a prefix."
    )
    parser.add_argument("packages", nargs="+", help="root package names, e.g. weston")
    parser.add_argument(
        "--prefix",
        required=True,
        help="destination Termux prefix (the directory that contains bin/, lib/, ...)",
    )
    parser.add_argument(
        "--cache",
        default=os.path.join(
            os.environ.get("XDG_CACHE_HOME", os.path.expanduser("~/.cache")),
            "phoshdroid",
            "termux-packages",
        ),
        help="download cache directory (default: ~/.cache/phoshdroid/termux-packages)",
    )
    parser.add_argument(
        "--index",
        action="append",
        default=None,
        metavar="URL",
        help="Packages.gz URL or local path; repeatable (default: Termux main + x11)",
    )
    parser.add_argument(
        "--manifest",
        default=None,
        help="write the resolved closure (name/version/bytes/sha256) as JSON here",
    )
    args = parser.parse_args(argv)

    indexes = args.index if args.index else DEFAULT_INDEXES
    os.makedirs(args.prefix, exist_ok=True)
    os.makedirs(args.cache, exist_ok=True)

    print(f"Reading {len(indexes)} package index(es)...")
    providers = load_indexes(indexes)

    closure, missing = resolve(args.packages, providers)
    if missing:
        print("ERROR: packages missing from every index: " + ", ".join(sorted(missing)))
        print("Refusing to substitute older or partial packages.")
        return 2

    manifest = []
    total_bytes = 0
    for name in sorted(closure):
        version, stanza, repo_root = closure[name]
        deb_path, url, size, cached = download(stanza, repo_root, args.cache)
        wrote = extract_deb(deb_path, args.prefix)
        sha = stanza.get("SHA256", "")
        total_bytes += size
        manifest.append(
            {
                "package": name,
                "version": version,
                "bytes": size,
                "sha256": sha,
                "url": url,
                "files_written": wrote,
                "cached": cached,
            }
        )
        print(
            f"  {name:<24} {version:<16} {size:>10} B  "
            f"{'cached' if cached else 'downloaded'}  {wrote} files"
        )

    print(f"\nInstalled {len(manifest)} package(s), {total_bytes} bytes of .deb payload.")
    if args.manifest:
        with open(args.manifest, "w", encoding="utf-8") as fh:
            json.dump(
                {
                    "roots": args.packages,
                    "indexes": indexes,
                    "prefix": os.path.abspath(args.prefix),
                    "total_deb_bytes": total_bytes,
                    "packages": manifest,
                },
                fh,
                indent=2,
                sort_keys=True,
            )
        print(f"Manifest written to {args.manifest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
