package ru.bolid.testdpls.core.session

import ru.bolid.testdpls.core.domain.ConnectionPhase
import ru.bolid.testdpls.core.domain.DeviceState
import ru.bolid.testdpls.core.domain.DplsUiState
import ru.bolid.testdpls.core.protocol.putU32

/**
 * Platform-independent Test-DPLS application session state.
 *
 * BLE implementations own transport/lifecycle details. Protocol sequence,
 * command ids, authentication material and UI-safe connection transitions live
 * here so Android and iOS cannot silently diverge.
 */
class DplsSessionRuntime {
    var sequence: Int = 1
    var commandId: Long = 1
    var sessionId: Long = 0

    var sessionToken: ByteArray = ByteArray(8)
        set(value) {
            field.fill(0)
            field = value.copyOf()
        }

    var clientNonce: ByteArray = ByteArray(16)
        set(value) {
            field.fill(0)
            field = value.copyOf()
        }

    var deviceNonce: ByteArray = ByteArray(16)
        set(value) {
            field.fill(0)
            field = value.copyOf()
        }

    var authSalt: ByteArray = ByteArray(16)
        set(value) {
            field.fill(0)
            field = value.copyOf()
        }

    var initialized: Boolean = false
    var awaitingDeviceInfo: Boolean = false
    var legacyFirmware: Boolean = false
    var reachedReady: Boolean = false

    fun nextSequence(): Int = sequence.also { sequence = (sequence + 1) and 0xffff }
    fun nextCommandId(): Long = commandId++

    fun setChallenge(
        sessionId: Long,
        deviceNonce: ByteArray,
        authSalt: ByteArray,
        initialized: Boolean,
    ) {
        require(deviceNonce.size == 16)
        require(authSalt.size == 16)
        this.sessionId = sessionId
        this.deviceNonce = deviceNonce
        this.authSalt = authSalt
        this.initialized = initialized
    }

    fun authenticate(token: ByteArray) {
        require(token.size == 8)
        sessionToken = token
    }

    fun authenticatedPayload(): ByteArray {
        val payload = ByteArray(12)
        putU32(payload, 0, sessionId)
        sessionToken.copyInto(payload, destinationOffset = 4)
        return payload
    }

    fun resetLink() {
        sessionToken = ByteArray(8)
        deviceNonce = ByteArray(16)
        authSalt = ByteArray(16)
        sessionId = 0
        awaitingDeviceInfo = false
        reachedReady = false
    }

    fun resetAll() {
        resetLink()
        clientNonce = ByteArray(16)
        sequence = 1
        commandId = 1
        initialized = false
        legacyFirmware = false
    }
}

sealed interface SessionEvent {
    data object Reset : SessionEvent
    data object LinkLost : SessionEvent
    data object Authenticating : SessionEvent
    data object Authenticated : SessionEvent
    data class StateReceived(val state: DeviceState, val nowMillis: Long) : SessionEvent
    data object CommandStarted : SessionEvent
    data class CommandCompleted(val nowMillis: Long) : SessionEvent
    data class Failed(val message: String) : SessionEvent
}

fun reduceSession(state: DplsUiState, event: SessionEvent): DplsUiState = when (event) {
    SessionEvent.Reset -> DplsUiState()
    SessionEvent.LinkLost -> state.copy(
        phase = ConnectionPhase.RECONNECTING,
        statusText = "Восстановление связи…",
        authenticated = false,
        credentialsReady = false,
        commandInProgress = false,
        staleState = state.state != null,
        identifyLedLive = false,
        error = null,
    )
    SessionEvent.Authenticating -> state.copy(
        phase = ConnectionPhase.AUTHENTICATING,
        statusText = "Подключение…",
        error = null,
    )
    SessionEvent.Authenticated -> state.copy(
        phase = ConnectionPhase.SYNCHRONIZING,
        statusText = "Чтение состояния…",
        authenticated = true,
        awaitingUserPassword = false,
        identifyActive = false,
        identifyLedLive = false,
        error = null,
    )
    is SessionEvent.StateReceived -> state.copy(
        phase = ConnectionPhase.READY,
        statusText = "Состояние получено",
        state = event.state,
        deviceBootEpochSeconds = event.nowMillis / 1000 - event.state.uptimeSeconds,
        authenticated = true,
        identifyActive = false,
        identifyLedLive = false,
        commandInProgress = false,
        staleState = false,
        lastAckMillis = event.nowMillis,
        error = null,
    )
    SessionEvent.CommandStarted -> state.copy(
        commandInProgress = true,
        pendingMode = null,
        statusText = "Команда отправлена…",
    )
    is SessionEvent.CommandCompleted -> state.copy(
        commandInProgress = false,
        statusText = "Команда применена, чтение состояния…",
        lastAckMillis = event.nowMillis,
    )
    is SessionEvent.Failed -> state.copy(
        phase = ConnectionPhase.ERROR,
        statusText = event.message,
        error = event.message,
        commandInProgress = false,
        logProgress = null,
    )
}
