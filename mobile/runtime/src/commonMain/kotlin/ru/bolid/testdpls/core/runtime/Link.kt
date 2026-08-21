package ru.bolid.testdpls.core.runtime

import kotlin.jvm.JvmInline

/** Стабильный ID устройства. BLE-адрес identity не является. */
@JvmInline
value class NodeId(val value: Long) {
    init { require(value in 1..0xffff_ffffL) }

    override fun toString(): String =
        value.toString(16).uppercase().padStart(8, '0')
}

/** Физический маршрут до устройства. Сейчас поддерживается только BLE. */
sealed interface LinkEndpoint {
    data class Ble(val address: String) : LinkEndpoint
}

sealed interface LinkFailure {
    data class Protocol(val detail: String) : LinkFailure
    data class Platform(val detail: String) : LinkFailure
}
