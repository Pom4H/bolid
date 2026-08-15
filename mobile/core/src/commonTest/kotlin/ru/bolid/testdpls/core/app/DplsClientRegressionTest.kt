package ru.bolid.testdpls.core.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.bolid.testdpls.core.domain.ConnectionPhase
import ru.bolid.testdpls.core.domain.DplsMode
import ru.bolid.testdpls.core.protocol.DplsProtocol
import ru.bolid.testdpls.core.protocol.decodeFrame
import ru.bolid.testdpls.core.protocol.encodeFrame
import ru.bolid.testdpls.core.protocol.parseStateReport
import ru.bolid.testdpls.core.protocol.putU16
import ru.bolid.testdpls.core.protocol.putU32

class DplsClientRegressionTest {
    @Test
    fun setupDisconnectImmediatelyReconnectsWithSavedVerifier() {
        val transport = FakeTransport()
        val platform = FakePlatform()
        val client = newClient(transport, platform)

        client.startScan()
        transport.discover(DplsTransportDevice("device-1", "Test-DPLS", 0x1234, -40))
        client.connect("device-1")
        transport.connected()
        transport.subscribed()
        transport.receive(DplsProtocol.Type.AUTH_CHALLENGE, authChallenge(initialized = false))

        client.setup("Test-DPLS-001", "12345678")
        assertEquals(DplsProtocol.Type.SETUP, transport.lastFrame().type)
        transport.receive(DplsProtocol.Type.AUTH_RESULT, byteArrayOf(3, 0, 0))
        assertEquals(ConnectionPhase.RECONNECTING, client.uiState.value.phase)
        assertTrue(platform.hasVerifier("addr:device-1"))

        transport.dropped()
        assertEquals(1, transport.reconnectCalls)
        assertEquals(ConnectionPhase.RECONNECTING, client.uiState.value.phase)
        assertEquals("device-1", client.uiState.value.selectedDevice?.address)
        client.close()
    }

    @Test
    fun unknownWireModeIsRejectedAndStopsConnectionKeepAlive() {
        val transport = FakeTransport()
        val platform = FakePlatform()
        val client = authenticatedClient(transport, platform)
        assertTrue(platform.keepAlive)

        val invalid = statePayload(DplsMode.NORMAL).also { it[0] = 0x7e }
        assertNull(parseStateReport(invalid, platform.nowMillis()))
        transport.receive(DplsProtocol.Type.STATE_REPORT, invalid)

        assertEquals(ConnectionPhase.ERROR, client.uiState.value.phase)
        assertNull(client.uiState.value.state)
        assertFalse(platform.keepAlive)
        client.close()
    }

    @Test
    fun timeSyncErrorWindowExpiresBeforeLaterUnsupportedHistogram() = runBlocking {
        val transport = FakeTransport()
        val platform = FakePlatform()
        val client = readyClient(transport, platform, withDeviceInfo = true)
        assertEquals(DplsProtocol.Type.TIME_SYNC, transport.lastFrame().type)

        delay(1_650)
        client.loadLogHistogram()
        assertEquals(DplsProtocol.Type.LOG_HIST_GET, transport.lastFrame().type)

        transport.receive(DplsProtocol.Type.ERROR, byteArrayOf(5))
        assertEquals(ConnectionPhase.READY, client.uiState.value.phase)
        assertNull(client.uiState.value.error)
        assertEquals(DplsProtocol.Type.LOG_START, transport.lastFrame().type)
        client.close()
    }

    @Test
    fun missingDeviceInfoDoesNotPermanentlyLatchAwaitingFlag() = runBlocking {
        val transport = FakeTransport()
        val platform = FakePlatform()
        val client = readyClient(transport, platform, withDeviceInfo = false)
        val before = transport.frames().count { it.type == DplsProtocol.Type.DEVICE_INFO_GET }
        assertEquals(1, before)

        delay(2_100)
        val after = transport.frames().count { it.type == DplsProtocol.Type.DEVICE_INFO_GET }
        assertTrue(after >= 2)
        client.close()
    }

