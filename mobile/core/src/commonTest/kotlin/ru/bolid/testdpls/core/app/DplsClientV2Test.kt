package ru.bolid.testdpls.core.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import ru.bolid.testdpls.core.protocol.putU16
import ru.bolid.testdpls.core.protocol.putU32
import ru.bolid.testdpls.core.protocol.readU32

class DplsClientV2Test {
    @Test
    fun scanPhaseIsAProjectionNotASeparateLifecycleWrite() {
        val client = newClient(FakeTransport(), FakePlatform())

        client.startScan()

        assertEquals(ConnectionPhase.SCANNING, client.uiState.value.phase)
        client.stopScan()
        assertEquals(ConnectionPhase.IDLE, client.uiState.value.phase)
        client.close()
    }

    @Test
    fun authenticationStaysSynchronizingUntilIdentityIsProven() {
        val transport = FakeTransport()
        val client = authenticatedClient(transport, FakePlatform())
        assertEquals(ConnectionPhase.SYNCHRONIZING, client.uiState.value.phase)

        val stateRequest = transport.lastFrame()
        transport.reply(
            DplsProtocol.Type.STATE_REPORT,
            statePayload(DplsMode.NORMAL),
            stateRequest.sequence,
        )

        assertEquals(ConnectionPhase.SYNCHRONIZING, client.uiState.value.phase)
        assertEquals(DplsProtocol.Type.DEVICE_INFO_GET, transport.lastFrame().type)

        val infoRequest = transport.lastFrame()
        transport.reply(
            DplsProtocol.Type.DEVICE_INFO_REPORT,
            deviceInfo(),
            infoRequest.sequence,
        )

        assertEquals(ConnectionPhase.READY, client.uiState.value.phase)
        client.close()
    }

    @Test
    fun advertisedIdentityMismatchFailsClosed() {
        val transport = FakeTransport()
        val client = authenticatedClient(transport, FakePlatform())
        val stateRequest = transport.lastFrame()
        transport.reply(
            DplsProtocol.Type.STATE_REPORT,
            statePayload(DplsMode.NORMAL),
            stateRequest.sequence,
        )

        val infoRequest = transport.lastFrame()
        transport.reply(
            DplsProtocol.Type.DEVICE_INFO_REPORT,
            deviceInfo(deviceId = 0x9999),
            infoRequest.sequence,
        )

        assertEquals(ConnectionPhase.ERROR, client.uiState.value.phase)
        assertFalse(client.uiState.value.authenticated)
        client.close()
    }

    @Test
    fun authStateAndModeUseFrameSequenceAsOnlyTransactionId() {
        val transport = FakeTransport()
        val client = readyClient(transport, FakePlatform())
        client.requestMode(DplsMode.SHORT_1)
        client.confirmMode()
        val request = transport.lastFrame()
        assertEquals(13, request.payload.size)
        assertTrue(request.isRequest)
        transport.reply(
            DplsProtocol.Type.COMMAND_RESULT,
            commandResult(DplsMode.SHORT_1),
            (request.sequence + 1) and 0xffff,
        )
        assertTrue(client.uiState.value.commandInProgress)
        transport.reply(
            DplsProtocol.Type.COMMAND_RESULT,
            commandResult(DplsMode.SHORT_1),
            request.sequence,
        )
        assertFalse(client.uiState.value.commandInProgress)
        client.close()
    }

    @Test
    fun setupDisconnectKeepsStableNodeCredentialAndReconnectIntent() {
        val transport = FakeTransport()
        val platform = FakePlatform()
        val client = newClient(transport, platform)
        client.startScan()
        transport.discover(DplsTransportDevice("ble-1", "Test-DPLS", 0x1234, -40))
        client.connect("ble-1")
        transport.connected()
        transport.subscribed()
        transport.reply(DplsProtocol.Type.AUTH_CHALLENGE, challenge(false))
        client.setup("Test-DPLS-001", "12345678")
        val setup = transport.lastFrame()
        transport.reply(DplsProtocol.Type.AUTH_RESULT, byteArrayOf(3, 0, 0), setup.sequence)
        assertEquals(ConnectionPhase.RECONNECTING, client.uiState.value.phase)
        assertTrue(platform.hasVerifier("node:4660"))
        transport.dropped()
        assertEquals(1, transport.reconnectCalls)
        client.close()
    }

