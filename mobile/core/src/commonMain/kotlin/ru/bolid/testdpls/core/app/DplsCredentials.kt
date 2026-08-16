package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.protocol.DplsAuth
import ru.bolid.testdpls.core.runtime.NodeId
import ru.bolid.testdpls.core.runtime.credentialKey

/**
 * Owns the one in-memory verifier and its persistence/zeroization rules.
 *
 * The caller may pass only a verified [NodeId]. An advertised candidate id never
 * reaches this type, so it cannot select another device's saved verifier.
 */
internal class DplsCredentials(
    private val platform: DplsPlatformServices,
) {
    private var verifier: ByteArray? = null

    val available: Boolean
        get() = verifier != null

    fun currentOrNull(): ByteArray? = verifier

    fun owns(value: ByteArray): Boolean = verifier === value

    fun replace(value: ByteArray?) {
        if (verifier !== value) verifier?.fill(0)
        verifier = value
    }

    fun load(nodeId: NodeId?, bleAddress: String?): Boolean {
        val stored = deviceStorageKeys(nodeId, bleAddress).firstNotNullOfOrNull { key ->
            platform.readDeviceVerifier(key)
                ?.takeIf { it.size == DplsAuth.VERIFIER_SIZE }
        }
        replace(stored)
        return stored != null
    }

    fun persist(nodeId: NodeId?, bleAddress: String?) {
        val stored = verifier
        deviceStorageKeys(nodeId, bleAddress).forEach { key ->
            platform.writeDeviceVerifier(key, stored)
        }
    }

    fun forget(nodeId: NodeId?, bleAddress: String?) {
        deviceStorageKeys(nodeId, bleAddress).forEach { key ->
            platform.writeDeviceVerifier(key, null)
        }
        replace(null)
    }

    fun matches(candidate: ByteArray): Boolean {
        val expected = verifier ?: return false
        if (expected.size != candidate.size) return false
        var diff = 0
        for (index in expected.indices) {
            diff = diff or (expected[index].toInt() xor candidate[index].toInt())
        }
        return diff == 0
    }
}

/** Stable-node keys first, then BLE migration keys. Candidate node ids are forbidden. */
internal fun deviceStorageKeys(nodeId: NodeId?, bleAddress: String?): List<String> = buildList {
    if (nodeId != null) {
        add(credentialKey(nodeId))
        add("id:${nodeId.value}")
    }
    bleAddress?.takeIf { it.isNotBlank() }?.let {
        add("legacy-addr:$it")
        add("addr:$it")
    }
}.distinct()
