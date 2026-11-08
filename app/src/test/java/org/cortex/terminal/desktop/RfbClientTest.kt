package org.cortex.terminal.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The RFB client against a server played by the test, byte by byte as Xvnc sends them.
 */
class RfbClientTest {

    private class Recorder : RfbClient.Listener {
        val events = LinkedBlockingQueue<String>()
        var cursor: IntArray? = null
        override fun onResize(width: Int, height: Int) { events.add("resize $width $height") }
        override fun onUpdate(x: Int, y: Int, width: Int, height: Int) { events.add("update $x $y $width $height") }
        override fun onUpdateEnd() { events.add("end") }
        override fun onCursor(width: Int, height: Int, hotX: Int, hotY: Int, pixels: IntArray?) {
            cursor = pixels
            events.add("cursor $width $height $hotX $hotY")
        }
        override fun onClipboard(text: String) { events.add("clipboard $text") }
        override fun onBell() { events.add("bell") }

        fun next(): String = events.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no event")
    }

    /** A connected client and the server's ends of its streams. */
    private class Pair(val client: RfbClient, val recorder: Recorder, val toClient: DataOutputStream, val fromClient: DataInputStream)

    private fun connect(width: Int = 4, height: Int = 3, security: ByteArray = byteArrayOf(1)): Pair {
        val serverOut = PipedOutputStream()
        val clientIn = PipedInputStream(serverOut, 1 shl 20)
        val clientOut = PipedOutputStream()
        val serverIn = PipedInputStream(clientOut, 1 shl 20)
        val toClient = DataOutputStream(serverOut)
        val fromClient = DataInputStream(serverIn)
        val recorder = Recorder()
        val client = RfbClient(clientIn, clientOut, recorder)

        toClient.write("RFB 003.008\n".toByteArray())
        toClient.writeByte(security.size)
        toClient.write(security)
        toClient.writeInt(0) // security result: OK
        toClient.writeShort(width)
        toClient.writeShort(height)
        toClient.write(ByteArray(16))
        val name = "cortex:1".toByteArray()
        toClient.writeInt(name.size)
        toClient.write(name)
        toClient.flush()
        client.handshake()

        val version = ByteArray(12)
        fromClient.readFully(version)
        assertEquals("RFB 003.008\n", String(version))
        assertEquals(1, fromClient.readUnsignedByte()) // security None
        assertEquals(1, fromClient.readUnsignedByte()) // shared
        return Pair(client, recorder, toClient, fromClient)
    }

    /** SetPixelFormat, SetEncodings and the first update request, as the handshake sends them. */
    private fun readSetup(p: Pair): IntArray {
        assertEquals(RfbClient.MSG_SET_PIXEL_FORMAT, p.fromClient.readUnsignedByte())
        val format = ByteArray(19)
        p.fromClient.readFully(format)
        assertEquals(32, format[3].toInt()) // bits per pixel
        assertEquals(0, format[5].toInt()) // little endian
        assertEquals(16, format[13].toInt()) // red shift
        assertEquals(RfbClient.MSG_SET_ENCODINGS, p.fromClient.readUnsignedByte())
        p.fromClient.readUnsignedByte()
        val n = p.fromClient.readUnsignedShort()
        val encodings = IntArray(n) { p.fromClient.readInt() }
        readUpdateRequest(p, incremental = false)
        return encodings
    }

    private fun readUpdateRequest(p: Pair, incremental: Boolean) {
        assertEquals(RfbClient.MSG_FRAMEBUFFER_UPDATE_REQUEST, p.fromClient.readUnsignedByte())
        assertEquals(if (incremental) 1 else 0, p.fromClient.readUnsignedByte())
        p.fromClient.readFully(ByteArray(8))
    }

    private fun DataOutputStream.rect(x: Int, y: Int, w: Int, h: Int, encoding: Int) {
        writeShort(x); writeShort(y); writeShort(w); writeShort(h); writeInt(encoding)
    }

