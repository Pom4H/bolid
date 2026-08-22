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
        assertContentEquals(token, requireNotNull(result.sessionToken))
    }

    @Test
    fun failedAuthHasNoToken() {
        val raw = byteArrayOf(2, 5, 0) + ByteArray(8)
        val result = requireNotNull(parseAuthResult(raw))
        assertEquals(2, result.status)
        assertEquals(5, result.retryAfterSeconds)
        assertNull(result.sessionToken)
    }

    @Test
    fun challengeAndCommandLayoutsMatchV2WireContract() {
        val challenge = ByteArray(37)
        putU32(challenge, 0, 0x78563412)
        repeat(16) { challenge[4 + it] = it.toByte() }
        repeat(16) { challenge[20 + it] = (it + 16).toByte() }
        challenge[36] = 1
        val parsedChallenge = requireNotNull(parseAuthChallenge(challenge))
        assertEquals(0x78563412, parsedChallenge.sessionId)
        assertTrue(parsedChallenge.initialized)

        val command = byteArrayOf(
            0,
            DplsMode.SHORT_1.wire.toByte(),
            30,
            0,
        )
        val parsedCommand = requireNotNull(parseCommandResult(command))
        assertEquals(DplsMode.SHORT_1, parsedCommand.mode)
        assertEquals(30, parsedCommand.automaticReturnSeconds)
    }

    @Test
    fun legacyCommandAndSettingsLayoutsAreRejected() {
        assertNull(parseCommandResult(ByteArray(8)))
        assertNull(parseSettingsResult(ByteArray(5)))
    }

    @Test
    fun timeSyncCarriesAuthenticatedUnixUtcSeconds() {
        val token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val unixSeconds = 1_786_732_800L
        val payload = requireNotNull(buildTimeSyncPayload(0x78563412, token, unixSeconds))
        assertEquals(16, payload.size)
        assertEquals(0x78563412, readU32(payload, 0))
        assertContentEquals(token, payload.copyOfRange(4, 12))
        assertEquals(unixSeconds, readU32(payload, 12))
        assertEquals(DplsProtocol.Type.TIME_SYNC, DplsProtocol.Type.fromWire(0x0b))
    }

    @Test
    fun timeSyncRejectsClearlyInvalidPhoneClock() {
        val token = ByteArray(8) { it.toByte() }
        assertNull(buildTimeSyncPayload(1, token, DplsProtocol.TIME_MIN_UNIX_SECONDS - 1))
        assertNull(buildTimeSyncPayload(1, token, DplsProtocol.TIME_MAX_UNIX_SECONDS + 1))
        assertNull(buildTimeSyncPayload(1, ByteArray(7), 1_786_732_800L))
    }
}
