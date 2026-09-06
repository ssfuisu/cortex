package org.cortex.terminal.runtime

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object BootstrapManager {

    fun initializeFileSystem(context: Context) {
        val root = Environment.getCortexRoot(context)
        val home = Environment.getHomeDir(context)
        val tmp = Environment.getTmpDir(context)
        val usr = Environment.getUsrDir(context)

        val dirs = listOf(
            root,
            home,
            tmp,
            usr,
            File(root, "bin"),
            File(root, "etc"),
            File(usr, "bin"),
            File(usr, "lib"),
            File(usr, "share"),
            File(root, "var/log")
        )

        for (dir in dirs) {
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }

        val d = "$"
        val bashrc = File(home, ".bashrc")
        if (!bashrc.exists() || !bashrc.readText().contains("PS1=")) {
            val bashrcContent = "# Cortex Terminal Environment\n" +
                "if [ -n \"" + d + "BASH_VERSION\" ]; then\n" +
                "    export PS1='\\w " + d + " '\n" +
                "else\n" +
                "    export PS1='~ " + d + " '\n" +
                "fi\n" +
                "alias ll='ls -la'\n" +
                "alias la='ls -A'\n" +
                "alias l='ls -CF'\n" +
                "alias cls='clear'\n"
            bashrc.writeText(bashrcContent)
        }

        val profile = File(home, ".profile")
        if (!profile.exists() || !profile.readText().contains("PS1=")) {
            val profileContent = "if [ -n \"" + d + "BASH_VERSION\" ]; then\n" +
                "    export PS1='\\w " + d + " '\n" +
                "else\n" +
                "    export PS1='~ " + d + " '\n" +
                "fi\n" +
                "if [ -f \"" + d + "HOME/.bashrc\" ]; then\n" +
                "    . \"" + d + "HOME/.bashrc\"\n" +
                "fi\n"
            profile.writeText(profileContent)
        }

        val cortexInfo = File(root, "bin/cortex-info")
        if (!cortexInfo.exists()) {
            val script = "#!/system/bin/sh\n" +
                "echo \"Cortex Rootless Terminal\"\n" +
                "echo \"Architecture: " + d + "(uname -m)\"\n" +
                "echo \"Kernel: " + d + "(uname -r)\"\n" +
                "echo \"Cortex Root: " + d + "CORTEX_ROOT\"\n" +
                "echo \"Prefix: " + d + "PREFIX\"\n"
            cortexInfo.writeText(script)
            cortexInfo.setExecutable(true, false)
        }

        val installScript = File(root, "bin/cortex-install-debian")
        if (!installScript.exists()) {
            val script = "#!/system/bin/sh\n" +
                "set -e\n" +
                "ARCH=\"" + d + "(uname -m)\"\n" +
                "case \"" + d + "ARCH\" in\n" +
                "    aarch64|arm64)\n" +
                "        DEBIAN_ARCH=\"arm64\"\n" +
                "        TAR_URL=\"https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-aarch64-pd-v4.17.3.tar.xz\"\n" +
                "        ;;\n" +
                "    armv7l|armv8l|arm)\n" +
                "        DEBIAN_ARCH=\"armhf\"\n" +
                "        TAR_URL=\"https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-arm-pd-v4.17.3.tar.xz\"\n" +
                "        ;;\n" +
                "    x86_64)\n" +
                "        DEBIAN_ARCH=\"x86_64\"\n" +
                "        TAR_URL=\"https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-x86_64-pd-v4.17.3.tar.xz\"\n" +
                "        ;;\n" +
                "    *)\n" +
                "        echo \"Unsupported CPU architecture: " + d + "ARCH\"\n" +
                "        exit 1\n" +
                "        ;;\n" +
                "esac\n\n" +
                "echo \"========================================================\"\n" +
                "echo \"Cortex Debian Glibc Rootfs & APT Installer\"\n" +
                "echo \"Architecture: " + d + "DEBIAN_ARCH\"\n" +
                "echo \"Root Directory: " + d + "CORTEX_ROOT\"\n" +
                "echo \"========================================================\"\n\n" +
                "TMP_FILE=\"" + d + "TMPDIR/debian-rootfs.tar.xz\"\n\n" +
                "echo \"Downloading Debian rootfs archive...\"\n" +
                "if command -v curl >/dev/null 2>&1; then\n" +
                "    curl -L --progress-bar -o \"" + d + "TMP_FILE\" \"" + d + "TAR_URL\"\n" +
                "elif command -v wget >/dev/null 2>&1; then\n" +
                "    wget -O \"" + d + "TMP_FILE\" \"" + d + "TAR_URL\"\n" +
                "else\n" +
                "    echo \"Error: Neither curl nor wget found on device.\"\n" +
                "    exit 1\n" +
                "fi\n\n" +
                "echo \"Extracting rootfs into Cortex environment...\"\n" +
                "tar -xf \"" + d + "TMP_FILE\" -C \"" + d + "CORTEX_ROOT\" --exclude=\"./dev\" --exclude=\"./proc\" --exclude=\"./sys\" 2>/dev/null || tar -xf \"" + d + "TMP_FILE\" -C \"" + d + "CORTEX_ROOT\"\n" +
                "rm -f \"" + d + "TMP_FILE\"\n\n" +
                "echo \"Configuring DNS and APT repository sources...\"\n" +
                "mkdir -p \"" + d + "CORTEX_ROOT/etc\"\n" +
                "echo \"nameserver 8.8.8.8\" > \"" + d + "CORTEX_ROOT/etc/resolv.conf\"\n" +
                "echo \"nameserver 1.1.1.1\" >> \"" + d + "CORTEX_ROOT/etc/resolv.conf\"\n\n" +
                "mkdir -p \"" + d + "CORTEX_ROOT/etc/apt\"\n" +
                "cat << 'EOF' > \"" + d + "CORTEX_ROOT/etc/apt/sources.list\"\n" +
                "deb http://deb.debian.org/debian bookworm main contrib non-free non-free-firmware\n" +
                "deb http://security.debian.org/debian-security bookworm-security main contrib non-free non-free-firmware\n" +
                "deb http://deb.debian.org/debian bookworm-updates main contrib non-free non-free-firmware\n" +
                "EOF\n\n" +
                "mkdir -p \"" + d + "CORTEX_ROOT/var/lib/dpkg\"\n" +
                "mkdir -p \"" + d + "CORTEX_ROOT/var/lib/apt/lists/partial\"\n" +
                "mkdir -p \"" + d + "CORTEX_ROOT/var/cache/apt/archives/partial\"\n" +
                "touch \"" + d + "CORTEX_ROOT/var/lib/dpkg/status\"\n\n" +
                "echo \"========================================================\"\n" +
                "echo \"Debian GNU/Linux environment & APT installed successfully!\"\n" +
                "echo \"Run 'apt update' to initialize package lists.\"\n" +
                "echo \"========================================================\"\n"
            installScript.writeText(script)
            installScript.setExecutable(true, false)
        }

        val aptWrapper = File(root, "bin/apt")
        if (!aptWrapper.exists()) {
            val script = "#!/system/bin/sh\n" +
                "if [ -x \"" + d + "CORTEX_ROOT/usr/bin/apt\" ]; then\n" +
                "    exec \"" + d + "CORTEX_ROOT/usr/bin/apt\" \"" + d + "@\"\n" +
                "fi\n\n" +
                "echo \"========================================================\"\n" +
                "echo \"Cortex APT Package Manager\"\n" +
                "echo \"========================================================\"\n" +
                "echo \"Debian rootfs is not yet installed.\"\n" +
                "echo \"Install Debian rootfs and APT now? [Y/n]\"\n" +
                "read -r reply\n" +
                "if [ \"" + d + "reply\" = \"n\" ] || [ \"" + d + "reply\" = \"N\" ]; then\n" +
                "    echo \"Cancelled.\"\n" +
                "    exit 1\n" +
                "fi\n" +
                "cortex-install-debian\n" +
                "if [ -x \"" + d + "CORTEX_ROOT/usr/bin/apt\" ]; then\n" +
                "    exec \"" + d + "CORTEX_ROOT/usr/bin/apt\" \"" + d + "@\"\n" +
                "fi\n"
            aptWrapper.writeText(script)
            aptWrapper.setExecutable(true, false)
        }

        val aptGetWrapper = File(root, "bin/apt-get")
        if (!aptGetWrapper.exists()) {
            val script = "#!/system/bin/sh\n" +
                "if [ -x \"" + d + "CORTEX_ROOT/usr/bin/apt-get\" ]; then\n" +
                "    exec \"" + d + "CORTEX_ROOT/usr/bin/apt-get\" \"" + d + "@\"\n" +
                "fi\n" +
                "exec \"" + d + "CORTEX_ROOT/bin/apt\" \"" + d + "@\"\n"
            aptGetWrapper.writeText(script)
            aptGetWrapper.setExecutable(true, false)
        }

        val dpkgWrapper = File(root, "bin/dpkg")
        if (!dpkgWrapper.exists()) {
            val script = "#!/system/bin/sh\n" +
                "if [ -x \"" + d + "CORTEX_ROOT/usr/bin/dpkg\" ]; then\n" +
                "    exec \"" + d + "CORTEX_ROOT/usr/bin/dpkg\" \"" + d + "@\"\n" +
                "fi\n" +
                "echo \"Debian rootfs with dpkg is not yet installed.\"\n" +
                "echo \"Run 'apt' or 'cortex-install-debian' to install.\"\n" +
                "exit 1\n"
            dpkgWrapper.writeText(script)
            dpkgWrapper.setExecutable(true, false)
        }
    }

    fun getInitialShellCommand(context: Context): String {
        val root = Environment.getCortexRoot(context)
        val debianBash = File(root, "usr/bin/bash")
        if (debianBash.exists() && debianBash.canExecute()) {
            return debianBash.absolutePath
        }
        val customBash = File(root, "bin/bash")
        if (customBash.exists() && customBash.canExecute()) {
            return customBash.absolutePath
        }
        val customSh = File(root, "bin/sh")
        if (customSh.exists() && customSh.canExecute()) {
            return customSh.absolutePath
        }
        return if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
    }
}
