package org.cortex.terminal.runtime

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Picks the Ubuntu ports mirror that answers fastest from where the phone is. Canonical's ports
 * servers are in Europe and North America, and reach some countries (China above all) only slowly.
 *
 * Every mirror is measured by fetching the noble-updates index; one is taken only when it is clearly
 * faster than the official server and has the current index (a few hours behind at most: mirrors
 * update a few times a day, some only once). Security updates always come from the official
 * server. A mirror cannot change a package unnoticed: apt checks every file against the archive's
 * signature.
 */
object MirrorSelector {
    private const val TAG = "MirrorSelector"

    const val OFFICIAL = "https://ports.ubuntu.com/ubuntu-ports/"

    /** Mirrors of ubuntu-ports that serve https (checked in October 2026). */
    val CANDIDATES = listOf(
        OFFICIAL,
        "https://mirror.yandex.ru/ubuntu-ports/",
        "https://ftp.tu-chemnitz.de/pub/linux/ubuntu-ports/",
        "https://free.nchc.org.tw/ubuntu-ports/",
        "https://mirror.csclub.uwaterloo.ca/ubuntu-ports/",
        "https://mirrors.ocf.berkeley.edu/ubuntu-ports/",
        "https://mirror.aarnet.edu.au/pub/ubuntu/ports/",
        "https://mirrors.ustc.edu.cn/ubuntu-ports/",
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/",
        "https://mirrors.aliyun.com/ubuntu-ports/"
    )

    /** A mirror replaces the official server only when it takes at most this share of its time. */
    private const val REQUIRED_SPEEDUP = 0.6

    /** How far a mirror's index may be behind the newest one. */
    private const val MAX_LAG_MS = 6 * 3600 * 1000L

    /** Written once the automatic choice has been made, so that it is not made on every start. */
    private const val CHOSEN_MARKER = "var/lib/cortex/mirror-chosen"

    private const val SOURCES = "etc/apt/sources.list.d/ubuntu.sources"

    private const val NONE_ANSWERED = "No mirror answered; the sources are unchanged.\n"
    private const val APT_BUSY = "apt or dpkg is running; the sources are unchanged.\n"

    /** A measured mirror: [millis] to fetch the index and the index's [date], null when it failed. */
    class Probe(val url: String, val millis: Long?, val date: Long?) {
        val ok: Boolean get() = millis != null && date != null
    }

    /**
     * The mirror to use, or null when none answered: the fastest one with a current index, if it is
     * clearly faster than the official server (or that did not answer).
     */
    internal fun choose(probes: List<Probe>): String? {
        val answered = probes.filter { it.ok }
        if (answered.isEmpty()) return null
        val official = answered.firstOrNull { it.url == OFFICIAL }
        val newest = official?.date ?: answered.maxOf { it.date!! }
        val current = answered.filter { it.date!! >= newest - MAX_LAG_MS }
        val fastest = current.minByOrNull { it.millis!! } ?: return null
        if (official == null) return fastest.url
        return if (fastest.url != OFFICIAL && fastest.millis!! < official.millis!! * REQUIRED_SPEEDUP) fastest.url else OFFICIAL
    }

    /** The "Date:" of a Release/InRelease file, in ms since the epoch. */
    internal fun releaseDate(text: String): Long? {
        val line = text.lineSequence().take(40).firstOrNull { it.startsWith("Date:") } ?: return null
        val value = line.removePrefix("Date:").trim().replace(Regex("\\s+"), " ")
        return try {
            SimpleDateFormat("EEE, d MMM yyyy H:mm:ss z", Locale.US).parse(value)?.time
        } catch (e: ParseException) {
            null
        }
    }

    /** Cortex's sources with [mirror] for the main suites; security from the official server. */
    internal fun sourcesFor(mirror: String): String =
        AptConfigurator.DEFAULT_UBUNTU_SOURCES.replaceFirst("URIs: $OFFICIAL", "URIs: $mirror")

