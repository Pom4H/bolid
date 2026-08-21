package ru.bolid.testdpls.core.runtime

/**
 * Pure lifecycle reducer for one Test-DPLS connection attempt.
 *
 * Platform callbacks are facts, never commands. Product code dispatches a fact
 * here and executes only the returned [ConnectionEffect] values. This keeps the
 * legal state graph in one place and makes every state/event pair host-testable.
 */
sealed interface ConnectionEvent {
    data class ConnectRequested(
        val endpoint: LinkEndpoint,
        val candidateNodeId: NodeId? = null,
    ) : ConnectionEvent

    data object LinkConnected : ConnectionEvent

    data class Subscribed(
        val clientNonce: ByteArray,
        val sendHello: Boolean = true,
    ) : ConnectionEvent {
        init { require(clientNonce.size == 16) }
    }

    data class ChallengeReceived(val challenge: SessionChallenge) : ConnectionEvent
    data class Authenticated(val auth: AuthSession) : ConnectionEvent
    data class IdentityVerified(val nodeId: NodeId) : ConnectionEvent
    data object SetupCommitted : ConnectionEvent
    data object LinkLost : ConnectionEvent
    data object BluetoothUnavailable : ConnectionEvent
    data object BluetoothAvailable : ConnectionEvent
    data object AttemptTimedOut : ConnectionEvent
    data class Failed(val failure: LinkFailure) : ConnectionEvent
    data object Reset : ConnectionEvent
}

sealed interface ConnectionEffect {
    data class OpenLink(val endpoint: LinkEndpoint, val reconnect: Boolean) : ConnectionEffect
    data class SendHello(val clientNonce: ByteArray) : ConnectionEffect
    data object RequestState : ConnectionEffect
    data object AwaitSetupDisconnect : ConnectionEffect
    data object CloseLink : ConnectionEffect
}

data class ConnectionTransition(
    val state: DeviceSession,
    val effects: List<ConnectionEffect> = emptyList(),
)

