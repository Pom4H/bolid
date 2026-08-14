package ru.bolid.testdpls.core.protocol

/**
 * Stable primitive/String boundary for Swift/Objective-C interop.
 *
 * Frames are small BLE messages, so hex keeps the exported API independent of
 * KotlinByteArray ABI details while the actual codec remains common Kotlin.
 */
class DplsCodecBridge {
    fun encodeHex(type: Int, sequence: Int, flags: Int, payloadHex: String): String? {
        val messageType = DplsProtocol.Type.fromWire(type) ?: return null
        val payload = payloadHex.hexToBytesOrNull() ?: return null
        return encodeFrame(DplsProtocol.Frame(messageType, sequence, flags, payload)).toHexString()
    }

    fun decodeHex(frameHex: String): DplsDecodedFrame? {
        val bytes = frameHex.hexToBytesOrNull() ?: return null
        return when (val result = decodeFrame(bytes)) {
            is DplsProtocol.DecodeResult.Success -> DplsDecodedFrame(
                type = result.frame.type.wire,
                sequence = result.frame.sequence,
                flags = result.frame.flags,
                payloadHex = result.frame.payload.toHexString(),
                error = null,
            )
            is DplsProtocol.DecodeResult.Failure -> DplsDecodedFrame(
                type = -1,
                sequence = 0,
                flags = 0,
                payloadHex = "",
                error = result.reason,
            )
        }
    }

    fun crc16Hex(bytesHex: String): Int =
        bytesHex.hexToBytesOrNull()?.let(::crc16CcittFalse) ?: -1
}

class DplsDecodedFrame(
    val type: Int,
    val sequence: Int,
    val flags: Int,
    val payloadHex: String,
    val error: String?,
)
