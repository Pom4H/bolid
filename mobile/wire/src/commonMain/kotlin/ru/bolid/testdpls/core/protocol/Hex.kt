package ru.bolid.testdpls.core.protocol

internal fun ByteArray.toHexString(): String = buildString(size * 2) {
    for (byte in this@toHexString) append((byte.toInt() and 0xff).toString(16).padStart(2, '0'))
}

internal fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val result = ByteArray(length / 2)
    for (index in result.indices) {
        val value = substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null
        result[index] = value.toByte()
    }
    return result
}
