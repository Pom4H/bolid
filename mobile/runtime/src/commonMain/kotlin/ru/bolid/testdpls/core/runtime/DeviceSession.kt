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

enum class RecoveryReason {
    LINK_LOSS,
    SETUP_APPLIED,
}

/**
 * The only owner of link/auth/recovery lifecycle.
 *
 * [candidateNodeId] is an untrusted discovery hint. It is used only to check that
 * authenticated DEVICE_INFO agrees with the advertisement; credentials are never
 * selected by this value before identity proof.
 */
sealed interface DeviceSession {
    data object Offline : DeviceSession

    data class Connecting(
        val endpoint: LinkEndpoint,
        val candidateNodeId: NodeId? = null,
    ) : DeviceSession

    data class Discovering(
        val endpoint: LinkEndpoint,
        val candidateNodeId: NodeId? = null,
    ) : DeviceSession

    /** Link is usable and owns the nonce that will start HELLO/auth. */
    data class Linked(
        val endpoint: LinkEndpoint,
        val clientNonce: ByteArray,
        val candidateNodeId: NodeId? = null,
    ) : DeviceSession {
        init { require(clientNonce.size == 16) }
    }

    data class Commissioning(
        val endpoint: LinkEndpoint,
        val challenge: SessionChallenge,
        val candidateNodeId: NodeId? = null,
    ) : DeviceSession

    data class Authenticating(
        val endpoint: LinkEndpoint,
        val challenge: SessionChallenge,
        val candidateNodeId: NodeId? = null,
    ) : DeviceSession

    /** Authentication succeeded; DEVICE_INFO must still prove the stable identity. */
    data class Synchronizing(
        val endpoint: LinkEndpoint,
        val auth: AuthSession,
        val candidateNodeId: NodeId? = null,
    ) : DeviceSession

    /** Fully usable session. A valid stable node identity is mandatory here. */
    data class Online(
        val nodeId: NodeId,
        val endpoint: LinkEndpoint,
        val auth: AuthSession,
    ) : DeviceSession

    data class Recovering(
        val nodeId: NodeId?,
        val endpoint: LinkEndpoint,
        val attempt: Int = 0,
        val reason: RecoveryReason = RecoveryReason.LINK_LOSS,
    ) : DeviceSession {
        init { require(attempt >= 0) }
    }

    data class Failed(
        val endpoint: LinkEndpoint?,
        val failure: LinkFailure,
    ) : DeviceSession
}

val DeviceSession.isAuthenticated: Boolean
    get() = this is DeviceSession.Synchronizing || this is DeviceSession.Online

val DeviceSession.credentialsReady: Boolean
    get() = this is DeviceSession.Commissioning ||
        this is DeviceSession.Authenticating ||
        this is DeviceSession.Synchronizing ||
        this is DeviceSession.Online

val DeviceSession.initializedOrNull: Boolean?
    get() = when (this) {
        is DeviceSession.Commissioning -> challenge.initialized
        is DeviceSession.Authenticating -> challenge.initialized
        is DeviceSession.Synchronizing, is DeviceSession.Online -> true
        else -> null
    }

val DeviceSession.challengeOrNull: SessionChallenge?
    get() = when (this) {
        is DeviceSession.Commissioning -> challenge
        is DeviceSession.Authenticating -> challenge
        else -> null
    }

val DeviceSession.authOrNull: AuthSession?
    get() = when (this) {
        is DeviceSession.Synchronizing -> auth
        is DeviceSession.Online -> auth
        else -> null
    }

val DeviceSession.endpointOrNull: LinkEndpoint?
    get() = when (this) {
        DeviceSession.Offline -> null
        is DeviceSession.Connecting -> endpoint
        is DeviceSession.Discovering -> endpoint
        is DeviceSession.Linked -> endpoint
        is DeviceSession.Commissioning -> endpoint
        is DeviceSession.Authenticating -> endpoint
        is DeviceSession.Synchronizing -> endpoint
        is DeviceSession.Online -> endpoint
        is DeviceSession.Recovering -> endpoint
        is DeviceSession.Failed -> endpoint
    }

/** Verified identity only. Never falls back to discovery/UI state. */
val DeviceSession.nodeIdOrNull: NodeId?
    get() = when (this) {
        is DeviceSession.Online -> nodeId
        is DeviceSession.Recovering -> nodeId
        else -> null
    }

/** Untrusted discovery hint used only for identity consistency checks. */
val DeviceSession.candidateNodeIdOrNull: NodeId?
    get() = when (this) {
        is DeviceSession.Connecting -> candidateNodeId
        is DeviceSession.Discovering -> candidateNodeId
        is DeviceSession.Linked -> candidateNodeId
        is DeviceSession.Commissioning -> candidateNodeId
        is DeviceSession.Authenticating -> candidateNodeId
        is DeviceSession.Synchronizing -> candidateNodeId
        is DeviceSession.Online -> nodeId
        is DeviceSession.Recovering -> nodeId
        DeviceSession.Offline, is DeviceSession.Failed -> null
    }
