package org.cortex.terminal.desktop

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A VNC client (RFB 3.8) for the X server of Cortex's desktop: Ubuntu's Xvnc, reached over a Unix
 * socket only the app can open, so it asks for no password ("None" security). The framebuffer is kept
 * as ARGB ints (as Android's Bitmap.setPixels() takes them); pixels come as Raw rectangles (no
 * compression: the socket is in the same phone) and CopyRect, the cursor's shape as its own pseudo
 * rectangle, the size of the screen through ExtendedDesktopSize (the client can ask for a new one when
 * the phone turns), and the clipboard as UTF-8 through TigerVNC's extended clipboard.
 *
 * Plain Kotlin on streams, so that it is tested on a computer against a real Xvnc.
 */
class RfbClient(input: InputStream, output: OutputStream, private val listener: Listener) {

    interface Listener {
        /** The framebuffer has a new size (also once after the handshake). */
        fun onResize(width: Int, height: Int) {}

        /** Pixels changed inside the rectangle; [onUpdateEnd] follows the last one of an update. */
        fun onUpdate(x: Int, y: Int, width: Int, height: Int) {}

        fun onUpdateEnd() {}

        /** The shape of the pointer: ARGB pixels (transparent where the mask is clear), or null for none. */
        fun onCursor(width: Int, height: Int, hotX: Int, hotY: Int, pixels: IntArray?) {}

        fun onClipboard(text: String) {}

        fun onBell() {}
    }

    private val ins = DataInputStream(BufferedInputStream(input, 1 shl 16))
    private val out = DataOutputStream(BufferedOutputStream(output, 8192))

    /** Guards [pixels], [width] and [height]: the reader thread writes them, the screen reads them. */
    val lock = Any()

    @Volatile
    var width = 0
        private set

    @Volatile
    var height = 0
        private set

    var pixels = IntArray(0)
        private set

    var desktopName = ""
        private set

    @Volatile
    var supportsResize = false
        private set

    @Volatile
    private var extendedClipboard = false

    @Volatile
    private var localClipboard: String? = null

    private var screenId = 0L
    private var screenFlags = 0L
    private var fullUpdateNext = false
    private var rowBuffer = ByteArray(0)

