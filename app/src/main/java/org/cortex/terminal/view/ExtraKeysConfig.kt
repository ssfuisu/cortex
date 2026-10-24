package org.cortex.terminal.view

import android.os.FileObserver
import android.util.Log
import org.cortex.terminal.runtime.FileInstaller
import java.io.File

/**
 * ~/.cortex/keys, the keys above the keyboard ([ExtraKeysLayout]), with ~/.cortex/keys.example
 * (the default layout and how to write one) next to it.
 */
class ExtraKeysConfig(home: File) {
    val dir = File(home, ".cortex")
    val file = File(dir, FILE_NAME)
    private val example = File(dir, "$FILE_NAME.example")

    // The text the keys were last made from ("" without the file)
    private var appliedText: String? = null
    private var observer: FileObserver? = null

    /** The layout to show when the file changed since the last call, null when it did not. */
    fun loadIfChanged(): ExtraKeysLayout.Result? {
        val text = read()
        if (text == appliedText) return null
        appliedText = text
        return when {
            text == TOO_BIG -> ExtraKeysLayout.Result(emptyList(), "larger than ${MAX_BYTES / 1024} KB")
            text.isBlank() -> ExtraKeysLayout.Result(ExtraKeysLayout.DEFAULT_ROWS)
            else -> ExtraKeysLayout.parse(text)
        }
    }

    private fun read(): String = try {
        when {
            !file.isFile -> ""
            file.length() > MAX_BYTES -> TOO_BIG
            else -> file.readText()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read ${file.path}: ${e.message}")
        ""
    }

    fun writeExample() {
        try {
            FileInstaller.install(example, ExtraKeysLayout.DEFAULT_TEXT + "\n", 420) // 0644
        } catch (e: Exception) {
            Log.w(TAG, "Cannot write ${example.path}: ${e.message}")
        }
    }

    /** Calls [onChange] (on the observer's thread) whenever ~/.cortex/keys is written, moved or removed. */
    @Suppress("DEPRECATION") // FileObserver(File) needs API 29
    fun watch(onChange: () -> Unit) {
        stopWatching()
        dir.mkdirs()
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM or
            FileObserver.DELETE or FileObserver.CREATE
        observer = object : FileObserver(dir.path, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path == FILE_NAME) onChange()
            }
        }.also { it.startWatching() }
    }

    fun stopWatching() {
        observer?.stopWatching()
        observer = null
    }

    companion object {
        private const val TAG = "ExtraKeysConfig"
        const val FILE_NAME = "keys"
        private const val MAX_BYTES = 64 * 1024L
        private const val TOO_BIG = "\u0000too big"
    }
}
