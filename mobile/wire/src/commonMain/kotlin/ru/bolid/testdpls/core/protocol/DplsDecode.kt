package ru.bolid.testdpls.core.protocol

fun decodeFrame(bytes: ByteArray): DplsProtocol.DecodeResult {
    if (bytes.size < DplsProtocol.OVERHEAD) {
        return DplsProtocol.DecodeResult.Failure(
            "Короткий кадр: ${bytes.size} байт (${bytes.hexPreview()})",
        )
    }
    if (bytes[0] != DplsProtocol.VERSION) {
        return DplsProtocol.DecodeResult.Failure(
            "Версия протокола не поддерживается: ${bytes[0].toInt() and 0xff}",
        )
    }
    val type = DplsProtocol.Type.fromWire(bytes[1].toInt() and 0xff)
        ?: return DplsProtocol.DecodeResult.Failure(
            "Неизвестный тип сообщения: ${bytes[1].toInt() and 0xff}",
        )
    val payloadLength = readU16(bytes, 5)
    if (payloadLength > DplsProtocol.MAX_PAYLOAD) {
        return DplsProtocol.DecodeResult.Failure(
            "Payload слишком большой: $payloadLength байт",
        )
    }
    if (bytes.size != DplsProtocol.OVERHEAD + payloadLength) {
        return DplsProtocol.DecodeResult.Failure(
            "Неверная длина кадра: получено ${bytes.size}, ожидается ${DplsProtocol.OVERHEAD + payloadLength}",
        )
    }
    val expected = readU16(bytes, bytes.size - DplsProtocol.TRAILER_SIZE)
    val actual = crc16CcittFalse(bytes, 0, bytes.size - DplsProtocol.TRAILER_SIZE)
    if (expected != actual) {
        return DplsProtocol.DecodeResult.Failure(
            "Ошибка CRC16: ${bytes.hexPreview()}",
        )
    }
    return DplsProtocol.DecodeResult.Success(
        DplsProtocol.Frame(
            type = type,
            sequence = readU16(bytes, 3),
            flags = bytes[2].toInt() and 0xff,
            payload = bytes.copyOfRange(
                DplsProtocol.HEADER_SIZE,
                DplsProtocol.HEADER_SIZE + payloadLength,
            ),
        ),
    )
}

private fun ByteArray.hexPreview(limit: Int = 24): String =
    take(limit).joinToString(" ") { byte ->
        (byte.toInt() and 0xff).toString(16).uppercase().padStart(2, '0')
    } + if (size > limit) " …" else ""
