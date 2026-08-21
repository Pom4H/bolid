package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.protocol.DplsProtocol
import ru.bolid.testdpls.core.protocol.decodeFrame
import ru.bolid.testdpls.core.protocol.encodeFrame
import ru.bolid.testdpls.core.session.FrameSequencer

/**
 * Deterministic stop-and-wait wire actor.
 *
 * Test-DPLS has no throughput requirement that justifies concurrent product
 * transactions. Exactly one request may await a response. A priority/flush
 * request may explicitly preempt that transaction; the new frame sequence is
 * then the only current correlation identity.
 */
internal class DplsWire(
    private val transport: DplsTransport,
    private val fail: (String) -> Unit,
) {
    private data class Pending(val sequence: Int, val type: DplsProtocol.Type)

    private val sequencer = FrameSequencer()
    private var pending: Pending? = null
    private var latestStateSequence: Int? = null

    val busy: Boolean get() = pending != null
    val pendingSequence: Int? get() = pending?.sequence

    fun reset() {
        sequencer.reset()
        pending = null
        latestStateSequence = null
    }

    fun decode(bytes: ByteArray): DplsProtocol.DecodeResult {
        val decoded = decodeFrame(bytes)
        if (decoded is DplsProtocol.DecodeResult.Success) observe(decoded.frame)
        return decoded
    }

    /**
     * Returns true only for a response that belongs to the current transaction.
     * Calling this directly also completes that transaction; production normally
     * gets the same behavior through [decode].
     */
    fun accepts(frame: DplsProtocol.Frame): Boolean {
        if (!frame.isResponse && !frame.isError) return false
        val current = pending ?: return false
        if (frame.sequence != current.sequence) return false
        observe(frame)
        return true
    }

    fun request(
        type: DplsProtocol.Type,
        payload: ByteArray = byteArrayOf(),
        priority: Boolean = false,
        flush: Boolean = false,
    ): Int? {
        if (pending != null) {
            if (!priority && !flush) return null
            pending = null
        }

        val sequence = sequencer.next()
        if (!send(type, sequence, DplsProtocol.Flags.REQUEST, payload, priority, flush)) return null
        pending = Pending(sequence, type)
        if (type == DplsProtocol.Type.STATE_GET) latestStateSequence = sequence
        return sequence
    }

    fun oneWay(
        type: DplsProtocol.Type,
        payload: ByteArray = byteArrayOf(),
    ) {
        /* Keep-alive/telemetry hints never overtake a real transaction. */
        if (pending != null) return
        send(type, sequencer.next(), 0, payload, priority = false, flush = false)
    }

    private fun observe(frame: DplsProtocol.Frame) {
        if (!frame.isResponse && !frame.isError) return
        val current = pending ?: return
        if (frame.sequence == current.sequence) pending = null
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
