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
        assertEquals("Test-DPLS-1234", DplsBle.displayName("DPLS1234", null, null))
        assertEquals("Test-DPLS-1234", DplsBle.displayName("Test-DPLS", null, 0x1234))
    }

    @Test
    fun discoveredUsesCurrentAirNameOnly() {
        val named = DplsBle.discovered(
            address = "AA:BB",
            advertisedName = "Test-DPLS-3B31",
            peripheralName = null,
            rssi = -51,
        )
        assertEquals("AA:BB", named.address)
        assertEquals("Test-DPLS-3B31", named.name)
        assertEquals(0x3B31, named.deviceId)
        assertEquals(0, named.advStatus)
        assertEquals(-51, named.rssi)
        assertNull(named.firmwareVersion)

        val compact = DplsBle.discovered(
            address = "mac",
            advertisedName = "DPLS1234",
            peripheralName = null,
            rssi = -67,
        )
        assertEquals(0x1234, compact.deviceId)
        assertEquals("Test-DPLS-1234", compact.name)

        val empty = DplsBle.discovered(
            address = "x",
            advertisedName = null,
            peripheralName = null,
            rssi = 0,
        )
        assertNull(empty.deviceId)
        assertEquals("Test-DPLS", empty.name)
    }
}
