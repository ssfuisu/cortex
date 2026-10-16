package org.cortex.terminal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShellProfileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val d = "$"

    // Byte-for-byte what Cortex 1.25.26 wrote into ~/.bashrc on a fresh install.
    private val legacyBashrc =
        "unset PREFIX\n" +
            "shopt -s checkwinsize 2>/dev/null\n" +
            "export GODEBUG=netdns=cgo\n" +
            "# Cortex Terminal Environment\n" +
            "if [ -n \"${d}BASH_VERSION\" ]; then\n" +
            "    export PS1='\\w ${d} '\n" +
            "else\n" +
            "    export PS1='~ ${d} '\n" +
            "fi\n" +
            "alias ll='ls -la'\n" +
            "alias la='ls -A'\n" +
            "alias l='ls -CF'\n" +
            "alias cls='clear'\n" +
            "export PATH=\"${d}HOME/.local/bin:${d}PATH\"\n" +
            "if [ -z \"${d}CORTEX_ROOT\" ]; then\n" +
            "    if [ -d \"${d}HOME/../etc\" ]; then\n" +
            "        export CORTEX_ROOT=\"$(cd \"${d}HOME/..\" && pwd)\"\n" +
            "    fi\n" +
            "fi\n" +
            "if [ -n \"${d}CORTEX_ROOT\" ]; then\n" +
            "    export SSL_CERT_FILE=\"${d}CORTEX_ROOT/etc/ssl/certs/ca-certificates.crt\"\n" +
            "    export SSL_CERT_DIR=\"${d}CORTEX_ROOT/etc/ssl/certs:/system/etc/security/cacerts\"\n" +
            "    export CURL_CA_BUNDLE=\"${d}CORTEX_ROOT/etc/ssl/certs/ca-certificates.crt\"\n" +
            "    export NODE_EXTRA_CA_CERTS=\"${d}CORTEX_ROOT/etc/ssl/certs/ca-certificates.crt\"\n" +
            "    export REQUESTS_CA_BUNDLE=\"${d}CORTEX_ROOT/etc/ssl/certs/ca-certificates.crt\"\n" +
            "else\n" +
            "    export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt\n" +
            "    export CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt\n" +
            "fi\n" +
            "if [ -d \"${d}HOME/../usr/share/zoneinfo\" ]; then\n" +
            "    export TZDIR=\"${d}HOME/../usr/share/zoneinfo\"\n" +
            "elif [ -d \"/usr/share/zoneinfo\" ]; then\n" +
            "    export TZDIR=\"/usr/share/zoneinfo\"\n" +
            "fi\n" +
            "if [ -f /etc/timezone ]; then export TZ=\"$(cat /etc/timezone 2>/dev/null)\"; fi\n" +
            "\n" +
            "reload() {\n" +
            "    set --\n" +
            "    if [ -f \"${d}HOME/.bashrc\" ]; then . \"${d}HOME/.bashrc\"; fi\n" +
            "    if [ -f \"${d}HOME/.profile\" ]; then . \"${d}HOME/.profile\"; fi\n" +
            "    if [ -f \"${d}HOME/.bash_profile\" ]; then . \"${d}HOME/.bash_profile\"; fi\n" +
            "    echo \"Configuration reloaded successfully.\"\n" +
            "}\n" +
            "alias reload='reload'\n" +
            "\n" +
            "if [ -f \"${d}HOME/.profile\" ] && [ -z \"${d}_CORTEX_PROF_GUARD\" ]; then\n" +
            "    _CORTEX_PROF_GUARD=1\n" +
            "    . \"${d}HOME/.profile\"\n" +
            "    unset _CORTEX_PROF_GUARD\n" +
            "fi\n" +
            "\n" +
            "if [ -d /etc/cortex/autostart ]; then\n" +
            "    for s in /etc/cortex/autostart/*; do\n" +
            "        if [ -f \"${d}s\" ]; then\n" +
            "            sname=\"$(basename \"${d}s\")\"\n" +
            "            service \"${d}sname\" start >/dev/null 2>&1 || true\n" +
            "        fi\n" +
            "    done\n" +
            "fi\n"

    private val legacyProfile =
        "unset PREFIX\n" +
            "shopt -s checkwinsize 2>/dev/null\n" +
            "export GODEBUG=netdns=cgo\n" +
            "if [ -n \"${d}BASH_VERSION\" ]; then\n" +
            "    export PS1='\\w ${d} '\n" +
            "else\n" +
            "    export PS1='~ ${d} '\n" +
            "fi\n" +
            "export PATH=\"${d}HOME/.local/bin:${d}PATH\"\n" +
            "\n" +
            "reload() {\n" +
            "    set --\n" +
            "    if [ -f \"${d}HOME/.bashrc\" ]; then . \"${d}HOME/.bashrc\"; fi\n" +
            "    if [ -f \"${d}HOME/.profile\" ]; then . \"${d}HOME/.profile\"; fi\n" +
            "    if [ -f \"${d}HOME/.bash_profile\" ]; then . \"${d}HOME/.bash_profile\"; fi\n" +
            "    echo \"Configuration reloaded successfully.\"\n" +
            "}\n" +
            "alias reload='reload'\n" +
            "\n" +
            "if [ -f \"${d}HOME/.bashrc\" ] && [ -z \"${d}_CORTEX_RC_GUARD\" ]; then\n" +
            "    _CORTEX_RC_GUARD=1\n" +
            "    . \"${d}HOME/.bashrc\"\n" +
            "    unset _CORTEX_RC_GUARD\n" +
            "fi\n"

    private val nvmLines =
        "export NVM_DIR=\"${d}([ -z \"${d}{XDG_CONFIG_HOME-}\" ] && printf %s \"${d}{HOME}/.nvm\" || printf %s \"${d}{XDG_CONFIG_HOME}/nvm\")\"\n" +
            "[ -s \"${d}NVM_DIR/nvm.sh\" ] && \\. \"${d}NVM_DIR/nvm.sh\" # This loads nvm\n"

    @Test
    fun migrate_removesInjectedLegacyBlocksFromBashrc() {
        val out = ShellProfile.migrateLegacyStartupFile(legacyBashrc, isProfile = false)

        for (gone in listOf(
            "_CORTEX_PROF_GUARD", "reload()", "alias reload", "unset PREFIX", "checkwinsize",
            "GODEBUG", "# Cortex Terminal Environment", "PS1=", "SSL_CERT_FILE", "TZDIR",
            "export TZ=", "/etc/cortex/autostart"
        )) {
            assertFalse("'$gone' must be removed from the migrated .bashrc", out.contains(gone))
        }
        assertTrue(out.contains("alias ll='ls -la'"))
        assertTrue(out.contains("alias cls='clear'"))
        assertTrue(out.contains("export PATH=\"${d}HOME/.local/bin:${d}PATH\""))
    }

    @Test
    fun migrate_keepsEverythingTheUserOrAnInstallerAppended() {
        val installerAdditions = "\n# opencode\nexport PATH=/home/me/.opencode/bin:${d}PATH\n\n" + nvmLines
        val out = ShellProfile.migrateLegacyStartupFile(legacyBashrc + installerAdditions, isProfile = false)

        assertTrue(out.contains("# opencode\nexport PATH=/home/me/.opencode/bin:${d}PATH\n"))
        assertTrue(out.contains(nvmLines))
    }

    @Test
    fun migrate_profileHandsOverToBashrcLikeUbuntu() {
        val out = ShellProfile.migrateLegacyStartupFile(legacyProfile, isProfile = true)

        assertFalse(out.contains("_CORTEX_RC_GUARD"))
        assertFalse(out.contains("reload()"))
        assertTrue(out.contains("if [ -n \"${d}BASH_VERSION\" ]; then"))
        assertTrue(out.contains(". \"${d}HOME/.bashrc\""))
        assertTrue(out.contains("export PATH=\"${d}HOME/.local/bin:${d}PATH\""))
    }

    @Test
    fun migrate_isIdempotent() {
        val once = ShellProfile.migrateLegacyStartupFile(legacyBashrc + nvmLines, isProfile = false)
        assertEquals(once, ShellProfile.migrateLegacyStartupFile(once, isProfile = false))

        val profileOnce = ShellProfile.migrateLegacyStartupFile(legacyProfile, isProfile = true)
        assertEquals(profileOnce, ShellProfile.migrateLegacyStartupFile(profileOnce, isProfile = true))
    }

    @Test
    fun migrate_leavesUbuntuDefaultsAndUserFilesUntouched() {
        assertEquals(ShellProfile.DEFAULT_BASHRC, ShellProfile.migrateLegacyStartupFile(ShellProfile.DEFAULT_BASHRC, false))
        assertEquals(ShellProfile.DEFAULT_PROFILE, ShellProfile.migrateLegacyStartupFile(ShellProfile.DEFAULT_PROFILE, true))

        // A user's own reload()/source() helpers must survive: only the exact generated block is legacy.
        val custom = "reload() {\n    echo mine\n}\nsource() { builtin source \"${d}@\"; }\n"
        assertEquals(custom, ShellProfile.migrateLegacyStartupFile(custom, isProfile = false))
    }

    @Test
    fun migrate_repairsOpencodePathLinesBrokenByTheOldStrip() {
        // /home/.opencode/bin: stripped out of the installer's absolute PATH line
        val brokenAbsolute = "# opencode\nexport PATH=/data/data/org.cortex.terminal/files/cortex${d}PATH\n"
        assertEquals(
            "# opencode\nexport PATH=/data/data/org.cortex.terminal/files/cortex/home/.opencode/bin:${d}PATH\n",
            ShellProfile.migrateLegacyStartupFile(brokenAbsolute, isProfile = false)
        )

        // ${'$'}HOME/.opencode/bin: stripped out of a ${'$'}HOME based line
        val brokenHome = "# opencode\nexport PATH=${d}PATH\n"
        assertEquals(
            "# opencode\nexport PATH=\"${d}HOME/.opencode/bin:${d}PATH\"\n",
            ShellProfile.migrateLegacyStartupFile(brokenHome, isProfile = false)
        )
    }

    @Test
    fun ensure_createsFromSkelAndKeepsCortexPrompt() {
        val home = tempFolder.newFolder("home")
        val skel = tempFolder.newFolder("skel")
        File(skel, ".bashrc").writeText("# ubuntu skel bashrc\nPS1='ubuntu> '\n")
        File(skel, ".profile").writeText("# ubuntu skel profile\n")

        ShellProfile.ensureUserStartupFiles(home, skel)

        val bashrc = File(home, ".bashrc").readText()
        assertTrue(bashrc.startsWith("# ubuntu skel bashrc\n"))
        assertTrue(bashrc.endsWith("PS1='\\w ${d} '\n"))
        assertEquals("# ubuntu skel profile\n", File(home, ".profile").readText())
    }

    @Test
    fun ensure_fallsBackToEmbeddedUbuntuDefaultsWithoutSkel() {
        val home = tempFolder.newFolder("home")

        ShellProfile.ensureUserStartupFiles(home, skel = null)

        assertTrue(File(home, ".profile").readText().contains("if [ -f \"${d}HOME/.bashrc\" ]; then"))
        assertTrue(File(home, ".bashrc").readText().contains("HISTCONTROL=ignoreboth"))
    }

    @Test
    fun ensure_doesNothingBeforeTheRootfsIsExtracted() {
        val home = tempFolder.newFolder("home")

        ShellProfile.ensureUserStartupFiles(home, skel = null, rootfsReady = false)

        assertFalse(File(home, ".bashrc").exists())
        assertFalse(File(home, ".profile").exists())
    }

    @Test
    fun ensure_neverRewritesAFileThatNeedsNoMigration() {
        val home = tempFolder.newFolder("home")
        val bashrc = File(home, ".bashrc")
        val userText = "# mine\nexport FOO=bar\n" + nvmLines
        bashrc.writeText(userText)
        bashrc.setLastModified(1_000_000_000_000L)
        File(home, ".profile").writeText("# mine\n")

        ShellProfile.ensureUserStartupFiles(home, skel = null)
        ShellProfile.ensureUserStartupFiles(home, skel = null)

        assertEquals(userText, bashrc.readText())
        assertEquals("Untouched file keeps its mtime so the shell auto-reload does not fire", 1_000_000_000_000L, bashrc.lastModified())
    }

    @Test
    fun ensure_migratesLegacyFilesOnceThenLeavesThemAlone() {
        val home = tempFolder.newFolder("home")
        File(home, ".bashrc").writeText(legacyBashrc + nvmLines)
        File(home, ".profile").writeText(legacyProfile)

        ShellProfile.ensureUserStartupFiles(home, skel = null)

        val bashrc = File(home, ".bashrc")
        assertFalse(bashrc.readText().contains("_CORTEX_PROF_GUARD"))
        assertTrue(bashrc.readText().contains(nvmLines))
        bashrc.setLastModified(1_000_000_000_000L)

        ShellProfile.ensureUserStartupFiles(home, skel = null)
        assertEquals(1_000_000_000_000L, bashrc.lastModified())
    }

    @Test
    fun ensure_removesOnlyTheOldGeneratedHelperScripts() {
        val home = tempFolder.newFolder("home")
        val localBin = File(home, ".local/bin").apply { mkdirs() }
        val generated = "#!/bin/bash\nif (return 0 2>/dev/null); then\n    . \"${d}HOME/.bashrc\"\nelse\n    exec \"${d}{SHELL:-/bin/bash}\" -l\nfi\n"
        File(localBin, "bashrc").writeText(generated)
        File(localBin, "profile").writeText("#!/bin/sh\necho user script\n")
        File(home, ".profile").writeText("# p\n")
        File(home, ".bashrc").writeText("# b\n")

        ShellProfile.ensureUserStartupFiles(home, skel = null)

        assertFalse(File(localBin, "bashrc").exists())
        assertTrue(File(localBin, "profile").exists())
    }
}
