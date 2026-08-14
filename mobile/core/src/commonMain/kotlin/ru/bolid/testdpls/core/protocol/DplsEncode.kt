package ru.bolid.testdpls.core.protocol

fun encodeFrame(frame: DplsProtocol.Frame): ByteArray {
    require(frame.sequence in 0..0xffff)
    require(frame.flags in 0..0xff)
    require(frame.payload.size <= 0xffff)
    val bytes = ByteArray(DplsProtocol.OVERHEAD + frame.payload.size)
    bytes[0] = DplsProtocol.VERSION
    bytes[1] = frame.type.wire.toByte()
    bytes[2] = frame.flags.toByte()
    putU16(bytes, 3, frame.sequence)
    putU16(bytes, 5, frame.payload.size)
    frame.payload.copyInto(bytes, DplsProtocol.HEADER_SIZE)
    putU16(bytes, bytes.size - DplsProtocol.TRAILER_SIZE, crc16CcittFalse(bytes, 0, bytes.size - DplsProtocol.TRAILER_SIZE))
    return bytes
}
