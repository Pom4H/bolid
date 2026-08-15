package ru.bolid.testdpls.core.runtime

/** Stable identity is a node id; a BLE address/serial port/mesh route is only a way to reach it. */
data class NodeRef(
    val id: NodeId,
    val lastEndpoint: LinkEndpoint,
    val displayName: String,
)

fun credentialKey(nodeId: NodeId): String = "node:${nodeId.value}"
