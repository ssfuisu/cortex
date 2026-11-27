package org.cortex.terminal.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.FileObserver
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared sound playback bridge for both CLI sessions and the Desktop UI.
 *
 * PulseAudio writes 44.1 kHz 16-bit stereo PCM to a named pipe (`audio.fifo`), and this reads and
 * plays it via Android's [AudioTrack]. Uses [FileObserver] when the FIFO does not exist yet and
 * infinite kernel `poll()` with a wakeup pipe once idle, so there is zero background CPU overhead
 * when silent and immediate wake + low-latency playback (~40 ms buffer) when audio plays.
 */
object CortexAudio {
    private const val TAG = "CortexAudio"
    private const val SAMPLE_RATE = 44100
    private const val FRAME_BYTES = 4
    private const val IDLE_TRACK_RELEASE_MS = 3000

    private val lock = Any()
    @Volatile private var running = false
    private var currentPipe: File? = null
    private var worker: Thread? = null
    private val wakeWriteFd = AtomicReference<FileDescriptor?>(null)
    private var fifoWaitLatch: CountDownLatch? = null

    fun start(pipe: File) {
        synchronized(lock) {
            if (running && currentPipe?.absolutePath == pipe.absolutePath && worker?.isAlive == true) {
                return
            }
            stopLocked()
            currentPipe = pipe
            running = true
            worker = Thread({ loop(pipe) }, "Cortex-Audio").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        running = false
        fifoWaitLatch?.countDown()
        fifoWaitLatch = null
        signalWake()
        val t = worker
        worker = null
        t?.interrupt()
    }

    private fun signalWake() {
        val fd = wakeWriteFd.get() ?: return
        try {
            Os.write(fd, byteArrayOf(1), 0, 1)
        } catch (_: Exception) {
        }
    }

    private fun loop(pipe: File) {
        var track: AudioTrack? = null
        val buffer = ByteArray(4096)
        val carry = ByteArray(FRAME_BYTES)
        var carryLen = 0

        var wakeFds: Array<FileDescriptor>? = null
        try {
            wakeFds = Os.pipe()
            wakeWriteFd.set(wakeFds[1])
        } catch (e: Exception) {
            Log.w(TAG, "pipe() unavailable: ${e.message}")
        }

        try {
            while (running) {
                var fd: FileDescriptor? = null
                try {
                    if (!pipe.exists()) {
                        track = releaseTrack(track)
                        carryLen = 0
                        waitForPipe(pipe)
                        continue
                    }

                    fd = Os.open(pipe.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
                    val fifoPoll = StructPollfd().apply {
                        this.fd = fd
                        events = OsConstants.POLLIN.toShort()
                    }
                    val wakePoll = wakeFds?.getOrNull(0)?.let { wfd ->
                        StructPollfd().apply {
                            this.fd = wfd
                            events = OsConstants.POLLIN.toShort()
                        }
                    }
                    val pollFds = if (wakePoll != null) arrayOf(fifoPoll, wakePoll) else arrayOf(fifoPoll)

                    while (running) {
                        val timeoutMs = if (track != null) IDLE_TRACK_RELEASE_MS else -1
                        val ready = Os.poll(pollFds, timeoutMs)
                        if (!running) break
                        if (wakePoll != null && (wakePoll.revents.toInt() and OsConstants.POLLIN) != 0) {
                            drainWake(wakePoll.fd)
                            if (!running) break
                        }
                        if (ready == 0) {
                            // Silence timeout: release AudioTrack so AudioFlinger resources are freed.
                            track = releaseTrack(track)
                            carryLen = 0
                            continue
                        }
                        if ((fifoPoll.revents.toInt() and (OsConstants.POLLIN or OsConstants.POLLHUP)) == 0) {
                            continue
                        }
                        val n = try {
                            Os.read(fd, buffer, 0, buffer.size)
                        } catch (e: ErrnoException) {
                            if (e.errno == OsConstants.EAGAIN) 0 else throw e
                        }
                        if (n <= 0) {
                            if ((fifoPoll.revents.toInt() and OsConstants.POLLHUP) != 0) {
                                track = releaseTrack(track)
                                carryLen = 0
                                Thread.sleep(200)
                                break
                            }
                            continue
                        }

                        val t = track ?: newTrack().also { track = it }
                        carryLen = writeAlignedPcm(t, buffer, n, carry, carryLen)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "sound: ${e.message}")
                    track = releaseTrack(track)
                    carryLen = 0
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        break
                    }
                } finally {
                    fd?.let { try { Os.close(it) } catch (_: Exception) {} }
                }
            }
        } finally {
            releaseTrack(track)
            wakeWriteFd.set(null)
            wakeFds?.forEach { f -> try { Os.close(f) } catch (_: Exception) {} }
        }
    }

    internal fun writeAlignedPcm(
        track: AudioTrack,
        data: ByteArray,
        len: Int,
        carry: ByteArray,
        carryLen: Int
    ): Int {
        var offset = 0
        var cLen = carryLen
        if (cLen > 0) {
            val need = FRAME_BYTES - cLen
            if (len < need) {
                System.arraycopy(data, 0, carry, cLen, len)
                return cLen + len
            }
            System.arraycopy(data, 0, carry, cLen, need)
            track.write(carry, 0, FRAME_BYTES)
            offset = need
            cLen = 0
        }
        val remaining = len - offset
        val aligned = remaining - (remaining % FRAME_BYTES)
        if (aligned > 0) {
            track.write(data, offset, aligned)
        }
        val leftover = remaining - aligned
        if (leftover > 0) {
            System.arraycopy(data, offset + aligned, carry, 0, leftover)
        }
        return leftover
    }

    @Suppress("DEPRECATION")
    private fun waitForPipe(pipe: File) {
        val stateDir = pipe.parentFile ?: return
        val tmpDir = stateDir.parentFile
        val latch = CountDownLatch(1)
        fifoWaitLatch = latch

        val watchTarget = if (stateDir.isDirectory) stateDir else if (tmpDir?.isDirectory == true) tmpDir else null
        val observer = watchTarget?.let { dir ->
            object : FileObserver(dir.path, CREATE or MOVED_TO or CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    if (!running || pipe.exists() || path == pipe.name || path == stateDir.name) {
                        latch.countDown()
                    }
                }
            }.also {
                try {
                    it.startWatching()
                } catch (_: Exception) {
                    latch.countDown()
                }
            }
        }

        try {
            if (pipe.exists() || !running) return
            if (observer != null) {
                // Periodically recheck directory creation if watching parent /tmp
                val waitMs = if (watchTarget == stateDir) 30_000L else 5_000L
                latch.await(waitMs, TimeUnit.MILLISECONDS)
            } else {
                Thread.sleep(1000)
            }
        } finally {
            fifoWaitLatch = null
            try { observer?.stopWatching() } catch (_: Exception) {}
        }
    }

    private fun drainWake(fd: FileDescriptor?) {
        if (fd == null) return
        val tmp = ByteArray(16)
        try {
            Os.fcntlInt(fd, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
            while (true) {
                if (Os.read(fd, tmp, 0, tmp.size) <= 0) break
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseTrack(track: AudioTrack?): AudioTrack? {
        if (track == null) return null
        try {
            track.pause()
            track.flush()
            track.stop()
        } catch (_: Exception) {
        }
        try {
            track.release()
        } catch (_: Exception) {
        }
        return null
    }

    private fun newTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val min = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_BYTES)
        // ~40 ms low-latency buffer (clamped to hardware minimum and aligned to 4-byte frames)
        val lowLatencyBytes = ((SAMPLE_RATE * FRAME_BYTES / 25) and -FRAME_BYTES).coerceAtLeast(min)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build()
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(lowLatencyBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build().also { it.play() }
    }
}
