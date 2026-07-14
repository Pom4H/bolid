package com.thebutton.ble.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for [DplsProtocol]. The CRC and framing here must stay
 * byte-for-byte compatible with the firmware core (Firmware/src/dpls_protocol.c),
 * so a divergence in either side fails these known-answer checks.
 */
class DplsProtocolTest {

    @Test
    fun crc16_matchesCcittFalseCheckVector() {
        // CRC-16/CCITT-FALSE of the standard "123456789" string is 0x29B1.
        // Firmware uses the same 0xFFFF init / 0x1021 poly / no reflection.
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x29B1, DplsProtocol.crc16(data))
    }

    @Test
    fun encodeThenDecode_roundTripsFrame() {
        val payload = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        val frame = DplsProtocol.Frame(DplsProtocol.Type.MODE_SET, sequence = 0x1234, payload = payload)

        val encoded = DplsProtocol.encode(frame)
        assertEquals(DplsProtocol.OVERHEAD + payload.size, encoded.size)

        val decoded = DplsProtocol.decode(encoded)
        assertTrue(decoded is DplsProtocol.DecodeResult.Success)
        val out = (decoded as DplsProtocol.DecodeResult.Success).frame
        assertEquals(DplsProtocol.Type.MODE_SET, out.type)
        assertEquals(0x1234, out.sequence)
        assertArrayEquals(payload, out.payload)
    }

    @Test
    fun encode_layout_isVersionTypeFlagsSeqLenPayloadCrc() {
        val frame = DplsProtocol.Frame(DplsProtocol.Type.HELLO, sequence = 1, payload = byteArrayOf())
        val encoded = DplsProtocol.encode(frame)
        assertEquals(DplsProtocol.OVERHEAD, encoded.size)
        assertEquals(DplsProtocol.VERSION, encoded[0])
        assertEquals(DplsProtocol.Type.HELLO.wire.toByte(), encoded[1])
        assertEquals(0, encoded[2].toInt()) // flags
        assertEquals(1, encoded[3].toInt()) // sequence LE low byte
        assertEquals(0, encoded[4].toInt()) // sequence LE high byte
        assertEquals(0, encoded[5].toInt()) // payload length LE low byte
        assertEquals(0, encoded[6].toInt()) // payload length LE high byte
    }

    @Test
    fun decode_rejectsCorruptCrc() {
        val encoded = DplsProtocol.encode(
            DplsProtocol.Frame(DplsProtocol.Type.STATE_GET, sequence = 7, payload = byteArrayOf(1, 2, 3)),
        )
        encoded[encoded.size - 1] = (encoded[encoded.size - 1] + 1).toByte()
        val decoded = DplsProtocol.decode(encoded)
        assertTrue(decoded is DplsProtocol.DecodeResult.Failure)
    }

    @Test
    fun decode_rejectsWrongLength() {
        val encoded = DplsProtocol.encode(
            DplsProtocol.Frame(DplsProtocol.Type.STATE_GET, sequence = 7, payload = byteArrayOf(1, 2, 3)),
        )
        val truncated = encoded.copyOf(encoded.size - 1)
        val decoded = DplsProtocol.decode(truncated)
        assertTrue(decoded is DplsProtocol.DecodeResult.Failure)
    }

    @Test
    fun decode_rejectsUnknownVersion() {
        val encoded = DplsProtocol.encode(
            DplsProtocol.Frame(DplsProtocol.Type.KEEP_ALIVE, sequence = 0, payload = byteArrayOf()),
        )
        encoded[0] = 0x7f
        val decoded = DplsProtocol.decode(encoded)
        assertTrue(decoded is DplsProtocol.DecodeResult.Failure)
    }
}
