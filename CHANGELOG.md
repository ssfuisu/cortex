# Changelog

What changed in each version of Cortex Terminal, newest first. The release workflow takes the notes
of a GitHub release from the section of its version here. Older versions are described on the
[GitHub releases](https://github.com/ssfuisu/cortex/releases) page.

## 1.26.44 (2026-10-27)

### Added

- **A white color scheme** ("Light", in *Settings > Color Scheme*): dark text on white, with the colors
  of GitHub's light terminal, which all can be read on white (the 16 colors are checked by a test).
  It is for the whole app, like the other schemes: the tabs, the keys, the drawer, the settings, the
  dialogs and the system bars (with dark icons) follow it. Selected text is shown on a light blue
  instead of white, which would not show on white.

### Changed

- **A cleaner drawer and tabs.** A session in the drawer is just *Session N* (and ROOT, for a root
  session), with the close button: the number in a box and the directory under it are gone. The
  header of the drawer is the name of the app and the two buttons: the `>_` mark and the line
  *Ubuntu 24.04 LTS* are gone (also from the first-start window). The tabs above the terminal show
  the number of the session, not its directory, so they no longer change as you `cd` (and the
  directories are no longer looked up every few seconds for them).
- The settings list and its choice dialogs (font, font size, language...) are in the colors of the
  scheme: the texts, the headings and the switches were of one fixed color, and could not be read on
  a light scheme. The back arrow of the settings is in the scheme's color too (it was white on the
  light bar).

## 1.26.43 (2026-10-27)

### Fixed

- **Chinese (and Japanese, Korean and emoji) overlapped in the terminal.** The terminal gave every
  character one cell, but these characters are two cells wide: their glyphs were drawn over the next
  character, and programs, which count them as two (bash, `ls`, `vim`, `tmux` use `wcwidth()`), put
  the cursor and the rest of their lines two cells on from where Cortex had them, so the text of a
  line, the cursor and the edits of the line editor (a deleted character, a redrawn prompt) were off
  by one cell for each character. Now:
  - A wide character takes two cells (the first holds it, drawn over both) and the cursor moves two;
    at the end of a line, when one cell is left, it goes to the next line as in `xterm`.
  - What Unicode's East Asian Width table (15.1) gives wide or fullwidth is wide: Chinese, Japanese
    and Korean text, the CJK punctuation, the fullwidth forms and the emoji (`wcwidth()` of the
    Ubuntu image says the same). Box-drawing and the other characters of one cell are as before,
    and the invisible ones (zero-width space and joiner, variation selectors) take no cell.
  - Writing, erasing, deleting or inserting over half of a wide character blanks the other half, so
    no half is left; the cursor over one covers both cells, and a long press selects the whole
    character.
  - Copying, the transcript, the search and the links count a wide character once and mark the right
    cells; the text is the same when the line is rewrapped.
  - Typing: the keyboard's text was sent per UTF-16 character, so an emoji typed arrived as two
    question marks; it is sent whole now. A keyboard that composes a word first (the pinyin of a
    Chinese word, Japanese kana) has the word on the line while it composes; the text it commits now
    replaces it instead of being added after it.
  The tables are made by `tests/scripts/gen_char_width.py`.
  Limits: the few characters of the supplementary planes that are one cell wide (the mathematical
  letters, say) are held in two, so a line with one has the cursor a cell ahead of the program; and
  when the terminal is rewrapped (turned, resized) exactly between the two cells of a wide character,
  the character is cut at the row's edge (the text is right, the glyph is clipped, or for an emoji
  not drawn there). Combining accents take a cell of their own, as before.

## 1.26.42 (2026-10-26)

### Changed

