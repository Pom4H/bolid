package ru.bolid.testdpls.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DplsWireTest {

    private fun stateBytes(
        mode: Int = 0,
        power: Int = 0,
        voltage: Int = 24100,
        remaining: Int = 0,
        reserveLow: Boolean = false,
        flags: Int = 0x01,
        uptime: Long = 12,
        revision: Long = 3,
        validity: Int? = StateValidity.LINE or StateValidity.RESERVE or StateValidity.POWER
            or StateValidity.PORT_2 or StateValidity.PORT_T,
        ports: IntArray? = intArrayOf(24100, 23800, 23700, 4200),
    ): ByteArray {
        val extra = if (validity != null) 1 else 0
        val portBytes = if (ports != null) 8 else 0
        val buf = ByteBuffer.allocate(16 + extra + portBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(mode.toByte())
        buf.put(power.toByte())
        buf.putShort(voltage.toShort())
        buf.putShort(remaining.toShort())
        buf.put(if (reserveLow) 1 else 0)
        buf.put(flags.toByte())
        buf.putInt(uptime.toInt())
        buf.putInt(revision.toInt())
        if (validity != null) buf.put(validity.toByte())
        if (ports != null) {
            for (mv in ports) buf.putShort(mv.toShort())
        }
        return buf.array()
    }

    @Test
    fun parseState_rejectsShortFrame() {
        assertNull(parseStateReport(ByteArray(15), 0L))
    }

    @Test
    fun parseState_legacySixteenBytes_marksNothingMeasured() {
        val state = parseStateReport(stateBytes(validity = null, ports = null), 1_000L)
        assertNotNull(state)
        assertEquals(DplsMode.NORMAL, state!!.mode)
        assertEquals(PowerSource.DPLS, state.powerSource)
        assertEquals(24100, state.voltageMv)
        assertFalse(state.lineVoltageValid)
        assertFalse(state.port2VoltageValid)
        assertEquals(24100, state.port1VoltageMv)
        assertEquals(0, state.port2VoltageMv)
        assertEquals(1_000L, state.receivedAtMillis)
    }

    @Test
    fun parseState_extendedVoltagesAndUnknownMode() {
        val state = parseStateReport(
            stateBytes(mode = 99, power = 1, flags = 0x03, remaining = 12),
            5_000L,
        )
        assertNotNull(state)
        assertEquals(DplsMode.NORMAL, state!!.mode)
        assertEquals(PowerSource.RESERVE, state.powerSource)
        assertTrue(state.realShort)
        assertEquals(12, state.automaticReturnSeconds)
        assertEquals(24100, state.port1VoltageMv)
        assertEquals(23800, state.port2VoltageMv)
        assertEquals(23700, state.portTVoltageMv)
        assertEquals(4200, state.reserveVoltageMv)
        assertTrue(state.port2VoltageValid)
        assertTrue(state.portTVoltageValid)
        assertTrue(state.reserveVoltageValid)
    }

    @Test
    fun parseDeviceInfo_roundTrip() {
        val name = "Тест-ДПЛС"
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val raw = ByteBuffer.allocate(12 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x1fe3d5c3)
            .put(1)
            .put(1).put(2).put(0)
            .put(2)
            .put((DeviceCaps.ADC_PRESENT or DeviceCaps.MULTI_VOLTAGE).toByte())
            .put(1)
            .put(nameBytes.size.toByte())
            .put(nameBytes)
            .array()
        val info = parseDeviceInfoReport(raw)
        assertNotNull(info)
        assertEquals("DPLS-1FE3D5C3", info!!.shortId)
        assertEquals("1.2.0", info.firmwareVersion)
        assertEquals(1, info.protocolVersion)
        assertEquals(2, info.hardwareRevision)
        assertTrue(info.adcPresent)
        assertFalse(info.hardwareReadback)
        assertTrue(info.multiVoltageReport)
        assertEquals(name, info.userName)
    }

    @Test
    fun parseDeviceInfo_rejectsShortAndEmptyName() {
        assertNull(parseDeviceInfoReport(ByteArray(11)))
        val raw = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1).put(1).put(0).put(0).put(0).put(0).put(0).put(0).put(0).array()
        val info = parseDeviceInfoReport(raw)
        assertNotNull(info)
        assertEquals("", info!!.userName)
        assertEquals("DPLS-00000001", info.shortId)
    }

    @Test
    fun parseLogChunk_andEventRecord() {
        assertNull(parseLogChunk(ByteArray(2)))
        assertNull(parseEventRecord(ByteArray(9)))

        val event = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(7).putInt(42).put(4).put(0).array()
        val rec = parseEventRecord(event)!!
        assertEquals(7L, rec.sequence)
        assertEquals(42L, rec.timestampSeconds)
        assertEquals(4, rec.type)

        val chunk = ByteBuffer.allocate(3 + 10).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0).put(1).put(event).array()
        val batch = parseLogChunk(chunk)!!
        assertEquals(0, batch.firstIndex)
        assertEquals(1, batch.records.size)
        assertEquals(7L, batch.records[0].sequence)

        val empty = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN).putShort(0).put(0).array()
        assertNull(parseLogChunk(empty))
    }

    @Test
    fun commandRejectReason_coversKnownCodes() {
        assertTrue(commandRejectReason(3).contains("недопустимый"))
        assertTrue(commandRejectReason(4).contains("аппаратное"))
        assertTrue(commandRejectReason(5).contains("автоизоляция"))
        assertTrue(commandRejectReason(9).contains("9"))
    }

    @Test
    fun eventLogExport_usesTitlesAndRelativeTime() {
        val events = listOf(
            EventRecord(1, 5, 1, 6),
            EventRecord(2, 10, 7, 3),
        )
        val csv = formatEventLogCsv(events, currentRunFirstSeq(events), bootEpochSec = null)
        assertTrue(csv.contains("Запуск устройства"))
        assertTrue(csv.contains("КЗ +1"))
        val txt = formatEventLogTxt(events, 1, null, "Kit")
        assertTrue(txt.contains("Устройство: Kit"))
        assertTrue(txt.contains("Записей: 2"))
        assertEquals(1L, currentRunFirstSeq(events))
        assertEquals(0L, currentRunFirstSeq(emptyList()))
    }
}
