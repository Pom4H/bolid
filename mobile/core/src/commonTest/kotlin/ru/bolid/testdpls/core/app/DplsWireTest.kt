package ru.bolid.testdpls.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.bolid.testdpls.core.protocol.DplsProtocol
import ru.bolid.testdpls.core.protocol.encodeFrame

class DplsWireTest {
    @Test
    fun ordinaryRequestsAreStrictlyStopAndWait() {
        val transport = FakeTransport()
        val wire = DplsWire(transport) { error(it) }

        val first = assertNotNull(wire.request(DplsProtocol.Type.STATE_GET))
        assertTrue(wire.busy)
        assertNull(wire.request(DplsProtocol.Type.DEVICE_INFO_GET))
        assertEquals(1, transport.writes)

        wire.decode(encodeFrame(stateFrame(first)))
        assertFalse(wire.busy)
        assertNotNull(wire.request(DplsProtocol.Type.DEVICE_INFO_GET))
        assertEquals(2, transport.writes)
    }

    @Test
    fun priorityRequestExplicitlyPreemptsCurrentTransaction() {
        val transport = FakeTransport()
        val wire = DplsWire(transport) { error(it) }

        val first = assertNotNull(wire.request(DplsProtocol.Type.STATE_GET))
        val priority = assertNotNull(
            wire.request(DplsProtocol.Type.MODE_SET, priority = true),
        )
        assertTrue(priority != first)
        assertEquals(priority, wire.pendingSequence)
        assertFalse(wire.accepts(stateFrame(first)))
        assertTrue(wire.busy)
    }

    @Test
    fun matchingResponseCompletesOnlyCurrentTransaction() {
        val transport = FakeTransport()
        val wire = DplsWire(transport) { error(it) }
        val sequence = assertNotNull(wire.request(DplsProtocol.Type.STATE_GET))

        assertFalse(wire.accepts(stateFrame((sequence + 1) and 0xffff)))
        assertTrue(wire.busy)
        assertTrue(wire.accepts(stateFrame(sequence)))
        assertFalse(wire.busy)
    }

    @Test
    fun resetInvalidatesPendingTransaction() {
        val transport = FakeTransport()
        val wire = DplsWire(transport) { error(it) }
        val sequence = assertNotNull(wire.request(DplsProtocol.Type.STATE_GET))

        wire.reset()

        assertFalse(wire.busy)
        assertFalse(wire.accepts(stateFrame(sequence)))
    }

    private fun stateFrame(sequence: Int) = DplsProtocol.Frame(
        type = DplsProtocol.Type.STATE_REPORT,
        sequence = sequence,
        flags = DplsProtocol.Flags.RESPONSE,
        payload = byteArrayOf(),
    )

    private class FakeTransport : DplsTransport {
        var writes = 0
        override fun setListener(listener: DplsTransportListener) = Unit
        override fun startScan() = false
        override fun stopScan() = Unit
        override fun connect(address: String) = false
        override fun reconnect() = false
        override fun send(bytes: ByteArray, priority: Boolean, flush: Boolean): Boolean {
            writes++
            return true
        }
        override fun readRssi() = false
        override fun disconnect(clearSelection: Boolean) = Unit
        override fun hasConnection() = false
        override fun close() = Unit
    }
}
