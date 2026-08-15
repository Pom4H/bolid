package ru.bolid.testdpls.core.session

import ru.bolid.testdpls.core.protocol.putU32

/** Secret-bearing wire session state. Frame sequence is the only stored transaction id. */
class DplsSessionRuntime {
    var sequence: Int = 1
    var sessionId: Long = 0

    var sessionToken: ByteArray = ByteArray(8)
        set(value) { field.fill(0); field = value.copyOf() }
    var clientNonce: ByteArray = ByteArray(16)
        set(value) { field.fill(0); field = value.copyOf() }
    var deviceNonce: ByteArray = ByteArray(16)
        set(value) { field.fill(0); field = value.copyOf() }
    var authSalt: ByteArray = ByteArray(16)
        set(value) { field.fill(0); field = value.copyOf() }
    var initialized: Boolean = false

    fun nextSequence(): Int = sequence.also { sequence = (sequence + 1) and 0xffff }

    /** Transitional source-compatibility only; it aliases sequence and has no second counter/state. */
    @Deprecated("Protocol v2 uses nextSequence() as the transaction id")
    fun nextCommandId(): Long = nextSequence().toLong()

    fun setChallenge(sessionId: Long, deviceNonce: ByteArray, authSalt: ByteArray, initialized: Boolean) {
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

    fun authenticatedPayload(): ByteArray = ByteArray(12).also {
        putU32(it, 0, sessionId)
        sessionToken.copyInto(it, 4)
    }

    fun resetLink() {
        sessionToken = ByteArray(8)
        deviceNonce = ByteArray(16)
        authSalt = ByteArray(16)
        sessionId = 0
    }

    fun resetAll() {
        resetLink()
        clientNonce = ByteArray(16)
        sequence = 1
        initialized = false
    }
}
