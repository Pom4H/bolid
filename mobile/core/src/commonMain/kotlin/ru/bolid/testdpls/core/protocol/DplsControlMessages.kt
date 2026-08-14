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
    val commandId: Long,
    val status: Int,
    val mode: DplsMode?,
    val automaticReturnSeconds: Int,
)

data class SettingsResult(val commandId: Long, val status: Int)

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
    return AuthResult(
        status = status,
        retryAfterSeconds = readU16(raw, 1),
        sessionToken = token,
    )
}

fun parseCommandResult(raw: ByteArray): CommandResult? {
    if (raw.size < 8) return null
    return CommandResult(
        commandId = readU32(raw, 0),
        status = raw[4].toInt() and 0xff,
        mode = DplsMode.fromWire(raw[5].toInt() and 0xff),
        automaticReturnSeconds = readU16(raw, 6),
    )
}

fun parseSettingsResult(raw: ByteArray): SettingsResult? {
    if (raw.size < 5) return null
    return SettingsResult(readU32(raw, 0), raw[4].toInt() and 0xff)
}
