package org.cortex.terminal.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

object TzifGenerator {

    fun createTzifBytes(offsetSeconds: Int, tzAbbr: String, posixStr: String): ByteArray {
        val abbrBytes = tzAbbr.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val charcnt = abbrBytes.size

        val bb = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN)

        // Header 1 (32-bit v1)
        bb.put("TZif2".toByteArray(Charsets.US_ASCII))
        bb.put(ByteArray(15))
        bb.putInt(0) // ttisgmtcnt
        bb.putInt(0) // ttisstdcnt
        bb.putInt(0) // leapcnt
        bb.putInt(0) // timecnt
        bb.putInt(1) // typecnt
        bb.putInt(charcnt) // charcnt

        // ttinfo 1
        bb.putInt(offsetSeconds)
        bb.put(0.toByte()) // isdst
        bb.put(0.toByte()) // abbridx
        bb.put(abbrBytes)

        // Header 2 (64-bit v2)
        bb.put("TZif2".toByteArray(Charsets.US_ASCII))
        bb.put(ByteArray(15))
        bb.putInt(0)
        bb.putInt(0)
        bb.putInt(0)
        bb.putInt(0)
        bb.putInt(1)
        bb.putInt(charcnt)

        // ttinfo 2
        bb.putInt(offsetSeconds)
        bb.put(0.toByte())
        bb.put(0.toByte())
        bb.put(abbrBytes)

        // Footer
        bb.put("\n$posixStr\n".toByteArray(Charsets.US_ASCII))

        bb.flip()
        val out = ByteArray(bb.remaining())
        bb.get(out)
        return out
    }
}
