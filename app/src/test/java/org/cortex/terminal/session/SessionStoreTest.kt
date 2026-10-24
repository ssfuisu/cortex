package org.cortex.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun sessionsComeBackAsTheyWereKept() {
        val file = File(tempFolder.root, "sessions")
        val state = SessionStore.State(
            listOf(
                SessionStore.Saved("/data/user/0/org.cortex.terminal/files/cortex/home/my project", "\$ make\nok \\ done\r\n"),
                SessionStore.Saved(null, ""),
                SessionStore.Saved("/odd\npath", "output\ndirectory x\nsession")
            ),
            current = 2
        )
        SessionStore(file).save(state)
        assertEquals(state, SessionStore(file).load())
    }

    @Test
    fun nothingKeptNothingBack() {
        val file = File(tempFolder.root, "sessions")
        val store = SessionStore(file)
        assertNull(store.load())
        store.save(SessionStore.State(emptyList(), 0))
        assertNull("Cortex's own exit leaves no sessions", store.load())
        store.save(SessionStore.State(listOf(SessionStore.Saved(null, "x")), 5))
        assertEquals("the current one is one of them", 0, store.load()!!.current)
        store.clear()
        assertFalse(file.exists())
    }

    @Test
    fun aFileOfAnotherVersionIsIgnored() {
        val file = File(tempFolder.root, "sessions")
        file.writeText("cortex-sessions 2\ncurrent 0\nsession\noutput x\n")
        assertNull(SessionStore(file).load())
        file.writeText("cortex-sessions 1\ncurrent 0\nsession\nsomething new\n")
        assertNull(SessionStore(file).load())
    }

    @Test
    fun longOutputKeepsItsEndFromALine() {
        val long = (1..20000).joinToString("\n") { "line $it" }
        val kept = SessionStore.trimOutput(long)
        assert(kept.length <= SessionStore.MAX_CHARS)
        assert(kept.startsWith("line ") && kept.endsWith("line 20000"))
        assertEquals("short output stays", "a\nb", SessionStore.trimOutput("a\nb"))
    }
}