    @Test
    fun unrelatedErrorCannotBeMistakenForTimeSync() {
        val transport = FakeTransport()
        val client = authenticatedThroughState(transport, FakePlatform())
        val info = transport.lastFrame()
        transport.reply(DplsProtocol.Type.DEVICE_INFO_REPORT, deviceInfo(), info.sequence)
        val sync = transport.lastFrame()
        assertEquals(DplsProtocol.Type.TIME_SYNC, sync.type)
        transport.error(5, (sync.sequence + 1) and 0xffff)
        assertEquals(ConnectionPhase.ERROR, client.uiState.value.phase)
        client.close()
    }

    @Test
    fun unsupportedHistogramFallsBackToJournalByItsSequence() {
        val transport = FakeTransport()
        val client = readyClient(transport, FakePlatform())
        client.loadLogHistogram()
        val histogram = transport.lastFrame()
        transport.error(5, histogram.sequence)
        assertEquals(DplsProtocol.Type.LOG_START, transport.lastFrame().type)
        client.close()
    }

    @Test
    fun unknownModeIsFailClosed() {
        val transport = FakeTransport()
        val platform = FakePlatform()
        val client = authenticatedClient(transport, platform)
        val request = transport.lastFrame()
        val invalid = statePayload(DplsMode.NORMAL).also { it[0] = 0x7e }
        transport.reply(DplsProtocol.Type.STATE_REPORT, invalid, request.sequence)
        assertEquals(ConnectionPhase.ERROR, client.uiState.value.phase)
        assertNull(client.uiState.value.state)
        assertFalse(platform.keepAlive)
        client.close()
    }

    @Test
    fun identifyBecomesLiveOnlyAfterDeviceResponse() {
        val transport = FakeTransport()
        val platform = FakePlatform().apply { now = 100_000 }
        val client = newClient(transport, platform)
        client.startScan()
        transport.discover(DplsTransportDevice("ble-1", "Test-DPLS", 0x1234, -40))
        client.identify("ble-1")
        transport.connected()
        transport.subscribed()
        val identify = transport.lastFrame()
        assertFalse(client.uiState.value.identifyLedLive)
        platform.now = 100_080
        transport.reply(DplsProtocol.Type.IDENTIFY_START, byteArrayOf(), identify.sequence)
        assertTrue(client.uiState.value.identifyLedLive)
        client.close()
    }

    @Test
    fun namePayloadHasNoSecondCommandId() {
        val transport = FakeTransport()
        val client = readyClient(transport, FakePlatform())
        client.setDeviceName("Lab")
        val name = transport.lastFrame()
        assertEquals(16, name.payload.size)
        transport.reply(DplsProtocol.Type.SETTINGS_RESULT, byteArrayOf(0), name.sequence)
        assertEquals("Lab", client.uiState.value.deviceInfo?.userName)
        client.close()
    }

    @Test
    fun bluetoothLossClearsAuthenticationButKeepsReconnectIntent() {
        val transport = FakeTransport()
        val client = readyClient(transport, FakePlatform())
        transport.bluetoothUnavailable()
        assertFalse(client.uiState.value.authenticated)
        assertEquals(ConnectionPhase.RECONNECTING, client.uiState.value.phase)
        assertTrue(client.uiState.value.staleState)
        client.close()
    }

    private fun readyClient(transport: FakeTransport, platform: FakePlatform): DplsClient {
        val client = authenticatedThroughState(transport, platform)
        val info = transport.lastFrame()
        transport.reply(DplsProtocol.Type.DEVICE_INFO_REPORT, deviceInfo(), info.sequence)
        val sync = transport.lastFrame()
        assertEquals(0x78563412, readU32(sync.payload, 0))
        transport.reply(DplsProtocol.Type.TIME_SYNC, byteArrayOf(), sync.sequence)
        return client
    }

    private fun authenticatedThroughState(
        transport: FakeTransport,
        platform: FakePlatform,
    ): DplsClient {
        val client = authenticatedClient(transport, platform)
        val request = transport.lastFrame()
        transport.reply(DplsProtocol.Type.STATE_REPORT, statePayload(DplsMode.NORMAL), request.sequence)
        return client
    }

