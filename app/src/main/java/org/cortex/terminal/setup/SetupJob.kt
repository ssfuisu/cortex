package org.cortex.terminal.setup

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import org.cortex.terminal.runtime.BootstrapManager
import org.cortex.terminal.runtime.DistroImage
import org.cortex.terminal.runtime.DistroInstaller
import org.cortex.terminal.runtime.DistroOption
import org.cortex.terminal.runtime.DistroSetup
import org.cortex.terminal.runtime.Environment
import org.cortex.terminal.runtime.SetupStep
import org.cortex.terminal.runtime.UserAccount
import org.cortex.terminal.service.CortexService
import java.io.IOException

/**
 * An installation the setup started: it runs on a thread of its own and outlives the screen (a turn
 * of the phone, the app in the background), which shows it again from [state] when it comes back. A
 * failed step is where [retry] starts again; the steps before it are not repeated.
 */
object SetupJob {
    private const val TAG = "SetupJob"
    const val PREF_DONE = "setup_done"

    enum class StepState { WAITING, RUNNING, DONE, FAILED }

    data class State(
        val distro: DistroOption,
        val desktop: String?,
        val steps: List<SetupStep>,
        val stepStates: List<StepState>,
        val percent: Int = -1,
        val failure: String? = null,
        val finished: Boolean = false
    ) {
        val running: Boolean get() = !finished && failure == null
    }

