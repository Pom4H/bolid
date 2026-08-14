package ru.bolid.testdpls.core.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.bolid.testdpls.core.domain.DplsMode

class DplsControlMessagesTest {
    @Test
    fun authResultTokenStartsAfterStatusAndRetryAfter() {
        val token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val raw = byteArrayOf(0, 0x34, 0x12) + token
        val result = requireNotNull(parseAuthResult(raw))
        assertEquals(0, result.status)
        assertEquals(0x1234, result.retryAfterSeconds)
        assertContentEquals(token, result.sessionToken)
    }

    @Test
    fun failedAuthHasNoToken() {
        val result = requireNotNull(parseAuthResult(byteArrayOf(2, 5, 0)))
        assertEquals(2, result.status)
        assertEquals(5, result.retryAfterSeconds)
        assertNull(result.sessionToken)
    }

    @Test
    fun challengeAndCommandLayoutsMatchWireContract() {
        val challenge = ByteArray(37)
        putU32(challenge, 0, 0x78563412)
        repeat(16) { challenge[4 + it] = it.toByte() }
        repeat(16) { challenge[20 + it] = (it + 16).toByte() }
        challenge[36] = 1
        val parsedChallenge = requireNotNull(parseAuthChallenge(challenge))
        assertEquals(0x78563412, parsedChallenge.sessionId)
        assertTrue(parsedChallenge.initialized)

        val command = ByteArray(8)
        putU32(command, 0, 0x11223344)
        command[4] = 0
        command[5] = DplsMode.SHORT_1.wire.toByte()
        putU16(command, 6, 30)
        val parsedCommand = requireNotNull(parseCommandResult(command))
        assertEquals(0x11223344, parsedCommand.commandId)
        assertEquals(DplsMode.SHORT_1, parsedCommand.mode)
        assertEquals(30, parsedCommand.automaticReturnSeconds)
    }
}
