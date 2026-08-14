package ru.bolid.testdpls.core.app

import kotlin.test.Test
import kotlin.test.assertEquals

class EventTimeTest {
    @Test
    fun syncedTimestampFormatsAsUtc() {
        assertEquals("utc", eventTimestampBasis(1_577_836_800L))
        assertEquals("2020-01-01 00:00:00 UTC", eventTimestampText(1_577_836_800L))
    }

    @Test
    fun preSyncTimestampRemainsUptime() {
        assertEquals("uptime", eventTimestampBasis(3_661L))
        assertEquals("+01:01:01", eventTimestampText(3_661L))
    }
}
