# Security

## Reporting a problem

If you find a security problem in Cortex, please don't open a public issue. Write on [Telegram](https://t.me/ratzgn), or open an issue that only says you found something and we'll take it private from there. I'll answer within a few days and credit you in the changelog if you want.

## What Cortex can and cannot do to your phone

**Cortex runs inside its Android app sandbox.** Everything inside your terminal (`apt`, `gcc`, a server you start) runs as the app's own Android user, with the same rights the terminal app itself has. Even when using `sudo` inside Ubuntu, the kernel still sees only the app's Android user. That means:

- It cannot change Android, the system partition, the bootloader or other apps' private data. It cannot brick the phone. A broken package can break Cortex's own Ubuntu, and clearing the app's data (or reinstalling it) gives you a fresh one.
- Uninstalling the app removes everything in its own storage. Nothing is left in the system.
- **Shared storage is the exception.** If you allow storage access, programs in Cortex can read, change and delete your files in `/sdcard` (photos, downloads, documents) just like a file manager can. A bad `rm` there is not undone by clearing data or uninstalling, and files programs create there stay after uninstalling.
- Ubuntu's files live in the app's private storage (`/data/data/org.cortex.terminal`), which other apps cannot read. Your phone's shared storage (`/sdcard`) is only reachable after you allow it.
- Packages come from Ubuntu's own mirrors and `apt` checks Ubuntu's signatures, the same as on a PC. Cortex ships Ubuntu's keyring and does not add any other package source.

**Using `tsu` / `android-su` on rooted phones is separate.** Those explicit commands call the phone's Android `su` binary (Magisk, KernelSU, APatch) only when you ask for Android host root; Ubuntu's local `sudo` and `su` never invoke phone root.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | `apt`, `git`, `curl` and every other network program. The app itself only talks to Ubuntu's mirrors (package lists and the fastest-mirror check) and to `api.github.com` (is there a new Cortex release). No analytics, no ads, no tracking. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK` | Keeps your sessions running when the app is in the background, with the notification you see. The wake lock is only taken when you turn it on in that notification. |
| `POST_NOTIFICATIONS`, `VIBRATE` | The session notification, `notify-send` and `cortex-api` notifications, and the bell. |
| `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE` | `/sdcard` in the terminal. The app asks once at the start; say *Later* and everything else works, only `/sdcard` stays closed. |
| `REQUEST_INSTALL_PACKAGES` | The in-app updater installs a newer Cortex APK, after you tap it. |

Cortex has no accessibility service, reads no contacts, SMS, location, camera or microphone, and the Files app entry only shows the Cortex home folder to the system file picker.

## Checking a download

From 1.27.7 on, every release is built on GitHub's servers from the tagged source and comes with a build attestation and `SHA256SUMS.txt`. How to check both, and the signing key fingerprints, is in [signing/README.md](signing/README.md). The code is all here: the library that does the path mapping is [`app/src/main/cpp/hook`](app/src/main/cpp/hook) and the bootstrap ELF trampoline is [`app/src/main/cpp/cortex-exec.c`](app/src/main/cpp/cortex-exec.c).
