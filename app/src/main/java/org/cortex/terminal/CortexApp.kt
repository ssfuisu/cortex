package org.cortex.terminal

import android.app.Application
import org.cortex.terminal.runtime.CortexRuntime

class CortexApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CortexRuntime.initialize(this)
    }
}
