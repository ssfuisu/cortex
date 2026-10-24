package org.cortex.terminal.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import org.cortex.terminal.CortexApp
import org.cortex.terminal.LocaleHelper
import org.cortex.terminal.MainActivity
import org.cortex.terminal.R
import org.cortex.terminal.runtime.Environment
import org.cortex.terminal.runtime.RootMode
import org.cortex.terminal.service.CortexService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ApiResult(val status: Int, val output: String)

/** The cortex-api commands; [run] is called on a worker thread for each request. */
object ApiCommands {
    private const val TAG = "CortexApi"
    private const val CHANNEL_ID = "cortex_api"
    private const val NOTIFICATION_TAG = "cortex-api:"

    private val main = Handler(Looper.getMainLooper())
    private val nextNotification = AtomicInteger(1)

    private val textExtensions = setOf(
        "", "c", "cc", "cpp", "h", "hpp", "rs", "go", "py", "rb", "pl", "php", "js", "ts", "kt", "java",
        "sh", "bash", "zsh", "fish", "conf", "cfg", "ini", "toml", "yaml", "yml", "md", "rst", "log",
        "csv", "tsv", "sql", "lua", "diff", "patch"
    )

    fun run(context: Context, req: ApiRequest): ApiResult = try {
        when (req.command) {
            "info" -> info(context)
            "battery" -> battery(context)
            "notification" -> notification(context, req)
            "notification-remove" -> notificationRemove(context, req)
            "clipboard-get" -> clipboardGet(context)
            "clipboard-set" -> clipboardSet(context, req)
            "toast" -> toast(context, req)
            "vibrate" -> vibrate(context, req)
            "open" -> open(context, req)
            "share" -> share(context, req)
            "tts" -> tts(context, req)
            "wakelock" -> wakelock(context, req)
            "mirror" -> mirror(context, req)
            "network" -> network(context)
            else -> fail("unknown command '${req.command}' (cortex-api --help lists them)")
        }
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "bad arguments")
    } catch (e: Exception) {
        Log.w(TAG, "${req.command} failed", e)
        fail("${req.command} failed: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun ok(text: String = ""): ApiResult =
        ApiResult(0, if (text.isEmpty() || text.endsWith("\n")) text else "$text\n")

    private fun fail(message: String): ApiResult = ApiResult(1, "cortex-api: $message\n")

    /** The operands as one text, or stdin when there are none. */
    private fun text(req: ApiRequest, args: ApiArgs): String =
        if (args.operands.isNotEmpty()) args.operands.joinToString(" ")
        else req.stdin?.trimEnd('\n') ?: throw IllegalArgumentException("no text: give it as an argument or on stdin")

    private fun <T> onMain(timeoutSeconds: Long = 10, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<Result<T>>()
        val latch = CountDownLatch(1)
        main.post {
            result.set(runCatching(block))
            latch.countDown()
        }
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) throw IllegalStateException("timed out")
        return result.get().getOrThrow()
    }

    // ---------------------------------------------------------------------------------------------

    private fun info(context: Context): ApiResult {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        return ok(
            Json.obj(
                "app" to context.packageName,
                "version" to version,
                "android" to Build.VERSION.RELEASE,
                "sdk" to Build.VERSION.SDK_INT,
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "abi" to Build.SUPPORTED_ABIS.firstOrNull(),
                "root_mode" to RootMode.isEnabled(context),
                "foreground" to CortexApp.isInForeground
            )
        )
    }

    /**
     * The phone's addresses in its networks (Wi-Fi first), one "interface address" line each, as the
     * apps see them (ip and ifconfig cannot ask Android for them).
     */
    private fun network(context: Context): ApiResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val lines = mutableListOf<Pair<Int, String>>()
        @Suppress("DEPRECATION") // allNetworks: the only list of networks before API 31
        val networks = cm.allNetworks
        for (network in networks) {
            val props = cm.getLinkProperties(network) ?: continue
            val caps = cm.getNetworkCapabilities(network)
            val rank = when {
                caps == null -> 3
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> 0
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
                else -> 2
            }
            val name = props.interfaceName ?: "?"
            for (link in props.linkAddresses) {
                val address = link.address
                if (address.isLoopbackAddress) continue
                lines += rank to "$name ${address.hostAddress?.substringBefore('%')}"
            }
        }
        if (lines.isEmpty()) return fail("no network")
        return ok(lines.sortedBy { it.first }.joinToString("\n") { it.second })
    }

    private fun battery(context: Context): ApiResult {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return fail("the battery state is not available")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
        val plugged = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            0 -> "none"
            else -> "other"
        }
        val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return ok(
            Json.obj(
                "percentage" to (if (level >= 0 && scale > 0) level * 100 / scale else null),
                "status" to status,
                "plugged" to plugged,
                "health" to health,
                "temperature" to (if (tenths != Int.MIN_VALUE) tenths / 10.0 else null)
            )
        )
    }

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notification(context: Context, req: ApiRequest): ApiResult {
        val args = ApiArgs.parse(req.args, withValue = setOf("-t", "--title", "-c", "--content", "-i", "--id"))
        if (args.operands.isNotEmpty()) throw IllegalArgumentException("unexpected '${args.operands.first()}' (the text goes after -c)")
        val title = args.value("-t", "--title") ?: "Cortex"
        val content = args.value("-c", "--content") ?: req.stdin?.trimEnd('\n') ?: ""
        val id = args.value("-i", "--id") ?: nextNotification.getAndIncrement().toString()

        val nm = notificationManager(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nm.areNotificationsEnabled()) {
            return fail("notifications are turned off for Cortex in Android's settings")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val lc = LocaleHelper.wrap(context)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, lc.getString(R.string.api_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = lc.getString(R.string.api_channel_description) }
            )
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder.setContentTitle(title)
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setAutoCancel(true)
        nm.notify(NOTIFICATION_TAG + id, 1, builder.build())
        return ok(id)
    }

    private fun notificationRemove(context: Context, req: ApiRequest): ApiResult {
        val id = req.args.singleOrNull() ?: throw IllegalArgumentException("usage: notification-remove ID")
        notificationManager(context).cancel(NOTIFICATION_TAG + id, 1)
        return ok()
    }

    private fun clipboard(context: Context): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun clipboardGet(context: Context): ApiResult {
        val text = onMain {
            val clip = clipboard(context).primaryClip
            if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(context)?.toString()
        }
        if (text == null && !CortexApp.isInForeground) {
            return fail("Android only lets the app on screen read the clipboard: switch to Cortex and try again")
        }
        return ok(text ?: "")
    }

    private fun clipboardSet(context: Context, req: ApiRequest): ApiResult {
        val text = text(req, ApiArgs.parse(req.args))
        onMain { clipboard(context).setPrimaryClip(ClipData.newPlainText("Cortex", text)) }
        return ok()
    }

    private fun toast(context: Context, req: ApiRequest): ApiResult {
        val args = ApiArgs.parse(req.args, flagNames = setOf("-l", "--long", "-s", "--short"))
        val text = text(req, args)
        val length = if (args.flag("-l", "--long")) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        onMain { Toast.makeText(context, text, length).show() }
        return ok()
    }

    private fun vibrate(context: Context, req: ApiRequest): ApiResult {
        val args = ApiArgs.parse(req.args, withValue = setOf("-d", "--duration"))
        val ms = (args.value("-d", "--duration") ?: "300").toLongOrNull()
            ?.takeIf { it in 1..10_000 } ?: throw IllegalArgumentException("the duration is 1 to 10000 ms")
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator == null || !vibrator.hasVibrator()) return fail("this device cannot vibrate")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(ms)
        }
        return ok()
    }

    /** A content URI other apps can read for a path as Ubuntu programs see it. */
    private fun fileUri(context: Context, path: String): Pair<Uri, String> {
        val file = File(ApiPaths.toAndroid(Environment.getCortexRoot(context).absolutePath, path))
        if (!file.exists()) throw IllegalArgumentException("no such file: $path")
        if (file.isDirectory) throw IllegalArgumentException("$path is a directory")
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("only files in Cortex and in shared storage can be opened by other apps")
        }
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (ext in textExtensions) "text/plain" else "application/octet-stream"
        return uri to mime
    }

    private fun startActivity(context: Context, intent: Intent, what: String): ApiResult {
        if (!CortexApp.isInForeground) {
            return fail("Android only lets the app on screen open other apps: switch to Cortex and try again")
        }
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ok()
        } catch (e: ActivityNotFoundException) {
            fail("no app can open $what")
        }
    }

    private fun open(context: Context, req: ApiRequest): ApiResult {
        val target = req.args.singleOrNull() ?: throw IllegalArgumentException("usage: open URL|FILE")
        val intent = if (ApiPaths.isUri(target)) {
            Intent(Intent.ACTION_VIEW, Uri.parse(target))
        } else {
            val (uri, mime) = fileUri(context, target)
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return startActivity(context, intent, target)
    }

    private fun share(context: Context, req: ApiRequest): ApiResult {
        val args = ApiArgs.parse(req.args, withValue = setOf("-t", "--title", "-f", "--file"))
        val file = args.value("-f", "--file")
        val send = Intent(Intent.ACTION_SEND)
        if (file != null) {
            if (args.operands.isNotEmpty()) throw IllegalArgumentException("share either a file (-f) or text")
            val (uri, mime) = fileUri(context, file)
            send.setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(null, uri)
        } else {
            send.setType("text/plain").putExtra(Intent.EXTRA_TEXT, text(req, args))
        }
        val chooser = Intent.createChooser(send, args.value("-t", "--title"))
        if (file != null) chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return startActivity(context, chooser, file ?: "text")
    }

    private fun tts(context: Context, req: ApiRequest): ApiResult {
        val text = text(req, ApiArgs.parse(req.args))
        val done = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        val engine = AtomicReference<TextToSpeech?>(null)
        val finish = { message: String? ->
            if (message != null) error.compareAndSet(null, message)
            engine.getAndSet(null)?.shutdown()
            done.countDown()
        }
        main.post {
            // The listener normally runs on this thread after the constructor returns, so the engine
            // is stored first; only a failure is reported from inside the constructor.
            val created = TextToSpeech(context) { status ->
                val tts = engine.get()
                if (status != TextToSpeech.SUCCESS || tts == null) {
                    finish("no text-to-speech engine is available")
                    return@TextToSpeech
                }
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        main.post { finish(null) }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post { finish("speaking failed") }
                    }
                })
                if (tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "cortex-api") != TextToSpeech.SUCCESS) {
                    finish("speaking failed")
                }
            }
            if (done.count == 0L) created.shutdown() else engine.set(created)
        }
        if (!done.await(120, TimeUnit.SECONDS)) {
            main.post { finish("timed out") }
            return fail("timed out")
        }
        return error.get()?.let { fail(it) } ?: ok()
    }

    private fun wakelock(context: Context, req: ApiRequest): ApiResult {
        val want = when (req.args.singleOrNull()) {
            "on" -> true
            "off" -> false
            "status", null -> return ok(if (CortexService.isWakeLockHeld) "on" else "off")
            else -> throw IllegalArgumentException("usage: wakelock on|off|status")
        }
        if (CortexService.isWakeLockHeld != want) {
            context.startService(Intent(context, CortexService::class.java).setAction(CortexService.ACTION_TOGGLE_WAKELOCK))
            val deadline = SystemClock.uptimeMillis() + 3000
            while (CortexService.isWakeLockHeld != want && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        }
        return ok(if (CortexService.isWakeLockHeld) "on" else "off")
    }

    /** The Ubuntu mirror apt uses: shown, chosen anew by measuring them, or back to the official one. */
    private fun mirror(context: Context, req: ApiRequest): ApiResult {
        val root = org.cortex.terminal.runtime.Environment.getCortexRoot(context)
        if (req.args.size > 1) throw IllegalArgumentException("usage: mirror [show|auto|official]")
        return when (req.args.firstOrNull() ?: "show") {
            "show" -> ok(org.cortex.terminal.runtime.MirrorSelector.status(root))
            "auto" -> ok(org.cortex.terminal.runtime.MirrorSelector.select(root, keepCustom = false))
            "official" ->
                if (org.cortex.terminal.runtime.MirrorSelector.setMirror(root, org.cortex.terminal.runtime.MirrorSelector.OFFICIAL)) {
                    ok(org.cortex.terminal.runtime.MirrorSelector.OFFICIAL)
                } else {
                    fail("apt or dpkg is running: try again when it has finished")
                }
            else -> throw IllegalArgumentException("usage: mirror [show|auto|official]")
        }
    }
}
