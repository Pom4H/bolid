package ru.bolid.testdpls.core.runtime

/**
 * Факты, которые принимает чистый reducer жизненного цикла соединения.
 *
 * Platform callbacks и milestones протокола приходят только как события. Caller
 * не передаёт желаемое следующее состояние и поэтому не может обойти граф переходов.
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

/**
 * Полный reducer без I/O и скрытых side effects.
 *
 * BLE и protocol work остаются в DplsClient/transport. Здесь находится только
 * допустимый граф состояний, поэтому lifecycle можно тестировать отдельно.
 */
object ConnectionMachine {
    fun reduce(state: DeviceSession, event: ConnectionEvent): DeviceSession = when (event) {
        is ConnectionEvent.ConnectRequested ->
            DeviceSession.Connecting(event.endpoint, event.candidateNodeId)

        ConnectionEvent.LinkConnected -> when (state) {
            is DeviceSession.Connecting ->
                DeviceSession.Discovering(state.endpoint, state.candidateNodeId)
            is DeviceSession.Recovering ->
                DeviceSession.Discovering(state.endpoint, state.nodeId)
            else -> state
        }

        is ConnectionEvent.Subscribed -> when (state) {
            is DeviceSession.Discovering -> DeviceSession.Linked(
                state.endpoint,
                event.clientNonce.copyOf(),
                state.candidateNodeId,
            )
            else -> state
        }

        is ConnectionEvent.ChallengeReceived -> when (state) {
            is DeviceSession.Linked,
            is DeviceSession.Commissioning,
            is DeviceSession.Authenticating,
            -> challengeTransition(state, event.challenge)
            else -> state
        }

        is ConnectionEvent.Authenticated -> when (state) {
            is DeviceSession.Authenticating -> DeviceSession.Synchronizing(
                endpoint = state.endpoint,
                auth = event.auth,
                candidateNodeId = state.candidateNodeId,
            )
            else -> state
        }

        is ConnectionEvent.IdentityVerified -> when (state) {
            is DeviceSession.Synchronizing -> {
                val candidate = state.candidateNodeId
                if (candidate != null && candidate != event.nodeId) {
                    DeviceSession.Failed(
                        state.endpoint,
                        LinkFailure.Protocol("device identity changed during connection"),
                    )
                } else {
                    DeviceSession.Online(event.nodeId, state.endpoint, state.auth)
                }
            }
            is DeviceSession.Online -> if (state.nodeId == event.nodeId) {
                state
            } else {
                DeviceSession.Failed(
                    state.endpoint,
                    LinkFailure.Protocol("device identity changed in active session"),
                )
            }
            else -> state
        }

        ConnectionEvent.SetupCommitted -> when (state) {
            is DeviceSession.Commissioning,
            is DeviceSession.Authenticating,
            -> state.endpointOrNull?.let { endpoint ->
                DeviceSession.Recovering(state.nodeIdOrNull, endpoint)
            } ?: state
            else -> state
        }

        ConnectionEvent.LinkLost -> if (shouldRecoverAfterLinkLoss(state)) {
            recover(state)
        } else {
            DeviceSession.Offline
        }

        ConnectionEvent.BluetoothUnavailable -> if (shouldRecoverAfterRadioLoss(state)) {
            recover(state)
        } else {
            state
        }

        /* Сам факт появления Bluetooth не создаёт новый product state.
         * Transport использует его только как разрешение повторить известный route. */
        ConnectionEvent.BluetoothAvailable -> state

        ConnectionEvent.AttemptTimedOut -> when (state) {
            is DeviceSession.Connecting,
            is DeviceSession.Discovering,
            is DeviceSession.Linked,
            is DeviceSession.Commissioning,
            is DeviceSession.Authenticating,
            is DeviceSession.Synchronizing,
            is DeviceSession.Recovering,
            -> DeviceSession.Failed(state.endpointOrNull, LinkFailure.Unavailable)
            else -> state
        }

        is ConnectionEvent.Failed -> DeviceSession.Failed(state.endpointOrNull, event.failure)

        ConnectionEvent.Reset -> DeviceSession.Offline
    }

    private fun challengeTransition(
        state: DeviceSession,
        challenge: SessionChallenge,
    ): DeviceSession {
        val endpoint = state.endpointOrNull ?: return state
        val candidate = state.candidateNodeIdOrNull
        return if (challenge.initialized) {
            DeviceSession.Authenticating(endpoint, challenge, candidate)
        } else {
            DeviceSession.Commissioning(endpoint, challenge, candidate)
        }
    }

    private fun recover(state: DeviceSession): DeviceSession {
        val endpoint = state.endpointOrNull ?: return DeviceSession.Offline
        return DeviceSession.Recovering(state.nodeIdOrNull, endpoint)
    }

    private fun shouldRecoverAfterLinkLoss(state: DeviceSession): Boolean = when (state) {
        is DeviceSession.Commissioning,
        is DeviceSession.Authenticating,
        is DeviceSession.Synchronizing,
        is DeviceSession.Online,
        is DeviceSession.Recovering,
        -> true
        else -> false
    }

    private fun shouldRecoverAfterRadioLoss(state: DeviceSession): Boolean = when (state) {
        is DeviceSession.Connecting,
        is DeviceSession.Discovering,
        is DeviceSession.Linked,
        is DeviceSession.Commissioning,
        is DeviceSession.Authenticating,
        is DeviceSession.Synchronizing,
        is DeviceSession.Online,
        is DeviceSession.Recovering,
        -> true
        DeviceSession.Offline,
        is DeviceSession.Failed,
        -> false
    }
}
