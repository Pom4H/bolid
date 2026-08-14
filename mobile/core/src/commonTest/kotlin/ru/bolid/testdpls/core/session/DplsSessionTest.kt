package ru.bolid.testdpls.core.session

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.bolid.testdpls.core.domain.ConnectionPhase
import ru.bolid.testdpls.core.domain.DeviceState
import ru.bolid.testdpls.core.domain.DplsMode
import ru.bolid.testdpls.core.domain.DplsUiState
import ru.bolid.testdpls.core.domain.PowerSource

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
    fun linkLossAlwaysDisablesAuthenticatedControls() {
        val current = DplsUiState(
            phase = ConnectionPhase.READY,
            authenticated = true,
            state = sampleState(DplsMode.SHORT_1),
        )
        val next = reduceSession(current, SessionEvent.LinkLost)
        assertEquals(ConnectionPhase.RECONNECTING, next.phase)
        assertFalse(next.authenticated)
        assertFalse(next.commandInProgress)
        assertTrue(next.staleState)
        assertFalse(next.controlsEnabled)
    }

    @Test
    fun receivedStateReturnsSessionToReady() {
        val next = reduceSession(
            DplsUiState(phase = ConnectionPhase.SYNCHRONIZING, authenticated = true),
            SessionEvent.StateReceived(sampleState(DplsMode.NORMAL), nowMillis = 50_000),
        )
        assertEquals(ConnectionPhase.READY, next.phase)
        assertTrue(next.authenticated)
        assertFalse(next.staleState)
        assertEquals(40L, next.deviceBootEpochSeconds)
    }

    @Test
    fun failureNeverLeavesCommandEnabled() {
        val next = reduceSession(
            DplsUiState(commandInProgress = true, authenticated = true),
            SessionEvent.Failed("boom"),
        )
        assertEquals(ConnectionPhase.ERROR, next.phase)
        assertFalse(next.commandInProgress)
        assertFalse(next.controlsEnabled)
    }

    private fun sampleState(mode: DplsMode) = DeviceState(
        mode = mode,
        voltageMv = 12_000,
        powerSource = PowerSource.DPLS,
        reserveLow = false,
        realShort = false,
        automaticReturnSeconds = 30,
        uptimeSeconds = 10,
        revision = 1,
    )
}
