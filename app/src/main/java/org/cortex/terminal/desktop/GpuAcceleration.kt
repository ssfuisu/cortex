package org.cortex.terminal.desktop

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log
import androidx.preference.PreferenceManager
import org.cortex.terminal.runtime.Environment
import org.cortex.terminal.runtime.FileInstaller
import java.io.File

/**
 * GPU acceleration for Linux programs (Settings › GPU acceleration, experimental): the app runs
 * virglrenderer's vtest server (scripts/build-virgl-android.sh), which draws with the phone's own
 * OpenGL ES driver, and the programs draw with Mesa's virpipe driver, which sends their OpenGL to it
 * over the rootfs' /tmp/.virgl_test. /etc/cortex/gpu.conf says it is on; cortex-desktop and the
 * terminal's environment set GALLIUM_DRIVER=virpipe while the server's socket is there.
 */
object GpuAcceleration {
    private const val TAG = "GpuAcceleration"
    const val PREF_ENABLED = "gpu_acceleration"
    private const val PREF_RENDERER = "gpu_renderer"
    private const val SERVER = "libvirgl_test_server.so"

    private var process: Process? = null

    fun enabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ENABLED, false)

    fun server(context: Context) = File(context.applicationInfo.nativeLibraryDir, SERVER)

    /** Whether this APK has the server (a build without it offers no setting) */
    fun available(context: Context) = server(context).isFile

    fun socket(context: Context) = File(Environment.getCortexRoot(context), "tmp/.virgl_test")

    private fun flag(context: Context) = File(Environment.getCortexRoot(context), "etc/cortex/gpu.conf")

    /** Whether programs started now get the GPU: the setting is on and the server answers */
    fun active(context: Context): Boolean = enabled(context) && socket(context).exists() && isRunning()

    @Synchronized
    fun isRunning(): Boolean = process?.let { p ->
        try { p.exitValue(); false } catch (e: IllegalThreadStateException) { true }
    } ?: false

    /** The environment of a Linux program while the GPU is on */
    fun environment(context: Context): List<String> =
        if (active(context)) listOf("GALLIUM_DRIVER=virpipe", "MESA_GL_VERSION_OVERRIDE=4.0") else emptyList()

    /** Starts or stops the server as the setting says (off the main thread: it waits for the socket) */
    fun sync(context: Context) {
        if (enabled(context) && available(context)) start(context) else stop(context)
    }

    @Synchronized
    fun start(context: Context): Boolean {
        val sock = socket(context)
        if (isRunning() && sock.exists()) return true
        stop(context)
        val bin = server(context)
        if (!bin.isFile) return false
        sock.parentFile?.mkdirs()
        sock.delete()
        val log = File(Environment.getHomeDir(context), ".cortex/virgl.log").apply { parentFile?.mkdirs() }
        return try {
            val pb = ProcessBuilder(bin.absolutePath, "--use-egl-surfaceless", "--use-gles",
                "--socket-path", sock.absolutePath)
                .redirectErrorStream(true)
            // an Android program: Android's environment, nothing of the rootfs' (no LD_PRELOAD)
            pb.environment().keys.retainAll(setOf("PATH", "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_RUNTIME_ROOT",
                "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT", "BOOTCLASSPATH"))
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            val p = pb.start()
            process = p
            // its output into the log (ProcessBuilder.Redirect needs Android 8)
            Thread({
                try {
                    p.inputStream.use { input -> java.io.FileOutputStream(log).use { input.copyTo(it) } }
                } catch (e: Exception) {
                }
            }, "Cortex-VirglLog").apply { isDaemon = true }.start()
            var waited = 0
            while (!sock.exists() && waited < 50 && isRunning()) {
                Thread.sleep(100)
                waited++
            }
            val up = sock.exists() && isRunning()
            if (up) {
                FileInstaller.install(flag(context), "GPU=virpipe\n", 420)
            } else {
                Log.w(TAG, "The GPU server did not start, see ${log.path}")
                stop(context)
            }
            up
        } catch (e: Exception) {
            Log.e(TAG, "Starting the GPU server failed", e)
            false
        }
    }

    @Synchronized
    fun stop(context: Context) {
        process?.destroy()
        process = null
        flag(context).delete()
        socket(context).delete()
    }

    /**
     * The phone's GPU as its OpenGL ES driver names it ("Mali-G925-Immortalis MC12"), read once from a
     * small offscreen context and kept. Off the main thread.
     */
    fun renderer(context: Context): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.getString(PREF_RENDERER, null)?.let { return it }
        val name = try { queryRenderer() } catch (t: Throwable) { Log.w(TAG, "No GPU name", t); null }
        if (!name.isNullOrBlank()) prefs.edit().putString(PREF_RENDERER, name).apply()
        return name
    }

    private fun queryRenderer(): String? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        try {
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            val ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            try {
                if (!EGL14.eglMakeCurrent(display, surface, surface, ctx)) return null
                return GLES20.glGetString(GLES20.GL_RENDERER)
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, ctx)
            }
        } finally {
            EGL14.eglTerminate(display)
        }
    }
}
