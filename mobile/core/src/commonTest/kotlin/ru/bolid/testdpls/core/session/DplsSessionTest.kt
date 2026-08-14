package ru.bolid.testdpls.core.session

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DplsSessionTest {
    @Test
    fun sequenceWrapsAtSixteenBits() {
        val session = DplsSessionRuntime()
        repeat(0xffff) { session.nextSequence() }
        assertEquals(0, session.nextSequence())
        assertEquals(1, session.nextSequence())
    }

    @Test
    fun authenticatedPayloadIsLittleEndianAndCopiesToken() {
        val session = DplsSessionRuntime().apply {
            sessionId = 0x78563412
            authenticate(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        }
        assertContentEquals(
            byteArrayOf(0x12, 0x34, 0x56, 0x78, 1, 2, 3, 4, 5, 6, 7, 8),
            session.authenticatedPayload(),
        )
    }

    @Test
    fun resetLinkWipesLinkSecrets() {
        val session = DplsSessionRuntime().apply {
            sessionId = 42
            authenticate(ByteArray(8) { 7 })
            deviceNonce = ByteArray(16) { 8 }
            authSalt = ByteArray(16) { 9 }
            clientNonce = ByteArray(16) { 10 }
            initialized = true
        }
        session.resetLink()
        assertEquals(0, session.sessionId)
        assertContentEquals(ByteArray(8), session.sessionToken)
        assertContentEquals(ByteArray(16), session.deviceNonce)
        assertContentEquals(ByteArray(16), session.authSalt)
        assertContentEquals(ByteArray(16) { 10 }, session.clientNonce)
        assertEquals(true, session.initialized)
    }

    @Test
    fun resetAllReturnsToFreshRuntime() {
        val session = DplsSessionRuntime().apply {
            repeat(5) { nextSequence() }
            repeat(3) { nextCommandId() }
            clientNonce = ByteArray(16) { 1 }
            initialized = true
        }
        session.resetAll()
        assertEquals(1, session.sequence)
        assertEquals(1, session.commandId)
        assertContentEquals(ByteArray(16), session.clientNonce)
        assertFalse(session.initialized)
    }
}