object ConnectionMachine {
    fun reduce(state: DeviceSession, event: ConnectionEvent): ConnectionTransition = when (event) {
        is ConnectionEvent.ConnectRequested -> ConnectionTransition(
            DeviceSession.Connecting(event.endpoint, event.candidateNodeId),
            listOf(ConnectionEffect.OpenLink(event.endpoint, reconnect = false)),
        )

        ConnectionEvent.LinkConnected -> when (state) {
            is DeviceSession.Connecting -> ConnectionTransition(
                DeviceSession.Discovering(state.endpoint, state.candidateNodeId),
            )
            is DeviceSession.Recovering -> ConnectionTransition(
                DeviceSession.Discovering(state.endpoint, state.nodeId),
            )
            else -> unchanged(state)
        }

        is ConnectionEvent.Subscribed -> when (state) {
            is DeviceSession.Discovering -> {
                val linked = DeviceSession.Linked(
                    state.endpoint,
                    event.clientNonce.copyOf(),
                    state.candidateNodeId,
                )
                ConnectionTransition(
                    linked,
                    if (event.sendHello) {
                        listOf(ConnectionEffect.SendHello(linked.clientNonce.copyOf()))
                    } else {
                        emptyList()
                    },
                )
            }
            else -> unchanged(state)
        }

        is ConnectionEvent.ChallengeReceived -> when (state) {
            is DeviceSession.Linked -> challengeTransition(state, event.challenge)
            is DeviceSession.Commissioning -> challengeTransition(state, event.challenge)
            is DeviceSession.Authenticating -> challengeTransition(state, event.challenge)
            else -> unchanged(state)
        }

        is ConnectionEvent.Authenticated -> when (state) {
            is DeviceSession.Authenticating -> ConnectionTransition(
                DeviceSession.Synchronizing(
                    endpoint = state.endpoint,
                    auth = event.auth,
                    candidateNodeId = state.candidateNodeId,
                ),
                listOf(ConnectionEffect.RequestState),
            )
            else -> unchanged(state)
        }

        is ConnectionEvent.IdentityVerified -> when (state) {
            is DeviceSession.Synchronizing -> {
                val candidate = state.candidateNodeId
                if (candidate != null && candidate != event.nodeId) {
                    ConnectionTransition(
                        DeviceSession.Failed(
                            state.endpoint,
                            LinkFailure.Protocol("device identity changed during connection"),
                        ),
                        listOf(ConnectionEffect.CloseLink),
                    )
                } else {
                    ConnectionTransition(
                        DeviceSession.Online(event.nodeId, state.endpoint, state.auth),
                    )
                }
            }
            is DeviceSession.Online -> if (state.nodeId == event.nodeId) {
                unchanged(state)
            } else {
                ConnectionTransition(
                    DeviceSession.Failed(
                        state.endpoint,
                        LinkFailure.Protocol("device identity changed in active session"),
                    ),
                    listOf(ConnectionEffect.CloseLink),
                )
            }
            else -> unchanged(state)
        }

        ConnectionEvent.SetupCommitted -> when (state) {
            is DeviceSession.Commissioning,
            is DeviceSession.Authenticating,
            -> state.endpointOrNull?.let { endpoint ->
                ConnectionTransition(
                    DeviceSession.Recovering(state.nodeIdOrNull, endpoint),
                    listOf(ConnectionEffect.AwaitSetupDisconnect),
                )
            } ?: unchanged(state)
            else -> unchanged(state)
        }

        ConnectionEvent.LinkLost -> lost(state, reconnect = shouldRecover(state))
        ConnectionEvent.BluetoothUnavailable -> lost(state, reconnect = false)

        ConnectionEvent.BluetoothAvailable -> when (state) {
            is DeviceSession.Recovering -> ConnectionTransition(
                state,
                listOf(ConnectionEffect.OpenLink(state.endpoint, reconnect = true)),
            )
            else -> unchanged(state)
        }

        ConnectionEvent.AttemptTimedOut -> when (state) {
            is DeviceSession.Connecting,
            is DeviceSession.Discovering,
            is DeviceSession.Linked,
            is DeviceSession.Commissioning,
            is DeviceSession.Authenticating,
            is DeviceSession.Synchronizing,
            is DeviceSession.Recovering,
            -> ConnectionTransition(
                DeviceSession.Failed(state.endpointOrNull, LinkFailure.Unavailable),
                listOf(ConnectionEffect.CloseLink),
            )
            else -> unchanged(state)
        }

        is ConnectionEvent.Failed -> ConnectionTransition(
            DeviceSession.Failed(state.endpointOrNull, event.failure),
            listOf(ConnectionEffect.CloseLink),
        )

        ConnectionEvent.Reset -> ConnectionTransition(
            DeviceSession.Offline,
            if (state is DeviceSession.Offline) emptyList() else listOf(ConnectionEffect.CloseLink),
        )
    }

    private fun challengeTransition(
        state: DeviceSession,
        challenge: SessionChallenge,
    ): ConnectionTransition {
        val endpoint = state.endpointOrNull ?: return unchanged(state)
        val candidate = state.candidateNodeIdOrNull
        return ConnectionTransition(
            if (challenge.initialized) {
                DeviceSession.Authenticating(endpoint, challenge, candidate)
            } else {
                DeviceSession.Commissioning(endpoint, challenge, candidate)
            },
        )
    }

    private fun lost(state: DeviceSession, reconnect: Boolean): ConnectionTransition {
        val endpoint = state.endpointOrNull ?: return ConnectionTransition(DeviceSession.Offline)
        if (!reconnect) {
            return ConnectionTransition(DeviceSession.Recovering(state.nodeIdOrNull, endpoint))
        }
        return ConnectionTransition(
            DeviceSession.Recovering(state.nodeIdOrNull, endpoint),
            listOf(ConnectionEffect.OpenLink(endpoint, reconnect = true)),
        )
    }

    private fun shouldRecover(state: DeviceSession): Boolean = when (state) {
        is DeviceSession.Commissioning,
        is DeviceSession.Authenticating,
        is DeviceSession.Synchronizing,
        is DeviceSession.Online,
        is DeviceSession.Recovering,
        -> true
        else -> false
    }

    private fun unchanged(state: DeviceSession) = ConnectionTransition(state)
}
