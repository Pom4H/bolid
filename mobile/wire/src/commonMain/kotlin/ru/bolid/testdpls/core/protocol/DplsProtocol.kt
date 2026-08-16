package ru.bolid.testdpls.core.protocol

object DplsProtocol {
    const val VERSION: Byte = 2
    const val HEADER_SIZE = 7
    const val TRAILER_SIZE = 2
    const val OVERHEAD = HEADER_SIZE + TRAILER_SIZE
    const val MAX_PAYLOAD = 235
    const val MAX_FRAME = OVERHEAD + MAX_PAYLOAD
    const val TIME_MIN_UNIX_SECONDS = 1_577_836_800L
    const val TIME_MAX_UNIX_SECONDS = 4_102_444_799L

    object Flags {
        const val REQUEST = 1 shl 0
        const val RESPONSE = 1 shl 1
        const val EVENT = 1 shl 2
        const val ERROR = 1 shl 3
    }

    enum class Type(val wire: Int) {
        HELLO(0x01), AUTH_CHALLENGE(0x02), AUTH_PROOF(0x03), AUTH_RESULT(0x04),
        SETUP(0x05), DEVICE_INFO_GET(0x06), DEVICE_INFO_REPORT(0x07),
        NAME_SET(0x08), PASSWORD_SET(0x09), SETTINGS_RESULT(0x0a), TIME_SYNC(0x0b),
        STATE_GET(0x10), STATE_REPORT(0x11), MODE_SET(0x12), COMMAND_RESULT(0x13),
        IDENTIFY_START(0x14), IDENTIFY_STOP(0x15), LOG_START(0x20), LOG_INFO(0x21),
        LOG_CHUNK(0x22), LOG_ACK(0x23), LOG_FINISH(0x24), LOG_RESULT(0x25),
        LOG_HIST_GET(0x26), LOG_HIST_REPORT(0x27), KEEP_ALIVE(0x30), ERROR(0x7f);

        companion object { fun fromWire(value: Int): Type? = entries.firstOrNull { it.wire == value } }
    }

    data class Frame(
        val type: Type,
        val sequence: Int,
        val flags: Int = 0,
        val payload: ByteArray = byteArrayOf(),
    ) {
        val isRequest: Boolean get() = flags and Flags.REQUEST != 0
        val isResponse: Boolean get() = flags and Flags.RESPONSE != 0
        val isEvent: Boolean get() = flags and Flags.EVENT != 0
        val isError: Boolean get() = flags and Flags.ERROR != 0 || type == Type.ERROR
    }

    sealed interface DecodeResult {
        data class Success(val frame: Frame) : DecodeResult
        data class Failure(val reason: String) : DecodeResult
    }
}