- **A new app icon** (#30): three cards, the front one with a prompt, in the design that
  [hasanelfalakiy](https://github.com/hasanelfalakiy) suggested. Thank you! It is an adaptive icon
  drawn as vectors (sharp at any size and in any shape the launcher cuts it to) and has a monochrome
  layer, so Android 13's themed icons show it in the colors of the wallpaper.

### Fixed

- **Holding backspace deleted one character** (#32). The terminal keeps no text of its own (what is
  typed goes to the program at once), and a keyboard that sees nothing before the cursor deletes
  once for a held backspace and stops (Gboard). The keyboard is now shown one space before its
  cursor, and the space again after each delete, so a held backspace keeps deleting, one character
  of the command line each time.
- **Copy and paste** (#32): the bar with *Copy*, *Paste* and *Select all* that comes up after a long
  press was placed as if the terminal began at the top of the window, so it was over the tabs or,
  on the first rows, under the status bar where it could not be tapped. It now goes above the
  selection, or under it when there is no room, always over the terminal; it is in the colors of
  the scheme and in the language of the phone. Editors that use a clipboard program find one:
  `termux-clipboard-set` and `termux-clipboard-get` (the names Neovim's and Helix's clipboard look
  for) use Android's clipboard through `cortex-api`, so `"+y` and `"+p` work. Copying with OSC 52
  (tmux, Neovim's `osc52`) worked before and still does.
- **The update download was a card on a gray card** (#33). Dialogs (the update, the download, exit,
  storage access, root mode, the color schemes) are now one rounded card in the colors of the
  interface, with the progress bar and buttons in its accent.

## 1.26.41 (2026-10-26)

### Changed

- **No more `~/storage` link to the shared storage.** Versions before made `~/storage -> /sdcard` in
  the home directory. It was not needed: `/sdcard` works as it is (Cortex has the "all files"
  access), and a command that deletes *through* the link (`rm -rf ~/storage/*`, `find -L ~ -delete`,
  a tool or an AI agent that follows links) empties the phone's shared storage. Deleting the link
  itself, or the home directory, never touched what it points to, and Cortex's own code (the Files
  app's delete, the backup, the reinstall) never followed it; the link was only a risk waiting for
  the wrong command. Cortex no longer makes it, and takes an old one that still points to
  `/sdcard` away once, when it starts after the update (the link only: it is a pointer, nothing in
  the storage is touched). `ln -s /sdcard ~/storage` makes it again; a link you make is left alone.
- The reinstall of the base system removes `home/ubuntu` as a link-safe delete: a link inside it is
  removed, never followed.

## 1.26.40 (2026-10-25)

### Fixed

- **redis, nginx and PostgreSQL install.** The Ubuntu image kept only `root`, `daemon` and `cortex` in
  `/etc/passwd` and `/etc/group`, but the scripts of packages use the system users and groups every
  Ubuntu has (`man`, `www-data`, `adm`, `tty`, `staff`...): `apt install redis-server`, `nginx` or
  `postgresql` (and any package whose man page the man-db trigger makes, with `setpriv --reuid=man`)
  stopped halfway. Cortex now adds what is missing from the list Ubuntu's own `base-passwd` keeps in
  the image (`/usr/share/base-passwd`), with locked entries in `/etc/shadow` and `/etc/gshadow`, and
  changes nothing that is there. redis then starts and answers (`PONG`) in the emulator test, nginx
  installs and serves a page. PostgreSQL installs too, but its server refuses to run as root and an
  app cannot become another user, so the cluster the package would make is not made outside root
  mode (`psql` works). MariaDB (`mariadb-server`) installs, starts and answers queries.
- **ripgrep, `rustc` and `cargo` crashed at the first file they opened.** Rust asks whether `statx`
  exists by calling it with a NULL path; glibc's header says the path is never NULL, so the compiler
  deleted the hook's check. Both programs now work (emulator test: ripgrep and Rust's hello world).
- **`chfn` and `adduser --gecos` (which PostgreSQL's installation runs) failed** ("Authentication
  failure"): Ubuntu's libraries found Android's SELinux and PAM's root check then refused root.
  SELinux is off for Ubuntu's programs, and the shadow database is read from the image's
  `/etc/shadow` (`getspnam`).
- **`go run` and `go build` could crash at random** (a segmentation fault in the Go tool): the
  scratch memory that Cortex's change in a Go program uses for the paths it rewrites was shared by
  goroutines that ran at the same time. Each slot now belongs to the goroutine that took it until
  it is done (the build runs a stress test: 96 goroutines read 200 files 300 times each). And the
  slot of a program that was started (by a vfork child, whose execve does not return) is given back
  when the goroutine goes on; before, a program that started programs from more than 64 goroutines
  at once could wait for a slot for good.
- **`sudo` and `su` on a phone without root said "root not found".** `sudo apt install x`, the first
  line of nearly every guide, now runs the command as it is (Cortex's shell is root inside Ubuntu as
  far as the programs can tell); `sudo -u USER`, `sudo -i`, `su USER` (in the shell that user has),
  `su -c CMD` and `su -` work through `runuser` and the shell. On a phone that gives Cortex real root they still ask Android for it.
- **After another color scheme was chosen, the old one came back as a block** (#29): close the
  keyboard and the rows the terminal grew into showed the previous scheme's background, in the size
  of the keyboard. The cells of a session keep the colors the scheme had when they were written, and
  a change of scheme only changed what was drawn next. Cortex now carries the new scheme into the
  screen, the scrollback and the other screen of every session when it is chosen (the default
  colors and the 16 ANSI ones; the 256-color and 24-bit colors of programs are the same in every
  scheme).
- A program that called `putenv()` or `setenv()` and then `execv()` before it used any file could
  crash in the exec (the hook set its variables only then, and the block the program had passed was
  freed by that). The hook now starts before the program does.

### Faster

- **Starting programs.** What the library adds to starting a program is about half of what it was.
  The look for the ARM translator's memory files (x86 emulators and Chromebooks; a phone has none)
  asked about each descriptor from 3 to 63, 61 system calls in every program; it now reads
  `/proc/self/fd` once, and only in the first program of a session: the ones it starts are told
  there is nothing to find (`CORTEX_NT=0`). `ld.so` is given every library directory once instead
  of twice, and the environment is prepared with a fifth of the comparisons. Measured against 1.26.39
  with the same benchmark on one x86 machine: the library's share of starting `ld.so` 457 µs ->
  189 µs, starting a program as a shell does 16% faster, a script 11%. Counted with qemu-user a
  program that does nothing makes 177 system calls instead of 236 (170 without the library), a
  shell starting a program five times 548 instead of 916, a login shell 1,111 instead of 1,357,
  `dpkg -l` 569 instead of 752, `ls -l` 713 instead of 837.
- **Less waking of the phone.** The watch for local web servers looked every 3 seconds even when
  nothing was listening, and on Android 10 and newer every look asked `/proc/net`, which they
  refuse (each refusal a line in the system log). There it now looks every 15 seconds when there is
  nothing to follow and asks `/proc/net` again only every 10 minutes; the hook's own reports still
  wake it at once, and a server that does not answer yet is looked at again within 3 seconds.
- No warning from the compiler or the Android SDK that the code can avoid: the unused function and
  variable, `stopForeground(true)` and `scaledDensity` are replaced.

### For developers

- `tests/scripts/test_hook_qemu.sh` runs the hook under qemu-user with small programs (statx with a
  NULL path, SELinux, the shadow file, the translator's descriptors, `CORTEX_NT`, `putenv` +
  `execv`); the build runs it. `tests/scripts/test_go_programs.sh` has a stress test of the Go
  change. `tests/scripts/test_shell_scripts.sh` covers `sudo` and `su` without root.
- The package compatibility run takes the packages to test (`only`; a name that is not in the table,
  or a list that is not names, fails the run instead of testing nothing or everything) and the APKs
  of a dry run (`apk_run`); the nginx test installs the `curl` it needs, and PostgreSQL has a client row (passes)
  next to the server row (a known gap outside root mode: the server refuses to run as root, and an
  app cannot become another user).
- Known gaps, seen in the x86 emulator only: Go programs and the JVM's compiler crash inside the
  emulator's ARM translator (a Go program also when it is not changed by Cortex; they run under
  qemu-user and on phones).

## 1.26.39 (2026-10-25)

### New

- **Programs written in Go see Ubuntu.** A Go program built without cgo (`gh`, `lazygit`, `fzf`,
  most single-file tools you download) makes its own system calls, so Cortex's library was never
  loaded into it: it looked for `/etc/resolv.conf`, `/etc/ssl` and `/bin/sh` in Android's files, and
  could not start Ubuntu's programs. Cortex now changes such a program's file once, before it
  starts it, so that it sees Ubuntu's files and starts Ubuntu's programs as everything else does.
  The file keeps its size and its time; a newer Cortex replaces the change with its own, and
  `CORTEX_NO_GOSYS=1` takes it out again and adds none (the program then runs as it was built).
  It works for AArch64 programs built with Go 1.19 or newer (tested up to 1.27); in root mode a
  changed program works as any other. Root mode is no longer needed for them.
- **Session tabs.** A strip above the terminal with one tab for each session, named after the
  directory its shell is in; the one on screen is in the accent color with a close button, and a
  `+` starts a new session. Settings > Session tabs turns it off.
- **Seven more color schemes**, thirteen in all: Solarized Dark, Monokai, Rosé Pine, Everforest,
  GitHub Dark, Ayu Mirage and a pure black one for OLED screens. Settings > Color Scheme shows each
  as a small piece of terminal in its own colors.
- **A way back to the end.** While the terminal is scrolled back, a thin thumb at the right edge
  shows where it is in the history and a round button takes it back to the newest line.

### Improved

- **The interface takes the colors of the color scheme.** The keys, the drawer, the search bar, the
  tabs, the links and selection handles, the first-start dialog, the settings and the status and
  navigation bars are made from the scheme's own colors, so choosing Nord changes the keys as much
  as the text. Text on them is moved only as far as it needs to be to read well, also in schemes
  with little contrast of their own (Solarized).
- The keys are flat and rounded, in monospace, and sink a little under the finger; CTRL and ALT
  light up in the accent while they wait for a key. The drawer has the app's mark, a card for each
  session with its number and directory (and the ROOT badge) and a filled New session button.
- The first start's progress is a dialog with the app's mark and a bar with the percentage, in the
  scheme's colors.

### For developers

- `tests/scripts/test_go_programs.sh` builds a Go program for arm64, a root file system of Ubuntu's
  own arm64 bash and the cross C library, and runs it under qemu-user: what it sees, what it
  starts, that the change is made once, replaced by a newer one and undone exactly, and that it
  does nothing in a chroot. The build runs it with Go 1.19 and 1.23. The emulator test runs the
  program too (the x86 emulator's ARM translator crashes on static Go programs, also unchanged
  ones, which the test tells apart by running the program with `CORTEX_NO_GOSYS=1` first).
- The emulator test checks that a second session starts and takes a picture of the color scheme
  list. Unit tests cover the tabs, keys, search bar, layouts and dialogs on the JVM, and the
  contrast of the interface's text in every color scheme.
- The nightly package compatibility run started `apt-get` through `timeout`, which puts it in the
  terminal's background where it is stopped (SIGTTOU) at its end; it uses `timeout --foreground`.

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
