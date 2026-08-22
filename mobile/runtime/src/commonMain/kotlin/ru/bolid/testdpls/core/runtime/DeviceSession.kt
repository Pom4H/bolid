package ru.bolid.testdpls.core.runtime

import ru.bolid.testdpls.core.protocol.putU32

/** Challenge протокола хранится прямо в состоянии соединения, которое его получило. */
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

/** Данные подтверждённой сессии. Второй копии в controller/UI нет. */
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
 * Единственное значение, описывающее link/auth lifecycle.
 *
 * candidateNodeId приходит из advertising и остаётся неподтверждённой подсказкой,
 * пока DEVICE_INFO не вернёт стабильный NodeId после аутентификации.
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

    /** BLE готов к протоколу; nonce принадлежит текущей попытке соединения. */
    data class Linked(
        val endpoint: LinkEndpoint,
        val clientNonce: ByteArray,
        val candidateNodeId: NodeId? = null,
    ) : DeviceSession {
        init { require(clientNonce.size == 16) }
    }

    /**
     * Получен AUTH_CHALLENGE. challenge.initialized определяет, нужна первичная
     * настройка или обычная аутентификация; отдельные состояния для этого не нужны.
     */
    data class Securing(
        val endpoint: LinkEndpoint,
        val challenge: SessionChallenge,
        val candidateNodeId: NodeId? = null,
    ) : DeviceSession

    /** Аутентификация прошла, но стабильный NodeId ещё не подтверждён. */
    data class Synchronizing(
        val endpoint: LinkEndpoint,
        val auth: AuthSession,
        val candidateNodeId: NodeId? = null,
    ) : DeviceSession

    /** Полностью готовая сессия с подтверждённым стабильным NodeId. */
    data class Online(
        val nodeId: NodeId,
        val endpoint: LinkEndpoint,
        val auth: AuthSession,
    ) : DeviceSession

    /** Маршрут известен, физическое соединение и auth нужно построить заново. */
    data class Recovering(
        val nodeId: NodeId?,
        val endpoint: LinkEndpoint,
    ) : DeviceSession

    data class Failed(
        val endpoint: LinkEndpoint?,
        val failure: LinkFailure,
    ) : DeviceSession
}

val DeviceSession.isAuthenticated: Boolean
    get() = this is DeviceSession.Synchronizing || this is DeviceSession.Online

val DeviceSession.credentialsReady: Boolean
    get() = this is DeviceSession.Securing ||
        this is DeviceSession.Synchronizing ||
        this is DeviceSession.Online

val DeviceSession.challengeOrNull: SessionChallenge?
    get() = (this as? DeviceSession.Securing)?.challenge

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
        is DeviceSession.Securing -> endpoint
        is DeviceSession.Synchronizing -> endpoint
        is DeviceSession.Online -> endpoint
        is DeviceSession.Recovering -> endpoint
        is DeviceSession.Failed -> endpoint
    }

/** Только подтверждённый identity. Discovery/UI fallback здесь запрещён. */
val DeviceSession.nodeIdOrNull: NodeId?
    get() = when (this) {
        is DeviceSession.Online -> nodeId
        is DeviceSession.Recovering -> nodeId
        else -> null
    }

/** Неподтверждённая подсказка из advertising для проверки identity consistency. */
val DeviceSession.candidateNodeIdOrNull: NodeId?
    get() = when (this) {
        is DeviceSession.Connecting -> candidateNodeId
        is DeviceSession.Discovering -> candidateNodeId
        is DeviceSession.Linked -> candidateNodeId
        is DeviceSession.Securing -> candidateNodeId
        is DeviceSession.Synchronizing -> candidateNodeId
        is DeviceSession.Online -> nodeId
        is DeviceSession.Recovering -> nodeId
        DeviceSession.Offline, is DeviceSession.Failed -> null
    }
