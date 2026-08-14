package ru.bolid.testdpls.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DplsSessionBridgeTest {
    @Test
    fun bridgePreservesPrimitiveAndSecretState() {
        val bridge = DplsSessionBridge()
        bridge.sessionId = 0x78563412
        bridge.commandId = 7
        bridge.initialized = true
        bridge.sessionTokenHex = "0102030405060708"
        bridge.clientNonceHex = "00112233445566778899aabbccddeeff"
        assertEquals("123456780102030405060708", bridge.authenticatedPayloadHex)
        assertEquals(7L, bridge.nextCommandId())
        assertEquals(8L, bridge.commandId)
        assertTrue(bridge.initialized)
    }

    @Test
    fun bridgeRejectsWrongSecretLengths() {
        val bridge = DplsSessionBridge()
        assertFailsWith<IllegalArgumentException> { bridge.sessionTokenHex = "00" }
        assertFailsWith<IllegalArgumentException> { bridge.clientNonceHex = "not-hex" }
        assertEquals("0000000000000000", bridge.sessionTokenHex)
    }
}
