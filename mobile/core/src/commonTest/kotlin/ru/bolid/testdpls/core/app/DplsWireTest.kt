package ru.bolid.testdpls.core.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import ru.bolid.testdpls.core.protocol.DplsProtocol

class DplsWireTest {
    @Test
    fun onlyLatestStateResponseMayUpdateTelemetry() {
        val transport = FakeTransport()
        val wire = DplsWire(transport) { error(it) }

        val first = assertNotNull(wire.request(DplsProtocol.Type.STATE_GET))
        val second = assertNotNull(wire.request(DplsProtocol.Type.STATE_GET))

        assertFalse(wire.accepts(stateFrame(first)))
        assertTrue(wire.accepts(stateFrame(second)))
    }

    @Test
    fun resetInvalidatesStateWatermark() {
        val transport = FakeTransport()
        val wire = DplsWire(transport) { error(it) }
        val sequence = assertNotNull(wire.request(DplsProtocol.Type.STATE_GET))

        wire.reset()

        assertFalse(wire.accepts(stateFrame(sequence)))
    }

    private fun stateFrame(sequence: Int) = DplsProtocol.Frame(
        type = DplsProtocol.Type.STATE_REPORT,
        sequence = sequence,
        flags = DplsProtocol.Flags.RESPONSE,
        payload = byteArrayOf(),
    )

    private class FakeTransport : DplsTransport {
        override fun setListener(listener: DplsTransportListener) = Unit
        override fun startScan() = false
        override fun stopScan() = Unit
        override fun connect(address: String) = false
        override fun reconnect() = false
        override fun send(bytes: ByteArray, priority: Boolean, flush: Boolean) = true
        override fun readRssi() = false
        override fun disconnect(clearSelection: Boolean) = Unit
        override fun hasConnection() = false
        override fun close() = Unit
    }
}