    private fun newClient(transport: FakeTransport, platform: FakePlatform): DplsClient =
        DplsClient(
            transport,
            platform,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

    private fun authenticatedClient(transport: FakeTransport, platform: FakePlatform): DplsClient {
        val client = newClient(transport, platform)
        client.startScan()
        transport.discover(DplsTransportDevice("device-1", "Test-DPLS", 0x1234, -40))
        client.connect("device-1")
        transport.connected()
        transport.subscribed()
        transport.receive(DplsProtocol.Type.AUTH_CHALLENGE, authChallenge(initialized = true))
        client.authenticate("12345678")
        transport.receive(
            DplsProtocol.Type.AUTH_RESULT,
            byteArrayOf(0, 0, 0) + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        )
        return client
    }

    private fun readyClient(
        transport: FakeTransport,
        platform: FakePlatform,
        withDeviceInfo: Boolean,
    ): DplsClient {
        val client = authenticatedClient(transport, platform)
        transport.receive(DplsProtocol.Type.STATE_REPORT, statePayload(DplsMode.NORMAL))
        assertEquals(DplsProtocol.Type.DEVICE_INFO_GET, transport.lastFrame().type)
        if (withDeviceInfo) {
            transport.receive(DplsProtocol.Type.DEVICE_INFO_REPORT, deviceInfoPayload())
        }
        return client
    }

    private fun authChallenge(initialized: Boolean): ByteArray = ByteArray(37).also {
        putU32(it, 0, 0x78563412)
        repeat(16) { index -> it[4 + index] = (0x20 + index).toByte() }
        repeat(16) { index -> it[20 + index] = (0x40 + index).toByte() }
        it[36] = if (initialized) 1 else 0
    }

    private fun deviceInfoPayload(): ByteArray {
        val name = "Test-DPLS".encodeToByteArray()
        return ByteArray(12 + name.size).also {
            putU32(it, 0, 0x1234)
            it[4] = 1
            it[5] = 1
            it[6] = 3
            it[7] = 0
            it[8] = 2
            it[9] = 0x0f
            it[11] = name.size.toByte()
            name.copyInto(it, 12)
        }
    }

    private fun statePayload(mode: DplsMode): ByteArray = ByteArray(25).also { raw ->
        raw[0] = mode.wire.toByte()
        raw[1] = 0
        putU16(raw, 2, 12_000)
        putU16(raw, 4, 30)
        raw[6] = 0
        raw[7] = 0
        putU32(raw, 8, 10)
        putU32(raw, 12, 1)
        raw[16] = 0x0f
        putU16(raw, 17, 12_000)
        putU16(raw, 19, 12_000)
        putU16(raw, 21, 12_000)
        putU16(raw, 23, 5_000)
    }

    private class FakePlatform : DplsPlatformServices {
        private val verifiers = mutableMapOf<String, ByteArray>()
        var keepAlive = false
        private var now = 1_786_732_800_000L

        override fun nowMillis(): Long = now
        override fun secureRandomBytes(count: Int): ByteArray = ByteArray(count) { it.toByte() }
        override fun readDeviceVerifier(deviceKey: String): ByteArray? = verifiers[deviceKey]?.copyOf()
        override fun writeDeviceVerifier(deviceKey: String, verifier: ByteArray?) {
            if (verifier == null) verifiers.remove(deviceKey) else verifiers[deviceKey] = verifier.copyOf()
        }
        override fun keepConnectionAlive(active: Boolean) {
            keepAlive = active
        }
        fun hasVerifier(deviceKey: String): Boolean = verifiers.containsKey(deviceKey)
    }

    private class FakeTransport : DplsTransport {
        private lateinit var listener: DplsTransportListener
        private val writes = mutableListOf<ByteArray>()
        private var connected = false
        var reconnectCalls = 0
            private set

        override fun setListener(listener: DplsTransportListener) {
            this.listener = listener
        }
        override fun startScan(): Boolean = true
        override fun stopScan() = Unit
        override fun connect(address: String): Boolean = true
        override fun reconnect(): Boolean {
            reconnectCalls++
            return true
        }
        override fun send(bytes: ByteArray, priority: Boolean, flush: Boolean): Boolean {
            if (flush) writes.clear()
            writes += bytes.copyOf()
            listener.onWriteComplete(null)
            return true
        }
        override fun readRssi(): Boolean = true
        override fun disconnect(clearSelection: Boolean) {
            connected = false
        }
        override fun hasConnection(): Boolean = connected
        override fun close() = Unit

        fun discover(device: DplsTransportDevice) = listener.onDiscovered(device)
        fun connected() = listener.onConnected()
        fun subscribed() {
            connected = true
            listener.onSubscribed(244)
        }
        fun dropped() {
            connected = false
            listener.onDisconnected(null)
        }
        fun receive(type: DplsProtocol.Type, payload: ByteArray) {
            listener.onBytes(encodeFrame(DplsProtocol.Frame(type = type, sequence = 1, payload = payload)))
        }
        fun frames(): List<DplsProtocol.Frame> = writes.map {
            (decodeFrame(it) as DplsProtocol.DecodeResult.Success).frame
        }
        fun lastFrame(): DplsProtocol.Frame = frames().last()
    }
}