    /** Version, security ("None"), the server's description, then our pixel format and encodings. */
    fun handshake() {
        val version = ByteArray(12)
        ins.readFully(version)
        val text = String(version, Charsets.ISO_8859_1)
        if (!text.startsWith("RFB ")) throw IOException("not a VNC server: ${text.trim()}")
        val major = text.substring(4, 7).toIntOrNull() ?: 0
        val minor = text.substring(8, 11).toIntOrNull() ?: 0
        if (major < 3 || (major == 3 && minor < 8)) throw IOException("the server speaks ${text.trim()}, 3.8 is needed")
        out.write("RFB 003.008\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()

        val count = ins.readUnsignedByte()
        if (count == 0) throw IOException("the server refused the connection: ${readReason()}")
        val types = ByteArray(count)
        ins.readFully(types)
        if (types.none { it.toInt() == SECURITY_NONE }) {
            throw IOException("the server asks for a password (security types ${types.map { it.toInt() and 0xff }})")
        }
        out.writeByte(SECURITY_NONE)
        out.flush()
        if (ins.readInt() != 0) throw IOException("the server refused the connection: ${readReason()}")

        out.writeByte(1) // shared: other viewers stay connected
        out.flush()
        val w = ins.readUnsignedShort()
        val h = ins.readUnsignedShort()
        ins.readFully(ByteArray(16)) // the server's pixel format; ours follows
        val nameLength = ins.readInt()
        if (nameLength < 0 || nameLength > 1 shl 16) throw IOException("bad desktop name length $nameLength")
        val name = ByteArray(nameLength)
        ins.readFully(name)
        desktopName = String(name, Charsets.UTF_8)
        resize(w, h)

        send {
            // 32 bits per pixel, depth 24, little endian, true colour: 0x00RRGGBB as an int
            writeByte(MSG_SET_PIXEL_FORMAT)
            write(ByteArray(3))
            writeByte(32)
            writeByte(24)
            writeByte(0)
            writeByte(1)
            writeShort(255)
            writeShort(255)
            writeShort(255)
            writeByte(16)
            writeByte(8)
            writeByte(0)
            write(ByteArray(3))
            val encodings = intArrayOf(
                ENCODING_COPY_RECT, ENCODING_RAW, PSEUDO_EXTENDED_DESKTOP_SIZE, PSEUDO_DESKTOP_SIZE,
                PSEUDO_CURSOR, PSEUDO_LAST_RECT, PSEUDO_EXTENDED_CLIPBOARD
            )
            writeByte(MSG_SET_ENCODINGS)
            writeByte(0)
            writeShort(encodings.size)
            for (e in encodings) writeInt(e)
            writeUpdateRequest(false)
        }
    }

    /** Reads the server's messages until the connection ends (an IOException). */
    fun run() {
        while (true) {
            when (val type = ins.readUnsignedByte()) {
                MSG_FRAMEBUFFER_UPDATE -> readFramebufferUpdate()
                MSG_SET_COLOUR_MAP -> {
                    ins.readUnsignedByte()
                    ins.readUnsignedShort()
                    val n = ins.readUnsignedShort()
                    ins.readFully(ByteArray(n * 6))
                }
                MSG_BELL -> listener.onBell()
                MSG_SERVER_CUT_TEXT -> readServerCutText()
                else -> throw IOException("unknown message $type from the server")
            }
        }
    }

    fun sendPointer(buttons: Int, x: Int, y: Int) = send {
        writeByte(MSG_POINTER_EVENT)
        writeByte(buttons and 0xff)
        writeShort(x.coerceIn(0, 0xffff))
        writeShort(y.coerceIn(0, 0xffff))
    }

    fun sendKey(keysym: Int, down: Boolean) = send {
        writeByte(MSG_KEY_EVENT)
        writeByte(if (down) 1 else 0)
        writeShort(0)
        writeInt(keysym)
    }

    /** Asks the X server for a screen of this size (Xvnc changes it with RandR); false when it cannot. */
    fun requestSize(w: Int, h: Int): Boolean {
        if (!supportsResize || w <= 0 || h <= 0) return false
        send {
            writeByte(MSG_SET_DESKTOP_SIZE)
            writeByte(0)
            writeShort(w)
            writeShort(h)
            writeByte(1)
            writeByte(0)
            writeInt(screenId.toInt())
            writeShort(0)
            writeShort(0)
            writeShort(w)
            writeShort(h)
            writeInt(screenFlags.toInt())
        }
        return true
    }

    /** Android's clipboard changed: the X side gets it (it asks for the text when it needs it). */
    fun setClipboard(text: String) {
        localClipboard = text
        if (extendedClipboard) {
            send { writeExtendedClipboard(CLIP_ACTION_NOTIFY or CLIP_FORMAT_TEXT, ByteArray(0)) }
        } else {
            val latin1 = ByteArray(text.length) { i -> text[i].code.let { if (it in 0..255) it.toByte() else '?'.code.toByte() } }
            send {
                writeByte(MSG_CLIENT_CUT_TEXT)
                write(ByteArray(3))
                writeInt(latin1.size)
                write(latin1)
            }
        }
    }

    // ---- server messages ------------------------------------------------------------------------

    private fun readFramebufferUpdate() {
        ins.readUnsignedByte()
        val rects = ins.readUnsignedShort()
        for (i in 0 until rects) {
            val x = ins.readUnsignedShort()
            val y = ins.readUnsignedShort()
            val w = ins.readUnsignedShort()
            val h = ins.readUnsignedShort()
            when (val encoding = ins.readInt()) {
                ENCODING_RAW -> readRaw(x, y, w, h)
                ENCODING_COPY_RECT -> readCopyRect(x, y, w, h)
                PSEUDO_CURSOR -> readCursor(x, y, w, h)
                PSEUDO_DESKTOP_SIZE -> resize(w, h)
                PSEUDO_EXTENDED_DESKTOP_SIZE -> readExtendedDesktopSize(x, y, w, h)
                PSEUDO_LAST_RECT -> break
                else -> throw IOException("unexpected encoding $encoding")
            }
        }
        listener.onUpdateEnd()
        val full = fullUpdateNext
        fullUpdateNext = false
        send { writeUpdateRequest(!full) }
    }

    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        val rowBytes = w * 4
        if (rowBuffer.size < rowBytes) rowBuffer = ByteArray(rowBytes)
        val row = rowBuffer
        for (r in 0 until h) {
            ins.readFully(row, 0, rowBytes)
            synchronized(lock) {
                val py = y + r
                if (py < height) {
                    val cols = minOf(w, width - x)
                    var at = py * width + x
                    var b = 0
                    for (c in 0 until cols) {
                        pixels[at++] = OPAQUE or
                            (row[b].toInt() and 0xff) or
                            ((row[b + 1].toInt() and 0xff) shl 8) or
                            ((row[b + 2].toInt() and 0xff) shl 16)
                        b += 4
                    }
                }
            }
        }
        listener.onUpdate(x, y, w, h)
    }

