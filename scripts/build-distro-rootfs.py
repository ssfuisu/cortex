#!/usr/bin/env python3
"""Builds the root file system of a distribution Cortex can install besides its own Ubuntu.

    build-distro-rootfs.py build ID ARCH OUTDIR     one image: OUTDIR/cortex-ID-ARCH.tar.zst and its .json
    build-distro-rootfs.py catalog OUTDIR           distros.json from the .json files in OUTDIR
    build-distro-rootfs.py list [ARCH]              "ID ARCH" for every image there is, one per line

The images come from images.linuxcontainers.org, which builds the plain system of each distribution
every day and publishes the SHA-256 of every file. The downloaded file is checked against it, then
changed as Cortex needs it:

- the dynamic linker and the C library get their seccomp-blocked system calls patched, as the Ubuntu
  of the app is (patch-bootstrap-ld.py). After an update of glibc the hook patches the new ld.so before
  the next program starts through it, so no distribution holds glibc back;
- absolute links become relative ones, since the rootfs is not at / on the phone;
- device files are left out (an app cannot make them), and so are manuals, documentation and
  translations other than English; /etc/resolv.conf and /etc/hostname become
  plain files, pacman leaves its sandbox off (Android's kernel has no Landlock, and pacman's download
  user cannot be switched to);
- /etc/cortex/distro tells the app which distribution it is.

Everything else (the hook, users, DNS, certificates, Cortex's tools) the app sets up on the phone, as
it does for its own Ubuntu.
"""

import hashlib
import importlib.util
import io
import json
import lzma
import os
import posixpath
import subprocess
import sys
import tarfile
import urllib.request

LXC = "https://images.linuxcontainers.org"

# id: name, version shown, package manager, (LXC distribution, release), architectures, description
DISTROS = {
    "ubuntu-26.04": {"name": "Ubuntu", "version": "26.04 LTS", "pm": "apt", "lxc": ("ubuntu", "resolute"), "arch": ["arm64", "armhf"]},
    "debian-13": {"name": "Debian", "version": "13", "pm": "apt", "lxc": ("debian", "trixie"), "arch": ["arm64", "armhf"]},
    "kali": {"name": "Kali Linux", "version": "rolling", "pm": "apt", "lxc": ("kali", "current"), "arch": ["arm64"]},
    "arch": {"name": "Arch Linux ARM", "version": "rolling", "pm": "pacman", "lxc": ("archlinux", "current"), "arch": ["arm64"]},
    "fedora-44": {"name": "Fedora", "version": "44", "pm": "dnf", "lxc": ("fedora", "44"), "arch": ["arm64"]},
    "opensuse": {"name": "openSUSE Tumbleweed", "version": "rolling", "pm": "zypper", "lxc": ("opensuse", "tumbleweed"), "arch": ["arm64"]},
    "void": {"name": "Void Linux", "version": "rolling", "pm": "xbps", "lxc": ("voidlinux", "current"), "arch": ["arm64"]},
}


