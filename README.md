# Cortex

[![Release](https://img.shields.io/github/v/release/ssfuisu/cortex?style=for-the-badge&color=blue)](https://github.com/ssfuisu/cortex/releases)
[![Telegram](https://img.shields.io/badge/Telegram-@ratzgn-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/ratzgn)
[![License](https://img.shields.io/badge/License-Apache_2.0-green.svg?style=for-the-badge)](LICENSE)

Cortex is a modern, high-performance Linux terminal application for Android powered by **Ubuntu 24.04 LTS (Noble Numbat)** and **Glibc 2.39**.

---

## What makes Cortex different?

Most existing Android terminal setups suffer from two major problems:

1. **Missing standard Linux libraries**: They rely on Android's custom system libraries, causing errors when compiling or running standard Linux programs.
2. **Slow execution**: When using tools like PRoot to fake a Linux system, every single action is intercepted at the operating system level, which slows down your device and consumes extra battery.

**Cortex takes a completely different approach:**

- **Zero-Overhead Native Speed**: Programs talk directly to your device's processor and Linux kernel. No heavy emulation and no system-call traps.
- **No Root Required**: Everything operates safely inside the application sandbox on standard, unrooted Android devices.
- **Full Library Support**: Built to support standard Glibc tools and modern Linux environments natively.

---

## Features

- **Ubuntu 24.04 LTS (Noble Numbat) Userland**: Full APT package management (`apt update`, `apt install`) with standard Ubuntu repositories and Glibc 2.39.
- **Pure Native Execution**: Zero PRoot, zero chroot, and zero virtualization overhead. Direct hardware execution on Android's Linux kernel with userspace translation (`libcortex-hook.so`).
- **CLI Browser Auto-Redirection**: Seamlessly bridges CLI auth tools (`antigravity auth login`, `gh auth login`, OAuth flows, and `xdg-open`) into Google Chrome or your default Android browser.
- **Direct Keyboard Image Insertion**: Tap any image in Gboard or Samsung Keyboard's clipboard to save it to `/sdcard/Pictures/` and insert its file path directly into the terminal prompt.
- **Background Service Manager**: Control services with `service`, `systemctl`, `/etc/init.d/`, and persistent background daemons via `/etc/cortex/autostart`.
- **All-Files Storage Access**: Deep integration with Android's `MANAGE_EXTERNAL_STORAGE` to work directly across device files on `/sdcard`.
- **High-Speed Display Engine**: Smooth text rendering with full 256-color, TrueColor, and crisp monospace typography.
- **Convenient Keyboard Bar & Tabs**: Quick-access buttons for Esc, Tab, Ctrl, Alt, arrows, and multi-tab session management.

---

## How to Install

1. Go to the **Releases** section on this repository.
2. Download the version for your device:
   - **Cortex-arm64-v8a-signed.apk**: For almost all modern Android phones and tablets (64-bit).
   - **Cortex-armeabi-v7a-signed.apk**: For older 32-bit Android devices.
3. Open the downloaded file on your Android device and tap **Install**.

---

## How it Works

```
+-----------------------------------------------------------+
|                      Cortex Terminal                      |
|                                                           |
|  +-----------------------------------------------------+  |
|  | Modern User Interface (Tabs, Keyboard Bar, Display) |  |
|  +-----------------------------------------------------+  |
|                            |                              |
|  +-----------------------------------------------------+  |
|  | Native PTY Controller (Direct Unix Terminal Bridge) |  |
|  +-----------------------------------------------------+  |
|                            |                              |
|  +-----------------------------------------------------+  |
|  | Zero-Overhead Userspace Hook (libcortex-hook.so)    |  |
|  +-----------------------------------------------------+  |
|                            |                              |
|  +-----------------------------------------------------+  |
|  | Android Linux Kernel (Direct Hardware Execution)    |  |
|  +-----------------------------------------------------+  |
+-----------------------------------------------------------+
```

1. **Native Terminal Interface**: The user interface draws text directly to the screen using your phone's graphics hardware.
2. **Direct Kernel Execution**: Terminal commands run directly on the underlying Linux kernel with zero emulation layers.
3. **Userspace Redirection**: A lightweight helper library redirects standard Linux paths (such as `/usr` and `/bin`) to Cortex's private directory without using slow kernel intercepts.

---

## Building from Source

This project is configured with GitHub Actions. Every release is built, split into 64-bit and 32-bit packages, and signed automatically in the cloud.

To build manually on your workstation:

```bash
# Build release APKs for 64-bit and 32-bit
./gradlew assembleRelease
```

---

## Contact & Community

Have feedback, questions, or feature requests? Reach out directly:

- **Telegram**: [@ratzgn](https://t.me/ratzgn)
- **GitHub Issues**: [Report an Issue / Feature Request](https://github.com/ssfuisu/cortex/issues)

---

## License

This project is licensed under the Apache License, Version 2.0.