    private fun authenticatedClient(transport: FakeTransport, platform: FakePlatform): DplsClient {
        val client = newClient(transport, platform)
        client.startScan()
        transport.discover(DplsTransportDevice("ble-1", "Test-DPLS", 0x1234, -40))
        client.connect("ble-1")
        transport.connected()
        transport.subscribed()
        val hello = transport.lastFrame()
        transport.reply(DplsProtocol.Type.AUTH_CHALLENGE, challenge(true), hello.sequence)
        client.authenticate("12345678")
        val proof = transport.lastFrame()
        transport.reply(
            DplsProtocol.Type.AUTH_RESULT,
            byteArrayOf(0, 0, 0) + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            proof.sequence,
        )
        return client
    }

    private fun newClient(transport: FakeTransport, platform: FakePlatform) =
        DplsClient(
            transport,
            platform,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

    private fun challenge(initialized: Boolean): ByteArray = ByteArray(37).also {
        putU32(it, 0, 0x78563412)
        repeat(16) { index -> it[4 + index] = (0x20 + index).toByte() }
        repeat(16) { index -> it[20 + index] = (0x40 + index).toByte() }
        it[36] = if (initialized) 1 else 0
    }

    private fun deviceInfo(deviceId: Long = 0x1234): ByteArray {
        val name = "Test-DPLS".encodeToByteArray()
        return ByteArray(12 + name.size).also {
            putU32(it, 0, deviceId)
            it[4] = DplsProtocol.VERSION
            it[5] = 2
            it[8] = 2
            it[9] = 0x0f
            it[11] = name.size.toByte()
            name.copyInto(it, 12)
        }
    }

    private fun statePayload(mode: DplsMode): ByteArray = ByteArray(25).also { raw ->
        raw[0] = mode.wire.toByte()
        putU16(raw, 2, 12_000)
        putU16(raw, 4, 30)
        putU32(raw, 8, 10)
        putU32(raw, 12, 1)
        raw[16] = 0x0f
        putU16(raw, 17, 12_000)
        putU16(raw, 19, 12_000)
        putU16(raw, 21, 12_000)
        putU16(raw, 23, 5_000)
    }

    private fun commandResult(mode: DplsMode) =
        byteArrayOf(0, mode.wire.toByte(), 30, 0)

    private class FakePlatform : DplsPlatformServices {
        private val verifiers = mutableMapOf<String, ByteArray>()
        var keepAlive = false
        var now = 1_786_732_800_000L

        override fun nowMillis(): Long = now

        override fun secureRandomBytes(count: Int): ByteArray =
            ByteArray(count) { it.toByte() }

        override fun readDeviceVerifier(deviceKey: String): ByteArray? =
            verifiers[deviceKey]?.copyOf()

        override fun writeDeviceVerifier(deviceKey: String, verifier: ByteArray?) {
            if (verifier == null) {
                verifiers.remove(deviceKey)
            } else {
                verifiers[deviceKey] = verifier.copyOf()
            }
        }

        override fun keepConnectionAlive(active: Boolean) {
            keepAlive = active
        }

        fun hasVerifier(key: String) = key in verifiers
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

        override fun startScan() = true

        override fun stopScan() = Unit

        override fun connect(address: String) = true

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

        override fun readRssi() = true

        override fun disconnect(clearSelection: Boolean) {
            connected = false
        }

        override fun hasConnection() = connected

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

        fun bluetoothUnavailable() {
            connected = false
            listener.onBluetoothUnavailable()
        }

        fun reply(
            type: DplsProtocol.Type,
            payload: ByteArray,
            sequence: Int = lastFrame().sequence,
        ) {
            listener.onBytes(
                encodeFrame(
                    DplsProtocol.Frame(
                        type,
                        sequence,
                        DplsProtocol.Flags.RESPONSE,
                        payload,
                    ),
                ),
            )
        }

        fun error(code: Int, sequence: Int = lastFrame().sequence) {
            listener.onBytes(
                encodeFrame(
                    DplsProtocol.Frame(
                        DplsProtocol.Type.ERROR,
                        sequence,
                        DplsProtocol.Flags.RESPONSE or DplsProtocol.Flags.ERROR,
                        byteArrayOf(code.toByte()),
                    ),
                ),
            )
        }

        fun lastFrame(): DplsProtocol.Frame =
            (decodeFrame(writes.last()) as DplsProtocol.DecodeResult.Success).frame
    }
}