    private fun startReader(client: RfbClient): Thread =
        Thread { try { client.run() } catch (e: IOException) { } }.also { it.isDaemon = true; it.start() }

    @Test
    fun handshakeAsksForRawCopyRectResizeCursorAndClipboard() {
        val p = connect()
        val encodings = readSetup(p)
        assertEquals("resize 4 3", p.recorder.next())
        assertEquals("cortex:1", p.client.desktopName)
        for (e in listOf(RfbClient.ENCODING_RAW, RfbClient.ENCODING_COPY_RECT, RfbClient.PSEUDO_EXTENDED_DESKTOP_SIZE,
            RfbClient.PSEUDO_CURSOR, RfbClient.PSEUDO_EXTENDED_CLIPBOARD)) {
            assertTrue("encoding $e", encodings.contains(e))
        }
    }

    @Test(expected = IOException::class)
    fun aServerThatWantsAPasswordIsRefused() {
        connect(security = byteArrayOf(2)) // VNC authentication only
    }

    @Test
    fun rawAndCopyRectFillTheFramebuffer() {
        val p = connect()
        readSetup(p)
        p.recorder.next()
        startReader(p.client)
        // Raw 2x1 at (1,1): blue then red, as little-endian 0x00RRGGBB
        p.toClient.writeByte(RfbClient.MSG_FRAMEBUFFER_UPDATE)
        p.toClient.writeByte(0)
        p.toClient.writeShort(2)
        p.toClient.rect(1, 1, 2, 1, RfbClient.ENCODING_RAW)
        p.toClient.write(byteArrayOf(0xff.toByte(), 0, 0, 0, 0, 0, 0xff.toByte(), 0))
        // CopyRect: the same two pixels to (2,2)
        p.toClient.rect(2, 2, 2, 1, RfbClient.ENCODING_COPY_RECT)
        p.toClient.writeShort(1)
        p.toClient.writeShort(1)
        p.toClient.flush()
        assertEquals("update 1 1 2 1", p.recorder.next())
        assertEquals("update 2 2 2 1", p.recorder.next())
        assertEquals("end", p.recorder.next())
        readUpdateRequest(p, incremental = false) // the first after a resize is a full one
        synchronized(p.client.lock) {
            val px = p.client.pixels
            assertEquals(0xff0000ff.toInt(), px[1 * 4 + 1])
            assertEquals(0xffff0000.toInt(), px[1 * 4 + 2])
            assertEquals(0xff0000ff.toInt(), px[2 * 4 + 2])
            assertEquals(0xffff0000.toInt(), px[2 * 4 + 3])
            assertEquals(0, px[0])
        }
    }

    @Test
    fun cursorShapeUsesItsMask() {
        val p = connect()
        readSetup(p)
        p.recorder.next()
        startReader(p.client)
        p.toClient.writeByte(RfbClient.MSG_FRAMEBUFFER_UPDATE)
        p.toClient.writeByte(0)
        p.toClient.writeShort(1)
        p.toClient.rect(1, 0, 2, 1, RfbClient.PSEUDO_CURSOR) // hot spot (1,0), 2x1
        p.toClient.write(byteArrayOf(0, 0xff.toByte(), 0, 0, 0, 0xff.toByte(), 0, 0))
        p.toClient.writeByte(0x80) // only the first pixel is shown
        p.toClient.flush()
        assertEquals("cursor 2 1 1 0", p.recorder.next())
        assertArrayEquals(intArrayOf(0xff00ff00.toInt(), 0), p.recorder.cursor)
    }

