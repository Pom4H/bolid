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
    fun missingAndLegacyRelativeTimestampsAreUnknown() {
        assertEquals("unknown", eventTimestampBasis(0L))
        assertEquals("Время не установлено", eventTimestampText(0L))
        assertEquals("unknown", eventTimestampBasis(3_661L))
        assertEquals("Время не установлено", eventTimestampText(3_661L))
    }
}
