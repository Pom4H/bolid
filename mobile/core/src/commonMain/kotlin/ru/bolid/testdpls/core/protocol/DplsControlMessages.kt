package ru.bolid.testdpls.core.protocol

import ru.bolid.testdpls.core.domain.DplsMode

data class AuthChallenge(
    val sessionId: Long,
    val deviceNonce: ByteArray,
    val salt: ByteArray,
    val initialized: Boolean,
)

data class AuthResult(
    val status: Int,
    val retryAfterSeconds: Int,
    val sessionToken: ByteArray?,
)

data class CommandResult(
    val status: Int,
    val mode: DplsMode?,
    val automaticReturnSeconds: Int,
)

data class SettingsResult(val status: Int)

fun parseAuthChallenge(raw: ByteArray): AuthChallenge? {
    if (raw.size < 37) return null
    return AuthChallenge(
        sessionId = readU32(raw, 0),
        deviceNonce = raw.copyOfRange(4, 20),
        salt = raw.copyOfRange(20, 36),
        initialized = raw[36].toInt() != 0,
    )
}

fun parseAuthResult(raw: ByteArray): AuthResult? {
    if (raw.size < 3) return null
    val status = raw[0].toInt() and 0xff
    val token = if (status == 0 && raw.size >= 11) raw.copyOfRange(3, 11) else null
    return AuthResult(status, readU16(raw, 1), token)
}

fun parseCommandResult(raw: ByteArray): CommandResult? {
    if (raw.size != 4) return null
    return CommandResult(
        status = raw[0].toInt() and 0xff,
        mode = DplsMode.fromWire(raw[1].toInt() and 0xff),
        automaticReturnSeconds = readU16(raw, 2),
    )
}

fun parseSettingsResult(raw: ByteArray): SettingsResult? =
    raw.singleOrNull()?.let { SettingsResult(it.toInt() and 0xff) }

fun buildTimeSyncPayload(sessionId: Long, sessionToken: ByteArray, unixSeconds: Long): ByteArray? {
    if (sessionToken.size != 8 || unixSeconds !in DplsProtocol.TIME_MIN_UNIX_SECONDS..DplsProtocol.TIME_MAX_UNIX_SECONDS) return null
    return ByteArray(16).also { payload ->
        putU32(payload, 0, sessionId)
        sessionToken.copyInto(payload, 4)
        putU32(payload, 12, unixSeconds)
    }
}
