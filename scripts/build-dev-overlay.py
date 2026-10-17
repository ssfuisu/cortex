#!/usr/bin/env python3
"""Build the C development overlay that the Cortex app installs next to its pinned glibc.

Why this exists: Cortex runs an Ubuntu glibc whose libc.so.6 and ld.so are patched for Android's
seccomp filter (scripts/patch-bootstrap-ld.py), so apt is kept away from it (01cortex-pins, dpkg
holds). Ubuntu's libc6-dev needs exactly the installed libc6, and its name matches the libc6* pin,
so libc6-dev could never be installed, and with it build-essential, g++, python3-dev, zlib1g-dev
and every other -dev package.

The overlay is the stock libc6-dev with its four dependencies (libc-dev-bin, libcrypt-dev,
linux-libc-dev, rpcsvc-proto), registered in dpkg's database the way dpkg would have done it. The
only change to the stock metadata: the exact "libc6 (= version)" and "libcrypt1 (= version)" in
Depends become "at least". Headers and static libraries are the same in every 2.39.x security
update, while the runtime stays the patched libc6 that the app ships, so the pairing is sound for
any 2.39 libc6, which is what lets the same overlay serve fresh installs and old ones.

The result is a tar.gz with root-relative paths:
  usr/...                                   the package files
  var/lib/dpkg/info/<package>[:arch].*      list, md5sums and maintainer scripts, as dpkg writes them
  var/lib/cortex/devpkgs/status             the status stanzas, merged into var/lib/dpkg/status by the app

Usage:
  build-dev-overlay.py --arch arm64 --out app/src/main/assets/devpkgs-arm64.tar.gz --keyring KEYRING.gpg
  build-dev-overlay.py --arch armhf --out devpkgs-arm.tar.gz --debs-dir DIR       (no download)
  build-dev-overlay.py --verify devpkgs-arm64.tar.gz --arch arm64                  (check an overlay)
"""
import argparse
import gzip
import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

PACKAGES = ("libc6-dev", "libc-dev-bin", "libcrypt-dev", "linux-libc-dev", "rpcsvc-proto")
TRIPLETS = {"arm64": "aarch64-linux-gnu", "armhf": "arm-linux-gnueabihf"}
GLIBC_SERIES = "2.39"
DEFAULT_MIRROR = "https://ports.ubuntu.com/ubuntu-ports/"
DEFAULT_SUITES = ("noble", "noble-updates", "noble-security")

STATUS_PATH = "var/lib/cortex/devpkgs/status"
INFO_DIR = "var/lib/dpkg/info/"
MAINTAINER_SCRIPTS = ("preinst", "postinst", "prerm", "postrm")
# Control members dpkg keeps in its info directory next to list and md5sums.
KEPT_CONTROL_MEMBERS = ("md5sums", "shlibs", "symbols") + MAINTAINER_SCRIPTS
# Anything else (conffiles, triggers, config, templates...) needs a human to decide what it means.
KNOWN_CONTROL_MEMBERS = ("control",) + KEPT_CONTROL_MEMBERS
# The scripts of libcrypt-dev only migrate a documentation directory that older versions had as a
# symlink; on a system that never had it they do nothing. Any other line is not reviewed.
BENIGN_SCRIPT_LINE = re.compile(
    r'^(#.*|set -e|dpkg-maintscript-helper (symlink_to_dir|dir_to_symlink|rm_conffile|mv_conffile) .* -- "\$@")?$'
)


class OverlayError(Exception):
    pass


def required_files(arch):
    t = TRIPLETS[arch]
    return (
        "usr/include/stdio.h",
        "usr/include/limits.h",
        "usr/include/crypt.h",
        "usr/include/linux/limits.h",
        "usr/include/%s/bits/types.h" % t,
        "usr/include/%s/gnu/stubs.h" % t,
        "usr/include/%s/asm/unistd.h" % t,
        "usr/include/rpcsvc/nfs_prot.h",
        "usr/lib/%s/libc.a" % t,
        "usr/lib/%s/libc.so" % t,
        "usr/lib/%s/crt1.o" % t,
        "usr/lib/%s/crti.o" % t,
        "usr/bin/gencat",
        "usr/bin/rpcgen",
    )