    private fun readCopyRect(x: Int, y: Int, w: Int, h: Int) {
        val sx = ins.readUnsignedShort()
        val sy = ins.readUnsignedShort()
        synchronized(lock) {
            if (x + w > width || y + h > height || sx + w > width || sy + h > height) return@synchronized
            // Rows in the order that keeps an overlapping source intact
            val rows = if (sy < y) (h - 1 downTo 0) else (0 until h)
            for (r in rows) System.arraycopy(pixels, (sy + r) * width + sx, pixels, (y + r) * width + x, w)
        }
        listener.onUpdate(x, y, w, h)
    }

    private fun readCursor(hotX: Int, hotY: Int, w: Int, h: Int) {
        if (w == 0 || h == 0) {
            listener.onCursor(0, 0, 0, 0, null)
            return
        }
        val data = ByteArray(w * h * 4)
        ins.readFully(data)
        val maskRow = (w + 7) / 8
        val mask = ByteArray(maskRow * h)
        ins.readFully(mask)
        val argb = IntArray(w * h)
        for (py in 0 until h) {
            for (px in 0 until w) {
                val visible = (mask[py * maskRow + px / 8].toInt() shr (7 - px % 8)) and 1 == 1
                if (!visible) continue
                val b = (py * w + px) * 4
                argb[py * w + px] = OPAQUE or (data[b].toInt() and 0xff) or
                    ((data[b + 1].toInt() and 0xff) shl 8) or ((data[b + 2].toInt() and 0xff) shl 16)
            }
        }
        listener.onCursor(w, h, hotX, hotY, argb)
    }

    private fun readExtendedDesktopSize(reason: Int, status: Int, w: Int, h: Int) {
        val screens = ins.readUnsignedByte()
        ins.readFully(ByteArray(3))
        for (i in 0 until screens) {
            val id = ins.readInt().toLong() and 0xffffffffL
            ins.readFully(ByteArray(8)) // x, y, width, height of the screen
            val flags = ins.readInt().toLong() and 0xffffffffL
            if (i == 0) {
                screenId = id
                screenFlags = flags
            }
        }
        supportsResize = true
        // reason 1 is the answer to our own request; a status other than 0 refused it
        if (reason == 1 && status != 0) return
        if (w != width || h != height) resize(w, h)
    }

    private fun resize(w: Int, h: Int) {
        synchronized(lock) {
            width = w
            height = h
            pixels = IntArray(w * h)
        }
        fullUpdateNext = true
        listener.onResize(w, h)
    }

    private fun readServerCutText() {
        ins.readFully(ByteArray(3))
        val length = ins.readInt()
        if (length >= 0) {
            if (length > MAX_CLIPBOARD) throw IOException("clipboard of $length bytes")
            val data = ByteArray(length)
            ins.readFully(data)
            listener.onClipboard(String(data, Charsets.ISO_8859_1).replace("\r\n", "\n"))
            return
        }
        val size = -length
        if (size < 4 || size > MAX_CLIPBOARD) throw IOException("extended clipboard message of $size bytes")
        val flags = ins.readInt()
        val payload = ByteArray(size - 4)
        ins.readFully(payload)
        handleExtendedClipboard(flags, payload)
    }

    private fun handleExtendedClipboard(flags: Int, payload: ByteArray) {
        val textFormat = flags and CLIP_FORMAT_TEXT != 0
        when {
            flags and CLIP_ACTION_CAPS != 0 -> {
                extendedClipboard = true
                // Our capabilities: text, with every action, up to 1 MB
                val caps = ByteArray(4).also { writeIntTo(it, 0, MAX_CLIPBOARD) }
                send {
                    writeExtendedClipboard(
                        CLIP_ACTION_CAPS or CLIP_ACTION_REQUEST or CLIP_ACTION_PEEK or CLIP_ACTION_NOTIFY or
                            CLIP_ACTION_PROVIDE or CLIP_FORMAT_TEXT,
                        caps
                    )
                }
            }
            flags and CLIP_ACTION_NOTIFY != 0 -> if (textFormat) {
                send { writeExtendedClipboard(CLIP_ACTION_REQUEST or CLIP_FORMAT_TEXT, ByteArray(0)) }
            }
            flags and CLIP_ACTION_REQUEST != 0 -> if (textFormat) {
                val text = localClipboard
                if (text != null) send { writeExtendedClipboard(CLIP_ACTION_PROVIDE or CLIP_FORMAT_TEXT, providedText(text)) }
            }
            flags and CLIP_ACTION_PEEK != 0 -> {
                val formats = if (localClipboard != null) CLIP_FORMAT_TEXT else 0
                send { writeExtendedClipboard(CLIP_ACTION_NOTIFY or formats, ByteArray(0)) }
            }
            flags and CLIP_ACTION_PROVIDE != 0 -> if (textFormat) {
                val text = inflatedText(payload)
                if (text != null) listener.onClipboard(text)
            }
        }
    }

