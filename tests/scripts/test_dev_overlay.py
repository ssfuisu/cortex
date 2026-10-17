#!/usr/bin/env python3
"""Tests for scripts/build-dev-overlay.py, which packs libc6-dev and its dependencies for the app.

They build small .deb files with dpkg-deb, so nothing is downloaded. Run:
    python3 -m unittest discover -s tests/scripts -p 'test_dev_overlay.py' -v
"""
import gzip
import hashlib
import importlib.util
import io
import os
import shutil
import subprocess
import tarfile
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "..", "..", "scripts", "build-dev-overlay.py")
spec = importlib.util.spec_from_file_location("build_dev_overlay", SCRIPT)
overlay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(overlay)

LIBC = "2.39-0ubuntu8.9"
LIBCRYPT = "1:4.4.36-4build1"
BENIGN = '#!/bin/sh\nset -e\n# Automatically added by dh_installdeb\ndpkg-maintscript-helper symlink_to_dir /usr/share/doc/libcrypt-dev libcrypt1 1:4.4.27-1.1\\~ -- "$@"\n'


def fixture_files(arch):
    t = overlay.TRIPLETS[arch]
    return {
        "libc6-dev": {
            "usr/include/stdio.h": b"/* stdio */\n",
            "usr/include/limits.h": b"/* limits */\n",
            "usr/include/%s/bits/types.h" % t: b"/* types */\n",
            "usr/include/%s/gnu/stubs.h" % t: b"/* stubs */\n",
            "usr/lib/%s/libc.a" % t: b"!<arch>\n",
            "usr/lib/%s/libc.so" % t: b"GROUP ( libc.so.6 )\n",
            "usr/lib/%s/crt1.o" % t: b"\x7fELF crt1",
            "usr/lib/%s/crti.o" % t: b"\x7fELF crti",
        },
        "libc-dev-bin": {"usr/bin/gencat": b"\x7fELF gencat"},
        "libcrypt-dev": {"usr/include/crypt.h": b"/* crypt */\n"},
        "linux-libc-dev": {
            "usr/include/linux/limits.h": b"/* linux limits */\n",
            "usr/include/%s/asm/unistd.h" % t: b"/* unistd */\n",
        },
        "rpcsvc-proto": {"usr/bin/rpcgen": b"\x7fELF rpcgen", "usr/include/rpcsvc/nfs_prot.h": b"/* nfs */\n"},
    }


def fixture_meta(arch):
    return {
        "libc6-dev": dict(version=LIBC, multiarch="same",
                          depends="libc6 (= %s), libc-dev-bin (= %s), linux-libc-dev, libcrypt-dev, rpcsvc-proto" % (LIBC, LIBC)),
        "libc-dev-bin": dict(version=LIBC, multiarch="foreign", depends="libc6 (>> 2.39), libc6 (<< 2.40)"),
        "libcrypt-dev": dict(version=LIBCRYPT, multiarch="same", depends="libcrypt1 (= %s)" % LIBCRYPT,
                             scripts={s: BENIGN for s in overlay.MAINTAINER_SCRIPTS}),
        "linux-libc-dev": dict(version="6.8.0-147.147", multiarch="same", depends=None),
        "rpcsvc-proto": dict(version="1.4.2-0ubuntu7", multiarch="foreign", depends="libc6 (>= 2.38)"),
    }


