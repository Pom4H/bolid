package ru.bolid.testdpls.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
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
        var t = ConnectionMachine.reduce(
            state,
            ConnectionEvent.ConnectRequested(endpoint, node),
        )
        state = t.state
        assertIs<DeviceSession.Connecting>(state)
        assertEquals(listOf(ConnectionEffect.OpenLink(endpoint, false)), t.effects)

        state = ConnectionMachine.reduce(state, ConnectionEvent.LinkConnected).state
        assertIs<DeviceSession.Discovering>(state)

        t = ConnectionMachine.reduce(state, ConnectionEvent.Subscribed(nonce))
        state = t.state
        assertIs<DeviceSession.Linked>(state)
        assertIs<ConnectionEffect.SendHello>(t.effects.single())

        state = ConnectionMachine.reduce(
            state,
            ConnectionEvent.ChallengeReceived(challenge),
        ).state
        assertIs<DeviceSession.Authenticating>(state)

        t = ConnectionMachine.reduce(state, ConnectionEvent.Authenticated(auth))
        state = t.state
        assertIs<DeviceSession.Synchronizing>(state)
        assertEquals(listOf(ConnectionEffect.RequestState), t.effects)

        state = ConnectionMachine.reduce(
            state,
            ConnectionEvent.IdentityVerified(node),
        ).state
        assertIs<DeviceSession.Online>(state)
    }

    @Test
    fun identityMismatchFailsClosed() {
        val state = DeviceSession.Synchronizing(endpoint, auth, node)
        val transition = ConnectionMachine.reduce(
            state,
            ConnectionEvent.IdentityVerified(NodeId(0x9999)),
        )
        assertIs<DeviceSession.Failed>(transition.state)
        assertEquals(listOf(ConnectionEffect.CloseLink), transition.effects)
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
            val transition = ConnectionMachine.reduce(state, ConnectionEvent.LinkLost)
            assertIs<DeviceSession.Recovering>(transition.state)
            assertEquals(listOf(ConnectionEffect.OpenLink(endpoint, true)), transition.effects)
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
                val transition = ConnectionMachine.reduce(state, event)
                ++exercised
                if (transition.state is DeviceSession.Online && state !is DeviceSession.Online) {
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
            assertEquals(offline, ConnectionMachine.reduce(offline, event).state)
        }
    }
}
