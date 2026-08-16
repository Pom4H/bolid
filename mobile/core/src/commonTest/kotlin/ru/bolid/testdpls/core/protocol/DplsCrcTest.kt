package ru.bolid.testdpls.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class DplsCrcTest {
    @Test
    fun standardCheckVector() {
        assertEquals(0x29B1, crc16CcittFalse("123456789".encodeToByteArray()))
    }
}
