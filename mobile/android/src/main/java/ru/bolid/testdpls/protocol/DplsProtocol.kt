package ru.bolid.testdpls.protocol

import java.nio.ByteBuffer
import ru.bolid.testdpls.core.protocol.DplsProtocol as CoreProtocol
import ru.bolid.testdpls.core.protocol.crc16CcittFalse
import ru.bolid.testdpls.core.protocol.decodeFrame
import ru.bolid.testdpls.core.protocol.encodeFrame

/** Android compatibility facade. Protocol semantics live in the KMP core. */
object DplsProtocol {
    const val VERSION: Byte = CoreProtocol.VERSION
    const val HEADER_SIZE = CoreProtocol.HEADER_SIZE
    const val TRAILER_SIZE = CoreProtocol.TRAILER_SIZE
    const val OVERHEAD = CoreProtocol.OVERHEAD

    typealias Type = CoreProtocol.Type
    typealias Frame = CoreProtocol.Frame
    typealias DecodeResult = CoreProtocol.DecodeResult

    fun encode(frame: Frame): ByteArray = encodeFrame(frame)
    fun decode(bytes: ByteArray): DecodeResult = decodeFrame(bytes)
    fun crc16(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int =
        crc16CcittFalse(bytes, offset, length)
}

fun ByteBuffer.putU32(value: Long): ByteBuffer = putInt(value.toInt())
fun ByteBuffer.u8(): Int = get().toInt() and 0xff
fun ByteBuffer.u16(): Int = short.toInt() and 0xffff
fun ByteBuffer.u32(): Long = int.toLong() and 0xffff_ffffL
