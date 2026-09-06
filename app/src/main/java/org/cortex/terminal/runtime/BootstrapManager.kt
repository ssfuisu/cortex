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
            val d = "$"
            cortexInfo.writeText(
                "#!/system/bin/sh\n" +
                "echo \"Cortex Rootless Terminal\"\n" +
                "echo \"Architecture: " + d + "(uname -m)\"\n" +
                "echo \"Kernel: " + d + "(uname -r)\"\n" +
                "echo \"Cortex Root: " + d + "CORTEX_ROOT\"\n" +
                "echo \"Prefix: " + d + "PREFIX\"\n"
            )
            cortexInfo.setExecutable(true, false)
        }
    }

    fun getInitialShellCommand(context: Context): String {
        val customBash = File(Environment.getCortexRoot(context), "bin/bash")
        if (customBash.exists() && customBash.canExecute()) {
            return customBash.absolutePath
        }
        val customSh = File(Environment.getCortexRoot(context), "bin/sh")
        if (customSh.exists() && customSh.canExecute()) {
            return customSh.absolutePath
        }
        return if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
    }
}
