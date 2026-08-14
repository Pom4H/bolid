package ru.bolid.testdpls.core.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.bolid.testdpls.core.domain.ConnectionPhase
import ru.bolid.testdpls.core.domain.DplsMode
import ru.bolid.testdpls.core.protocol.DplsProtocol
import ru.bolid.testdpls.core.protocol.decodeFrame
import ru.bolid.testdpls.core.protocol.encodeFrame
import ru.bolid.testdpls.core.protocol.putU16
import ru.bolid.testdpls.core.protocol.putU32

class DplsClientTest {
    @Test
    fun oneControllerOwnsDiscoveryAuthStateAndCommands() {
        val transport = FakeTransport()
        val platform = FakePlatform()
        val client = DplsClient(
            transport,
            platform,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        client.startScan()
        assertEquals(ConnectionPhase.SCANNING, client.uiState.value.phase)
        transport.discover(DplsTransportDevice("device-1", "Test-DPLS", 0x1234, -42))
        assertEquals("device-1", client.uiState.value.devices.single().address)

        client.connect("device-1")
        transport.connected()
        transport.subscribed()
        val hello = transport.lastFrame()
        assertEquals(DplsProtocol.Type.HELLO, hello.type)
        assertContentEquals(ByteArray(16) { it.toByte() }, hello.payload)

        val challenge = ByteArray(37)
        putU32(challenge, 0, 0x78563412)
        repeat(16) { challenge[4 + it] = (0x20 + it).toByte() }
        repeat(16) { challenge[20 + it] = (0x40 + it).toByte() }
        challenge[36] = 1
        transport.receive(DplsProtocol.Type.AUTH_CHALLENGE, challenge)
        assertTrue(client.uiState.value.credentialsReady)
        assertTrue(client.uiState.value.awaitingUserPassword)

        client.authenticate("12345678")
        assertEquals(DplsProtocol.Type.AUTH_PROOF, transport.lastFrame().type)

        val token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        transport.receive(DplsProtocol.Type.AUTH_RESULT, byteArrayOf(0, 0, 0) + token)
        assertEquals(DplsProtocol.Type.STATE_GET, transport.lastFrame().type)

        transport.receive(DplsProtocol.Type.STATE_REPORT, statePayload(DplsMode.NORMAL, revision = 1))
        assertEquals(ConnectionPhase.READY, client.uiState.value.phase)
        assertTrue(client.uiState.value.controlsEnabled)

        client.requestMode(DplsMode.SHORT_1)
        client.confirmMode()
        val command = transport.lastFrame()
        assertEquals(DplsProtocol.Type.MODE_SET, command.type)
        assertTrue(client.uiState.value.commandInProgress)

        val commandResult = ByteArray(8)
        putU32(commandResult, 0, 1)
        commandResult[4] = 0
        commandResult[5] = DplsMode.SHORT_1.wire.toByte()
        putU16(commandResult, 6, 30)
        transport.receive(DplsProtocol.Type.COMMAND_RESULT, commandResult)
        assertEquals(DplsProtocol.Type.STATE_GET, transport.lastFrame().type)

        transport.receive(DplsProtocol.Type.STATE_REPORT, statePayload(DplsMode.SHORT_1, revision = 2))
        assertEquals(DplsMode.SHORT_1, client.uiState.value.state?.mode)
        assertFalse(client.uiState.value.commandInProgress)

        client.disconnect()
        assertEquals(ConnectionPhase.IDLE, client.uiState.value.phase)
        assertFalse(client.uiState.value.authenticated)
        assertTrue(transport.clearSelectionOnLastDisconnect)
        client.close()
    }

    @Test
    fun bluetoothLossClearsAuthenticatedSessionButKeepsReconnectIntent() {
        val transport = FakeTransport()
        val client = DplsClient(
            transport,
            FakePlatform(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.startScan()
        transport.discover(DplsTransportDevice("device-1", "Test-DPLS", null, -50))
        client.connect("device-1")
        transport.connected()
        transport.subscribed()

        val challenge = ByteArray(37).also {
            putU32(it, 0, 7)
            it[36] = 1
        }
        transport.receive(DplsProtocol.Type.AUTH_CHALLENGE, challenge)
        client.authenticate("12345678")
        transport.receive(
            DplsProtocol.Type.AUTH_RESULT,
            byteArrayOf(0, 0, 0) + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        )
        transport.receive(DplsProtocol.Type.STATE_REPORT, statePayload(DplsMode.NORMAL, revision = 1))
        assertTrue(client.uiState.value.authenticated)

        transport.bluetoothUnavailable()
        assertFalse(client.uiState.value.authenticated)
        assertEquals(ConnectionPhase.RECONNECTING, client.uiState.value.phase)
        assertTrue(client.uiState.value.staleState)
        client.close()
    }

    private fun statePayload(mode: DplsMode, revision: Long): ByteArray = ByteArray(25).also { raw ->
        raw[0] = mode.wire.toByte()
        raw[1] = 0
        putU16(raw, 2, 12_000)
        putU16(raw, 4, 30)
        raw[6] = 0
        raw[7] = 0
        putU32(raw, 8, 10)
        putU32(raw, 12, revision)
        raw[16] = 0x0f
        putU16(raw, 17, 12_000)
        putU16(raw, 19, 12_000)
        putU16(raw, 21, 12_000)
        putU16(raw, 23, 5_000)
    }

    private class FakePlatform : DplsPlatformServices {
        override fun nowMillis(): Long = 100_000
        override fun secureRandomBytes(count: Int): ByteArray = ByteArray(count) { it.toByte() }
    }

    private class FakeTransport : DplsTransport {
        private lateinit var listener: DplsTransportListener
        private val writes = mutableListOf<ByteArray>()
        private var connected = false
        var clearSelectionOnLastDisconnect = false
            private set

        override fun setListener(listener: DplsTransportListener) {
            this.listener = listener
        }

        override fun startScan(): Boolean = true
        override fun stopScan() = Unit
        override fun connect(address: String): Boolean = true
        override fun reconnect(): Boolean = true

        override fun send(bytes: ByteArray, priority: Boolean, flush: Boolean): Boolean {
            if (flush) writes.clear()
            writes += bytes.copyOf()
            listener.onWriteComplete(null)
            return true
        }

        override fun disconnect(clearSelection: Boolean) {
            connected = false
            clearSelectionOnLastDisconnect = clearSelection
        }

        override fun hasConnection(): Boolean = connected
        override fun close() = Unit

        fun discover(device: DplsTransportDevice) = listener.onDiscovered(device)
        fun connected() = listener.onConnected()
        fun subscribed() {
            connected = true
            listener.onSubscribed(244)
        }
        fun bluetoothUnavailable() {
            connected = false
            listener.onBluetoothUnavailable()
        }

        fun receive(type: DplsProtocol.Type, payload: ByteArray) {
            listener.onBytes(
                encodeFrame(
                    DplsProtocol.Frame(
                        type = type,
                        sequence = 1,
                        payload = payload,
                    ),
                ),
            )
        }

        fun lastFrame(): DplsProtocol.Frame =
            (decodeFrame(writes.last()) as DplsProtocol.DecodeResult.Success).frame
    }
}
