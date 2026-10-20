# Changelog

What changed in each version of Cortex Terminal, newest first. The release workflow takes the notes
of a GitHub release from the section of its version here. Older versions are described on the
[GitHub releases](https://github.com/ssfuisu/cortex/releases) page.

## 1.26.34 (2026-10-20)

### New

- **Root mode.** On a rooted phone (Magisk, KernelSU or APatch), *Settings > Root mode* runs new
  sessions as real root in a chroot of the same Ubuntu files that normal sessions use, with nothing
  translated or emulated: statically linked Go programs, `mount`, real `chown`, hard links,
  set-user-ID programs such as `sudo` for other users, services that switch users and ports below
  1024 work as on a Linux PC. The switch first checks
  that su gives root and that the chroot can be set up (with an animation), and stays on across
  restarts until it is turned off. Each root session gets its own `/proc`, `/sys`, `/dev` and
  `/sdcard` mounts, which nothing outside the session sees. In root mode `su` is Ubuntu's own su
  and `sudo` runs the command directly. Root sessions are marked ROOT in the session list.
  Turning root mode off closes them and gives the files created as root back to the app, so that
  normal sessions can change them again.

### Good to know

- While root mode is on, normal sessions that are still open cannot change files created as root.
- When root mode is turned off, every file belongs to the app again: owners set in root mode (for
  example a database directory owned by `postgres`) have to be set again in the next root session.
- systemd still cannot run as the init system, and Android commands such as `am` are not inside the
  chroot.

### For developers

- `app/src/main/cpp/root/cortex-chroot.c` is root mode's helper (a static executable run through
  su); `tests/root/test_cortex_chroot.sh` tests it with real mounts in CI.

## 1.26.33 (2026-10-19)

### New

- **Statically linked programs run.** Programs built with `gcc -static` (and other static glibc
  programs) used to be killed at start by Android's seccomp filter, because their own copy of the C
  library makes system calls Android forbids for apps (`set_robust_list` at start-up, `clone3` for
  every thread, `rseq`, the `set*id` family and calls newer than the filter). Before such a program
  starts for the first time, Cortex now patches those calls in its file to the answers it already
  gives dynamically linked programs (success, or "not supported", from which the C library falls
  back). The file keeps its times and mode, and is not touched again. Go programs are left alone:
  they never make these calls, but since they bypass the C library they still do not see Ubuntu's
  files such as `/etc/resolv.conf`.
- **Lines rewrap when the width changes.** Turning the phone or changing the font size used to leave
  long lines broken in the middle (wider screen) or cut off (narrower screen). The scrollback and the
  screen are now rewrapped for the new width, with their colours and links. The cursor keeps its
  place, the top of the screen stays where it was (a cleared screen is not refilled), and full-screen
  programs redraw themselves as before. Showing or hiding the keyboard works as it did.
- **IPv6-only networks.** On networks that give only IPv6 DNS servers (some mobile networks), those
  servers are now used for name resolution. Cortex's fallback resolver also asks for IPv6 addresses
  when a program wants one, uses IPv6 DNS servers, and DNS queries sent to `::1` are redirected like
  those sent to `127.0.0.1`.

### Improved

- **Faster drawing.** The terminal draws a row in runs of characters with the same colour and style
  instead of one character at a time (about 5000 drawing calls per frame on a full phone screen
  before). Other characters than plain ASCII are still drawn one by one, so they stay in their cells.
- **Hard links.** Android does not let apps create hard links, so Cortex copies the file instead and
  makes the copies look linked. All names of one file now report the same inode number and the real
  number of names as their link count (it was always 2), a relative name and an absolute name of a
  file are recognised as the same file, a copy keeps the times of the original, and a process can
  make any number of links (after 128, earlier ones were lost).