    @Test
    fun extendedDesktopSizeResizesAndAllowsRequests() {
        val p = connect()
        readSetup(p)
        p.recorder.next()
        startReader(p.client)
        p.toClient.writeByte(RfbClient.MSG_FRAMEBUFFER_UPDATE)
        p.toClient.writeByte(0)
        p.toClient.writeShort(1)
        p.toClient.rect(0, 0, 8, 6, RfbClient.PSEUDO_EXTENDED_DESKTOP_SIZE)
        p.toClient.writeByte(1)
        p.toClient.write(ByteArray(3))
        p.toClient.writeInt(7) // screen id
        p.toClient.writeShort(0); p.toClient.writeShort(0); p.toClient.writeShort(8); p.toClient.writeShort(6)
        p.toClient.writeInt(0)
        p.toClient.flush()
        assertEquals("resize 8 6", p.recorder.next())
        assertEquals("end", p.recorder.next())
        readUpdateRequest(p, incremental = false)
        assertTrue(p.client.supportsResize)
        assertTrue(p.client.requestSize(10, 12))
        assertEquals(RfbClient.MSG_SET_DESKTOP_SIZE, p.fromClient.readUnsignedByte())
        p.fromClient.readUnsignedByte()
        assertEquals(10, p.fromClient.readUnsignedShort())
        assertEquals(12, p.fromClient.readUnsignedShort())
        assertEquals(1, p.fromClient.readUnsignedByte())
        p.fromClient.readUnsignedByte()
        assertEquals(7, p.fromClient.readInt())
    }

    @Test
    fun sizeAskedBeforeTheServerCanIsSentWhenItCan() {
        val p = connect()
        readSetup(p)
        p.recorder.next()
        // asked for right after the handshake, before the server said it can change its size
        assertTrue(!p.client.requestSize(10, 12))
        startReader(p.client)
        p.toClient.writeByte(RfbClient.MSG_FRAMEBUFFER_UPDATE)
        p.toClient.writeByte(0)
        p.toClient.writeShort(1)
        p.toClient.rect(0, 0, 8, 6, RfbClient.PSEUDO_EXTENDED_DESKTOP_SIZE)
        p.toClient.writeByte(1)
        p.toClient.write(ByteArray(3))
        p.toClient.writeInt(7)
        p.toClient.writeShort(0); p.toClient.writeShort(0); p.toClient.writeShort(8); p.toClient.writeShort(6)
        p.toClient.writeInt(0)
        p.toClient.flush()
        assertEquals(RfbClient.MSG_SET_DESKTOP_SIZE, p.fromClient.readUnsignedByte())
        p.fromClient.readUnsignedByte()
        assertEquals(10, p.fromClient.readUnsignedShort())
        assertEquals(12, p.fromClient.readUnsignedShort())
    }

    @Test
    fun keysAndPointerAreSentAsRfbMessages() {
        val p = connect()
        readSetup(p)
        p.client.sendKey(0x01000131, true) // ı
        assertEquals(RfbClient.MSG_KEY_EVENT, p.fromClient.readUnsignedByte())
        assertEquals(1, p.fromClient.readUnsignedByte())
        p.fromClient.readUnsignedShort()
        assertEquals(0x01000131, p.fromClient.readInt())
        p.client.sendPointer(1, 300, 70000)
        assertEquals(RfbClient.MSG_POINTER_EVENT, p.fromClient.readUnsignedByte())
        assertEquals(1, p.fromClient.readUnsignedByte())
        assertEquals(300, p.fromClient.readUnsignedShort())
        assertEquals(0xffff, p.fromClient.readUnsignedShort())
    }

