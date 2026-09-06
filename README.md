# Cortex

Cortex is a modern, high-performance Linux terminal application for Android.

It provides a native Linux command-line environment directly on your Android phone or tablet without needing root access and without the performance penalties of virtualized isolation tools.

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

- **High-Speed Display Engine**: Smooth, hardware-accelerated text rendering with full 256-color and TrueColor support.
- **Convenient Keyboard Bar**: Dedicated quick-access buttons for Esc, Tab, Ctrl, Alt, navigation arrows, and common terminal symbols.
- **Multiple Tabs**: Run multiple command-line sessions at the same time and switch between them instantly.
- **Customizable Appearance**: Change font sizes, appearance settings, and keep the screen awake during long tasks.
- **Native PTY Controller**: Uses custom native components written in C to handle terminal sessions cleanly.

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

## License

This project is licensed under the Apache License, Version 2.0.
