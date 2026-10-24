package org.cortex.terminal.session

import android.content.Context

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class SessionManager(private val context: Context) {

    fun interface SessionListener {
        fun onSessionChanged(session: TerminalSession?)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<SessionListener>()

    val sessions = mutableListOf<TerminalSession>()
    var currentSessionIndex = -1
        private set

    var onSessionChanged: ((TerminalSession?) -> Unit)? = null

    /** Where the sessions are kept to be brought back after Android ended the app (null: not kept). */
    var store: SessionStore? = null

    private val saveExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Cortex-SessionStore").apply { isDaemon = true }
    }

    private fun restoreEnabled() =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_RESTORE_SESSIONS, true)

    /**
     * Keeps the sessions (directories and the end of their output) in [store], off the calling
     * thread. Called on each change and when Cortex goes to the background.
     */
    fun saveState() {
        val target = store ?: return
        val snapshot: List<TerminalSession>
        val current: Int
        synchronized(sessions) {
            snapshot = ArrayList(sessions)
            current = currentSessionIndex
        }
        try {
            saveExecutor.execute {
                try {
                    if (!restoreEnabled()) {
                        target.clear()
                        return@execute
                    }
                    val saved = snapshot.map { SessionStore.Saved(it.currentDirectory(), it.transcript()) }
                    target.save(SessionStore.State(saved, current.coerceAtLeast(0)))
                } catch (e: Exception) {
                    Log.w(TAG, "Could not keep the sessions: ${e.message}")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    /**
     * Starts again the sessions kept when Android ended the app, each in its directory and with the
     * end of its output; false when none were kept.
     */
    fun restore(rows: Int, cols: Int, widthPx: Int, heightPx: Int): Boolean {
        val target = store ?: return false
        if (!restoreEnabled()) return false
        val state = target.load() ?: return false
        for (saved in state.sessions) {
            newSession(rows, cols, widthPx, heightPx, startDirectory = saved.directory, restoredOutput = saved.output)
        }
        switchTo(state.current)
        return true
    }

    val currentSession: TerminalSession?
        get() = synchronized(sessions) {
            if (currentSessionIndex in 0 until sessions.size) {
                sessions[currentSessionIndex]
            } else {
                null
            }
        }

    fun addListener(listener: SessionListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: SessionListener) {
        listeners.remove(listener)
    }

    private fun notifySessionChanged(session: TerminalSession?) {
        mainHandler.post {
            onSessionChanged?.invoke(session)
            for (listener in listeners) {
                listener.onSessionChanged(session)
            }
        }
    }

    fun newSession(
        rows: Int = 24,
        cols: Int = 80,
        widthPx: Int = 0,
        heightPx: Int = 0,
        onRedraw: (() -> Unit)? = null,
        startDirectory: String? = null,
        restoredOutput: String? = null
    ): TerminalSession {
        val session = TerminalSession(context.applicationContext, rows, cols, widthPx, heightPx, onRedraw)
        session.startDirectory = startDirectory
        session.restoredOutput = restoredOutput
        session.numberProvider = { synchronized(sessions) { sessions.indexOf(session) + 1 }.coerceAtLeast(1) }
        val startTime = System.currentTimeMillis()
        session.onSessionFinished = { exitCode ->
            val duration = System.currentTimeMillis() - startTime
            if (exitCode == 0 && duration > 1500) {
                removeSession(session)
            }
        }
        session.onCloseRequested = {
            removeSession(session)
        }
        synchronized(sessions) {
            sessions.add(session)
            currentSessionIndex = sessions.size - 1
        }
        session.start()
        notifySessionChanged(session)
        saveState()
        return session
    }

    fun switchTo(index: Int) {
        val sessionToNotify: TerminalSession?
        synchronized(sessions) {
            if (index in 0 until sessions.size && index != currentSessionIndex) {
                currentSessionIndex = index
                sessionToNotify = currentSession
            } else {
                return
            }
        }
        notifySessionChanged(sessionToNotify)
        saveState()
    }

    fun removeSession(session: TerminalSession) {
        val sessionToNotify: TerminalSession?
        synchronized(sessions) {
            val idx = sessions.indexOf(session)
            if (idx >= 0) {
                session.destroy()
                sessions.removeAt(idx)
                if (sessions.isEmpty()) {
                    currentSessionIndex = -1
                    sessionToNotify = null
                } else {
                    currentSessionIndex = currentSessionIndex.coerceIn(0, sessions.size - 1)
                    sessionToNotify = currentSession
                }
            } else {
                return
            }
        }
        notifySessionChanged(sessionToNotify)
        saveState()
    }

    /**
     * Ends all sessions. [forget]: Cortex's own exit, after which none come back; false when they
     * are ended for an update of the app, after which they do.
     */
    fun destroyAll(forget: Boolean = true) {
        val sessionsToDestroy: List<TerminalSession>
        synchronized(sessions) {
            sessionsToDestroy = ArrayList(sessions)
            sessions.clear()
            currentSessionIndex = -1
        }
        if (forget) {
            val target = store
            try {
                saveExecutor.execute { target?.clear() }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
            }
        }
        for (s in sessionsToDestroy) {
            s.destroy()
        }
        notifySessionChanged(null)
    }

    companion object {
        private const val TAG = "SessionManager"
        const val PREF_RESTORE_SESSIONS = "restore_sessions"
    }
}
