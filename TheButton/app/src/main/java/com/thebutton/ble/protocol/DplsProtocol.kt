package com.thebutton.ble.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object DplsProtocol {
    const val VERSION: Byte = 1
    const val HEADER_SIZE = 7
    const val TRAILER_SIZE = 2
    const val OVERHEAD = HEADER_SIZE + TRAILER_SIZE

    enum class Type(val wire: Int) {
        HELLO(0x01), AUTH_CHALLENGE(0x02), AUTH_PROOF(0x03), AUTH_RESULT(0x04),
        SETUP(0x05), STATE_GET(0x10), STATE_REPORT(0x11), MODE_SET(0x12),
        COMMAND_RESULT(0x13), IDENTIFY_START(0x14), IDENTIFY_STOP(0x15),
        LOG_START(0x20), LOG_INFO(0x21), LOG_CHUNK(0x22), LOG_ACK(0x23),
        LOG_FINISH(0x24), LOG_RESULT(0x25), KEEP_ALIVE(0x30), ERROR(0x7f);

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

    fun encode(frame: Frame): ByteArray {
        require(frame.sequence in 0..0xffff)
        require(frame.payload.size <= 0xffff)
        val bytes = ByteBuffer.allocate(OVERHEAD + frame.payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(VERSION)
            .put(frame.type.wire.toByte())
            .put(frame.flags.toByte())
            .putShort(frame.sequence.toShort())
            .putShort(frame.payload.size.toShort())
            .put(frame.payload)
            .array()
        val crc = crc16(bytes, 0, bytes.size - TRAILER_SIZE)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(bytes.size - TRAILER_SIZE, crc.toShort())
        return bytes
    }

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size < OVERHEAD) return DecodeResult.Failure("Короткий кадр")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.get() != VERSION) return DecodeResult.Failure("Версия протокола не поддерживается")
        val type = Type.fromWire(buffer.get().toInt() and 0xff)
            ?: return DecodeResult.Failure("Неизвестный тип сообщения")
        val flags = buffer.get().toInt() and 0xff
        val sequence = buffer.short.toInt() and 0xffff
        val payloadLength = buffer.short.toInt() and 0xffff
        if (bytes.size != OVERHEAD + payloadLength) return DecodeResult.Failure("Неверная длина кадра")
        val expected = ByteBuffer.wrap(bytes, bytes.size - 2, 2).order(ByteOrder.LITTLE_ENDIAN)
            .short.toInt() and 0xffff
        val actual = crc16(bytes, 0, bytes.size - 2)
        if (expected != actual) return DecodeResult.Failure("Ошибка CRC16")
        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        return DecodeResult.Success(Frame(type, sequence, flags, payload))
    }

    fun crc16(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        var crc = 0xffff
        for (index in offset until offset + length) {
            crc = crc xor ((bytes[index].toInt() and 0xff) shl 8)
            repeat(8) { crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xffff
        }
        return crc
    }
}

fun ByteBuffer.putU32(value: Long): ByteBuffer = putInt(value.toInt())
fun ByteBuffer.u8(): Int = get().toInt() and 0xff
fun ByteBuffer.u16(): Int = short.toInt() and 0xffff
fun ByteBuffer.u32(): Long = int.toLong() and 0xffff_ffffL