    // ---- client messages ------------------------------------------------------------------------

    private fun DataOutputStream.writeUpdateRequest(incremental: Boolean) {
        writeByte(MSG_FRAMEBUFFER_UPDATE_REQUEST)
        writeByte(if (incremental) 1 else 0)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
    }

    private fun DataOutputStream.writeExtendedClipboard(flags: Int, payload: ByteArray) {
        writeByte(MSG_CLIENT_CUT_TEXT)
        write(ByteArray(3))
        writeInt(-(4 + payload.size))
        writeInt(flags)
        write(payload)
    }

    /** One message at a time, from any thread. */
    private fun send(block: DataOutputStream.() -> Unit) {
        synchronized(out) {
            out.block()
            out.flush()
        }
    }

    private fun readReason(): String {
        val length = ins.readInt()
        if (length < 0 || length > 1 shl 16) return "(no reason)"
        val data = ByteArray(length)
        ins.readFully(data)
        return String(data, Charsets.UTF_8)
    }

    companion object {
        const val SECURITY_NONE = 1

        const val MSG_FRAMEBUFFER_UPDATE = 0
        const val MSG_SET_COLOUR_MAP = 1
        const val MSG_BELL = 2
        const val MSG_SERVER_CUT_TEXT = 3

        const val MSG_SET_PIXEL_FORMAT = 0
        const val MSG_SET_ENCODINGS = 2
        const val MSG_FRAMEBUFFER_UPDATE_REQUEST = 3
        const val MSG_KEY_EVENT = 4
        const val MSG_POINTER_EVENT = 5
        const val MSG_CLIENT_CUT_TEXT = 6
        const val MSG_SET_DESKTOP_SIZE = 251

        const val ENCODING_RAW = 0
        const val ENCODING_COPY_RECT = 1
        const val PSEUDO_CURSOR = -239
        const val PSEUDO_DESKTOP_SIZE = -223
        const val PSEUDO_LAST_RECT = -224
        const val PSEUDO_EXTENDED_DESKTOP_SIZE = -308
        const val PSEUDO_EXTENDED_CLIPBOARD = -1063131698 // 0xC0A1E5CE

        const val CLIP_FORMAT_TEXT = 1
        const val CLIP_ACTION_CAPS = 1 shl 24
        const val CLIP_ACTION_REQUEST = 1 shl 25
        const val CLIP_ACTION_PEEK = 1 shl 26
        const val CLIP_ACTION_NOTIFY = 1 shl 27
        const val CLIP_ACTION_PROVIDE = 1 shl 28

        const val MAX_CLIPBOARD = 1 shl 20
        const val OPAQUE = -0x1000000 // 0xff000000

        /** The "provide" payload for text: zlib of the UTF-8 length (with its NUL) and the text, CRLF ends. */
        fun providedText(text: String): ByteArray {
            val utf8 = (text.replace("\r\n", "\n").replace("\n", "\r\n") + "\u0000").toByteArray(Charsets.UTF_8)
            val plain = ByteArray(4 + utf8.size)
            writeIntTo(plain, 0, utf8.size)
            System.arraycopy(utf8, 0, plain, 4, utf8.size)
            val deflater = Deflater()
            deflater.setInput(plain)
            deflater.finish()
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (!deflater.finished()) buffer.write(chunk, 0, deflater.deflate(chunk))
            deflater.end()
            return buffer.toByteArray()
        }

        /** The text of a "provide" payload, or null when it holds none. */
        fun inflatedText(payload: ByteArray): String? {
            val inflater = Inflater()
            try {
                inflater.setInput(payload)
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (!inflater.finished() && buffer.size() <= MAX_CLIPBOARD + 4) {
                    val n = inflater.inflate(chunk)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    buffer.write(chunk, 0, n)
                }
                val plain = buffer.toByteArray()
                if (plain.size < 4) return null
                val length = ((plain[0].toInt() and 0xff) shl 24) or ((plain[1].toInt() and 0xff) shl 16) or
                    ((plain[2].toInt() and 0xff) shl 8) or (plain[3].toInt() and 0xff)
                if (length <= 0 || length > plain.size - 4) return null
                var end = 4 + length
                if (plain[end - 1].toInt() == 0) end--
                return String(plain, 4, end - 4, Charsets.UTF_8).replace("\r\n", "\n")
            } catch (e: java.util.zip.DataFormatException) {
                return null
            } finally {
                inflater.end()
            }
        }

        private fun writeIntTo(target: ByteArray, at: Int, value: Int) {
            target[at] = (value ushr 24).toByte()
            target[at + 1] = (value ushr 16).toByte()
            target[at + 2] = (value ushr 8).toByte()
            target[at + 3] = value.toByte()
        }
    }
}
