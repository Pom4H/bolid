package ru.bolid.testdpls.core.runtime

fun credentialKey(nodeId: NodeId): String = "node:${nodeId.value}"
