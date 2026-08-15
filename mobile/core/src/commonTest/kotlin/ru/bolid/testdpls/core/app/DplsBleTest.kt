package ru.bolid.testdpls.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DplsBleTest {
    @Test
    fun displayNamePrefersAdvertisedThenPeripheralThenDeviceId() {
        assertEquals("Custom", DplsBle.displayName("Custom", "Other", 0x3B31))
        assertEquals("Board", DplsBle.displayName(null, "Board", 0x3B31))
        assertEquals("Test-DPLS-3B31", DplsBle.displayName(null, null, 0x3B31))
        assertEquals("Test-DPLS", DplsBle.displayName("  ", "", null))
    }

    @Test
    fun discoveredParsesManufacturerPayloadOnce() {
        val androidScan = DplsBle.discovered(
            address = "AA:BB",
            advertisedName = null,
            peripheralName = null,
            manufacturerPayload = byteArrayOf(0x31, 0x3B, 0x00, 0x00, 0x05),
            manufacturerIncludesCompanyId = false,
            rssi = -51,
        )
        assertEquals("AA:BB", androidScan.address)
        assertEquals("Test-DPLS-3B31", androidScan.name)
        assertEquals(0x3B31, androidScan.deviceId)
        assertEquals(5, androidScan.advStatus)
        assertEquals(-51, androidScan.rssi)

        val iosScan = DplsBle.discovered(
            address = "uuid",
            advertisedName = "Live",
            peripheralName = "Other",
            manufacturerPayload = byteArrayOf(0x01, 0x0B, 0x31, 0x3B, 0x00, 0x00),
            manufacturerIncludesCompanyId = true,
            rssi = -40,
        )
        assertEquals("Live", iosScan.name)
        assertEquals(0x3B31, iosScan.deviceId)
        assertEquals(0, iosScan.advStatus)

        val empty = DplsBle.discovered("x", null, null, null, false, 0)
        assertNull(empty.deviceId)
        assertEquals("Test-DPLS", empty.name)
    }
}
