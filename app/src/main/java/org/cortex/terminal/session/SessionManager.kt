package org.cortex.terminal.session

import android.content.Context

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

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

    fun newSession(rows: Int = 24, cols: Int = 80, widthPx: Int = 0, heightPx: Int = 0, onRedraw: (() -> Unit)? = null): TerminalSession {
        val session = TerminalSession(context.applicationContext, rows, cols, widthPx, heightPx, onRedraw)
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
    }

    fun destroyAll() {
        val sessionsToDestroy: List<TerminalSession>
        synchronized(sessions) {
            sessionsToDestroy = ArrayList(sessions)
            sessions.clear()
            currentSessionIndex = -1
        }
        for (s in sessionsToDestroy) {
            s.destroy()
        }
        notifySessionChanged(null)
    }
}
