package ru.bolid.testdpls.core.protocol

/** Protocol-level authentication constants and byte layout shared by both clients. */
object DplsAuth {
    const val PBKDF2_ITERATIONS = 10_000
    const val VERIFIER_SIZE = 32
    const val NONCE_SIZE = 16
    const val SALT_SIZE = 16
    const val TOKEN_SIZE = 8

    fun proofMessage(deviceNonce: ByteArray, clientNonce: ByteArray, sessionId: Long): ByteArray {
        require(deviceNonce.size == NONCE_SIZE)
        require(clientNonce.size == NONCE_SIZE)
        val result = ByteArray(NONCE_SIZE * 2 + 4)
        deviceNonce.copyInto(result, 0)
        clientNonce.copyInto(result, NONCE_SIZE)
        putU32(result, NONCE_SIZE * 2, sessionId)
        return result
    }
}
