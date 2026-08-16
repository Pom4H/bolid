package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.protocol.DplsFrameStream
import ru.bolid.testdpls.core.protocol.DplsProtocol
import ru.bolid.testdpls.core.protocol.encodeFrame
import ru.bolid.testdpls.core.session.FrameSequencer

/** Wire mechanics only: frame boundaries, sequence generation and correlation. */
internal class DplsWire(
    private val transport: DplsTransport,
    private val fail: (String) -> Unit,
) {
    private val incoming = DplsFrameStream()
    private val sequencer = FrameSequencer()
    private var latestStateSequence: Int? = null

    fun reset() {
        incoming.reset()
        sequencer.reset()
        latestStateSequence = null
    }

    fun receive(bytes: ByteArray): List<DplsProtocol.DecodeResult> = incoming.push(bytes)

    fun accepts(frame: DplsProtocol.Frame): Boolean =
        frame.type != DplsProtocol.Type.STATE_REPORT || frame.sequence == latestStateSequence

    fun request(
        type: DplsProtocol.Type,
        payload: ByteArray = byteArrayOf(),
        priority: Boolean = false,
        flush: Boolean = false,
    ): Int? {
        val sequence = sequencer.next()
        if (!send(type, sequence, DplsProtocol.Flags.REQUEST, payload, priority, flush)) return null
        if (type == DplsProtocol.Type.STATE_GET) latestStateSequence = sequence
        return sequence
    }

    fun oneWay(
        type: DplsProtocol.Type,
        payload: ByteArray = byteArrayOf(),
    ) {
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
