package ru.bolid.testdpls.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.bolid.testdpls.core.domain.DplsMode
import ru.bolid.testdpls.core.domain.PowerSource

class DplsMessagesTest {
    @Test
    fun stateReportParsesExtendedVoltagesAndValidity() {
        val raw = ByteArray(25)
        raw[0] = DplsMode.SHORT_2.wire.toByte()
        raw[1] = 1
        putU16(raw, 2, 12_345)
        putU16(raw, 4, 42)
        raw[6] = 1
        raw[7] = 0x02
        putU32(raw, 8, 123)
        putU32(raw, 12, 456)
        raw[16] = (StateValidity.LINE or StateValidity.RESERVE or StateValidity.PORT_2 or StateValidity.PORT_T).toByte()
        putU16(raw, 17, 12_300)
        putU16(raw, 19, 12_200)
        putU16(raw, 21, 12_100)
        putU16(raw, 23, 4_900)

        val state = assertNotNull(parseStateReport(raw, 999))
        assertEquals(DplsMode.SHORT_2, state.mode)
        assertEquals(PowerSource.RESERVE, state.powerSource)
        assertEquals(12_200, state.port2VoltageMv)
        assertEquals(12_100, state.portTVoltageMv)
        assertEquals(4_900, state.reserveVoltageMv)
        assertTrue(state.realShort)
        assertTrue(state.port2VoltageValid)
        assertTrue(state.portTVoltageValid)
        assertFalse(state.powerValid)
    }

    @Test
    fun deviceInfoRejectsTruncatedName() {
        val raw = ByteArray(12)
        raw[11] = 5
        assertNull(parseDeviceInfoReport(raw))
    }

    @Test
    fun deviceInfoParsesHostSimCapability() {
        val raw = ByteArray(12)
        raw[5] = 1
        raw[6] = 4
        raw[7] = 1
        raw[9] = (1 shl 6).toByte()
        val info = assertNotNull(parseDeviceInfoReport(raw))
        assertEquals("1.4.1", info.firmwareVersion)
        assertTrue(info.hostSim)
    }

    @Test
    fun logChunkRequiresExactRecordLength() {
        val valid = ByteArray(13)
        putU16(valid, 0, 7)
        valid[2] = 1
        putU32(valid, 3, 100)
        putU32(valid, 7, 200)
        valid[11] = 4
        valid[12] = 9
        val chunk = assertNotNull(parseLogChunk(valid))
        assertEquals(7, chunk.firstIndex)
        assertEquals(100, chunk.records.single().sequence)
        assertNull(parseLogChunk(valid + byteArrayOf(0)))
    }
}
