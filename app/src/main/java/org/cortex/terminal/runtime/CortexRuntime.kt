package org.cortex.terminal.runtime

import android.content.Context
import android.os.Build

object CortexRuntime {
    val architecture: String
        get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    val is64Bit: Boolean
        get() = architecture.contains("64")

    fun initialize(context: Context) {
        BootstrapManager.initializeFileSystem(context)
    }
}