def load_patcher():
    here = os.path.dirname(os.path.abspath(__file__))
    spec = importlib.util.spec_from_file_location("patch_bootstrap_ld", os.path.join(here, "patch-bootstrap-ld.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def fetch(url, dest=None):
    req = urllib.request.Request(url, headers={"User-Agent": "cortex-rootfs-builder"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                if dest is None:
                    return r.read()
                with open(dest, "wb") as f:
                    while True:
                        chunk = r.read(1 << 20)
                        if not chunk:
                            break
                        f.write(chunk)
                return None
        except Exception as e:  # network: retried
            if attempt == 3:
                raise
            print(f"retrying {url}: {e}", file=sys.stderr)


def find_image(distro, release, arch):
    """(serial, path) of the newest default image, from the index of all of them"""
    index = fetch(f"{LXC}/meta/1.0/index-system").decode()
    for line in index.splitlines():
        f = line.split(";")
        if len(f) >= 6 and f[0] == distro and f[1] == release and f[2] == arch and f[3] == "default":
            return f[4], f[5]
    raise SystemExit(f"no {distro} {release} {arch} image in the index")


def norm(name):
    while name.startswith("./"):
        name = name[2:]
    return name.lstrip("/")


# Documentation and translations: a third of an image, which the minimal images of Ubuntu leave out
# as well. Packages installed later bring theirs.
TRIM = ("usr/share/man/", "usr/share/doc/", "usr/share/info/", "usr/share/gtk-doc/", "usr/share/help/")


def trimmed(name):
    if name.startswith(TRIM):
        return True
    if name.startswith("usr/share/locale/"):
        rest = name[len("usr/share/locale/"):]
        lang = rest.split("/", 1)[0]
        return "/" in rest and not (lang == "en" or lang.startswith("en_") or lang.startswith("en@"))
    return False


def text_member(name, data, mode=0o644):
    m = tarfile.TarInfo(name)
    m.size = len(data)
    m.mode = mode
    m.mtime = 0
    return m, io.BytesIO(data)


def adjust_pacman_conf(text):
    """pacman's download sandbox off: no Landlock in Android's kernel, no switching to the alpm user"""
    out = []
    for line in text.splitlines():
        s = line.strip()
        if s.lstrip("#").split("=")[0].strip() in ("DownloadUser", "DisableSandbox", "DisableSandboxFilesystem", "DisableSandboxSyscalls"):
            continue
        out.append(line)
        if s == "[options]":
            out += ["# Cortex: Android's kernel has no Landlock and the download user cannot be switched to",
                    "DisableSandboxFilesystem", "DisableSandboxSyscalls"]
    return "\n".join(out) + "\n"


def build(did, arch, outdir):
    d = DISTROS[did]
    if arch not in d["arch"]:
        raise SystemExit(f"{did} has no {arch} image")
    os.makedirs(outdir, exist_ok=True)
    serial, path = find_image(d["lxc"][0], d["lxc"][1], arch)
    sums = fetch(f"{LXC}/{path}SHA256SUMS").decode()
    want = None
    for line in sums.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].lstrip("*") == "rootfs.tar.xz":
            want = parts[0]
    if not want:
        raise SystemExit("rootfs.tar.xz is not in SHA256SUMS")
    src = os.path.join(outdir, f"{did}-{arch}.rootfs.tar.xz")
    print(f"{did} {arch}: image {serial}")
    fetch(f"{LXC}/{path}rootfs.tar.xz", src)
    h = hashlib.sha256()
    with open(src, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    if h.hexdigest() != want:
        raise SystemExit(f"SHA-256 of rootfs.tar.xz is {h.hexdigest()}, the image says {want}")

    patcher = load_patcher()
    tar_path = os.path.join(outdir, f"cortex-{did}-{arch}.tar")
    patched = 0
    names = set()
    with lzma.open(src) as xz, tarfile.open(fileobj=xz, mode="r|") as tin, tarfile.open(tar_path, "w", format=tarfile.GNU_FORMAT) as tout:
        for m in tin:
            name = norm(m.name)
            if not name or name == ".":
                continue
            if name.startswith("dev/") or trimmed(name):
                continue
            if name in ("etc/resolv.conf", "etc/hostname", "etc/machine-id"):
                continue  # written below (machine-id: by the app, one per phone)
            m.name = name
            if m.islnk():
                m.linkname = norm(m.linkname)
            if m.issym() and m.linkname.startswith("/"):
                m.linkname = posixpath.relpath(m.linkname.lstrip("/") or ".", posixpath.dirname(name) or ".")
            names.add(name)
            f = tin.extractfile(m) if m.isreg() else None
            if f is None:
                tout.addfile(m)
                continue
            data = f.read()
            base = posixpath.basename(name)
            if base.startswith("ld-linux") or base.startswith("libc.so") or base.startswith("libc-"):
                buf, n = patcher.patch_elf_bytes(bytearray(data), name)
                patched += n
                data = bytes(buf)
            elif name == "etc/pacman.conf":
                data = adjust_pacman_conf(data.decode()).encode()
            m.size = len(data)
            tout.addfile(m, io.BytesIO(data))

        for extra, data in (
            ("etc/resolv.conf", b"nameserver 8.8.8.8\nnameserver 1.1.1.1\n"),
            ("etc/hostname", b"localhost\n"),
            ("etc/cortex/distro", f"ID={did}\nNAME={d['name']}\nVERSION={d['version']}\nPM={d['pm']}\nIMAGE={serial}\n".encode()),
        ):
            if extra == "etc/cortex/distro" and "etc/cortex" not in names:
                dm = tarfile.TarInfo("etc/cortex")
                dm.type = tarfile.DIRTYPE
                dm.mode = 0o755
                tout.addfile(dm)
            m, data_io = text_member(extra, data)
            tout.addfile(m, data_io)
    os.remove(src)
    if patched <= 0:
        raise SystemExit(f"no seccomp-blocked call was patched in {did} {arch}: the C library looks different")
    for need in ("usr/bin/bash",):
        if need not in names:
            raise SystemExit(f"{did} {arch} has no /{need}")
    unpacked = os.path.getsize(tar_path)
    out = tar_path + ".zst"
    # Level 19 keeps the window at 8 MB, which is what the app's decoder needs as memory
    subprocess.run(["zstd", "-19", "-T0", "-q", "--rm", "-f", tar_path, "-o", out], check=True)
    subprocess.run(["zstd", "-q", "-t", out], check=True)
    h = hashlib.sha256()
    with open(out, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    info = {
        "id": did, "arch": arch, "name": d["name"], "version": d["version"], "pm": d["pm"],
        "file": os.path.basename(out), "sha256": h.hexdigest(), "size": os.path.getsize(out),
        "unpacked": unpacked, "image": serial, "patched": patched,
    }
    with open(os.path.join(outdir, f"cortex-{did}-{arch}.json"), "w") as f:
        json.dump(info, f, indent=1)
    print(f"{out}: {info['size']} bytes, {patched} calls patched")


def catalog(outdir):
    images = []
    for n in sorted(os.listdir(outdir)):
        if n.startswith("cortex-") and n.endswith(".json"):
            with open(os.path.join(outdir, n)) as f:
                images.append(json.load(f))
    if not images:
        raise SystemExit("no images")
    with open(os.path.join(outdir, "distros.json"), "w") as f:
        json.dump({"format": 1, "images": images}, f, indent=1)
    print(f"distros.json: {len(images)} images")


def main(argv):
    if len(argv) >= 5 and argv[1] == "build":
        build(argv[2], argv[3], argv[4])
    elif len(argv) == 3 and argv[1] == "catalog":
        catalog(argv[2])
    elif len(argv) in (2, 3) and argv[1] == "list":
        for did, d in DISTROS.items():
            for a in d["arch"]:
                if len(argv) == 2 or argv[2] == a:
                    print(did, a)
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
