package ru.bolid.testdpls.core.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.bolid.testdpls.core.runtime.NodeId

class DplsCredentialsTest {
    @Test
    fun replacingVerifierZeroesPreviousSecret() {
        val credentials = DplsCredentials(FakePlatform())
        val first = ByteArray(32) { 0x55 }
        val second = ByteArray(32) { 0x22 }

        credentials.replace(first)
        credentials.replace(second)

        assertTrue(first.all { it == 0.toByte() })
        assertContentEquals(second, credentials.currentOrNull())
    }

    @Test
    fun provisionalCredentialUsesCurrentEndpointUntilNodeIsVerified() {
        val platform = FakePlatform()
        val credentials = DplsCredentials(platform)
        val verifier = ByteArray(32) { it.toByte() }
        credentials.replace(verifier)

        credentials.persist(nodeId = null, bleEndpoint = "ble-1")

        assertTrue(platform.contains("endpoint:ble-1"))
        assertFalse(platform.contains("addr:ble-1"))
        assertFalse(platform.contains("legacy-addr:ble-1"))
        assertFalse(platform.contains("node:4660"))

        credentials.persist(nodeId = NodeId(0x1234), bleEndpoint = "ble-1")

        assertTrue(platform.contains("node:4660"))
        assertTrue(platform.contains("endpoint:ble-1"))
    }

    private class FakePlatform : DplsPlatformServices {
        private val verifiers = mutableMapOf<String, ByteArray>()

        override fun nowMillis() = 0L

        override fun secureRandomBytes(count: Int) = ByteArray(count)

        override fun readDeviceVerifier(deviceKey: String): ByteArray? =
            verifiers[deviceKey]?.copyOf()

        override fun writeDeviceVerifier(deviceKey: String, verifier: ByteArray?) {
            if (verifier == null) {
                verifiers.remove(deviceKey)
            } else {
                verifiers[deviceKey] = verifier.copyOf()
            }
        }

        fun contains(key: String) = key in verifiers
    }
}
