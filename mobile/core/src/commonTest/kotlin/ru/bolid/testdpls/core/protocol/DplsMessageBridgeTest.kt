package ru.bolid.testdpls.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DplsMessageBridgeTest {
    @Test
    fun bridgeProjectsStateWithoutChangingWireMeaning() {
        val raw = ByteArray(25)
        raw[0] = 3
        raw[1] = 0
        putU16(raw, 2, 12_345)
        putU16(raw, 4, 30)
        putU32(raw, 8, 100)
        putU32(raw, 12, 7)
        raw[16] = (StateValidity.LINE or StateValidity.PORT_2).toByte()
        putU16(raw, 17, 12_300)
        putU16(raw, 19, 12_200)
        putU16(raw, 21, 12_100)
        putU16(raw, 23, 4_900)

        val state = assertNotNull(DplsMessageBridge().parseStateHex(raw.toHexString(), 1_000))
        assertEquals(3, state.mode)
        assertEquals(12_345, state.voltageMv)
        assertEquals(12_200, state.port2VoltageMv)
        assertEquals(100L, state.uptimeSeconds)
        assertEquals(7L, state.revision)
    }

    @Test
    fun bridgeProjectsJournalRecord() {
        val raw = ByteArray(10)
        putU32(raw, 0, 42)
        putU32(raw, 4, 99)
        raw[8] = 14
        raw[9] = 1
        val event = assertNotNull(DplsMessageBridge().parseEventHex(raw.toHexString()))
        assertEquals(42L, event.sequence)
        assertEquals(99L, event.timestampSeconds)
        assertEquals(14, event.type)
        assertEquals(1, event.parameter)
    }
}
