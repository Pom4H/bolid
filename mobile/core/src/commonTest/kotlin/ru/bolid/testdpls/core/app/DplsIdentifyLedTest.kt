package ru.bolid.testdpls.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DplsIdentifyLedTest {
    @Test
    fun firmwareContractIsOneHertzFiftyPercentStartingOn() {
        assertEquals(500, DplsIdentifyLed.HALF_MS)
        assertTrue(DplsIdentifyLed.on(0))
        assertTrue(DplsIdentifyLed.on(499))
        assertFalse(DplsIdentifyLed.on(500))
        assertFalse(DplsIdentifyLed.on(999))
        assertTrue(DplsIdentifyLed.on(1000))
    }

    @Test
    fun phaseAtAckIsHalfWriteRtt() {
        assertEquals(40, DplsIdentifyLed.phaseAtAckMs(100_000, 100_080))
        assertEquals(0, DplsIdentifyLed.phaseAtAckMs(100_000, 99_000))
    }
}
