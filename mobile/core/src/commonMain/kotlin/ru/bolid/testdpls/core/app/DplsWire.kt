package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.protocol.DplsProtocol
import ru.bolid.testdpls.core.protocol.decodeFrame
import ru.bolid.testdpls.core.protocol.deviceErrorReason
import ru.bolid.testdpls.core.protocol.encodeFrame
import ru.bolid.testdpls.core.session.FrameSequencer

/** Wire mechanics only: frame sequence and codec. */
internal class DplsWire(
    private val transport: DplsTransport,
    private val fail: (String) -> Unit,
) {
    private val sequencer = FrameSequencer()

    fun reset() = sequencer.reset()

    fun decode(bytes: ByteArray): DplsProtocol.DecodeResult {
        val decoded = decodeFrame(bytes)
        if (decoded is DplsProtocol.DecodeResult.Success && decoded.frame.isError) {
            val code = decoded.frame.payload.firstOrNull()?.toInt()?.and(0xff) ?: 0
            /* Bootstrap errors used to collapse into opaque ERROR 2. Keep the
             * normal correlated error path for established-session operations,
             * but surface setup/session failures immediately by meaning. */
            if (code in 8..11) return DplsProtocol.DecodeResult.Failure(deviceErrorReason(code))
        }
        return decoded
    }

    fun request(
        type: DplsProtocol.Type,
        payload: ByteArray = byteArrayOf(),
        priority: Boolean = false,
        flush: Boolean = false,
    ): Int? {
        val sequence = sequencer.next()
        return sequence.takeIf {
            send(type, sequence, DplsProtocol.Flags.REQUEST, payload, priority, flush)
        }
    }

    fun oneWay(type: DplsProtocol.Type, payload: ByteArray = byteArrayOf()) {
        send(type, sequencer.next(), 0, payload, priority = false, flush = false)
    }

    private fun send(
        type: DplsProtocol.Type,
        sequence: Int,
        flags: Int,
        payload: ByteArray,
        priority: Boolean,
        flush: Boolean,
    ): Boolean {
        val bytes = encodeFrame(DplsProtocol.Frame(type, sequence, flags, payload))
        if (transport.send(bytes, priority, flush)) return true
        fail("Кадр ${bytes.size} байт не помещается в BLE write limit")
        return false
    }
}
