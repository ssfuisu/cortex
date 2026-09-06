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

        val bashrc = File(home, ".bashrc")
        if (!bashrc.exists()) {
            bashrc.writeText(
                """
                # Cortex Terminal Environment
                export PS1='\[\033[01;32m\]cortex\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
                alias ll='ls -la'
                alias la='ls -A'
                alias l='ls -CF'
                alias cls='clear'
                """.trimIndent()
            )
        }

        val profile = File(home, ".profile")
        if (!profile.exists()) {
            profile.writeText(
                """
                if [ -f "$HOME/.bashrc" ]; then
                    . "$HOME/.bashrc"
                fi
                """.trimIndent()
            )
        }

        val cortexInfo = File(root, "bin/cortex-info")
        if (!cortexInfo.exists()) {
            cortexInfo.writeText(
                """#!/system/bin/sh
                echo "Cortex Rootless Terminal"
                echo "Architecture: $(uname -m)"
                echo "Kernel: $(uname -r)"
                echo "Cortex Root: $CORTEX_ROOT"
                echo "Prefix: $PREFIX"
                """.trimIndent()
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
