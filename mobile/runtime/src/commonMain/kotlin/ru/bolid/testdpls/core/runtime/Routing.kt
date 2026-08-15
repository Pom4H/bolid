package ru.bolid.testdpls.core.runtime

/** Network envelope outside the DPLS end-device frame. */
data class RoutedPacket(
    val source: NodeId,
    val destination: NodeId,
    val packetId: UInt,
    val ttl: UByte,
    val payload: ByteArray,
)

interface PacketRouter {
    suspend fun route(packet: RoutedPacket)
}

/**
 * Passive channels (for example an RS-232 tap) deliberately cannot masquerade
 * as a controllable DPLS link.
 */
data class Observation(
    val source: String,
    val timestampMillis: Long,
    val bytes: ByteArray,
)

interface ObservationSource {
    val observations: kotlinx.coroutines.flow.Flow<Observation>
}

/** RSSI/neighbor reports can feed topology estimation without touching auth/control. */
data class NeighborSample(
    val observer: NodeId,
    val neighbor: NodeId,
    val rssi: Int,
    val timestampMillis: Long,
)
