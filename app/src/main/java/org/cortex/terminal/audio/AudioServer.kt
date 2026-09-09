package org.cortex.terminal.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

object AudioServer {
    private const val TAG = "AudioServer"
    const val PORT = 4712

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingPath: String? = null
    private val lock = Any()

    fun start(context: Context) {
        synchronized(lock) {
            if (isRunning) return
            isRunning = true
        }

        thread(name = "Cortex-AudioServer", isDaemon = true) {
            try {
                val server = ServerSocket(PORT, 10, InetAddress.getByName("127.0.0.1"))
                serverSocket = server
                Log.i(TAG, "AudioServer listening on 127.0.0.1:$PORT")

                while (isRunning && !server.isClosed) {
                    try {
                        val client = server.accept()
                        thread(name = "Cortex-AudioClient", isDaemon = true) {
                            handleClient(client)
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AudioServer", e)
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = PrintWriter(OutputStreamWriter(client.getOutputStream()), true)

            val line = reader.readLine() ?: return
            val trimmed = line.trim()
            val parts = trimmed.split(" ", limit = 2)
            val cmd = parts[0].uppercase()
            val arg = if (parts.size > 1) parts[1].trim() else ""

            when (cmd) {
                "PLAY" -> {
                    if (arg.isEmpty()) {
                        writer.println("ERROR: Missing file path")
                        return
                    }
                    val file = File(arg)
                    if (!file.exists()) {
                        writer.println("ERROR: File not found: $arg")
                        return
                    }
                    val success = playFile(file)
                    if (success) {
                        writer.println("OK: Playing ${file.name}")
                    } else {
                        writer.println("ERROR: Failed to play ${file.name}")
                    }
                }
                "STOP" -> {
                    stopPlayback()
                    writer.println("OK: Stopped")
                }
                "PAUSE" -> {
                    pausePlayback()
                    writer.println("OK: Paused")
                }
                "RESUME" -> {
                    resumePlayback()
                    writer.println("OK: Resumed")
                }
                "STATUS" -> {
                    synchronized(lock) {
                        if (mediaPlayer?.isPlaying == true) {
                            writer.println("PLAYING: $currentPlayingPath")
                        } else {
                            writer.println("IDLE")
                        }
                    }
                }
                "BEEP" -> {
                    val freq = arg.toIntOrNull() ?: 440
                    playTone(freq, 800)
                    writer.println("OK: Beep $freq Hz")
                }
                "STREAM" -> {
                    writer.println("OK: Ready for raw PCM")
                    streamPcm(client.getInputStream())
                }
                else -> {
                    writer.println("ERROR: Unknown command: $cmd")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio client error", e)
        } finally {
            try { client.close() } catch (e: Exception) {}
        }
    }

    private fun playFile(file: File): Boolean {
        synchronized(lock) {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null

                val mp = MediaPlayer()
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener {
                    synchronized(lock) {
                        currentPlayingPath = null
                        it.release()
                        if (mediaPlayer === it) mediaPlayer = null
                    }
                }
                mp.prepare()
                mp.start()
                mediaPlayer = mp
                currentPlayingPath = file.absolutePath
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play audio file ${file.absolutePath}", e)
                return false
            }
        }
    }

    private fun stopPlayback() {
        synchronized(lock) {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (e: Exception) {}
            mediaPlayer = null
            currentPlayingPath = null
        }
    }

    private fun pausePlayback() {
        synchronized(lock) {
            try {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.pause()
                }
            } catch (e: Exception) {}
        }
    }

    private fun resumePlayback() {
        synchronized(lock) {
            try {
                mediaPlayer?.start()
            } catch (e: Exception) {}
        }
    }

    private fun playTone(freqHz: Int, durationMs: Int) {
        thread(name = "Cortex-Beep", isDaemon = true) {
            try {
                val sampleRate = 44100
                val numSamples = (durationMs * sampleRate / 1000)
                val buffer = ShortArray(numSamples)
                val angularFreq = 2.0 * Math.PI * freqHz / sampleRate
                for (i in 0 until numSamples) {
                    buffer[i] = (Math.sin(angularFreq * i) * 32767 * 0.7).toInt().toShort()
                }

                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                @Suppress("DEPRECATION")
                val track = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, numSamples * 2),
                    AudioTrack.MODE_STREAM
                )
                track.play()
                track.write(buffer, 0, numSamples)
                Thread.sleep(durationMs.toLong())
                track.stop()
                track.release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play beep", e)
            }
        }
    }

    private fun streamPcm(inputStream: InputStream) {
        var track: AudioTrack? = null
        try {
            val sampleRate = 44100
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            @Suppress("DEPRECATION")
            track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
                AudioTrack.MODE_STREAM
            )
            track.play()
            val buf = ByteArray(4096)
            var bytesRead: Int
            while (inputStream.read(buf).also { bytesRead = it } != -1) {
                track.write(buf, 0, bytesRead)
            }
            track.stop()
        } catch (e: Exception) {
            Log.e(TAG, "PCM stream error", e)
        } finally {
            try { track?.release() } catch (e: Exception) {}
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        stopPlayback()
    }
}
