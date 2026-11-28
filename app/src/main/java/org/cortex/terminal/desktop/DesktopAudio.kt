package org.cortex.terminal.desktop

import org.cortex.terminal.service.CortexAudio
import java.io.File

/**
 * Compatibility wrapper delegating to the shared [CortexAudio] playback bridge in
 * [org.cortex.terminal.service.CortexService].
 */
class DesktopAudio(private val pipe: File) {
    fun start() = CortexAudio.start(pipe)
    fun stop() = Unit
}
