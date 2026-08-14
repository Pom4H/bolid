package ru.bolid.testdpls.core.session

import ru.bolid.testdpls.core.protocol.hexToBytesOrNull
import ru.bolid.testdpls.core.protocol.toHexString

/** Primitive/String facade over [DplsSessionRuntime] for Swift interop. */
class DplsSessionBridge {
    private val runtime = DplsSessionRuntime()

    var sequence: Int
        get() = runtime.sequence
        set(value) { runtime.sequence = value and 0xffff }

    var commandId: Long
        get() = runtime.commandId
        set(value) { runtime.commandId = value }

    var sessionId: Long
        get() = runtime.sessionId
        set(value) { runtime.sessionId = value }

    var initialized: Boolean
        get() = runtime.initialized
        set(value) { runtime.initialized = value }

    var awaitingDeviceInfo: Boolean
        get() = runtime.awaitingDeviceInfo
        set(value) { runtime.awaitingDeviceInfo = value }

    var legacyFirmware: Boolean
        get() = runtime.legacyFirmware
        set(value) { runtime.legacyFirmware = value }

    var reachedReady: Boolean
        get() = runtime.reachedReady
        set(value) { runtime.reachedReady = value }

    var sessionTokenHex: String
        get() = runtime.sessionToken.toHexString()
        set(value) { setFixedHex(value, 8) { runtime.sessionToken = it } }

    var clientNonceHex: String
        get() = runtime.clientNonce.toHexString()
        set(value) { setFixedHex(value, 16) { runtime.clientNonce = it } }

    var deviceNonceHex: String
        get() = runtime.deviceNonce.toHexString()
        set(value) { setFixedHex(value, 16) { runtime.deviceNonce = it } }

    var authSaltHex: String
        get() = runtime.authSalt.toHexString()
        set(value) { setFixedHex(value, 16) { runtime.authSalt = it } }

    val authenticatedPayloadHex: String
        get() = runtime.authenticatedPayload().toHexString()

    fun nextSequence(): Int = runtime.nextSequence()
    fun nextCommandId(): Long = runtime.nextCommandId()
    fun resetLink() = runtime.resetLink()
    fun resetAll() = runtime.resetAll()

    private inline fun setFixedHex(value: String, bytes: Int, assign: (ByteArray) -> Unit) {
        val decoded = requireNotNull(value.hexToBytesOrNull()) { "invalid hex" }
        require(decoded.size == bytes) { "expected $bytes bytes" }
        try {
            assign(decoded)
        } finally {
            decoded.fill(0)
        }
    }
}
