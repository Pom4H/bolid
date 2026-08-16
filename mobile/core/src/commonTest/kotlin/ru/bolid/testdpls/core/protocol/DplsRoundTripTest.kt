package ru.bolid.testdpls.core.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DplsRoundTripTest {
    @Test
    fun everyMessageTypeRoundTrips() {
        DplsProtocol.Type.entries.forEachIndexed { index, type ->
            val input = DplsProtocol.Frame(
                type = type,
                sequence = index,
                payload = byteArrayOf(index.toByte(), (index + 1).toByte()),
            )
            val output = assertIs<DplsProtocol.DecodeResult.Success>(decodeFrame(encodeFrame(input))).frame
            assertEquals(input.type, output.type)
            assertEquals(input.sequence, output.sequence)
            assertContentEquals(input.payload, output.payload)
        }
    }
}
