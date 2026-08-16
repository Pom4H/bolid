package ru.bolid.testdpls.core.protocol

/** Reassembles the DPLS byte stream without depending on BLE callback boundaries. */
class DplsFrameStream {
    private var pending = byteArrayOf()

    fun reset() {
        pending = byteArrayOf()
    }

    fun push(chunk: ByteArray): List<DplsProtocol.DecodeResult> {
        if (chunk.isEmpty()) return emptyList()
        pending += chunk

        val decoded = mutableListOf<DplsProtocol.DecodeResult>()
        while (true) {
            alignToVersion()
            if (pending.size < DplsProtocol.HEADER_SIZE) break

            val payloadLength = readU16(pending, 5)
            if (payloadLength > DplsProtocol.MAX_PAYLOAD) {
                decoded += DplsProtocol.DecodeResult.Failure(
                    "Некорректная длина payload в потоке: $payloadLength",
                )
                pending = pending.copyOfRange(1, pending.size)
                continue
            }

            val frameLength = DplsProtocol.OVERHEAD + payloadLength
            if (pending.size < frameLength) break

            decoded += decodeFrame(pending.copyOfRange(0, frameLength))
            pending = pending.copyOfRange(frameLength, pending.size)
        }
        return decoded
    }

    private fun alignToVersion() {
        if (pending.isEmpty() || pending[0] == DplsProtocol.VERSION) return
        val start = pending.indexOfFirst { it == DplsProtocol.VERSION }
        pending = if (start < 0) byteArrayOf() else pending.copyOfRange(start, pending.size)
    }
}