- **Name resolution.** When glibc finds no address, Cortex's own lookup now returns both IPv4 and
  IPv6 addresses to programs that accept either, stops at the first server that says a name does not
  exist (it used to send the name on to the other servers and the public resolvers), no longer turns
  a numeric-only lookup (`AI_NUMERICHOST`) into a DNS query, looks service names such as `ssh` up in
  `/etc/services`, and keeps glibc's error ("try again later" or "no such name") when it finds
  nothing either. It also reads compressed names in replies correctly (lookups behind a CNAME could
  fail) and only accepts replies from the server it asked.

### Fixed

- Security: where the kernel or Android's filter has no `openat2()`, opening a file with
  `RESOLVE_BENEATH` or `RESOLVE_NO_SYMLINKS` ignored those limits for symbolic links inside the path.
  Such calls now report "not supported", so programs such as GNU tar check the path themselves.
- `mknod()` of a regular file emptied an existing file instead of failing, and a mode without a file
  type created nothing.
- `getgrouplist()` reported success when the caller's buffer was too small.
- Binding a Unix socket with only the address family (autobind) could bind to a random path.
- Spaces in underlined text were drawn without their underline.
- `res_init()` replaced a resolver configuration that only lists IPv6 servers with IPv4 servers.
- `rename()` over an emulated hard link left the replaced file counted as a link.

### For developers

- `app/src/main/cpp/cortex-hook.c` is split into parts by topic in `app/src/main/cpp/hook/`
  (paths, files, exec, static programs, net, ...). It is still built as one file with the same
  commands; the compiled code was checked to be identical for arm64, armhf and x86-64.
- `BootstrapManager` logs every failure it used to swallow silently (60 places).
- The notes of a GitHub release now come from this file.

## 1.26.32 (2026-10-19)

### New

- **Bracketed paste.** Programs that ask for it (bash, zsh, fish, vim, neovim) are told that text
  was pasted, so a multi-line paste keeps its lines and nothing runs until you press Enter. Control
  characters in the pasted text are removed. Other programs get the paste joined into one line, as
  before.
- **Cursor shapes.** Programs can switch between a block, underline or bar cursor, steady or
  blinking (for example neovim's bar cursor in insert mode).
- **Focus events.** tmux, vim and neovim are told when Cortex goes to the background or another
  session is shown.
- **Synchronized output.** Programs that announce their frames (neovim 0.10 and newer) are only shown
  complete frames, so full-screen redraws no longer flicker.
- Programs can ask which of these modes the terminal supports (DECRQM).

### Fixed

- vim and other programs that send `CSI > 4;2 m` no longer turn the following text underlined.
- fish and neovim, which send `CSI ? u` at start-up, no longer move the cursor to an old position.
- `CSI ? r` no longer resets the scroll region, and sequences with an intermediate byte no longer run
  a different command.

## 1.26.31 (2026-10-17)

Includes the changes of 1.26.30, which was only published as a pre-release.

### New

- **`apt install build-essential` works**, and so does `make`. The C library headers (`libc6-dev`,
  `linux-libc-dev`, `libcrypt-dev`, `libc-dev-bin`, `rpcsvc-proto`) come with the app, so `-dev`
  packages such as `zlib1g-dev` or `python3-dev` install too.

### Improved

- **Faster programs.** The preloaded library adds less to file calls (`stat()` +18% to +3%, `fopen()`
  +12% to +2%, looking up a command that is not in `/usr/bin` +141% to +8%) and a program starts with
  7 instead of 19 system calls. Measurements are in the README.
- **Faster output.** Scrolling moves rows instead of copying them: about three times as much plain
  text per second through the terminal.

### Fixed

- GNU make was killed when it started commands.
- Starting scripts and Android programs through `posix_spawn`, scripts that start themselves in a
  loop, error lines in `ls -l`, and `xz -T<n>` ignoring the compression level.

### Security

- Releases are signed with a new key (the old one was public). Android 13 and newer move to the new
  key through key rotation; older versions keep updating with the original one. The fingerprints are
  in [signing/README.md](signing/README.md).