def make_deb(workdir, name, arch, files, meta, executables=("usr/bin/gencat", "usr/bin/rpcgen"), symlinks=None,
             empty_dirs=(), extra_control=None):
    tree = tempfile.mkdtemp(dir=workdir)
    for rel, data in files.items():
        path = os.path.join(tree, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
        os.chmod(path, 0o755 if rel in executables else 0o644)
    for rel, target in (symlinks or {}).items():
        path = os.path.join(tree, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        os.symlink(target, path)
    for rel in empty_dirs:
        os.makedirs(os.path.join(tree, rel), exist_ok=True)
    debian = os.path.join(tree, "DEBIAN")
    os.makedirs(debian)
    for dirpath, dirnames, _ in os.walk(tree):  # dpkg-deb insists on 0755 for the control directory
        os.chmod(dirpath, 0o755)
    control = ["Package: " + name, "Version: " + meta["version"], "Architecture: " + arch,
               "Maintainer: Test <test@example.com>", "Installed-Size: 1"]
    if meta.get("multiarch"):
        control.append("Multi-Arch: " + meta["multiarch"])
    if meta.get("depends"):
        control.append("Depends: " + meta["depends"])
    control.extend(extra_control or [])
    control.append("Description: test package\n long description line")
    with open(os.path.join(debian, "control"), "w") as f:
        f.write("\n".join(control) + "\n")
    sums = ["%s  %s" % (hashlib.md5(d).hexdigest(), rel) for rel, d in sorted(files.items())]
    with open(os.path.join(debian, "md5sums"), "w") as f:
        f.write("\n".join(sums) + "\n")
    for script, body in (meta.get("scripts") or {}).items():
        with open(os.path.join(debian, script), "w") as f:
            f.write(body)
        os.chmod(os.path.join(debian, script), 0o755)
    for member, body in (meta.get("control_members") or {}).items():
        with open(os.path.join(debian, member), "w") as f:
            f.write(body)
    out = os.path.join(workdir, "%s_%s_%s.deb" % (name, meta["version"].replace(":", "%3a"), arch))
    subprocess.run(["dpkg-deb", "--root-owner-group", "-Zgzip", "--build", tree, out],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return out


@unittest.skipUnless(shutil.which("dpkg-deb"), "dpkg-deb is needed to build the fixtures")
class OverlayTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="overlay-test-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def debs(self, arch="arm64", meta_override=None, files_override=None, **kwargs):
        meta = fixture_meta(arch)
        files = fixture_files(arch)
        for name, change in (meta_override or {}).items():
            meta[name].update(change)
        for name, change in (files_override or {}).items():
            files[name].update(change)
        return [make_deb(self.tmp, name, arch, files[name], meta[name], **kwargs.get(name, {})) for name in overlay.PACKAGES]

    def build(self, arch="arm64", **kwargs):
        out = os.path.join(self.tmp, "overlay-%s.tar.gz" % arch)
        overlay.build_overlay(self.debs(arch, **kwargs), arch, out, log=lambda _: None)
        return out

    def members(self, path):
        with tarfile.open(path, "r:gz") as tf:
            return {m.name: (m, tf.extractfile(m).read() if m.isfile() else None) for m in tf.getmembers()}

    # --- what the overlay contains ------------------------------------------------------------

    def test_builds_a_consistent_overlay_for_both_architectures(self):
        for arch in ("arm64", "armhf"):
            path = self.build(arch)
            overlay.verify_overlay(path, arch)
            names = self.members(path)
            t = overlay.TRIPLETS[arch]
            self.assertIn("usr/lib/%s/libc.a" % t, names)
            self.assertIn("var/lib/dpkg/info/libc6-dev:%s.list" % arch, names)

    def test_info_files_are_named_the_way_dpkg_names_them(self):
        names = set(self.members(self.build()))
        # Multi-Arch: same packages carry the architecture, the others do not
        for expected in ("libc6-dev:arm64", "libcrypt-dev:arm64", "linux-libc-dev:arm64", "libc-dev-bin", "rpcsvc-proto"):
            self.assertIn("var/lib/dpkg/info/%s.list" % expected, names)
            self.assertIn("var/lib/dpkg/info/%s.md5sums" % expected, names)
        self.assertIn("var/lib/dpkg/info/libcrypt-dev:arm64.postinst", names)
        self.assertNotIn("var/lib/dpkg/info/libc-dev-bin.postinst", names)

    def test_list_files_start_with_the_root_and_list_directories_without_slash(self):
        members = self.members(self.build())
        lines = members["var/lib/dpkg/info/libc-dev-bin.list"][1].decode().split("\n")
        self.assertEqual("/.", lines[0])
        self.assertIn("/usr/bin", lines)
        self.assertIn("/usr/bin/gencat", lines)
        self.assertEqual("", lines[-1], "ends with a newline")

    def test_status_stanzas_say_installed_and_relax_the_exact_dependencies(self):
        members = self.members(self.build())
        text = members[overlay.STATUS_PATH][1].decode()
        stanzas = [s for s in text.split("\n\n") if s.strip()]
        self.assertEqual(5, len(stanzas))
        for s in stanzas:
            self.assertEqual("Status: install ok installed", s.split("\n")[1], "dpkg writes the status second")
        libc_dev = [s for s in stanzas if s.startswith("Package: libc6-dev\n")][0]
        self.assertIn("Depends: libc6 (>= 2.39), libc-dev-bin (= %s), linux-libc-dev, libcrypt-dev, rpcsvc-proto" % LIBC, libc_dev)
        libcrypt = [s for s in stanzas if s.startswith("Package: libcrypt-dev\n")][0]
        self.assertIn("Depends: libcrypt1 (>= 1:4.4.36)\n", libcrypt)
        self.assertIn("Description: test package\n long description line", libc_dev, "continuation lines are kept")
        self.assertTrue(text.endswith("\n"))

    def test_files_keep_their_modes_and_only_empty_directories_get_entries(self):
        files = fixture_files("arm64")
        meta = fixture_meta("arm64")
        built = []
        for name in overlay.PACKAGES:
            extra = {"empty_dirs": ("usr/share/empty-dir",)} if name == "rpcsvc-proto" else {}
            built.append(make_deb(self.tmp, name, "arm64", files[name], meta[name], **extra))
        out = os.path.join(self.tmp, "o.tar.gz")
        overlay.build_overlay(built, "arm64", out, log=lambda _: None)
        members = self.members(out)
        self.assertTrue(members["usr/bin/gencat"][0].mode & 0o111, "gencat stays executable")
        self.assertFalse(members["usr/include/stdio.h"][0].mode & 0o111)
        dirs = [n for n, (m, _) in members.items() if m.isdir()]
        self.assertEqual(["usr/share/empty-dir"], dirs)

    def test_relative_symlinks_are_kept(self):
        files = fixture_files("arm64")
        meta = fixture_meta("arm64")
        built = []
        for name in overlay.PACKAGES:
            extra = {"symlinks": {"usr/lib/aarch64-linux-gnu/libm.so.link": "libc.so"}} if name == "libc6-dev" else {}
            built.append(make_deb(self.tmp, name, "arm64", files[name], meta[name], **extra))
        out = os.path.join(self.tmp, "o.tar.gz")
        overlay.build_overlay(built, "arm64", out, log=lambda _: None)
        member = self.members(out)["usr/lib/aarch64-linux-gnu/libm.so.link"][0]
        self.assertTrue(member.issym())
        self.assertEqual("libc.so", member.linkname)

    def test_the_output_does_not_depend_on_when_it_was_built(self):
        debs = self.debs()
        outputs = []
        for n in range(2):
            out = os.path.join(self.tmp, "build-%d.tar.gz" % n)
            overlay.build_overlay(debs, "arm64", out, log=lambda _: None)
            with open(out, "rb") as f:
                outputs.append(f.read())
        self.assertEqual(outputs[0], outputs[1])

    def test_every_file_is_owned_by_root(self):
        for name, (m, _) in self.members(self.build()).items():
            self.assertEqual((0, 0), (m.uid, m.gid), name)

    # --- what it refuses ------------------------------------------------------------------------

    def refused(self, **kwargs):
        with self.assertRaises(overlay.OverlayError) as ctx:
            self.build(**kwargs)
        return str(ctx.exception)

    def test_refuses_a_maintainer_script_nobody_reviewed(self):
        message = self.refused(meta_override={"libcrypt-dev": {"scripts": {"postinst": "#!/bin/sh\nset -e\nrm -rf /tmp/x\n"}}})
        self.assertIn("unreviewed", message)

    def test_refuses_control_members_the_installer_does_not_handle(self):
        message = self.refused(meta_override={"rpcsvc-proto": {"control_members": {"triggers": "activate-noawait ldconfig\n"}}})
        self.assertIn("triggers", message)

    def test_refuses_another_glibc_series(self):
        message = self.refused(meta_override={"libc6-dev": {"version": "2.40-1"}, "libc-dev-bin": {"version": "2.40-1"}})
        self.assertIn("2.39", message)

    def test_refuses_libc6_dev_and_libc_dev_bin_of_different_versions(self):
        message = self.refused(meta_override={"libc-dev-bin": {"version": "2.39-0ubuntu8.8"}})
        self.assertIn("differ", message)

    def test_refuses_packages_of_the_wrong_architecture(self):
        debs = self.debs("armhf")
        with self.assertRaises(overlay.OverlayError) as ctx:
            overlay.build_overlay(debs, "arm64", os.path.join(self.tmp, "x.tar.gz"), log=lambda _: None)
        self.assertIn("armhf", str(ctx.exception))

    def test_refuses_a_missing_package(self):
        debs = self.debs()[:-1]
        with self.assertRaises(overlay.OverlayError) as ctx:
            overlay.build_overlay(debs, "arm64", os.path.join(self.tmp, "x.tar.gz"), log=lambda _: None)
        self.assertIn("missing", str(ctx.exception))

    def test_refuses_an_absolute_symlink(self):
        files = fixture_files("arm64")
        meta = fixture_meta("arm64")
        built = []
        for name in overlay.PACKAGES:
            extra = {"symlinks": {"usr/lib/aarch64-linux-gnu/libx.so": "/lib/aarch64-linux-gnu/libx.so.1"}} if name == "libc6-dev" else {}
            built.append(make_deb(self.tmp, name, "arm64", files[name], meta[name], **extra))
        with self.assertRaises(overlay.OverlayError) as ctx:
            overlay.build_overlay(built, "arm64", os.path.join(self.tmp, "x.tar.gz"), log=lambda _: None)
        self.assertIn("absolute symlink", str(ctx.exception))

    def test_refuses_two_packages_that_ship_the_same_file(self):
        message = self.refused(files_override={"rpcsvc-proto": {"usr/include/stdio.h": b"clash\n"}})
        self.assertIn("both ship", message)

    def test_refuses_an_overlay_without_a_required_file(self):
        files = fixture_files("arm64")
        del files["libc6-dev"]["usr/lib/aarch64-linux-gnu/crt1.o"]
        meta = fixture_meta("arm64")
        built = [make_deb(self.tmp, n, "arm64", files[n], meta[n]) for n in overlay.PACKAGES]
        with self.assertRaises(overlay.OverlayError) as ctx:
            overlay.build_overlay(built, "arm64", os.path.join(self.tmp, "x.tar.gz"), log=lambda _: None)
        self.assertIn("crt1.o", str(ctx.exception))
        self.assertEqual([], [f for f in os.listdir(self.tmp) if f.endswith(".tmp") or f.startswith("x.tar")],
                         "the temporary overlay of a failed verification must not stay behind")

    def test_a_failed_build_leaves_no_output_behind(self):
        out = os.path.join(self.tmp, "never.tar.gz")
        debs = self.debs(meta_override={"libc-dev-bin": {"version": "2.39-0ubuntu8.8"}})
        with self.assertRaises(overlay.OverlayError):
            overlay.build_overlay(debs, "arm64", out, log=lambda _: None)
        self.assertFalse(os.path.exists(out))
        self.assertFalse(os.path.exists(out + ".tmp"))

    def test_verify_notices_a_file_that_differs_from_its_checksum(self):
        path = self.build()
        tampered = os.path.join(self.tmp, "tampered.tar.gz")
        with tarfile.open(path, "r:gz") as src, tarfile.open(tampered, "w:gz") as dst:
            for m in src.getmembers():
                data = src.extractfile(m).read() if m.isfile() else None
                if m.name == "usr/include/stdio.h":
                    data = b"/* changed */\n"
                    m.size = len(data)
                dst.addfile(m, io.BytesIO(data) if data is not None else None)
        with self.assertRaises(overlay.OverlayError) as ctx:
            overlay.verify_overlay(tampered, "arm64")
        self.assertIn("checksum", str(ctx.exception))

    def test_verify_notices_a_file_that_no_package_list_knows(self):
        path = self.build()
        extra = os.path.join(self.tmp, "extra.tar.gz")
        with tarfile.open(path, "r:gz") as src, tarfile.open(extra, "w:gz") as dst:
            for m in src.getmembers():
                dst.addfile(m, src.extractfile(m) if m.isfile() else None)
            info = tarfile.TarInfo("usr/include/stowaway.h")
            info.size = 3
            dst.addfile(info, io.BytesIO(b"hi\n"))
        with self.assertRaises(overlay.OverlayError) as ctx:
            overlay.verify_overlay(extra, "arm64")
        self.assertIn("no package list", str(ctx.exception))


class HelperTest(unittest.TestCase):
    def test_relax_depends_changes_only_the_exact_libc6_and_libcrypt1(self):
        text = "libc6 (= 2.39-0ubuntu8.9), libc-dev-bin (= 2.39-0ubuntu8.9), libcrypt1 (= 1:4.4.36-4build1), foo (>= 1)"
        self.assertEqual(
            "libc6 (>= 2.39), libc-dev-bin (= 2.39-0ubuntu8.9), libcrypt1 (>= 1:4.4.36), foo (>= 1)",
            overlay.relax_depends(text, "2.39", "1:4.4.36"))

    def test_relax_depends_leaves_ranges_alone(self):
        text = "libc6 (>> 2.39), libc6 (<< 2.40)"
        self.assertEqual(text, overlay.relax_depends(text, "2.39", "1:4.4.36"))

    def test_relax_depends_does_not_touch_similar_names(self):
        text = "libc6-dev (= 1), mylibc6 (= 2)"
        self.assertEqual(text, overlay.relax_depends(text, "2.39", "1:4.4.36"))

    def test_upstream_version_keeps_the_epoch_and_drops_the_revision(self):
        self.assertEqual("1:4.4.36", overlay.upstream_version("1:4.4.36-4build1"))
        self.assertEqual("2.39", overlay.upstream_version("2.39-0ubuntu8.9"))
        self.assertEqual("6.8.0", overlay.upstream_version("6.8.0"))

    def test_list_entries_follow_dpkg(self):
        self.assertEqual("/.", overlay.list_entry("./"))
        self.assertEqual("/.", overlay.list_entry("."))
        self.assertEqual("/usr", overlay.list_entry("./usr/"))
        self.assertEqual("/usr/include/stdio.h", overlay.list_entry("./usr/include/stdio.h"))
        self.assertEqual("/usr/include/stdio.h", overlay.list_entry("usr/include/stdio.h"))

    def test_info_prefix_qualifies_only_multi_arch_same(self):
        self.assertEqual("libc6-dev:arm64", overlay.info_prefix("libc6-dev", "arm64", "same"))
        self.assertEqual("libc-dev-bin", overlay.info_prefix("libc-dev-bin", "arm64", "foreign"))
        self.assertEqual("x", overlay.info_prefix("x", "arm64", None))

    def test_benign_script_lines(self):
        for line in ("#!/bin/sh", "set -e", "", "# comment", BENIGN.split("\n")[3]):
            self.assertTrue(overlay.BENIGN_SCRIPT_LINE.match(line), line)
        for line in ("rm -rf /", "echo hello", "dpkg-maintscript-helper other_command x -- \"$@\"", "set -x"):
            self.assertFalse(overlay.BENIGN_SCRIPT_LINE.match(line), line)

    def test_parse_control_keeps_continuation_lines(self):
        fields = overlay.parse_control("Package: x\nDescription: one\n two\n  three\nVersion: 1\n")
        self.assertEqual([["Package", ["Package: x"]],
                          ["Description", ["Description: one", " two", "  three"]],
                          ["Version", ["Version: 1"]]], fields)
        self.assertEqual("1", overlay.field_value(fields, "Version"))
        self.assertIsNone(overlay.field_value(fields, "Depends"))


if __name__ == "__main__":
    unittest.main()
