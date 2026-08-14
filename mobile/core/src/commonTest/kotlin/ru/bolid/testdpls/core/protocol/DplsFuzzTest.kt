package ru.bolid.testdpls.core.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

class DplsFuzzTest {
    @Test
    fun arbitraryInputNeverThrows() {
        val random = Random(0xD015)
        repeat(10_000) {
            val bytes = ByteArray(random.nextInt(0, 300)) { random.nextInt(0, 256).toByte() }
            decodeFrame(bytes)
        }
    }

    @Test
    fun arbitraryPayloadRoundTrips() {
        val random = Random(0x6252)
        repeat(2_000) { sequence ->
            val payload = ByteArray(random.nextInt(0, 220)) { random.nextInt(0, 256).toByte() }
            val type = DplsProtocol.Type.entries[sequence % DplsProtocol.Type.entries.size]
            val frame = DplsProtocol.Frame(
                type = type,
                sequence = sequence and 0xffff,
                flags = random.nextInt(0, 256),
                payload = payload,
            )
            val decoded = assertIs<DplsProtocol.DecodeResult.Success>(decodeFrame(encodeFrame(frame))).frame
            assertContentEquals(payload, decoded.payload)
        }
    }
}
