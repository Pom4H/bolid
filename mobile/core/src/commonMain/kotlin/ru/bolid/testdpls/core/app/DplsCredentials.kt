package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.protocol.DplsAuth
import ru.bolid.testdpls.core.runtime.NodeId
import ru.bolid.testdpls.core.runtime.credentialKey

/**
 * Владеет единственным verifier в памяти и правилами его хранения/обнуления.
 *
 * Долговременный ключ устройства — только подтверждённый [NodeId]. До получения
 * DEVICE_INFO verifier может временно храниться по текущему BLE endpoint, чтобы
 * пережить reboot после первичной настройки. Endpoint — маршрут, а не identity:
 * после DEVICE_INFO verifier обязательно сохраняется по NodeId.
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

    fun load(nodeId: NodeId?, bleEndpoint: String?): Boolean {
        val stored = deviceStorageKeys(nodeId, bleEndpoint).firstNotNullOfOrNull { key ->
            platform.readDeviceVerifier(key)
                ?.takeIf { it.size == DplsAuth.VERIFIER_SIZE }
        }
        replace(stored)
        return stored != null
    }

    fun persist(nodeId: NodeId?, bleEndpoint: String?) {
        val stored = verifier
        deviceStorageKeys(nodeId, bleEndpoint).forEach { key ->
            platform.writeDeviceVerifier(key, stored)
        }
    }

    fun forget(nodeId: NodeId?, bleEndpoint: String?) {
        deviceStorageKeys(nodeId, bleEndpoint).forEach { key ->
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

/**
 * Канонические ключи текущей схемы. Старые `id:`, `addr:` и `legacy-addr:`
 * намеренно не читаются: до выхода в серию миграционная совместимость не нужна.
 */
internal fun deviceStorageKeys(nodeId: NodeId?, bleEndpoint: String?): List<String> = buildList {
    if (nodeId != null) add(credentialKey(nodeId))
    bleEndpoint?.takeIf { it.isNotBlank() }?.let { add("endpoint:$it") }
}.distinct()
