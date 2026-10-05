package org.cortex.terminal

import android.app.Application
import org.cortex.terminal.runtime.BootstrapManager

class CortexApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BootstrapManager.initializeFileSystem(this)
    }
}