    @Test
    fun extendedClipboardCarriesUtf8BothWays() {
        val p = connect()
        readSetup(p)
        p.recorder.next()
        startReader(p.client)
        // Server's capabilities -> client answers with its own
        p.toClient.writeByte(RfbClient.MSG_SERVER_CUT_TEXT)
        p.toClient.write(ByteArray(3))
        p.toClient.writeInt(-(4 + 4))
        p.toClient.writeInt(RfbClient.CLIP_ACTION_CAPS or RfbClient.CLIP_FORMAT_TEXT or RfbClient.CLIP_ACTION_PROVIDE)
        p.toClient.writeInt(1 shl 20)
        p.toClient.flush()
        assertEquals(RfbClient.MSG_CLIENT_CUT_TEXT, p.fromClient.readUnsignedByte())
        p.fromClient.readFully(ByteArray(3))
        assertEquals(-8, p.fromClient.readInt())
        val caps = p.fromClient.readInt()
        assertTrue(caps and RfbClient.CLIP_ACTION_CAPS != 0 && caps and RfbClient.CLIP_FORMAT_TEXT != 0)
        p.fromClient.readInt()

        // Server provides text
        val provided = RfbClient.providedText("Merhaba dünya\nşğı")
        p.toClient.writeByte(RfbClient.MSG_SERVER_CUT_TEXT)
        p.toClient.write(ByteArray(3))
        p.toClient.writeInt(-(4 + provided.size))
        p.toClient.writeInt(RfbClient.CLIP_ACTION_PROVIDE or RfbClient.CLIP_FORMAT_TEXT)
        p.toClient.write(provided)
        p.toClient.flush()
        assertEquals("clipboard Merhaba dünya\nşğı", p.recorder.next())

        // Android's clipboard: notify, the server requests, the client provides
        p.client.setClipboard("çay ☕")
        assertEquals(RfbClient.MSG_CLIENT_CUT_TEXT, p.fromClient.readUnsignedByte())
        p.fromClient.readFully(ByteArray(3))
        assertEquals(-4, p.fromClient.readInt())
        assertEquals(RfbClient.CLIP_ACTION_NOTIFY or RfbClient.CLIP_FORMAT_TEXT, p.fromClient.readInt())
        p.toClient.writeByte(RfbClient.MSG_SERVER_CUT_TEXT)
        p.toClient.write(ByteArray(3))
        p.toClient.writeInt(-4)
        p.toClient.writeInt(RfbClient.CLIP_ACTION_REQUEST or RfbClient.CLIP_FORMAT_TEXT)
        p.toClient.flush()
        assertEquals(RfbClient.MSG_CLIENT_CUT_TEXT, p.fromClient.readUnsignedByte())
        p.fromClient.readFully(ByteArray(3))
        val size = -p.fromClient.readInt()
        assertEquals(RfbClient.CLIP_ACTION_PROVIDE or RfbClient.CLIP_FORMAT_TEXT, p.fromClient.readInt())
        val payload = ByteArray(size - 4)
        p.fromClient.readFully(payload)
        assertEquals("çay ☕", RfbClient.inflatedText(payload))
    }

    @Test
    fun plainCutTextIsLatin1() {
        val p = connect()
        readSetup(p)
        p.recorder.next()
        startReader(p.client)
        p.toClient.writeByte(RfbClient.MSG_SERVER_CUT_TEXT)
        p.toClient.write(ByteArray(3))
        val text = "café\r\nok".toByteArray(Charsets.ISO_8859_1)
        p.toClient.writeInt(text.size)
        p.toClient.write(text)
        p.toClient.flush()
        assertEquals("clipboard café\nok", p.recorder.next())
        p.client.setClipboard("aş") // no extended clipboard: Latin-1, '?' for the rest
        assertEquals(RfbClient.MSG_CLIENT_CUT_TEXT, p.fromClient.readUnsignedByte())
        p.fromClient.readFully(ByteArray(3))
        assertEquals(2, p.fromClient.readInt())
        val out = ByteArray(2)
        p.fromClient.readFully(out)
        assertEquals("a?", String(out, Charsets.ISO_8859_1))
    }

    @Test
    fun providedTextRoundTrips() {
        val long = "x".repeat(100_000) + "\n" + "ğ".repeat(10)
        assertEquals(long, RfbClient.inflatedText(RfbClient.providedText(long)))
        assertEquals(null, RfbClient.inflatedText(byteArrayOf(1, 2, 3)))
        assertNotNull(ByteArrayOutputStream())
    }
}
