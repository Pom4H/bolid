package ru.bolid.testdpls.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectionMachineTest {
    private val endpoint = LinkEndpoint.Ble("AA:BB:CC:DD:EE:FF")
    private val node = NodeId(0x1234)
    private val nonce = ByteArray(16) { it.toByte() }
    private val challenge = SessionChallenge(
        sessionId = 7,
        clientNonce = nonce,
        deviceNonce = ByteArray(16) { (it + 16).toByte() },
        authSalt = ByteArray(16) { (it + 32).toByte() },
        initialized = true,
    )
    private val auth = AuthSession(7, ByteArray(8) { it.toByte() }, challenge.authSalt)

    @Test
    fun happyPathIsLinearAndReadyRequiresVerifiedIdentity() {
        var state: DeviceSession = DeviceSession.Offline

        state = ConnectionMachine.reduce(
            state,
            ConnectionEvent.ConnectRequested(endpoint, node),
        )
        assertIs<DeviceSession.Connecting>(state)

        state = ConnectionMachine.reduce(state, ConnectionEvent.LinkConnected)
        assertIs<DeviceSession.Discovering>(state)

        state = ConnectionMachine.reduce(state, ConnectionEvent.Subscribed(nonce))
        assertIs<DeviceSession.Linked>(state)

        state = ConnectionMachine.reduce(
            state,
            ConnectionEvent.ChallengeReceived(challenge),
        )
        assertIs<DeviceSession.Authenticating>(state)

        state = ConnectionMachine.reduce(state, ConnectionEvent.Authenticated(auth))
        assertIs<DeviceSession.Synchronizing>(state)

        state = ConnectionMachine.reduce(
            state,
            ConnectionEvent.IdentityVerified(node),
        )
        assertIs<DeviceSession.Online>(state)
    }

    @Test
    fun identityMismatchFailsClosed() {
        val state = DeviceSession.Synchronizing(endpoint, auth, node)
        val next = ConnectionMachine.reduce(
            state,
            ConnectionEvent.IdentityVerified(NodeId(0x9999)),
        )
        assertIs<DeviceSession.Failed>(next)
    }

    @Test
    fun linkLossFromAuthenticatedStatesAlwaysBecomesRecovering() {
        val states = listOf<DeviceSession>(
            DeviceSession.Authenticating(endpoint, challenge, node),
            DeviceSession.Synchronizing(endpoint, auth, node),
            DeviceSession.Online(node, endpoint, auth),
            DeviceSession.Recovering(node, endpoint),
        )
        states.forEach { state ->
            assertIs<DeviceSession.Recovering>(
                ConnectionMachine.reduce(state, ConnectionEvent.LinkLost),
            )
        }
    }

    @Test
    fun failedStateCannotBeRevivedByLinkFacts() {
        val failed: DeviceSession = DeviceSession.Failed(endpoint, LinkFailure.Closed)
        val afterAvailable = ConnectionMachine.reduce(failed, ConnectionEvent.BluetoothAvailable)
        val afterLost = ConnectionMachine.reduce(failed, ConnectionEvent.LinkLost)

        assertIs<DeviceSession.Failed>(afterAvailable)
        assertFalse(afterAvailable is DeviceSession.Recovering)
        assertEquals(DeviceSession.Offline, afterLost)
    }

    @Test
    fun resetAlwaysReturnsOffline() {
        val states = listOf<DeviceSession>(
            DeviceSession.Connecting(endpoint, node),
            DeviceSession.Discovering(endpoint, node),
            DeviceSession.Linked(endpoint, nonce, node),
            DeviceSession.Authenticating(endpoint, challenge, node),
            DeviceSession.Synchronizing(endpoint, auth, node),
            DeviceSession.Online(node, endpoint, auth),
            DeviceSession.Recovering(node, endpoint),
            DeviceSession.Failed(endpoint, LinkFailure.Closed),
        )
        states.forEach { state ->
            assertEquals(DeviceSession.Offline, ConnectionMachine.reduce(state, ConnectionEvent.Reset))
        }
    }

    @Test
    fun allStateEventPairsAreTotalAndNeverForgeOnline() {
        val states = listOf<DeviceSession>(
            DeviceSession.Offline,
            DeviceSession.Connecting(endpoint, node),
            DeviceSession.Discovering(endpoint, node),
            DeviceSession.Linked(endpoint, nonce, node),
            DeviceSession.Commissioning(endpoint, challenge.copy(initialized = false), node),
            DeviceSession.Authenticating(endpoint, challenge, node),
            DeviceSession.Synchronizing(endpoint, auth, node),
            DeviceSession.Online(node, endpoint, auth),
            DeviceSession.Recovering(node, endpoint),
            DeviceSession.Failed(endpoint, LinkFailure.Closed),
        )
        val events = listOf<ConnectionEvent>(
            ConnectionEvent.ConnectRequested(endpoint, node),
            ConnectionEvent.LinkConnected,
            ConnectionEvent.Subscribed(nonce),
            ConnectionEvent.ChallengeReceived(challenge),
            ConnectionEvent.Authenticated(auth),
            ConnectionEvent.IdentityVerified(node),
            ConnectionEvent.SetupCommitted,
            ConnectionEvent.LinkLost,
            ConnectionEvent.BluetoothUnavailable,
            ConnectionEvent.BluetoothAvailable,
            ConnectionEvent.AttemptTimedOut,
            ConnectionEvent.Failed(LinkFailure.Unavailable),
            ConnectionEvent.Reset,
        )

        var exercised = 0
        states.forEach { state ->
            events.forEach { event ->
                val next = ConnectionMachine.reduce(state, event)
                ++exercised
                if (next is DeviceSession.Online && state !is DeviceSession.Online) {
                    assertTrue(
                        event is ConnectionEvent.IdentityVerified &&
                            state is DeviceSession.Synchronizing,
                        "Online can only be entered by identity proof: state=$state event=$event",
                    )
                }
            }
        }
        assertEquals(states.size * events.size, exercised)
    }

    @Test
    fun staleOrImpossibleEventsAreIdempotent() {
        val offline = DeviceSession.Offline
        val events = listOf<ConnectionEvent>(
            ConnectionEvent.LinkConnected,
            ConnectionEvent.Subscribed(nonce),
            ConnectionEvent.ChallengeReceived(challenge),
            ConnectionEvent.Authenticated(auth),
            ConnectionEvent.IdentityVerified(node),
            ConnectionEvent.LinkLost,
        )
        events.forEach { event ->
            assertEquals(offline, ConnectionMachine.reduce(offline, event))
        }
    }
}
