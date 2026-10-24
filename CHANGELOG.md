# Changelog

What changed in each version of Cortex Terminal, newest first. The release workflow takes the notes
of a GitHub release from the section of its version here. Older versions are described on the
[GitHub releases](https://github.com/ssfuisu/cortex/releases) page.

## 1.26.38 (2026-10-24)

### New

- **Sessions come back.** When Android ends Cortex (to free memory, or for an update of the app),
  its sessions are there again at the next start: as many as there were, the one that was shown,
  each in its folder and with the end of its output, dimmed. The programs that ran in them have
  stopped and start anew. Leaving Cortex with Exit closes them for good; Settings > Sessions turns
  it off.
- **Your own keys above the keyboard.** `~/.cortex/keys` sets the rows (up to 4) and keys (up to 12
  a row): names such as `ESC`, `CTRL`, `UP`, `F5` or `PASTE`, symbols such as `|` and `~`, and text
  such as `gs="git status\n"`. Cortex uses the file as soon as it is saved and says what is wrong
  with one it cannot use; `~/.cortex/keys.example` explains it and has the keys as they are now.
  The keys F1 to F12 are new, and CTRL then an arrow key moves by words (`CSI 1;5D`, as xterm sends
  it).
- **Text size with two fingers**: spreading or pinching them on the terminal changes it; Cortex
  keeps the size.
- **An SSH server in one step.** Settings > Remote access > Start the SSH server (or `cortex-sshd`)
  installs Ubuntu's OpenSSH the first time, starts it on port 8022 and shows how to connect from a
  computer. `cortex-sshd key 'ssh-ed25519 ...'` lets a computer's key in, `cortex-sshd enable`
  starts the server with Cortex every time, `cortex-sshd stop` ends it.
- **Local web servers in the notifications.** When a program in Cortex serves web pages
  (`npm run dev`, `python3 -m http.server`, Jupyter...), a notification opens them in the browser
  or copies their address, and goes away when the server stops. Settings > Notifications &
  Clipboard turns it off.
- **Backups with the installed packages.** `cortex-backup` also keeps the list of the packages
  installed with apt and the apt sources of other vendors (such as NodeSource); `cortex-backup
  restore` installs the missing ones again after asking (`--no-packages` leaves them out,
  `cortex-backup packages` does it later).
- `cortex-api network` lists the phone's addresses, which `ip` and `ifconfig` cannot get from
  Android.

### Improved

- **A smaller app that unpacks faster**: Ubuntu's files are compressed with Zstandard, which the
  app decompresses itself, on a thread of its own, while it unpacks them. The APK is 31 MB instead
  of 40.5 MB (arm64-v8a) and 29 MB instead of 37.9 MB (armeabi-v7a).

### Fixed

- **git works in your repositories**: it refused them with "detected dubious ownership".
  Programs in Cortex run as root, but the files they made showed the app's own user as their owner;
  now they show root, as `ls -l` already did by name. This also lets `tmux` start (its socket
  directory had "unsafe permissions"), `ssh` read `~/.ssh/config` ("Bad owner") and the SSH server
  start.
- Servers that take connections with `accept()` (OpenSSH's among them) get them: the call is made
  as `accept4()`, the one Android's seccomp filter is made for. On the emulator it answered with a
  descriptor that did not exist.
- sshd's privilege-separated user has root's ids, as apt's sandbox user is root: Cortex cannot drop
  privileges, and sshd ended each connection when it found it could take them back.
- Programs built with `_FORTIFY_SOURCE` (all of Ubuntu's) get the hook's answer from `getgroups()`
  and `recvfrom()` too: their checked variants went past it, and sshd ended each login on
  "getgroups: Invalid argument".
- On x86 devices and emulators, which run Cortex through Android's ARM translator, programs that
  close all their descriptors (sshd does at start) no longer close the translator's own memory file
  with them, and limits that leave a process no new descriptors or file writes (sandboxes such as
  sshd's set them before login) are not applied: the translator aborted in sshd's processes.

### For developers

- The GitHub Actions run on Node 24 and are pinned to commits; Dependabot keeps them current.
- The emulator test also runs on Google's 16 KB page size image of Android 15 (without the checks
  that install packages: its ARM translator spins forever in apt's first update), checks the
  restored session, the SSH login with a key, the web server notification and that the files
  Cortex makes are root's. Its adb commands have time limits, so that a stuck emulator ends the
  test at its timeout.
- A nightly run installs about 30 popular packages (Python, Node.js, gcc, Rust, Go, Java, git,
  PostgreSQL, Redis, nginx...) in the latest release on the emulator and shows which work in its
  job summary.

## 1.26.37 (2026-10-24)

### New

- **The fastest Ubuntu mirror.** After the first start Cortex measures Ubuntu's mirrors once and
  lets apt use the one that answers fastest from where the phone is, if it has the current
  packages and is clearly faster than Ubuntu's own server (which is slow from some countries).
  Security updates still come from Ubuntu's server, and apt checks every package against Ubuntu's
  signature as before. `cortex-api mirror show` tells which mirror apt uses, `cortex-api mirror
  auto` measures them again and `cortex-api mirror official` goes back to Ubuntu's server. Sources
  changed by hand are left alone.
- **Send a bug report** (Settings > Community & Support): a text file with the versions of the app,
  Android and Ubuntu, the state of root mode and the app's own log, shared with an app you choose.
  Nothing is sent otherwise.
- The first start shows how far unpacking Ubuntu has got.

### Improved

- **The app is half the size.** It no longer contains Ubuntu's package lists, which were weeks old
  by the time the app was installed: the first `apt install` (or `apt upgrade`, `apt search`...)
  runs `apt update` by itself.
- **Hard links** made by one program are now seen by all others, also after Cortex restarts:
  `ln a b` and then `ls -li` or `stat` show the same inode and a link count of 2, as tools such as
  `tar`, `rsync -H`, `du` and `find -samefile` expect. (Android does not let apps make real hard
  links, so Cortex copies the file and shows the copies as one; a copy that is written to becomes a
  file of its own.)
- New terminal sessions start faster, without copying the app's memory.
- The terminal takes in plain text (build logs, `cat` of a big file) about twice as fast, and
  finding links in the output costs less.
- Looking at symlinks (`ls -l`, `find`, `stat`) is faster in normal mode.
- The first start and updates of the app look for Ubuntu's dynamic linker only in the library
  directories instead of the whole system.

### Fixed

- On Android 14 the settings reached under the navigation bar, which covered the last ones.
- `~/.local/bin/reload` was only installed from the second start on.
- `cortex-api` could stop with a crash of Android's ARM translator on x86 devices (emulators,
  Chromebooks): it opened its connection on file descriptor 3, which the translator uses itself.

### For developers

- `BootstrapManagerFlowTest` runs the first start, later starts, the update after a new release and
  the extraction on the JVM with Robolectric, on the test machine's file system.
- `UbuntuOnDeviceTest` (`app/src/androidTest`) installs Ubuntu on a real phone and runs commands in
  it; CI runs it on Firebase Test Lab once the repository is set up for it (`tests/device/README.md`).
- The emulator test also installs the 32-bit APK, on the Android 11 emulator (the only one that
  runs 32-bit ARM code; its translator stops Ubuntu's armhf programs, so that test ends with the
  installed and set-up system), and compares the start-up times with earlier runs in the job
  summary.

## 1.26.36 (2026-10-23)

### Improved

- **Much faster first start.** Unpacking Ubuntu takes 2.5 to 5 seconds instead of about 10 on the
  test emulator. The fast built-in extractor gave up on every first start and the much slower
  system `tar` took over; the archive now also goes straight from the app into the extractor
  instead of being copied first, so the first start needs a few hundred MB less free space.
- **Faster start of the app.** The checks of the Ubuntu system (DNS, time zone, certificates, apt,
  Cortex's tools) no longer run on the main thread at every start, nor several times over; they run
  once in the background, and files are only written when something changed. After an update of
  the app, the installed system is brought up to date in the background too, before the first
  session opens.
- New sessions start a little faster, and fast output (a long build log, `cat` of a big file)
  keeps the screen busy with fewer redraw requests.

### Fixed

- The first start changed the permissions of the files in the phone's shared storage (through
  `~/storage`) while it set up Ubuntu, and took the execute permission from programs outside
  `bin` and `sbin`.
- Turning the phone, or changing the theme or language, deleted the sockets, locks and pid files
  of programs running in `/tmp`.
- Files Cortex manages (`/etc/passwd`, `/etc/resolv.conf`, `/etc/localtime`, the profile scripts,
  `su`, `sudo`...) were rewritten at every start; a program reading one at that moment could find
  it empty. They are now only replaced when they change, and in one step.
- Cortex no longer touches apt's and dpkg's files (the package database, downloads, package
  lists) while `apt` or `dpkg` runs.
- After the terminal was shown again, links and search hits were no longer kept up to date
  during new output.

### For developers

- `FileInstaller` (`app/src/main/java/org/cortex/terminal/runtime/FileInstaller.kt`) writes the
  files the app manages; `FileLocks` takes apt's and dpkg's lock files the way they do.
- The emulator test also restarts the app, starts it after a simulated update, fails on start-up
  errors in the app's log and records start-up times (`timings.txt`).

## 1.26.35 (2026-10-23)

### New

- **`cortex-api`: Android features from the terminal.** Programs can show notifications, read and
  set the clipboard, open links and files in Android apps, share text and files, read the battery
  state, show a toast, speak text and vibrate, and keep the CPU awake (`cortex-api --help` lists
  the commands). It works in normal and in root mode. `notify-send` now shows its notifications on
  Android, and `xdg-open` (with `open`, `termux-open` and the browser names) opens links and files
  in Android apps instead of only printing them. Only Cortex itself and root can use it.

### Good to know

- Android lets an app open other apps only while it is on screen, and from Android 10 on also read
  the clipboard only then: `cortex-api open` and `share` (and `clipboard-get` from Android 10 on)
  report an error while Cortex is in the background (`xdg-open` then prints the link as before).

### Fixed

- The README and the website no longer say that Cortex never uses a chroot (root mode does).

### For developers

- The cortex-api client is a bash script (`app/src/main/assets/scripts/cortex-api.sh`) that talks
  to `org.cortex.terminal.api.ApiServer` over 127.0.0.1 with a token from `/run/cortex-api`; the
  shell tests run it against a stand-in server (`tests/scripts/cortex_api_mock.py`).
- **End-to-end test on an Android emulator** (`tests/e2e/`, `.github/workflows/emulator-test.yml`).
  For pull requests and dry runs, the arm64 APK is installed on an Android 14 x86_64 emulator, whose
  ARM translator runs Ubuntu. The test waits for Ubuntu to be unpacked, types a command into the
  terminal like a user, and checks the system, files, `apt install`, Python and every cortex-api
  command, and that the notifications appear on Android; it keeps screenshots and logcat. A manual
  run with `emulator_test_of_run` tests the APKs of an earlier run without building. Android 11's
  translator crashes every forked glibc program, so the test uses Android 14; on the emulator,
  arm64 programs start through a small shim (`tests/e2e/emu-runner.c`) that keeps `LD_PRELOAD` and
  `LD_LIBRARY_PATH` away from the translator itself.

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