    /** The mirror of sources Cortex wrote, or null when they were changed by hand. */
    internal fun mirrorOf(sources: String): String? = CANDIDATES.firstOrNull { sources == sourcesFor(it) }

    fun probe(mirror: String): Probe {
        var connection: HttpURLConnection? = null
        return try {
            val started = System.nanoTime()
            connection = URL(mirror + "dists/noble-updates/InRelease").openConnection() as HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 6000
            connection.setRequestProperty("User-Agent", "Cortex")
            if (connection.responseCode != 200) return Probe(mirror, null, null)
            val text = connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            Probe(mirror, (System.nanoTime() - started) / 1_000_000, releaseDate(text))
        } catch (e: Exception) {
            Probe(mirror, null, null)
        } finally {
            connection?.disconnect()
        }
    }

    /** Measures every candidate at once; about 10 s at most. */
    fun probeAll(): List<Probe> {
        val pool = Executors.newFixedThreadPool(CANDIDATES.size)
        return try {
            val futures = pool.invokeAll(CANDIDATES.map { url -> Callable { probe(url) } }, 15, TimeUnit.SECONDS)
            futures.mapIndexed { i, f -> if (f.isCancelled) Probe(CANDIDATES[i], null, null) else f.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    /** The mirror apt uses now, as a line of text. */
    fun status(root: File): String {
        val sources = File(root, SOURCES)
        val text = try { sources.readText() } catch (e: Exception) { "" }
        return when (val mirror = mirrorOf(text)) {
            null -> "custom sources in /$SOURCES"
            else -> mirror
        }
    }

    /**
     * Makes apt use [mirror]; false, and nothing changed, while apt or dpkg runs. When the server
     * changes, the package lists of the old one go: the apt wrapper fetches new ones before the next
     * install, which with the old lists still there it would not (and apt would find no packages).
     */
    fun setMirror(root: File, mirror: String): Boolean {
        val locks = FileLocks.tryLock(AptConfigurator.aptLockFiles(root)) ?: return false
        locks.use {
            val changed = status(root) != mirror
            FileInstaller.install(File(root, SOURCES), sourcesFor(mirror), 420) // 0644
            if (changed) {
                File(root, "var/lib/apt/lists").listFiles()?.forEach { f ->
                    if (f.isFile && f.name.contains("_Packages")) f.delete()
                }
            }
        }
        return true
    }

    /**
     * Measures the mirrors and sets the fastest. [keepCustom]: sources changed by hand are left as
     * they are. Returns a report, one line per mirror.
     */
    fun select(root: File, keepCustom: Boolean): String {
        val probes = probeAll()
        val report = StringBuilder()
        for (p in probes) {
            report.append(if (p.ok) "%6d ms  ".format(p.millis) else "  failed  ").append(p.url).append('\n')
        }
        val chosen = choose(probes)
        val current = try { File(root, SOURCES).readText() } catch (e: Exception) { "" }
        when {
            chosen == null -> report.append(NONE_ANSWERED)
            keepCustom && current.isNotEmpty() && mirrorOf(current) == null ->
                report.append("The sources were changed by hand; they are left as they are.\n")
            setMirror(root, chosen) -> report.append("Using $chosen\n")
            else -> report.append(APT_BUSY)
        }
        Log.i(TAG, report.toString().trimEnd())
        return report.toString()
    }

    /** The automatic choice after the first start, made once (again when no mirror answered or apt ran). */
    fun selectOnce(root: File) {
        val marker = File(root, CHOSEN_MARKER)
        if (marker.exists()) return
        val report = select(root, keepCustom = true)
        if (!report.endsWith(NONE_ANSWERED) && !report.endsWith(APT_BUSY)) {
            try {
                FileInstaller.install(marker, report, 384)
            } catch (e: Exception) {
                Log.w(TAG, "Could not write ${marker.path}", e)
            }
        }
    }
}