# --- reading a .deb --------------------------------------------------------------------------


def _deb_tar(deb, option):
    proc = subprocess.run(["dpkg-deb", option, deb], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        raise OverlayError("dpkg-deb %s %s failed: %s" % (option, deb, proc.stderr.decode("utf-8", "replace").strip()))
    return tarfile.open(fileobj=io.BytesIO(proc.stdout), mode="r:")


def read_control_members(deb):
    """Name -> bytes for the members of the control archive."""
    members = {}
    with _deb_tar(deb, "--ctrl-tarfile") as tf:
        for m in tf.getmembers():
            name = m.name[2:] if m.name.startswith("./") else m.name
            if m.isfile():
                members[name] = tf.extractfile(m).read()
            elif name not in ("", "."):
                raise OverlayError("%s: unexpected control entry %r" % (deb, m.name))
    return members


def parse_control(text):
    """Control text -> list of [key, [lines]] (a field keeps its continuation lines)."""
    fields = []
    for line in text.split("\n"):
        if not line:
            continue
        if line[0] in " \t":
            if not fields:
                raise OverlayError("control file starts with a continuation line")
            fields[-1][1].append(line)
        else:
            key, sep, _ = line.partition(":")
            if not sep:
                raise OverlayError("malformed control line: %r" % line)
            fields.append([key, [line]])
    return fields


def field_value(fields, key):
    for k, lines in fields:
        if k == key:
            return lines[0].partition(":")[2].strip()
    return None


# --- rewriting the metadata -------------------------------------------------------------------


def relax_depends(depends, libc6_floor, libcrypt1_floor):
    """Turn "libc6 (= V)" and "libcrypt1 (= V)" into "(>= floor)"; every other dependency stays."""
    floors = {"libc6": libc6_floor, "libcrypt1": libcrypt1_floor}

    def repl(match):
        name = match.group(1)
        return "%s (>= %s)" % (name, floors[name])

    return re.sub(r"\b(libc6|libcrypt1) \(= [^)]*\)", repl, depends)


def status_stanza(fields, libc6_floor, libcrypt1_floor):
    """The stanza dpkg would keep in its status file for an installed package."""
    out = []
    for key, lines in fields:
        if key == "Depends":
            value = lines[0].partition(":")[2].strip() + "".join(" " + l.strip() for l in lines[1:])
            lines = ["Depends: " + relax_depends(value, libc6_floor, libcrypt1_floor)]
        out.extend(lines)
        if key == "Package":
            out.append("Status: install ok installed")
    return "\n".join(out) + "\n"


def upstream_version(version):
    """"1:4.4.36-4build1" -> "1:4.4.36" (epoch kept, Debian revision dropped)."""
    return version.rsplit("-", 1)[0] if "-" in version else version


def info_prefix(name, arch, multiarch):
    # dpkg qualifies the info files of Multi-Arch: same packages with the architecture.
    return "%s:%s" % (name, arch) if multiarch == "same" else name


def list_entry(tar_name):
    name = tar_name[1:] if tar_name.startswith("./") else "/" + tar_name
    name = name.rstrip("/")
    return "/." if name in ("", ".", "/") else name


# --- building ---------------------------------------------------------------------------------


class Entry:
    def __init__(self, info, data=None):
        self.info = info
        self.data = data


def _tarinfo(name, mode, mtime, kind=tarfile.REGTYPE, linkname="", size=0):
    ti = tarfile.TarInfo(name)
    ti.type = kind
    ti.mode = mode
    ti.mtime = mtime
    ti.uid = ti.gid = 0
    ti.uname = ti.gname = "root"
    ti.linkname = linkname
    ti.size = size
    return ti


def package_entries(deb, arch):
    """Everything one .deb contributes: files, dpkg info files and the status stanza."""
    control = read_control_members(deb)
    unknown = sorted(set(control) - set(KNOWN_CONTROL_MEMBERS))
    if unknown:
        raise OverlayError("%s: control members %s are not handled by the overlay installer" % (deb, unknown))
    fields = parse_control(control["control"].decode("utf-8"))
    name = field_value(fields, "Package")
    version = field_value(fields, "Version")
    if field_value(fields, "Architecture") != arch:
        raise OverlayError("%s is for %s, not %s" % (deb, field_value(fields, "Architecture"), arch))
    if name not in PACKAGES:
        raise OverlayError("%s: %s is not one of %s" % (deb, name, ", ".join(PACKAGES)))

    for script in MAINTAINER_SCRIPTS:
        if script in control:
            for line in control[script].decode("utf-8").split("\n"):
                if not BENIGN_SCRIPT_LINE.match(line.strip()):
                    raise OverlayError("%s: unreviewed line in %s: %r" % (name, script, line))

    prefix = info_prefix(name, arch, field_value(fields, "Multi-Arch"))
    files, listing, dirs, paths = [], [], set(), set()
    with _deb_tar(deb, "--fsys-tarfile") as tf:
        for m in tf.getmembers():
            plain = m.name[2:] if m.name.startswith("./") else m.name
            plain = plain.rstrip("/")
            listing.append(list_entry(m.name))
            if plain in ("", "."):
                continue
            paths.add(plain)
            if m.isdir():
                dirs.add(plain)
                continue
            if m.islnk():
                raise OverlayError("%s: hard link %s is not supported" % (name, m.name))
            if m.issym():
                if m.linkname.startswith("/"):
                    raise OverlayError("%s: absolute symlink %s -> %s" % (name, m.name, m.linkname))
                files.append(Entry(_tarinfo(plain, m.mode & 0o7777, m.mtime, tarfile.SYMTYPE, m.linkname)))
            elif m.isfile():
                data = tf.extractfile(m).read()
                files.append(Entry(_tarinfo(plain, m.mode & 0o7777, m.mtime, size=len(data)), data))
            else:
                raise OverlayError("%s: special file %s is not supported" % (name, m.name))

    # Directories that hold something are created on demand while unpacking; only empty ones need an entry,
    # and listing the others would rewrite the permissions of directories the root file system already has.
    holding = set()
    for path in paths:
        parent = os.path.dirname(path)
        while parent:
            holding.add(parent)
            parent = os.path.dirname(parent)
    for d in sorted(dirs - holding):
        files.append(Entry(_tarinfo(d, 0o755, 0, tarfile.DIRTYPE)))

    info = [("list", ("\n".join(listing) + "\n").encode("utf-8"), 0o644)]
    for member in KEPT_CONTROL_MEMBERS:
        if member in control:
            info.append((member, control[member], 0o755 if member in MAINTAINER_SCRIPTS else 0o644))
    return {
        "name": name,
        "version": version,
        "fields": fields,
        "prefix": prefix,
        "files": files,
        "info": info,
    }


def build_overlay(deb_paths, arch, out_path, log=print):
    if arch not in TRIPLETS:
        raise OverlayError("unsupported architecture %r" % arch)
    packages = {}
    for deb in deb_paths:
        pkg = package_entries(deb, arch)
        if pkg["name"] in packages:
            raise OverlayError("two .deb files for %s" % pkg["name"])
        packages[pkg["name"]] = pkg
    missing = [p for p in PACKAGES if p not in packages]
    if missing:
        raise OverlayError("missing packages: %s" % ", ".join(missing))

    libc = packages["libc6-dev"]["version"]
    if packages["libc-dev-bin"]["version"] != libc:
        raise OverlayError("libc6-dev %s and libc-dev-bin %s differ" % (libc, packages["libc-dev-bin"]["version"]))
    if not libc.startswith(GLIBC_SERIES + "-"):
        raise OverlayError("libc6-dev %s is not from glibc %s; the app's libc6 is" % (libc, GLIBC_SERIES))

    libcrypt_floor = upstream_version(packages["libcrypt-dev"]["version"])
    stanzas = [status_stanza(packages[p]["fields"], GLIBC_SERIES, libcrypt_floor) for p in PACKAGES]
    for stanza in stanzas:
        for dep in re.findall(r"\b(?:libc6|libcrypt1) \(= [^)]*\)", stanza):
            raise OverlayError("an exact dependency survived the relaxation: %s" % dep)

    seen = {}
    entries = []
    for p in PACKAGES:
        pkg = packages[p]
        for e in pkg["files"]:
            if e.info.name in seen:
                raise OverlayError("%s and %s both ship %s" % (seen[e.info.name], p, e.info.name))
            seen[e.info.name] = p
            entries.append(e)
        for member, data, mode in pkg["info"]:
            entries.append(Entry(_tarinfo("%s%s.%s" % (INFO_DIR, pkg["prefix"], member), mode, 0, size=len(data)), data))
    status = "\n".join(stanzas).encode("utf-8")
    entries.append(Entry(_tarinfo(STATUS_PATH, 0o644, 0, size=len(status)), status))

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    tmp = out_path + ".tmp"
    with open(tmp, "wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as gz:
            with tarfile.open(fileobj=gz, mode="w", format=tarfile.GNU_FORMAT) as tf:
                for e in entries:
                    tf.addfile(e.info, io.BytesIO(e.data) if e.data is not None else None)
    try:
        verify_overlay(tmp, arch)
    except BaseException:
        os.unlink(tmp)
        raise
    os.replace(tmp, out_path)
    log("overlay for %s: %s (%d files, %d bytes)" % (
        arch, ", ".join("%s %s" % (p, packages[p]["version"]) for p in PACKAGES),
        sum(1 for e in entries if e.info.isfile() or e.info.issym()), os.path.getsize(out_path)))


# --- checking an overlay ------------------------------------------------------------------------


def verify_overlay(path, arch):
    """Raise OverlayError unless the overlay is complete and consistent with its own dpkg records."""
    with tarfile.open(path, "r:gz") as tf:
        members = {m.name: m for m in tf.getmembers()}

        def data(name):
            return tf.extractfile(members[name]).read()

        for needed in required_files(arch):
            if needed not in members:
                raise OverlayError("required file %s is missing" % needed)
        if STATUS_PATH not in members:
            raise OverlayError("%s is missing" % STATUS_PATH)

        stanzas = [s for s in data(STATUS_PATH).decode("utf-8").split("\n\n") if s.strip()]
        names = []
        listed = set()
        for stanza in stanzas:
            fields = parse_control(stanza)
            name = field_value(fields, "Package")
            names.append(name)
            if field_value(fields, "Status") != "install ok installed":
                raise OverlayError("%s: Status is not 'install ok installed'" % name)
            if field_value(fields, "Architecture") != arch:
                raise OverlayError("%s: architecture is not %s" % (name, arch))
            depends = field_value(fields, "Depends") or ""
            if re.search(r"\b(libc6|libcrypt1) \(= ", depends):
                raise OverlayError("%s: Depends still pins an exact libc6/libcrypt1: %s" % (name, depends))
            prefix = info_prefix(name, arch, field_value(fields, "Multi-Arch"))
            for needed in ("list", "md5sums"):
                if INFO_DIR + prefix + "." + needed not in members:
                    raise OverlayError("%s: %s%s.%s is missing" % (name, INFO_DIR, prefix, needed))
            for line in data(INFO_DIR + prefix + ".list").decode("utf-8").split("\n"):
                if line:
                    listed.add(line)
            for line in data(INFO_DIR + prefix + ".md5sums").decode("utf-8").split("\n"):
                if not line:
                    continue
                digest, _, rel = line.partition("  ")
                if rel not in members:
                    raise OverlayError("%s: %s is in md5sums but not in the overlay" % (name, rel))
                if hashlib.md5(data(rel)).hexdigest() != digest:
                    raise OverlayError("%s: checksum of %s differs from md5sums" % (name, rel))
        if sorted(names) != sorted(PACKAGES):
            raise OverlayError("status holds %s, expected %s" % (sorted(names), sorted(PACKAGES)))
        for name in members:
            if name.startswith(("var/lib/dpkg/", "var/lib/cortex/")):
                continue
            if "/" + name not in listed:
                raise OverlayError("%s is in the overlay but in no package list" % name)


# --- downloading --------------------------------------------------------------------------------


def download_debs(arch, dest, keyring, mirror=DEFAULT_MIRROR, suites=DEFAULT_SUITES):
    """apt-get download of the five packages from the Ubuntu archive, with the archive keyring checking
    the signatures, in an apt configuration of its own that ignores the machine's sources."""
    keyring = os.path.abspath(keyring)
    if not os.path.isfile(keyring):
        raise OverlayError("keyring %s not found" % keyring)
    work = tempfile.mkdtemp(prefix="devpkgs-apt-")
    try:
        os.makedirs(os.path.join(work, "sources.list.d"))
        os.makedirs(os.path.join(work, "state", "lists", "partial"))
        os.makedirs(os.path.join(work, "cache", "archives", "partial"))
        open(os.path.join(work, "status"), "w").close()
        with open(os.path.join(work, "sources.list.d", "ubuntu.sources"), "w") as f:
            f.write("Types: deb\nURIs: %s\nSuites: %s\nComponents: main\nArchitectures: %s\nSigned-By: %s\n"
                    % (mirror, " ".join(suites), arch, keyring))
        opts = [
            "-o", "Dir::Etc::sourcelist=/dev/null",
            "-o", "Dir::Etc::sourceparts=" + os.path.join(work, "sources.list.d"),
            "-o", "Dir::Etc::preferences=/dev/null",
            "-o", "Dir::Etc::preferencesparts=/dev/null",
            "-o", "Dir::State=" + os.path.join(work, "state"),
            "-o", "Dir::State::status=" + os.path.join(work, "status"),
            "-o", "Dir::Cache=" + os.path.join(work, "cache"),
            "-o", "APT::Architecture=" + arch,
            "-o", "APT::Architectures=" + arch,
            "-o", "APT::Sandbox::User=",
            "-o", "Debug::NoLocking=1",
            "-o", "Acquire::Languages=none",
            "-o", "Acquire::Retries=3",
        ]
        subprocess.run(["apt-get"] + opts + ["update"], check=True)
        os.makedirs(dest, exist_ok=True)
        subprocess.run(["apt-get"] + opts + ["download"] + list(PACKAGES), check=True, cwd=dest)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return sorted(os.path.join(dest, f) for f in os.listdir(dest) if f.endswith(".deb"))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--arch", required=True, choices=sorted(TRIPLETS))
    ap.add_argument("--out", help="overlay to write (tar.gz)")
    ap.add_argument("--debs-dir", help="use the .deb files in this directory instead of downloading them")
    ap.add_argument("--keyring", help="Ubuntu archive keyring that signs the downloaded indexes")
    ap.add_argument("--mirror", default=DEFAULT_MIRROR)
    ap.add_argument("--verify", metavar="OVERLAY", help="only check an existing overlay")
    args = ap.parse_args(argv)
    downloaded = None
    try:
        if args.verify:
            verify_overlay(args.verify, args.arch)
            print("%s is consistent" % args.verify)
            return 0
        if not args.out:
            ap.error("--out is required")
        if args.debs_dir:
            debs = sorted(os.path.join(args.debs_dir, f) for f in os.listdir(args.debs_dir) if f.endswith(".deb"))
        else:
            if not args.keyring:
                ap.error("--keyring is required when downloading")
            downloaded = tempfile.mkdtemp(prefix="devpkgs-debs-")
            debs = download_debs(args.arch, downloaded, args.keyring, args.mirror)
        build_overlay(debs, args.arch, args.out)
        return 0
    except (OverlayError, subprocess.CalledProcessError) as e:
        print("ERROR: %s" % e, file=sys.stderr)
        return 1
    finally:
        if downloaded:
            shutil.rmtree(downloaded, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
