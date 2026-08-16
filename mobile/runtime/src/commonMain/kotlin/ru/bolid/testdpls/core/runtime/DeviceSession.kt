package ru.bolid.testdpls.core.runtime

import ru.bolid.testdpls.core.protocol.putU32

/** Wire challenge owned by the lifecycle state that received it. */
data class SessionChallenge(
    val sessionId: Long,
    val clientNonce: ByteArray,
    val deviceNonce: ByteArray,
    val authSalt: ByteArray,
    val initialized: Boolean,
) {
    init {
        require(clientNonce.size == 16)
        require(deviceNonce.size == 16)
        require(authSalt.size == 16)
    }
}

/** Authenticated wire material. There is no second copy in the controller/UI. */
data class AuthSession(
    val sessionId: Long,
    val token: ByteArray,
    val authSalt: ByteArray,
) {
    init {
        require(token.size == 8)
        require(authSalt.size == 16)
    }

    fun authenticatedPayload(): ByteArray = ByteArray(12).also {
        putU32(it, 0, sessionId)
        token.copyInto(it, 4)
    }
}

/**
 * Single source of truth for link/auth lifecycle.
 *
 * UI fields are projections of this state and must never drive protocol decisions.
 */
sealed interface DeviceSession {
    data object Offline : DeviceSession

    data class Connecting(
        val endpoint: LinkEndpoint,
    ) : DeviceSession

    /** Link is usable and owns the nonce that will start HELLO/auth. */
    data class Linked(
        val endpoint: LinkEndpoint,
        val clientNonce: ByteArray,
    ) : DeviceSession {
        init { require(clientNonce.size == 16) }
    }

    data class Commissioning(
        val endpoint: LinkEndpoint,
        val challenge: SessionChallenge,
    ) : DeviceSession

    data class Authenticating(
        val endpoint: LinkEndpoint,
        val challenge: SessionChallenge,
    ) : DeviceSession

    /** Authentication is complete. Node identity may arrive a moment later in DEVICE_INFO. */
    data class Online(
        val nodeId: NodeId?,
        val endpoint: LinkEndpoint,
        val auth: AuthSession,
    ) : DeviceSession

    data class Recovering(
        val nodeId: NodeId?,
        val endpoint: LinkEndpoint,
        val attempt: Int,
    ) : DeviceSession

    data class Failed(
        val endpoint: LinkEndpoint?,
        val failure: LinkFailure,
    ) : DeviceSession
}

val DeviceSession.isAuthenticated: Boolean get() = this is DeviceSession.Online

val DeviceSession.credentialsReady: Boolean
    get() = this is DeviceSession.Commissioning ||
        this is DeviceSession.Authenticating ||
        this is DeviceSession.Online

val DeviceSession.initializedOrNull: Boolean?
    get() = when (this) {
        is DeviceSession.Commissioning -> challenge.initialized
        is DeviceSession.Authenticating -> challenge.initialized
        is DeviceSession.Online -> true
        else -> null
    }

val DeviceSession.challengeOrNull: SessionChallenge?
    get() = when (this) {
        is DeviceSession.Commissioning -> challenge
        is DeviceSession.Authenticating -> challenge
        else -> null
    }

val DeviceSession.authOrNull: AuthSession?
    get() = (this as? DeviceSession.Online)?.auth

val DeviceSession.endpointOrNull: LinkEndpoint?
    get() = when (this) {
        DeviceSession.Offline -> null
        is DeviceSession.Connecting -> endpoint
        is DeviceSession.Linked -> endpoint
        is DeviceSession.Commissioning -> endpoint
        is DeviceSession.Authenticating -> endpoint
        is DeviceSession.Online -> endpoint
        is DeviceSession.Recovering -> endpoint
        is DeviceSession.Failed -> endpoint
    }

val DeviceSession.nodeIdOrNull: NodeId?
    get() = when (this) {
        is DeviceSession.Online -> nodeId
        is DeviceSession.Recovering -> nodeId
        else -> null
    }
