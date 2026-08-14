package ru.bolid.testdpls.core.protocol

fun crc16CcittFalse(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
    require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
    var crc = 0xffff
    for (index in offset until offset + length) {
        crc = crc xor ((bytes[index].toInt() and 0xff) shl 8)
        repeat(8) {
            crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
            crc = crc and 0xffff
        }
    }
    return crc
}
