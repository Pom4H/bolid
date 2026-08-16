package ru.bolid.testdpls.core.runtime

/** Stable device identity. It is deliberately not a BLE address. */
@JvmInline
value class NodeId(val value: Long) {
    init { require(value in 1..0xffff_ffffL) }

    override fun toString(): String =
        value.toString(16).uppercase().padStart(8, '0')
}

/** Current physical route to a device. More endpoint kinds belong to their feature PRs. */
sealed interface LinkEndpoint {
    data class Ble(val address: String) : LinkEndpoint
}

sealed interface LinkFailure {
    data object StaleCredentials : LinkFailure
    data object Unavailable : LinkFailure
    data object Closed : LinkFailure
    data class Protocol(val detail: String) : LinkFailure
    data class Platform(val detail: String) : LinkFailure
}