    @Volatile var state: State? = null
        private set
    private var listener: ((State) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var thread: Thread? = null

    private var image: DistroImage? = null
    private var name = UserAccount.DEFAULT_NAME
    private var password = ""

    fun listen(l: ((State) -> Unit)?) {
        listener = l
        state?.let { s -> l?.invoke(s) }
    }

    val isRunning: Boolean get() = thread?.isAlive == true

    /** Starts installing [distro] ([image] from the catalog unless it is the bundled Ubuntu) */
    @Synchronized
    fun start(context: Context, distro: DistroOption, image: DistroImage?, desktop: String?, browser: Boolean, name: String, password: String) {
        if (isRunning) return
        this.image = image
        this.name = name
        this.password = password
        val steps = DistroSetup.steps(distro, desktop, browser)
        state = State(distro, desktop, steps, steps.map { StepState.WAITING })
        launch(context.applicationContext, 0)
    }

    /** Runs the installation again from the step that failed */
    @Synchronized
    fun retry(context: Context) {
        val s = state ?: return
        if (isRunning || s.failure == null) return
        val from = s.stepStates.indexOfFirst { it == StepState.FAILED }.coerceAtLeast(0)
        state = s.copy(failure = null, percent = -1, stepStates = s.stepStates.mapIndexed { i, st -> if (i >= from) StepState.WAITING else st })
        launch(context.applicationContext, from)
    }

    fun clear() {
        if (!isRunning) state = null
    }

    private fun publish(s: State) {
        state = s
        main.post { listener?.invoke(s) }
    }

    private fun update(f: (State) -> State) {
        val s = state ?: return
        publish(f(s))
    }

    private fun launch(app: Context, from: Int) {
        thread = Thread({ run(app, from) }, "Cortex-Setup").apply { start() }
    }

    private fun run(app: Context, from: Int) {
        val s = state ?: return
        for (i in from until s.steps.size) {
            update { it.copy(percent = -1, stepStates = it.stepStates.mapIndexed { j, st -> if (j == i) StepState.RUNNING else st }) }
            try {
                runStep(app, s.distro, s.steps[i])
                update { it.copy(stepStates = it.stepStates.mapIndexed { j, st -> if (j == i) StepState.DONE else st }) }
            } catch (t: Throwable) {
                Log.e(TAG, "Setup step ${app.getString(s.steps[i].title)} failed", t)
                update {
                    it.copy(failure = t.message ?: t.javaClass.simpleName,
                        stepStates = it.stepStates.mapIndexed { j, st -> if (j == i) StepState.FAILED else st })
                }
                return
            }
        }
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(PREF_DONE, true).apply()
        update { it.copy(finished = true, percent = 100) }
    }

    private fun progress(p: Int) = update { if (it.percent == p) it else it.copy(percent = p) }

    private fun runStep(app: Context, distro: DistroOption, step: SetupStep) {
        val root = Environment.getCortexRoot(app)
        when (step.title) {
            org.cortex.terminal.R.string.setup_step_check -> {
                // what the unpacked system takes, and room for the archive while it is unpacked
                val need = (image?.let { it.unpacked + it.size } ?: (1_400L shl 20)) + (300L shl 20)
                val free = StatFs(app.filesDir.absolutePath).availableBytes
                if (free < need) {
                    throw IOException(app.getString(org.cortex.terminal.R.string.setup_no_space,
                        android.text.format.Formatter.formatShortFileSize(app, need),
                        android.text.format.Formatter.formatShortFileSize(app, free)))
                }
                // A system is put aside or brought back only when no session runs in it
                endSessions(app)
            }
            org.cortex.terminal.R.string.setup_step_find -> {
                if (image == null) {
                    image = DistroInstaller.fetchCatalog().firstOrNull { it.id == distro.id }
                        ?: throw IOException("${distro.name} ${distro.version}: no image for ${DistroInstaller.arch()}")
                }
            }
            org.cortex.terminal.R.string.setup_step_download -> {
                val img = image ?: throw IOException("no image")
                DistroInstaller.download(app, img, ::progress)
            }
            org.cortex.terminal.R.string.setup_step_unpack -> {
                UserAccount.setName(app, name)
                // whatever is active now waits in files/distros (also a failed install of this one, which is cleared)
                val active = DistroInstaller.active(app)
                if (active != null && active.id == distro.id && BootstrapManager.isBootstrapInstalled(app)) return
                if (active != null && active.id == distro.id) DistroInstaller.discardActive(app)
                if (DistroInstaller.makeActive(app, distro.id) && BootstrapManager.isBootstrapInstalled(app)) return
                val ok = if (distro.isBundled) {
                    BootstrapManager.installBootstrapFromAssets(app, ::progress)
                } else {
                    val img = image ?: throw IOException("no image")
                    val archive = java.io.File(app.cacheDir, img.file)
                    DistroInstaller.installArchive(app, archive, ::progress).also { if (it) archive.delete() }
                }
                if (!ok) throw IOException(app.getString(org.cortex.terminal.R.string.bootstrap_failed))
            }
            org.cortex.terminal.R.string.setup_step_configure -> {
                UserAccount.setSudoPassword(root, password)
                step.command?.let { command(app, it) }
            }
            else -> step.command?.let { command(app, it) }
        }
    }

    /** Ends every session (on the main thread, where their screen is), and waits for that */
    fun endSessions(app: Context) {
        // the desktop's X server runs in a session of its own, which ending the terminal's leaves
        if (org.cortex.terminal.desktop.DesktopAddon.isRunning(app)) {
            try {
                org.cortex.terminal.desktop.DesktopAddon.run(app, "cortex-desktop stop", 20_000)
            } catch (e: Exception) {
                Log.w(TAG, "Stopping the desktop failed", e)
            }
        }
        val done = java.util.concurrent.CountDownLatch(1)
        main.post {
            try {
                CortexService.getOrCreateSessionManager(app).destroyAll(forget = true)
            } finally {
                done.countDown()
            }
        }
        done.await(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** A setup command as root in the new system; its output's last lines are the error when it fails */
    private fun command(app: Context, command: String) {
        val tail = StringBuilder()
        val code = BootstrapManager.runBackgroundCommand(app, command) { out ->
            synchronized(tail) {
                tail.append(out)
                if (tail.length > 4000) tail.delete(0, tail.length - 4000)
            }
        }
        if (code != 0) {
            val lines = tail.toString().replace("\r", "").lines().filter { it.isNotBlank() }.takeLast(6)
            throw IOException("$command: exit $code\n" + lines.joinToString("\n"))
        }
    }
}
