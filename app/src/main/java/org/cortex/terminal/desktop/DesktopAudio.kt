package org.cortex.terminal.desktop

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.File
import java.io.FileDescriptor

/**
 * The desktop's sound: PulseAudio (started by cortex-desktop) writes what its programs play into a
 * pipe, 44.1 kHz 16-bit stereo, and this plays it on Android. The pipe is opened without waiting for
 * a writer and polled, so that stopping never hangs and a PulseAudio started later is picked up.
 */
class DesktopAudio(private val pipe: File) {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "Cortex-DesktopAudio").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var track: AudioTrack? = null
        val buffer = ByteArray(8192)
        while (running) {
            var fd: FileDescriptor? = null
            try {
                if (!pipe.exists()) {
                    Thread.sleep(1000)
                    continue
                }
                fd = Os.open(pipe.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
                val poll = StructPollfd().apply {
                    this.fd = fd
                    events = OsConstants.POLLIN.toShort()
                }
                while (running) {
                    val ready = Os.poll(arrayOf(poll), 500)
                    if (ready == 0) continue
                    val n = try {
                        Os.read(fd, buffer, 0, buffer.size)
                    } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EAGAIN) 0 else throw e
                    }
                    if (n <= 0) {
                        // no writer (PulseAudio stopped): wait, then open the pipe anew
                        if (poll.revents.toInt() and OsConstants.POLLHUP != 0) {
                            Thread.sleep(300)
                            break
                        }
                        continue
                    }
                    val t = track ?: newTrack().also { track = it }
                    t.write(buffer, 0, n - n % 4)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "sound: ${e.message}")
                try {
                    Thread.sleep(1000)
                } catch (ie: InterruptedException) {
                    break
                }
            } finally {
                fd?.let { try { Os.close(it) } catch (e: Exception) { } }
            }
        }
        track?.let {
            try {
                it.stop()
            } catch (e: Exception) { }
            it.release()
        }
    }

    private fun newTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build()
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, SAMPLE_RATE * 4 / 5)) // 200 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    companion object {
        private const val TAG = "DesktopAudio"
        private const val SAMPLE_RATE = 44100
    }
}
