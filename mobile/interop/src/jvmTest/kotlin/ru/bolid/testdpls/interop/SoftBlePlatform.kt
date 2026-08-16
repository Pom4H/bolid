package ru.bolid.testdpls.interop

import ru.bolid.testdpls.core.app.DplsPlatformServices

/** Deterministic platform services for soft-BLE JVM bridge tests. */
class SoftBlePlatform : DplsPlatformServices {
    private val verifiers = mutableMapOf<String, ByteArray>()
    var now: Long = 1_786_732_800_000L
    var keepAlive: Boolean = false
        private set

    override fun nowMillis(): Long = now

    override fun secureRandomBytes(count: Int): ByteArray =
        ByteArray(count) { index -> (0xA0 + index).toByte() }

    override fun readDeviceVerifier(deviceKey: String): ByteArray? =
        verifiers[deviceKey]?.copyOf()

    override fun writeDeviceVerifier(deviceKey: String, verifier: ByteArray?) {
        if (verifier == null) {
            verifiers.remove(deviceKey)
        } else {
            verifiers[deviceKey] = verifier.copyOf()
        }
    }

    override fun keepConnectionAlive(active: Boolean) {
        keepAlive = active
    }

    override fun sessionTrace(message: String) {
        System.err.println("TestDplsSession: $message")
    }

    fun hasVerifier(key: String): Boolean = key in verifiers
}
