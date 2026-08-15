package ru.bolid.testdpls.core.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@JvmInline
value class NodeId(val value: Long) {
    init { require(value in 1..0xffff_ffffL) }
    override fun toString(): String = value.toString(16).uppercase().padStart(8, '0')
}

sealed interface LinkEndpoint {
    data class Ble(val address: String) : LinkEndpoint
    data class Serial(val port: String) : LinkEndpoint
    data class Routed(val gateway: NodeId, val target: NodeId) : LinkEndpoint
}

enum class Delivery { RELIABLE, STREAM }

data class LinkMetrics(
    val rssi: Int? = null,
    val mtu: Int? = null,
    val routeHops: Int = 0,
)

sealed interface LinkFailure {
    data object StaleCredentials : LinkFailure
    data object Unavailable : LinkFailure
    data object Closed : LinkFailure
    data class Protocol(val detail: String) : LinkFailure
    data class Platform(val detail: String) : LinkFailure
}

/**
 * Transport-neutral byte pipe. GATT queues, bonding, UART framing and mesh
 * routing stay below this boundary. Runtime code only owns DPLS frames.
 */
interface ByteLink {
    val endpoint: LinkEndpoint
    val incoming: Flow<ByteArray>
    val metrics: StateFlow<LinkMetrics>

    suspend fun send(bytes: ByteArray, delivery: Delivery = Delivery.RELIABLE)
    suspend fun close()
}

data class DiscoveredEndpoint(
    val endpoint: LinkEndpoint,
    val nodeId: NodeId?,
    val displayName: String,
    val metrics: LinkMetrics = LinkMetrics(),
    val advertisementStatus: Int = 0,
)

/** Discovery and an established link are deliberately independent concepts. */
interface DiscoverySource {
    fun discover(): Flow<DiscoveredEndpoint>
}
