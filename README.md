# Cortex

[![Release](https://img.shields.io/github/v/release/ssfuisu/cortex?style=for-the-badge&color=blue)](https://github.com/ssfuisu/cortex/releases)
[![Telegram](https://img.shields.io/badge/Telegram-@ratzgn-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/ratzgn)
[![License](https://img.shields.io/badge/License-Apache_2.0-green.svg?style=for-the-badge)](LICENSE)

<p align="center">
  <img src="docs/screenshots/cortex-neofetch.jpg" width="31%" alt="Cortex Terminal Neofetch" />
  <img src="docs/screenshots/cortex-sessions.jpg" width="31%" alt="Sessions Drawer" />
  <img src="docs/screenshots/cortex-keyboard.jpg" width="31%" alt="Keyboard & Virtual Keys" />
</p>
ANNOUNCEMENT: DUE TO MY BUSY SCHEDULE, I AM UNABLE TO ACTIVELY DEVELOP THE PROJECT. I MAY NOT BE ABLE TO ADDRESS YOUR REQUESTS AND REPORT ERRORS IN A TIMELY MANNER. I ONLY HAVE THE OPPORTUNITY TO DEVELOP IT ON WEEKENDS.
## What is Cortex?

Cortex is a next-generation native Linux terminal and development powerhouse engineered for Android.  
It delivers an authentic **Ubuntu 24.04 LTS (Noble Numbat)** environment powered by **GNU C Library (Glibc 2.39)** on unrooted devices.  
Unlike existing solutions, Cortex operates with **ZERO virtualization, ZERO PRoot, and ZERO containers**.  
All command-line utilities, compilers, and runtimes execute directly on your physical CPU cores and the Android Linux kernel.  
With full APT package management, high-speed developer launchers, and a modern Material 3 interface, Cortex turns your Android device into a complete coding workstation.

---

## Features

- **Genuine Ubuntu 24.04 LTS & Glibc 2.39**: Run standard GNU/Linux software, compilers, and modern libraries without Android Bionic libc limitations or missing header errors.
- **Pure Native Execution (Zero Overhead)**: No PRoot `ptrace` system call traps, no chroot, and no QEMU/KVM emulation. Enjoy 100% native CPU speed, minimal latency, and optimal battery efficiency.
- **Full Debian/Ubuntu APT Package Manager**: Complete, zero-warning package installation (`apt update`, `apt install`) verified with official Ubuntu archive GPG keyrings and dynamic DNS.
- **Full OpenJDK 17 JDK & JRE Runtime**: Out-of-the-box Java 17 development support (`apt install openjdk-17-jdk`) with dynamic linker library path preservation and POSIX shared memory (`/dev/shm`).
- **High-Speed AI & Developer Launchers**: One-command instant CDN setups for OpenCode, Meta Muse Code, and Google Antigravity.
- **Bun & Node.js Crash Prevention**: Transparent `inotify_add_watch` auto-provisioning ensures Bun-based tools like OpenCode never crash due to missing configuration directories.
- **Modern Material 3 Terminal Interface**: Sleek dark UI, high-framerate font rendering, full 256-color & 24-bit TrueColor support, and JetBrains Mono monospace typography.
- **2-Row Virtual Keyboard Toolbar**: Quick-access tactile keys for `ESC`, `TAB`, `CTRL`, `ALT`, arrow navigation, pipes (`|`), brackets, and symbol combinations.
- **Integrated Terminal Search**: Swipe across the keyboard toolbar or tap `FIND` to search through 5,000 lines of scrollback buffer with real-time match counts and navigation.
- **Clickable Links & Clipboard Image Insertion**: Underlined URLs open instantly in your default Android browser; tapping images in your keyboard clipboard automatically saves and types their file path.
- **Multi-Session Management**: Seamlessly spawn, switch between, and manage multiple active terminal sessions from the left navigation drawer.
- **Full Storage & Background Services**: Deep `/sdcard` device storage integration and standard background service controls via `service`, `/etc/init.d/`, and `/etc/cortex/autostart`.

---

## How It Works

```
+-----------------------------------------------------------+
|                      Cortex Terminal                      |
|                                                           |
|  +-----------------------------------------------------+  |
|  | Modern Material 3 UI (Sessions, Search, Keys Bar)   |  |
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
|  | Android Linux Kernel (Direct Hardware CPU Execution)|  |
|  +-----------------------------------------------------+  |
+-----------------------------------------------------------+
```

1. **Direct Kernel Execution**: Cortex does not run inside a virtual machine or container. Programs execute directly on your device's real ARM64 / ARMv7 hardware threads using Android's native Linux kernel.
2. **Userspace Redirection (`libcortex-hook.so`)**: Using an ultra-lightweight dynamic linker hook injected via `LD_PRELOAD`, standard Linux filesystem paths (`/usr`, `/bin`, `/etc`, `/tmp`) are transparently redirected to Cortex's private application space without slow operating system-level traps.
3. **No Root Required**: All path mapping, permission polyfills, and simulated root privileges (`getuid` / `geteuid`) happen strictly in userspace, allowing full development capabilities on any unrooted Android device.

---

## How to Install

1. Navigate to the [Releases](https://github.com/ssfuisu/cortex/releases) section of this repository.
2. Download the APK corresponding to your device architecture:
   - **`Cortex-arm64-v8a-signed.apk`**: For all modern 64-bit Android smartphones and tablets (recommended).
   - **`Cortex-armeabi-v7a-signed.apk`**: For legacy 32-bit Android devices.
3. Install the APK and grant storage permissions when prompted.

---

## Community & Support

Have feedback, questions, or ideas for new features? We'd love to hear from you:

- **GitHub Issues**: Open a bug report or feature request on [GitHub Issues](https://github.com/ssfuisu/cortex/issues).
- **Telegram**: Chat directly with the developer on Telegram: [@ratzgn](https://t.me/ratzgn).

---

## License

This project is licensed under the **Apache License, Version 2.0**. See the [LICENSE](LICENSE) file for full details.
