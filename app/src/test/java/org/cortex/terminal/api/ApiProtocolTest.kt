package org.cortex.terminal.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ApiProtocolTest {

    /** Builds a request the way the shell client does (lengths are UTF-8 byte counts). */
    private fun request(token: String, command: String, args: List<String>, stdin: String?): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        w("CORTEX-API 1\n$token\n$command\n${args.size}\n")
        for (a in args) {
            w("${a.toByteArray(Charsets.UTF_8).size}\n")
            w(a)
        }
        if (stdin == null) w("-1\n") else {
            w("${stdin.toByteArray(Charsets.UTF_8).size}\n")
            w(stdin)
        }
        return out.toByteArray()
    }

    private fun parse(bytes: ByteArray) = ApiProtocol.read(ByteArrayInputStream(bytes))

    private fun assertRejected(bytes: ByteArray) {
        try {
            parse(bytes)
            fail("request was accepted")
        } catch (e: ApiProtocol.ProtocolException) {
            // expected
        }
    }

    @Test
    fun readsArgumentsAndStdinByTheirByteLength() {
        val args = listOf("-t", "Merhaba dünya ✓", "line\nbreak", "")
        val req = parse(request("secret", "notification", args, "çıktı\n"))
        assertEquals("secret", req.token)
        assertEquals("notification", req.command)
        assertEquals(args, req.args)
        assertEquals("çıktı\n", req.stdin)
    }

    @Test
    fun noStdinIsMinusOne() {
        val req = parse(request("t", "battery", emptyList(), null))
        assertTrue(req.args.isEmpty())
        assertNull(req.stdin)
    }

    @Test
    fun malformedRequestsAreRejected() {
        assertRejected("HTTP/1.1 GET /\n".toByteArray())
        assertRejected("CORTEX-API 1\ntoken\n".toByteArray()) // ends early
        assertRejected("CORTEX-API 1\nt\nopen\n1\n10\nshort".toByteArray()) // argument shorter than said
        assertRejected("CORTEX-API 1\nt\nopen\n${ApiProtocol.MAX_ARGS + 1}\n".toByteArray())
        assertRejected("CORTEX-API 1\nt\nopen\n1\n${ApiProtocol.MAX_ARG_BYTES + 1}\n".toByteArray())
        assertRejected("CORTEX-API 1\nt\nopen\nx\n".toByteArray())
        assertRejected(("CORTEX-API 1\n" + "a".repeat(10_000) + "\n").toByteArray()) // line too long
    }

    @Test
    fun aWrongTokenStopsTheReading() {
        // Without the check after the token line, the rest would be read (and found malformed).
        val bytes = "CORTEX-API 1\nwrong\nopen\nnot-a-number\n".toByteArray()
        try {
            ApiProtocol.read(ByteArrayInputStream(bytes)) { it == "secret" }
            fail("request was accepted")
        } catch (e: ApiProtocol.Unauthorized) {
            // expected
        }
        val req = ApiProtocol.read(ByteArrayInputStream(request("secret", "info", emptyList(), null))) { it == "secret" }
        assertEquals("info", req.command)
    }

    @Test
    fun responseIsTheStatusLineThenTheOutput() {
        val out = ByteArrayOutputStream()
        ApiProtocol.writeResponse(out, 3, "olmadı\n")
        assertEquals("3\nolmadı\n", out.toString("UTF-8"))
    }

    @Test
    fun tokenComparison() {
        assertTrue(ApiProtocol.tokenMatches("abc123", "abc123"))
        assertFalse(ApiProtocol.tokenMatches("abc124", "abc123"))
        assertFalse(ApiProtocol.tokenMatches("", "abc123"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownOptionIsAnError() {
        ApiArgs.parse(listOf("-t", "Title", "-x"), withValue = setOf("-t"))
    }

    @Test
    fun optionsParse() {
        val a = ApiArgs.parse(
            listOf("-t", "Title", "--content=Body text", "-l", "rest", "--", "-not-an-option"),
            withValue = setOf("-t", "--title", "-c", "--content"),
            flagNames = setOf("-l")
        )
        assertEquals("Title", a.value("-t", "--title"))
        assertEquals("Body text", a.value("-c", "--content"))
        assertTrue(a.flag("-l"))
        assertFalse(a.flag("-s"))
        assertEquals(listOf("rest", "-not-an-option"), a.operands)
    }

    @Test(expected = IllegalArgumentException::class)
    fun optionWithoutValueIsAnError() {
        ApiArgs.parse(listOf("-t"), withValue = setOf("-t"))
    }

    @Test
    fun pathsInsideTheRootfsAndOnAndroid() {
        val root = "/data/user/0/org.cortex.terminal/files/cortex"
        assertEquals("$root/home/notes.txt", ApiPaths.toAndroid(root, "/home/notes.txt"))
        assertEquals("$root/etc/hosts", ApiPaths.toAndroid("$root/", "/etc/hosts"))
        assertEquals("/sdcard/Download/a.pdf", ApiPaths.toAndroid(root, "/sdcard/Download/a.pdf"))
        assertEquals("/storage/emulated/0/x", ApiPaths.toAndroid(root, "/storage/emulated/0/x"))
        // Android's own path to the rootfs (which root mode's chroot also has) is left as it is
        assertEquals("$root/home/a", ApiPaths.toAndroid(root, "$root/home/a"))
        assertEquals("$root/sdcardfoo", ApiPaths.toAndroid(root, "/sdcardfoo")) // a prefix only counts as a whole name
        assertEquals("$root/mnt/usb", ApiPaths.toAndroid(root, "/mnt/usb")) // Ubuntu's /mnt is in the rootfs
    }

    @Test
    fun urisAreToldApartFromFiles() {
        assertTrue(ApiPaths.isUri("https://example.com/a?b=c"))
        assertTrue(ApiPaths.isUri("mailto:someone@example.com"))
        assertTrue(ApiPaths.isUri("geo:41.0,29.0"))
        assertFalse(ApiPaths.isUri("/home/report.pdf"))
        assertFalse(ApiPaths.isUri("notes:today.txt"))
    }

    @Test
    fun jsonEscapesStrings() {
        val json = Json.obj("name" to "a\"b\\c\nd\u0001", "n" to 5, "ok" to true, "none" to null, "t" to 31.5)
        assertEquals(
            "{\n  \"name\": \"a\\\"b\\\\c\\nd\\u0001\",\n  \"n\": 5,\n  \"ok\": true,\n  \"none\": null,\n  \"t\": 31.5\n}\n",
            json
        )
    }
}
