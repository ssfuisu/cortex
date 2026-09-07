package org.cortex.terminal.session

import android.content.Context

class SessionManager(private val context: Context) {
    val sessions = mutableListOf<TerminalSession>()
    var currentSessionIndex = -1
        private set

    var onSessionChanged: ((TerminalSession?) -> Unit)? = null

    val currentSession: TerminalSession?
        get() = if (currentSessionIndex in 0 until sessions.size) {
            sessions[currentSessionIndex]
        } else {
            null
        }

    fun newSession(rows: Int = 24, cols: Int = 80, widthPx: Int = 0, heightPx: Int = 0, onRedraw: () -> Unit): TerminalSession {
        val session = TerminalSession(context, rows, cols, widthPx, heightPx, onRedraw)
        val startTime = System.currentTimeMillis()
        session.onSessionFinished = { exitCode ->
            val duration = System.currentTimeMillis() - startTime
            if (exitCode == 0 && duration > 2000) {
                removeSession(session)
            }
        }
        sessions.add(session)
        currentSessionIndex = sessions.size - 1
        session.start()
        onSessionChanged?.invoke(session)
        return session
    }

    fun switchTo(index: Int) {
        if (index in 0 until sessions.size && index != currentSessionIndex) {
            currentSessionIndex = index
            onSessionChanged?.invoke(currentSession)
        }
    }

    fun removeSession(session: TerminalSession) {
        val idx = sessions.indexOf(session)
        if (idx >= 0) {
            session.destroy()
            sessions.removeAt(idx)
            if (sessions.isEmpty()) {
                currentSessionIndex = -1
                onSessionChanged?.invoke(null)
            } else {
                currentSessionIndex = currentSessionIndex.coerceIn(0, sessions.size - 1)
                onSessionChanged?.invoke(currentSession)
            }
        }
    }

    fun destroyAll() {
        for (s in sessions) {
            s.destroy()
        }
        sessions.clear()
        currentSessionIndex = -1
        onSessionChanged?.invoke(null)
    }
}
