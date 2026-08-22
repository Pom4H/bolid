package ru.bolid.testdpls.core.runtime

/** Факты, которые могут изменить lifecycle соединения. */
sealed interface ConnectionEvent {
    data class ConnectRequested(
        val endpoint: LinkEndpoint,
        val candidateNodeId: NodeId? = null,
    ) : ConnectionEvent

    data object LinkConnected : ConnectionEvent

    data class Subscribed(val clientNonce: ByteArray) : ConnectionEvent {
        init { require(clientNonce.size == 16) }
    }

    data class ChallengeReceived(val challenge: SessionChallenge) : ConnectionEvent
    data class Authenticated(val auth: AuthSession) : ConnectionEvent
    data class IdentityVerified(val nodeId: NodeId) : ConnectionEvent
    data object LinkLost : ConnectionEvent
    data object BluetoothUnavailable : ConnectionEvent
    data class Failed(val failure: LinkFailure) : ConnectionEvent
    data object Reset : ConnectionEvent
}

/**
 * Чистый граф переходов без I/O и таймеров.
 *
 * DplsClient сообщает только случившийся факт. Следующее состояние выбирается
 * здесь, поэтому product-код не может перескочить через auth или проверку identity.
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
            is DeviceSession.Linked -> DeviceSession.Securing(
                state.endpoint,
                event.challenge,
                state.candidateNodeId,
            )
            is DeviceSession.Securing -> DeviceSession.Securing(
                state.endpoint,
                event.challenge,
                state.candidateNodeId,
            )
            else -> state
        }

        is ConnectionEvent.Authenticated -> when (state) {
            is DeviceSession.Securing -> if (state.challenge.initialized) {
                DeviceSession.Synchronizing(
                    endpoint = state.endpoint,
                    auth = event.auth,
                    candidateNodeId = state.candidateNodeId,
                )
            } else {
                state
            }
            else -> state
        }

        is ConnectionEvent.IdentityVerified -> when (state) {
            is DeviceSession.Synchronizing -> {
                val candidate = state.candidateNodeId
                if (candidate != null && candidate != event.nodeId) {
                    DeviceSession.Failed(
                        state.endpoint,
                        LinkFailure.Protocol("Идентификатор устройства изменился во время подключения"),
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
                    LinkFailure.Protocol("Устройство сменило идентификатор в активной сессии"),
                )
            }
            else -> state
        }

        ConnectionEvent.LinkLost -> when (state) {
            is DeviceSession.Securing,
            is DeviceSession.Synchronizing,
            is DeviceSession.Online,
            is DeviceSession.Recovering,
            -> recover(state)
            is DeviceSession.Failed -> state
            else -> DeviceSession.Offline
        }

        ConnectionEvent.BluetoothUnavailable -> when (state) {
            DeviceSession.Offline,
            is DeviceSession.Failed,
            -> state
            else -> recover(state)
        }

        is ConnectionEvent.Failed -> DeviceSession.Failed(state.endpointOrNull, event.failure)

        ConnectionEvent.Reset -> DeviceSession.Offline
    }

    private fun recover(state: DeviceSession): DeviceSession {
        val endpoint = state.endpointOrNull ?: return DeviceSession.Offline
        return DeviceSession.Recovering(state.nodeIdOrNull, endpoint)
    }
}
