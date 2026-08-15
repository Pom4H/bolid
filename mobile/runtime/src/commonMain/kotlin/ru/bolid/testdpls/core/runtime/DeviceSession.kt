package ru.bolid.testdpls.core.runtime

data class AuthSession(
    val sessionId: Long,
    val token: ByteArray,
) {
    init { require(token.size == 8) }
}

/** Connection truth without product/UI state. */
sealed interface DeviceSession {
    data object Offline : DeviceSession
    data class Connecting(val endpoint: LinkEndpoint) : DeviceSession
    data class Commissioning(val endpoint: LinkEndpoint, val sessionId: Long) : DeviceSession
    data class Authenticating(val endpoint: LinkEndpoint, val sessionId: Long) : DeviceSession
    data class Online(
        val nodeId: NodeId,
        val endpoint: LinkEndpoint,
        val auth: AuthSession,
    ) : DeviceSession
    data class Recovering(
        val nodeId: NodeId?,
        val endpoint: LinkEndpoint,
        val attempt: Int,
    ) : DeviceSession
    data class Failed(val endpoint: LinkEndpoint?, val failure: LinkFailure) : DeviceSession
}

val DeviceSession.isOnline: Boolean get() = this is DeviceSession.Online
val DeviceSession.nodeIdOrNull: NodeId?
    get() = when (this) {
        is DeviceSession.Online -> nodeId
        is DeviceSession.Recovering -> nodeId
        else -> null
    }
