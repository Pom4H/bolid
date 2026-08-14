package ru.bolid.testdpls.core.protocol

object DplsProtocol {
    const val VERSION: Byte = 1
    const val HEADER_SIZE = 7
    const val TRAILER_SIZE = 2
    const val OVERHEAD = HEADER_SIZE + TRAILER_SIZE

    enum class Type(val wire: Int) {
        HELLO(0x01), AUTH_CHALLENGE(0x02), AUTH_PROOF(0x03), AUTH_RESULT(0x04),
        SETUP(0x05), DEVICE_INFO_GET(0x06), DEVICE_INFO_REPORT(0x07),
        NAME_SET(0x08), PASSWORD_SET(0x09), SETTINGS_RESULT(0x0a),
        STATE_GET(0x10), STATE_REPORT(0x11), MODE_SET(0x12), COMMAND_RESULT(0x13),
        IDENTIFY_START(0x14), IDENTIFY_STOP(0x15), LOG_START(0x20), LOG_INFO(0x21),
        LOG_CHUNK(0x22), LOG_ACK(0x23), LOG_FINISH(0x24), LOG_RESULT(0x25),
        KEEP_ALIVE(0x30), ERROR(0x7f);

        companion object {
            fun fromWire(value: Int): Type? = entries.firstOrNull { it.wire == value }
        }
    }

    data class Frame(
        val type: Type,
        val sequence: Int,
        val flags: Int = 0,
        val payload: ByteArray = byteArrayOf(),
    )

    sealed interface DecodeResult {
        data class Success(val frame: Frame) : DecodeResult
        data class Failure(val reason: String) : DecodeResult
    }
}
