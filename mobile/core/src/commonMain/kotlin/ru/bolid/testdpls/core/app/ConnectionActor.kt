package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.runtime.ConnectionEffect
import ru.bolid.testdpls.core.runtime.ConnectionEvent
import ru.bolid.testdpls.core.runtime.ConnectionMachine
import ru.bolid.testdpls.core.runtime.DeviceSession
import ru.bolid.testdpls.core.runtime.endpointOrNull

/**
 * Mutable shell around the pure reducer. DplsClient dispatches facts; it never
 * owns a second mutable lifecycle copy.
 */
internal class ConnectionActor(
    initial: DeviceSession = DeviceSession.Offline,
) {
    var state: DeviceSession = initial
        private set

    fun dispatch(event: ConnectionEvent): List<ConnectionEffect> {
        val transition = ConnectionMachine.reduce(state, event)
        state = transition.state
        return transition.effects
    }

    /**
     * RC6 compatibility bridge while DplsClient call sites are moved to semantic
     * events. Even a legacy `transitionTo` cannot bypass the reducer: the desired
     * state must be exactly one legal reducer step from the current state.
     */
    fun transitionTo(next: DeviceSession): List<ConnectionEffect> {
        val event = eventFor(state, next)
        val transition = ConnectionMachine.reduce(state, event)
        check(sameSessionShape(transition.state, next)) {
            "Illegal connection transition ${state::class.simpleName} -> ${next::class.simpleName} via $event"
        }
        state = next
        return transition.effects
    }

    private fun eventFor(current: DeviceSession, next: DeviceSession): ConnectionEvent = when (next) {
        DeviceSession.Offline -> ConnectionEvent.Reset
        is DeviceSession.Connecting -> ConnectionEvent.ConnectRequested(next.endpoint, next.candidateNodeId)
        is DeviceSession.Discovering -> ConnectionEvent.LinkConnected
        is DeviceSession.Linked -> ConnectionEvent.Subscribed(next.clientNonce, sendHello = false)
        is DeviceSession.Commissioning -> ConnectionEvent.ChallengeReceived(next.challenge)
        is DeviceSession.Authenticating -> ConnectionEvent.ChallengeReceived(next.challenge)
        is DeviceSession.Synchronizing -> ConnectionEvent.Authenticated(next.auth)
        is DeviceSession.Online -> ConnectionEvent.IdentityVerified(next.nodeId)
        is DeviceSession.Recovering -> if (
            current is DeviceSession.Commissioning || current is DeviceSession.Authenticating
        ) {
            ConnectionEvent.SetupCommitted
        } else {
            ConnectionEvent.LinkLost
        }
        is DeviceSession.Failed -> ConnectionEvent.Failed(next.failure)
    }

    private fun sameSessionShape(actual: DeviceSession, expected: DeviceSession): Boolean {
        if (actual::class != expected::class) return false
        if (actual.endpointOrNull != expected.endpointOrNull) return false
        return when {
            actual is DeviceSession.Linked && expected is DeviceSession.Linked ->
                actual.clientNonce.contentEquals(expected.clientNonce) &&
                    actual.candidateNodeId == expected.candidateNodeId
            actual is DeviceSession.Commissioning && expected is DeviceSession.Commissioning ->
                sameChallenge(actual.challenge, expected.challenge) &&
                    actual.candidateNodeId == expected.candidateNodeId
            actual is DeviceSession.Authenticating && expected is DeviceSession.Authenticating ->
                sameChallenge(actual.challenge, expected.challenge) &&
                    actual.candidateNodeId == expected.candidateNodeId
            actual is DeviceSession.Synchronizing && expected is DeviceSession.Synchronizing ->
                sameAuth(actual.auth, expected.auth) &&
                    actual.candidateNodeId == expected.candidateNodeId
            actual is DeviceSession.Online && expected is DeviceSession.Online ->
                actual.nodeId == expected.nodeId && sameAuth(actual.auth, expected.auth)
            actual is DeviceSession.Recovering && expected is DeviceSession.Recovering ->
                actual.nodeId == expected.nodeId
            actual is DeviceSession.Failed && expected is DeviceSession.Failed -> true
            else -> true
        }
    }

    private fun sameChallenge(a: ru.bolid.testdpls.core.runtime.SessionChallenge,
                              b: ru.bolid.testdpls.core.runtime.SessionChallenge): Boolean =
        a.sessionId == b.sessionId &&
            a.initialized == b.initialized &&
            a.clientNonce.contentEquals(b.clientNonce) &&
            a.deviceNonce.contentEquals(b.deviceNonce) &&
            a.authSalt.contentEquals(b.authSalt)

    private fun sameAuth(a: ru.bolid.testdpls.core.runtime.AuthSession,
                         b: ru.bolid.testdpls.core.runtime.AuthSession): Boolean =
        a.sessionId == b.sessionId &&
            a.token.contentEquals(b.token) &&
            a.authSalt.contentEquals(b.authSalt)
}
