package org.cortex.terminal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseUsersTest {

    private val passwdMaster = """
        root:*:0:0:root:/root:/bin/bash
        daemon:*:1:1:daemon:/usr/sbin:/usr/sbin/nologin
        man:*:6:12:man:/var/cache/man:/usr/sbin/nologin
        www-data:*:33:33:www-data:/var/www:/usr/sbin/nologin
        nobody:*:65534:65534:nobody:/nonexistent:/usr/sbin/nologin
    """.trimIndent() + "\n"

    private val groupMaster = """
        root:*:0:
        daemon:*:1:
        adm:*:4:
        shadow:*:42:
        users:*:100:
        nogroup:*:65534:
    """.trimIndent() + "\n"

    @Test
    fun mergePasswd_addsTheMissingUsersAfterTheOnesThatAreThere() {
        val have = "root:x:0:0:root:/data/home:/bin/bash\ncortex:x:0:0:Cortex:/data/home:/bin/bash\n_ssh:x:100:100:OpenSSH daemon:/run/sshd:/usr/sbin/nologin\n"

        val merged = BaseUsers.mergePasswd(have, passwdMaster)

        assertEquals(listOf("daemon", "man", "www-data", "nobody"), merged.added)
        assertEquals(
            have +
                "daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin\n" +
                "man:x:6:12:man:/var/cache/man:/usr/sbin/nologin\n" +
                "www-data:x:33:33:www-data:/var/www:/usr/sbin/nologin\n" +
                "nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin\n",
            merged.text
        )
    }

    @Test
    fun mergePasswd_changesNothingThatIsThere() {
        // A user the user or a package changed keeps its fields, and a second merge adds nothing
        val have = "root:x:0:0:root:/home:/bin/zsh\nman:x:6:12:man:/elsewhere:/bin/sh\n"

        val once = BaseUsers.mergePasswd(have, passwdMaster)
        val twice = BaseUsers.mergePasswd(once.text, passwdMaster)

        assertTrue(once.text.startsWith(have))
        assertTrue(once.text.contains("man:x:6:12:man:/elsewhere:/bin/sh\n"))
        assertEquals(1, Regex("^man:", RegexOption.MULTILINE).findAll(once.text).count())
        assertEquals(emptyList<String>(), twice.added)
        assertEquals(once.text, twice.text)
    }

    @Test
    fun mergePasswd_endsAFileWithoutANewlineBeforeAddingToIt() {
        val merged = BaseUsers.mergePasswd("root:x:0:0:root:/home:/bin/bash", passwdMaster)

        assertTrue(merged.text.startsWith("root:x:0:0:root:/home:/bin/bash\ndaemon:x:1:1:"))
    }

    @Test
    fun mergePasswd_withoutAMasterOrAnEmptyFile() {
        assertEquals("root:x:0:0\n", BaseUsers.mergePasswd("root:x:0:0\n", "").text)
        assertEquals(emptyList<String>(), BaseUsers.mergePasswd("root:x:0:0\n", "").added)
        // Lines that are not entries (comments, short ones) are not taken
        assertEquals(emptyList<String>(), BaseUsers.mergePasswd("", "# a comment\nbroken:*:1\n").added)
        assertEquals("", BaseUsers.mergePasswd("", "").text)
    }

    @Test
    fun mergeGroup_addsTheGroupsPackagesAskFor() {
        val have = "root:x:0:\ncortex:x:0:\n_ssh:x:100:\nssh:x:101:\n"

        val merged = BaseUsers.mergeGroup(have, groupMaster)

        // adm (redis's `chown redis:adm`), shadow, nogroup (adduser --system)...
        assertEquals(listOf("daemon", "adm", "shadow", "users", "nogroup"), merged.added)
        assertTrue(merged.text.contains("adm:x:4:\n"))
        assertTrue(merged.text.contains("nogroup:x:65534:\n"))
        // gid 100 is _ssh's and users' too: the duplicate is allowed, the name is what packages look for
        assertTrue(merged.text.contains("users:x:100:\n"))
    }

    @Test
    fun mergeShadow_locksTheNewAccountsOnce() {
        val shadow = "root:*:19700:0:99999:7:::\n"

        val once = BaseUsers.mergeShadow(shadow, listOf("man", "root", "www-data"), BaseUsers::shadowEntry)
        val twice = BaseUsers.mergeShadow(once, listOf("man", "root", "www-data"), BaseUsers::shadowEntry)

        assertEquals(shadow + "man:*:19700:0:99999:7:::\nwww-data:*:19700:0:99999:7:::\n", once)
        assertEquals(once, twice)
        assertEquals("adm:*::\n", BaseUsers.mergeShadow("", listOf("adm"), BaseUsers::gshadowEntry))
    }
}
