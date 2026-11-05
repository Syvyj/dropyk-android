package dev.syvyj.dropyk

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickShareTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** RFC 5869, test case 1. */
    @Test fun hkdf() {
        val okm = QuickShare.hkdf(hex("0b".repeat(22)), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.hex())
    }

    /** NearDrop's formula: bytes as signed, weighted by powers of 31, mod 9973. */
    @Test fun pin() {
        assertEquals("0000", QuickShare.pinCode(ByteArray(32)))
        assertEquals("0001", QuickShare.pinCode(byteArrayOf(1)))
        assertEquals("0032", QuickShare.pinCode(byteArrayOf(1, 1)))
        assertEquals("0001", QuickShare.pinCode(byteArrayOf(-1)))
    }

    @Test fun endpointInfo() {
        val info = QuickShare.endpointInfo("Living room TV")
        assertEquals(6, info[0].toInt())
        assertEquals(14, info[17].toInt())
        assertEquals("Living room TV", String(info, 18, 14))
    }
}
