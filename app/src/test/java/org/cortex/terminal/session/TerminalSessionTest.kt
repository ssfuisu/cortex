package org.cortex.terminal.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class TerminalSessionTest {

    private fun allocateWithoutConstructor(): TerminalSession {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val session = allocateInstance.invoke(unsafe, TerminalSession::class.java) as TerminalSession

        val execField = TerminalSession::class.java.getDeclaredField("writeExecutor")
        execField.isAccessible = true
        execField.set(
            session,
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "Test-PtyWriter").apply { isDaemon = true }
            }
        )
        return session
    }

    @Test
    fun testIsRunningFieldIsVolatile() {
        val runningField = TerminalSession::class.java.getDeclaredField("isRunning")
        assertTrue(
            "isRunning must be @Volatile for thread-safe visibility across reader/writer/UI threads",
            Modifier.isVolatile(runningField.modifiers)
        )
    }

    @Test
    fun testWriteAfterDestroyDoesNotThrowRejectedExecutionException() {
        val session = allocateWithoutConstructor()
        val runningField = TerminalSession::class.java.getDeclaredField("isRunning")
        runningField.isAccessible = true
        runningField.setBoolean(session, true)

        session.destroy()
        assertFalse(session.isRunning)

        // Simulate race where caller checks isRunning=true right as writeExecutor shuts down
        runningField.setBoolean(session, true)
        session.write("echo test\n".toByteArray(Charsets.UTF_8))

        // Also test when writeExecutor isn't reporting isShutdown yet but throws RejectedExecutionException on execute()
        val execField = TerminalSession::class.java.getDeclaredField("writeExecutor")
        execField.isAccessible = true
        val rejectingExecutor = java.lang.reflect.Proxy.newProxyInstance(
            ExecutorService::class.java.classLoader,
            arrayOf(ExecutorService::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "isShutdown" -> false
                "execute" -> throw RejectedExecutionException("Simulated shutdown race")
                "shutdownNow" -> emptyList<Runnable>()
                else -> null
            }
        } as ExecutorService
        execField.set(session, rejectingExecutor)

        // Must not throw RejectedExecutionException
        session.write("echo race\n".toByteArray(Charsets.UTF_8))
        session.destroy()
        assertFalse(session.isRunning)
    }
}
