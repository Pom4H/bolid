package ru.bolid.testdpls.core.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.bolid.testdpls.core.domain.ConnectionPhase
import ru.bolid.testdpls.core.domain.DplsMode
import ru.bolid.testdpls.core.domain.SettingsOp
import ru.bolid.testdpls.core.domain.UiTheme
import ru.bolid.testdpls.core.protocol.DplsProtocol
import ru.bolid.testdpls.core.protocol.decodeFrame
import ru.bolid.testdpls.core.protocol.encodeFrame
import ru.bolid.testdpls.core.protocol.putU16
import ru.bolid.testdpls.core.protocol.putU32
import ru.bolid.testdpls.core.protocol.readU32

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
        assertTrue(platform.keepAlive)
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
        val timeSync = transport.frames().last { it.type == DplsProtocol.Type.TIME_SYNC }
        assertEquals(16, timeSync.payload.size)
        assertEquals(0x78563412, readU32(timeSync.payload, 0))
        assertContentEquals(token, timeSync.payload.copyOfRange(4, 12))
        assertEquals(platform.nowMillis() / 1000L, readU32(timeSync.payload, 12))
        assertEquals(DplsProtocol.Type.STATE_GET, transport.lastFrame().type)

        transport.receive(DplsProtocol.Type.STATE_REPORT, statePayload(DplsMode.NORMAL, revision = 1))
        assertEquals(ConnectionPhase.READY, client.uiState.value.phase)
        assertTrue(client.uiState.value.controlsEnabled)

        client.requestMode(DplsMode.SHORT_1)
        client.confirmMode()
        val command = transport.lastFrame()
        assertEquals(DplsProtocol.Type.MODE_SET, command.type)
        assertTrue(client.uiState.value.commandInProgress)

        val staleResult = commandResult(commandId = 999, mode = DplsMode.SHORT_1)
        transport.receive(DplsProtocol.Type.COMMAND_RESULT, staleResult)
        assertTrue(client.uiState.value.commandInProgress)
        assertEquals(DplsProtocol.Type.MODE_SET, transport.lastFrame().type)

        transport.receive(
            DplsProtocol.Type.COMMAND_RESULT,
            commandResult(commandId = 1, mode = DplsMode.SHORT_1),
        )
        assertFalse(client.uiState.value.commandInProgress)
        assertEquals(DplsProtocol.Type.STATE_GET, transport.lastFrame().type)

        transport.receive(DplsProtocol.Type.STATE_REPORT, statePayload(DplsMode.SHORT_1, revision = 2))
        assertEquals(DplsMode.SHORT_1, client.uiState.value.state?.mode)
        transport.receive(DplsProtocol.Type.STATE_REPORT, statePayload(DplsMode.NORMAL, revision = 3))
        assertEquals(DplsOperatorAlerts.NORMAL_TITLE, platform.alerts.last().first)
        client.disconnect()
        assertFalse(platform.keepAlive)
        assertEquals(ConnectionPhase.IDLE, client.uiState.value.phase)
        assertFalse(client.uiState.value.authenticated)
        assertTrue(transport.clearSelectionOnLastDisconnect)
        client.close()
    }

    @Test
    fun unsupportedTimeSyncDoesNotBreakLegacyConnection() {
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
        assertTrue(transport.frames().any { it.type == DplsProtocol.Type.TIME_SYNC })
        transport.receive(DplsProtocol.Type.ERROR, byteArrayOf(5))
        assertEquals(ConnectionPhase.SYNCHRONIZING, client.uiState.value.phase)
        assertEquals(null, client.uiState.value.error)
        transport.receive(DplsProtocol.Type.STATE_REPORT, statePayload(DplsMode.NORMAL, revision = 1))
        assertEquals(ConnectionPhase.READY, client.uiState.value.phase)
        client.close()
    }

    @Test
    fun browseKeepsLiveSessionUntilAnotherDeviceIsChosen() {
        val transport = FakeTransport()
        val client = DplsClient(
            transport,
            FakePlatform(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.startScan()
        transport.discover(DplsTransportDevice("device-1", "Test-DPLS-001", 0x1234, -42))
        client.connect("device-1")
        transport.connected()
        transport.subscribed()
        val challenge = ByteArray(37)
        putU32(challenge, 0, 0x78563412)
        repeat(16) { challenge[4 + it] = (0x20 + it).toByte() }
        repeat(16) { challenge[20 + it] = (0x40 + it).toByte() }
        challenge[36] = 1
        transport.receive(DplsProtocol.Type.AUTH_CHALLENGE, challenge)
        client.authenticate("12345678")
        transport.receive(DplsProtocol.Type.AUTH_RESULT, byteArrayOf(0, 0, 0) + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        transport.receive(DplsProtocol.Type.STATE_REPORT, statePayload(DplsMode.NORMAL, revision = 1))
        assertTrue(client.uiState.value.authenticated)
        assertTrue(transport.hasConnection())

        client.startScan()
        assertTrue(client.uiState.value.authenticated)
        assertTrue(client.uiState.value.browsingDevices)
        assertTrue(transport.hasConnection())
        assertTrue(transport.scanning)
        transport.discover(DplsTransportDevice("device-2", "Test-DPLS-002", 0x5678, -30))
        assertEquals(2, client.uiState.value.devices.size)

        client.resumeSession()
        assertTrue(client.uiState.value.authenticated)
        assertFalse(client.uiState.value.browsingDevices)
        assertTrue(transport.hasConnection())
        assertFalse(transport.scanning)

        client.startScan()
        client.identify("device-2")
        assertFalse(client.uiState.value.authenticated)
        assertFalse(client.uiState.value.browsingDevices)
        assertEquals("device-2", client.uiState.value.selectedDevice?.address)
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
        assertTrue(client.uiState.value.needsPeriodicStateRefresh)

        transport.bluetoothUnavailable()
        assertFalse(client.uiState.value.authenticated)
        assertEquals(ConnectionPhase.RECONNECTING, client.uiState.value.phase)
        assertTrue(client.uiState.value.staleState)
        client.close()
    }

    @Test
    fun identifyDisconnectFailsInsteadOfReconnectLoop() {
        val transport = FakeTransport()
        val client = DplsClient(
            transport,
            FakePlatform(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.startScan()
        transport.discover(DplsTransportDevice("device-1", "Test-DPLS", null, -40))
        client.identify("device-1")
        assertTrue(client.uiState.value.identifyActive)
        assertEquals("device-1", client.uiState.value.selectedDevice?.address)
        transport.dropped("link lost")
        assertEquals(ConnectionPhase.ERROR, client.uiState.value.phase)
        assertEquals("link lost", client.uiState.value.error)
        assertFalse(client.uiState.value.identifyLedLive)
        client.close()
    }

    @Test
    fun preAuthDisconnectWithoutIdentifyReturnsToDiscovery() {
        val transport = FakeTransport()
        val client = DplsClient(
            transport,
            FakePlatform(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.startScan()
        transport.discover(DplsTransportDevice("device-1", "Test-DPLS", null, -40))
        client.connect("device-1")
        transport.connected()
        transport.dropped("link lost")
        assertNull(client.uiState.value.selectedDevice)
        assertEquals(ConnectionPhase.IDLE, client.uiState.value.phase)
        client.close()
    }

    @Test
    fun confirmAfterDropClearsSelectedDevice() {
        val transport = FakeTransport(completeWritesImmediately = false)
        val client = DplsClient(
            transport,
            FakePlatform(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.startScan()
        transport.discover(DplsTransportDevice("device-1", "Test-DPLS", null, -40))
        client.identify("device-1")
        transport.connected()
        transport.subscribed()
        transport.completeWrite()
        transport.dropped("link lost")
        assertEquals(ConnectionPhase.ERROR, client.uiState.value.phase)
        client.confirmIdentifiedDevice()
        assertNull(client.uiState.value.selectedDevice)
        client.close()
    }

    @Test
    fun bluetoothPowerOnStartsPendingScan() {
        val transport = FakeTransport()
        transport.scanEnabled = false
        val client = DplsClient(
            transport,
            FakePlatform(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.startScan()
        assertEquals(ConnectionPhase.SCANNING, client.uiState.value.phase)
        assertFalse(transport.scanning)
        transport.scanEnabled = true
        transport.bluetoothAvailable()
        assertTrue(transport.scanning)
        client.close()
    }

    @Test
    fun identifyLedFollowsFirmwareSquareWaveAndWriteRttPhase() {
        assertTrue(DplsIdentifyLed.on(0))
        assertTrue(DplsIdentifyLed.on(499))
        assertFalse(DplsIdentifyLed.on(500))
        assertFalse(DplsIdentifyLed.on(999))
        assertTrue(DplsIdentifyLed.on(1_000))
        assertEquals(40, DplsIdentifyLed.phaseAtAckMs(100_000, 100_080))

        val transport = FakeTransport(completeWritesImmediately = false)
        val platform = FakePlatform()
        platform.now = 100_000
        val client = DplsClient(
            transport,
            platform,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.startScan()
        transport.discover(DplsTransportDevice("device-1", "Test-DPLS", null, -40))
        client.identify("device-1")
        transport.connected()
        transport.subscribed()
        assertEquals(DplsProtocol.Type.IDENTIFY_START, transport.lastFrame().type)
        platform.now = 100_080
        transport.completeWrite()
        assertTrue(client.uiState.value.identifyLedLive)
        assertEquals(40, client.uiState.value.identifyLedPhaseOffsetMs)
        transport.rssi(-72)
        assertEquals(-72, client.uiState.value.linkRssi)
        assertEquals(-72, client.uiState.value.selectedDevice?.rssi)
        client.stopIdentify()
        assertNull(client.uiState.value.linkRssi)
        client.close()
    }

    @Test
    fun logInfoAcksFirstChunkThenAssemblesJournal() {
        val transport = FakeTransport()
        val client = readyClient(transport)

        client.loadEventLog()
        assertEquals(DplsProtocol.Type.LOG_START, transport.lastFrame().type)
        assertEquals(0f, client.uiState.value.logProgress)

        val info = ByteArray(10)
        putU32(info, 0, 0x78563412)
        putU32(info, 4, 10)
        putU16(info, 8, 1)
        transport.receive(DplsProtocol.Type.LOG_INFO, info)
        val ack = transport.lastFrame()
        assertEquals(DplsProtocol.Type.LOG_ACK, ack.type)
        assertEquals(0, ack.payload[12].toInt() and 0xff)
        assertEquals(0, ack.payload[13].toInt() and 0xff)

        val chunk = ByteArray(13)
        putU16(chunk, 0, 0)
        chunk[2] = 1
        putU32(chunk, 3, 7)
        putU32(chunk, 7, 42)
        chunk[11] = 4
        chunk[12] = 0
        transport.receive(DplsProtocol.Type.LOG_CHUNK, chunk)
        assertEquals(1, client.uiState.value.eventLog.size)
        assertEquals(7, client.uiState.value.eventLog.single().sequence)
        assertEquals(null, client.uiState.value.logProgress)
        assertEquals(false, client.uiState.value.logHasMore)

        client.close()
    }

    @Test
    fun journalLoadsFirstPageThenMoreOnDemand() {
        val transport = FakeTransport()
        val client = readyClient(transport)

        client.loadEventLog()
        val info = ByteArray(10)
        putU32(info, 0, 0x78563412)
        putU32(info, 4, 160)
        putU16(info, 8, 16)
        transport.receive(DplsProtocol.Type.LOG_INFO, info)
        assertEquals(DplsProtocol.Type.LOG_ACK, transport.lastFrame().type)
        assertEquals(1, transport.lastFrame().payload[12].toInt() and 0xff)

        transport.receive(DplsProtocol.Type.LOG_CHUNK, logChunk(firstIndex = 1, count = 15, firstSequence = 101))
        assertEquals(15, client.uiState.value.eventLog.size)
        assertEquals(115, client.uiState.value.eventLog.first().sequence)
        assertEquals(16, client.uiState.value.logTotal)
        assertEquals(true, client.uiState.value.logHasMore)
        assertEquals(DplsProtocol.Type.LOG_ACK, transport.lastFrame().type)
        assertEquals(0, transport.lastFrame().payload[12].toInt() and 0xff)
        assertEquals(0, transport.lastFrame().payload[13].toInt() and 0xff)

        transport.receive(DplsProtocol.Type.LOG_CHUNK, logChunk(firstIndex = 0, count = 1, firstSequence = 100))
        assertEquals(16, client.uiState.value.eventLog.size)
        assertEquals(115, client.uiState.value.eventLog.first().sequence)
        assertEquals(100, client.uiState.value.eventLog.last().sequence)
        assertEquals(false, client.uiState.value.logHasMore)
        assertEquals(null, client.uiState.value.logProgress)
        assertEquals(42L, client.uiState.value.logFirstTimestampSeconds)
        assertEquals(42L, client.uiState.value.logLastTimestampSeconds)

        client.close()
    }

    @Test
    fun setDeviceNameShowsAppliedNotice() {
        val transport = FakeTransport()
        val client = readyClient(transport)

        client.setDeviceName("Цех-1")
        val sent = transport.lastFrame()
        assertEquals(DplsProtocol.Type.NAME_SET, sent.type)
        val commandId = readU32(sent.payload, 12)
        val result = ByteArray(5)
        putU32(result, 0, commandId)
        result[4] = 0
        transport.receive(DplsProtocol.Type.SETTINGS_RESULT, result)

        assertEquals(SettingsOp.DONE, client.uiState.value.settingsOp)
        assertEquals("Имя «Цех-1» применено", client.uiState.value.settingsNotice)
        assertEquals("Цех-1", client.uiState.value.selectedDevice?.userName)
        client.close()
    }

    @Test
    fun journalDrainLoadsRemainingPages() {
        val transport = FakeTransport()
        val client = readyClient(transport)

        client.loadEventLog()
        val info = ByteArray(10)
        putU32(info, 0, 0x78563412)
        putU32(info, 4, 310)
        putU16(info, 8, 31)
        transport.receive(DplsProtocol.Type.LOG_INFO, info)
        assertEquals(16, transport.lastFrame().payload[12].toInt() and 0xff)

        transport.receive(DplsProtocol.Type.LOG_CHUNK, logChunk(firstIndex = 16, count = 15, firstSequence = 116))
        assertEquals(15, client.uiState.value.eventLog.size)
        assertEquals(true, client.uiState.value.logHasMore)
        assertEquals(0, transport.lastFrame().payload[12].toInt() and 0xff)

        transport.receive(DplsProtocol.Type.LOG_CHUNK, logChunk(firstIndex = 0, count = 15, firstSequence = 100))
        assertEquals(30, client.uiState.value.eventLog.size)
        assertEquals(true, client.uiState.value.logHasMore)
        assertEquals(42L, client.uiState.value.logFirstTimestampSeconds)
        assertEquals(42L, client.uiState.value.logLastTimestampSeconds)

        client.loadRemainingEventLog()
        assertEquals(15, transport.lastFrame().payload[12].toInt() and 0xff)

        transport.receive(DplsProtocol.Type.LOG_CHUNK, logChunk(firstIndex = 15, count = 1, firstSequence = 115))
        assertEquals(31, client.uiState.value.eventLog.size)
        assertEquals(false, client.uiState.value.logHasMore)
        assertEquals(null, client.uiState.value.logProgress)
        client.close()
    }

    @Test
    fun journalReadsFirstAndLastTimestampsForPeriodScale() {
        val transport = FakeTransport()
        val client = readyClient(transport)
        client.loadEventLog()
        val info = ByteArray(10)
        putU32(info, 0, 0x78563412)
        putU32(info, 4, 310)
        putU16(info, 8, 31)
        transport.receive(DplsProtocol.Type.LOG_INFO, info)
        transport.receive(
            DplsProtocol.Type.LOG_CHUNK,
            logChunk(firstIndex = 16, count = 15, firstSequence = 116) { index -> index * 10L },
        )
        assertEquals(null, client.uiState.value.logFirstTimestampSeconds)
        transport.receive(
            DplsProtocol.Type.LOG_CHUNK,
            logChunk(firstIndex = 0, count = 15, firstSequence = 100) { index -> index * 10L },
        )
        assertEquals(0L, client.uiState.value.logFirstTimestampSeconds)
        assertEquals(300L, client.uiState.value.logLastTimestampSeconds)
        client.close()
    }

    @Test
    fun utcDateTimeUsesRussianCivilCalendar() {
        assertEquals("1 января 1970, 00:00:00", formatUtcDateTime(0))
        assertEquals("1 января 1970, 00:02:12", formatUtcDateTime(132))
    }

    @Test
    fun logAckWaitsUntilPreviousWriteCompletes() {
        val transport = FakeTransport(completeWritesImmediately = false)
        val client = readyClient(transport)

        client.loadEventLog()
        assertEquals(DplsProtocol.Type.LOG_START, transport.lastFrame().type)
        assertEquals(true, transport.lastFlush)
        assertEquals(false, transport.lastPriority)

        val info = ByteArray(10)
        putU32(info, 0, 0x78563412)
        putU32(info, 4, 10)
        putU16(info, 8, 1)
        transport.receive(DplsProtocol.Type.LOG_INFO, info)
        assertEquals(DplsProtocol.Type.LOG_START, transport.lastFrame().type)

        transport.completeWrite()
        val ack = transport.lastFrame()
        assertEquals(DplsProtocol.Type.LOG_ACK, ack.type)
        assertEquals(false, transport.lastPriority)
        assertEquals(0, ack.payload[12].toInt() and 0xff)
        assertEquals(0, ack.payload[13].toInt() and 0xff)
        assertEquals(14, ack.payload.size)

        client.close()
    }

    @Test
    fun uiThemePersistsAcrossDisconnect() {
        val transport = FakeTransport()
        val platform = FakePlatform()
        val client = DplsClient(
            transport,
            platform,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        assertEquals(UiTheme.SYSTEM, client.uiState.value.uiTheme)
        client.setUiTheme(UiTheme.LIGHT)
        assertEquals(UiTheme.LIGHT, client.uiState.value.uiTheme)
        assertEquals(UiTheme.LIGHT, platform.readUiTheme())
        client.disconnect()
        assertEquals(UiTheme.LIGHT, client.uiState.value.uiTheme)
        client.close()
    }

    @Test
    fun keepScreenAndHapticsPersistAcrossDisconnect() {
        val transport = FakeTransport()
        val platform = FakePlatform()
        val client = DplsClient(
            transport,
            platform,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        assertTrue(client.uiState.value.keepScreenOn)
        assertTrue(client.uiState.value.hapticsEnabled)
        client.setKeepScreenOn(false)
        client.setHapticsEnabled(false)
        assertFalse(platform.readKeepScreenOn())
        assertFalse(platform.readHapticsEnabled())
        client.disconnect()
        assertFalse(client.uiState.value.keepScreenOn)
        assertFalse(client.uiState.value.hapticsEnabled)
        client.close()
    }

    @Test
    fun forgetSavedPasswordClearsStoredVerifier() {
        val platform = FakePlatform()
        val transport = FakeTransport()
        val client = DplsClient(
            transport,
            platform,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.startScan()
        transport.discover(DplsTransportDevice("device-1", "Test-DPLS", 0x1234, -42))
        client.connect("device-1")
        transport.connected()
        transport.subscribed()
        val challenge = ByteArray(37).also {
            putU32(it, 0, 0x78563412)
            repeat(16) { index -> it[4 + index] = (0x20 + index).toByte() }
            repeat(16) { index -> it[20 + index] = (0x40 + index).toByte() }
            it[36] = 1
        }
        transport.receive(DplsProtocol.Type.AUTH_CHALLENGE, challenge)
        client.authenticate("12345678")
        transport.receive(DplsProtocol.Type.AUTH_RESULT, byteArrayOf(0, 0, 0) + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertTrue(client.uiState.value.savedCredentials)
        assertTrue(platform.readDeviceVerifier("addr:device-1") != null)

        client.forgetSavedPassword()
        assertFalse(client.uiState.value.savedCredentials)
        assertNull(platform.readDeviceVerifier("addr:device-1"))
        assertEquals("Сохранённый пароль удалён", client.uiState.value.settingsNotice)
        client.close()
    }

    @Test
    fun savedVerifierLogsInWithoutTypingPassword() {
        val platform = FakePlatform()
        val first = FakeTransport()
        val client = DplsClient(
            first,
            platform,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.startScan()
        first.discover(DplsTransportDevice("device-1", "Test-DPLS", 0x1234, -42))
        client.connect("device-1")
        first.connected()
        first.subscribed()
        val challenge = ByteArray(37).also {
            putU32(it, 0, 0x78563412)
            repeat(16) { index -> it[4 + index] = (0x20 + index).toByte() }
            repeat(16) { index -> it[20 + index] = (0x40 + index).toByte() }
            it[36] = 1
        }
        first.receive(DplsProtocol.Type.AUTH_CHALLENGE, challenge)
        assertTrue(client.uiState.value.awaitingUserPassword)
        client.authenticate("12345678")
        first.receive(DplsProtocol.Type.AUTH_RESULT, byteArrayOf(0, 0, 0) + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        client.close()

        val second = FakeTransport()
        val restored = DplsClient(
            second,
            platform,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        restored.startScan()
        second.discover(DplsTransportDevice("device-1", "Test-DPLS", 0x1234, -42))
        restored.connect("device-1")
        second.connected()
        second.subscribed()
        second.receive(DplsProtocol.Type.AUTH_CHALLENGE, challenge)
        assertFalse(restored.uiState.value.awaitingUserPassword)
        assertEquals(DplsProtocol.Type.AUTH_PROOF, second.lastFrame().type)
        restored.close()
    }

    @Test
    fun wrongPasswordForgetsSavedVerifier() {
        val platform = FakePlatform()
        val transport = FakeTransport()
        val client = DplsClient(
            transport,
            platform,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.startScan()
        transport.discover(DplsTransportDevice("device-1", "Test-DPLS", null, -42))
        client.connect("device-1")
        transport.connected()
        transport.subscribed()
        val challenge = ByteArray(37).also {
            putU32(it, 0, 7)
            it[36] = 1
        }
        transport.receive(DplsProtocol.Type.AUTH_CHALLENGE, challenge)
        client.authenticate("12345678")
        transport.receive(DplsProtocol.Type.AUTH_RESULT, byteArrayOf(1, 0, 0))
        assertTrue(client.uiState.value.awaitingUserPassword)
        assertNull(platform.readDeviceVerifier("addr:device-1"))
        client.close()
    }

    @Test
    fun logHistogramApiLoadsBinsThenStartsJournal() {
        val transport = FakeTransport()
        val client = readyClient(transport)
        client.loadLogHistogram()
        assertEquals(DplsProtocol.Type.LOG_HIST_GET, transport.lastFrame().type)
        assertEquals(24, transport.lastFrame().payload[12].toInt() and 0xff)

        val report = ByteArray(26)
        putU32(report, 0, 10)
        putU32(report, 4, 40)
        putU32(report, 8, 1)
        putU32(report, 12, 3)
        putU16(report, 16, 3)
        putU32(report, 18, 15)
        report[22] = 3
        report[23] = 1
        report[24] = 0
        report[25] = 2
        transport.receive(DplsProtocol.Type.LOG_HIST_REPORT, report)
        assertEquals(10L, client.uiState.value.logHistogram?.firstTimestampSeconds)
        assertEquals(listOf(1, 0, 2), client.uiState.value.logHistogram?.counts)
        assertEquals(DplsProtocol.Type.LOG_START, transport.lastFrame().type)
        client.close()
    }

    @Test
    fun refreshEventLogRestartsJournalTransfer() {
        val transport = FakeTransport()
        val client = readyClient(transport)
        client.loadEventLog()
        val info = ByteArray(10)
        putU32(info, 0, 0x78563412)
        putU32(info, 4, 10)
        putU16(info, 8, 1)
        transport.receive(DplsProtocol.Type.LOG_INFO, info)
        val chunk = ByteArray(13)
        putU16(chunk, 0, 0)
        chunk[2] = 1
        putU32(chunk, 3, 7)
        putU32(chunk, 7, 42)
        chunk[11] = 4
        chunk[12] = 0
        transport.receive(DplsProtocol.Type.LOG_CHUNK, chunk)
        assertEquals(1, client.uiState.value.eventLog.size)
        client.refreshEventLog()
        assertEquals(DplsProtocol.Type.LOG_START, transport.lastFrame().type)
        assertEquals(1, client.uiState.value.eventLog.size)
        client.close()
    }

    @Test
    fun refreshEventLogFetchesOnlyNewTail() {
        val transport = FakeTransport()
        val client = readyClient(transport)
        client.loadEventLog()
        val info = ByteArray(10)
        putU32(info, 0, 0x78563412)
        putU32(info, 4, 160)
        putU16(info, 8, 16)
        transport.receive(DplsProtocol.Type.LOG_INFO, info)
        transport.receive(DplsProtocol.Type.LOG_CHUNK, logChunk(firstIndex = 1, count = 15, firstSequence = 101))
        transport.receive(DplsProtocol.Type.LOG_CHUNK, logChunk(firstIndex = 0, count = 1, firstSequence = 100))
        assertEquals(16, client.uiState.value.eventLog.size)
        assertEquals(false, client.uiState.value.logHasMore)

        client.refreshEventLog()
        assertEquals(DplsProtocol.Type.LOG_START, transport.lastFrame().type)
        putU16(info, 8, 18)
        putU32(info, 4, 180)
        transport.receive(DplsProtocol.Type.LOG_INFO, info)
        val ack = transport.lastFrame()
        assertEquals(DplsProtocol.Type.LOG_ACK, ack.type)
        assertEquals(15, ack.payload[12].toInt() and 0xff)
        assertEquals(0, ack.payload[13].toInt() and 0xff)

        transport.receive(DplsProtocol.Type.LOG_CHUNK, logChunk(firstIndex = 15, count = 3, firstSequence = 115))
        assertEquals(18, client.uiState.value.eventLog.size)
        assertEquals(117, client.uiState.value.eventLog.first().sequence)
        assertEquals(100, client.uiState.value.eventLog.last().sequence)
        assertEquals(false, client.uiState.value.logHasMore)
        assertEquals(null, client.uiState.value.logProgress)
        client.close()
    }

    @Test
    fun refreshEventLogChecksNewestWhenCountUnchanged() {
        val transport = FakeTransport()
        val client = readyClient(transport)
        client.loadEventLog()
        val info = ByteArray(10)
        putU32(info, 0, 0x78563412)
        putU32(info, 4, 160)
        putU16(info, 8, 16)
        transport.receive(DplsProtocol.Type.LOG_INFO, info)
        transport.receive(DplsProtocol.Type.LOG_CHUNK, logChunk(firstIndex = 1, count = 15, firstSequence = 101))
        transport.receive(DplsProtocol.Type.LOG_CHUNK, logChunk(firstIndex = 0, count = 1, firstSequence = 100))

        client.refreshEventLog()
        transport.receive(DplsProtocol.Type.LOG_INFO, info)
        val ack = transport.lastFrame()
        assertEquals(DplsProtocol.Type.LOG_ACK, ack.type)
        assertEquals(15, ack.payload[12].toInt() and 0xff)

        transport.receive(DplsProtocol.Type.LOG_CHUNK, logChunk(firstIndex = 15, count = 1, firstSequence = 115))
        assertEquals(16, client.uiState.value.eventLog.size)
        assertEquals(115, client.uiState.value.eventLog.first().sequence)
        assertEquals(false, client.uiState.value.logHasMore)
        client.close()
    }

    @Test
    fun refreshEventLogReloadsWhenNewestSequenceShifts() {
        val transport = FakeTransport()
        val client = readyClient(transport)
        client.loadEventLog()
        val info = ByteArray(10)
        putU32(info, 0, 0x78563412)
        putU32(info, 4, 160)
        putU16(info, 8, 16)
        transport.receive(DplsProtocol.Type.LOG_INFO, info)
        transport.receive(DplsProtocol.Type.LOG_CHUNK, logChunk(firstIndex = 1, count = 15, firstSequence = 101))
        transport.receive(DplsProtocol.Type.LOG_CHUNK, logChunk(firstIndex = 0, count = 1, firstSequence = 100))

        client.refreshEventLog()
        transport.receive(DplsProtocol.Type.LOG_INFO, info)
        transport.receive(DplsProtocol.Type.LOG_CHUNK, logChunk(firstIndex = 15, count = 1, firstSequence = 200))
        val ack = transport.lastFrame()
        assertEquals(DplsProtocol.Type.LOG_ACK, ack.type)
        assertEquals(1, ack.payload[12].toInt() and 0xff)
        assertEquals(16, client.uiState.value.eventLog.size)
        client.close()
    }

    @Test
    fun advertisementFaultsSortAheadOfRssi() {
        val transport = FakeTransport()
        val client = DplsClient(
            transport,
            FakePlatform(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.startScan()
        transport.discover(DplsTransportDevice("quiet", "Test-DPLS-0001", 1, -30, 0))
        transport.discover(
            DplsTransportDevice(
                "fault",
                "Test-DPLS-0002",
                2,
                -80,
                ru.bolid.testdpls.core.protocol.DplsAdvertisement.REAL_SHORT,
            ),
        )
        val devices = client.uiState.value.devices
        assertEquals("fault", devices.first().address)
        assertTrue(devices.first().realShort)
        client.close()
    }

    @Test
    fun staleBondStopsIdentifyRetryLoop() {
        val transport = FakeTransport()
        val client = DplsClient(
            transport,
            FakePlatform(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.startScan()
        transport.discover(DplsTransportDevice("device-1", "Test-DPLS-3B31", 0x3B31, -51))
        client.identify("device-1")
        transport.staleBond()
        assertTrue(client.uiState.value.staleBond)
        assertEquals(ConnectionPhase.ERROR, client.uiState.value.phase)
        assertTrue(client.uiState.value.error.orEmpty().contains("сопряжение"))
        transport.dropped("GATT 133")
        assertTrue(client.uiState.value.staleBond)
        assertEquals(ConnectionPhase.ERROR, client.uiState.value.phase)
        client.close()
    }

    @Test
    fun peerRemovedPairingOnDisconnectIsStaleBond() {
        val transport = FakeTransport()
        val client = DplsClient(
            transport,
            FakePlatform(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.startScan()
        transport.discover(DplsTransportDevice("device-1", "Test-DPLS-3B31", 0x3B31, -51))
        client.identify("device-1")
        transport.dropped("Peer removed pairing information")
        assertTrue(client.uiState.value.staleBond)
        assertEquals(ConnectionPhase.ERROR, client.uiState.value.phase)
        assertTrue(client.uiState.value.error.orEmpty().contains("сопряжение"))
        assertFalse(client.uiState.value.error.orEmpty().contains("Peer removed"))
        client.close()
    }

    @Test
    fun peerRemovedPairingOnTransportErrorIsStaleBond() {
        val transport = FakeTransport()
        val client = DplsClient(
            transport,
            FakePlatform(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.startScan()
        transport.discover(DplsTransportDevice("device-1", "Test-DPLS-3B31", 0x3B31, -51))
        client.identify("device-1")
        transport.transportError("Подписка на BLE-события: Peer removed pairing information")
        assertTrue(client.uiState.value.staleBond)
        assertTrue(client.uiState.value.error.orEmpty().contains("сопряжение"))
        client.close()
    }

    @Test
    fun looksLikeStaleBondDetectsIosAndAndroidWording() {
        assertTrue(looksLikeStaleBondError("Peer removed pairing information"))
        assertTrue(looksLikeStaleBondError("Удалённая сторона удалила информацию о сопряжении"))
        assertTrue(looksLikeStaleBondError("Encryption timed out"))
        assertFalse(looksLikeStaleBondError("Связь с платой оборвалась до идентификации"))
        assertFalse(looksLikeStaleBondError(null))
    }

    @Test
    fun openBluetoothSettingsUsesPlatform() {
        val transport = FakeTransport()
        val platform = FakePlatform()
        val client = DplsClient(
            transport,
            platform,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.openBluetoothSettings()
        assertTrue(platform.openedSettings)
        client.close()
    }

    @Test
    fun identifyFailureNotifiesOperator() {
        val transport = FakeTransport()
        val platform = FakePlatform()
        val client = DplsClient(
            transport,
            platform,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        client.identify("device-1")
        assertTrue(platform.keepAlive)
        transport.dropped("Не удалось подписаться")
        assertEquals(DplsOperatorAlerts.ERROR_TITLE, platform.alerts.single().first)
        client.disconnect()
        assertFalse(platform.keepAlive)
        client.close()
    }

    private fun readyClient(transport: FakeTransport): DplsClient {
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
            putU32(it, 0, 0x78563412)
            it[36] = 1
        }
        transport.receive(DplsProtocol.Type.AUTH_CHALLENGE, challenge)
        client.authenticate("12345678")
        transport.receive(
            DplsProtocol.Type.AUTH_RESULT,
            byteArrayOf(0, 0, 0) + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        )
        transport.receive(DplsProtocol.Type.STATE_REPORT, statePayload(DplsMode.NORMAL, revision = 1))
        return client
    }

    private fun logChunk(
        firstIndex: Int,
        count: Int,
        firstSequence: Int,
        timestampAt: (Int) -> Long = { 42L },
    ): ByteArray =
        ByteArray(3 + count * 10).also { raw ->
            putU16(raw, 0, firstIndex)
            raw[2] = count.toByte()
            repeat(count) { index ->
                val offset = 3 + index * 10
                val absolute = firstIndex + index
                putU32(raw, offset, (firstSequence + index).toLong())
                putU32(raw, offset + 4, timestampAt(absolute))
                raw[offset + 8] = 4
                raw[offset + 9] = 0
            }
        }

    private fun commandResult(commandId: Long, mode: DplsMode): ByteArray = ByteArray(8).also { raw ->
        putU32(raw, 0, commandId)
        raw[4] = 0
        raw[5] = mode.wire.toByte()
        putU16(raw, 6, 30)
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
        var now: Long = 1_786_732_800_000L
        private var storedTheme: UiTheme = UiTheme.SYSTEM
        private var storedKeepScreenOn: Boolean = true
        private var storedHaptics: Boolean = true
        private val verifiers = mutableMapOf<String, ByteArray>()
        var openedSettings: Boolean = false
        var keepAlive: Boolean = false
        val alerts = mutableListOf<Pair<String, String>>()
        override fun nowMillis(): Long = now
        override fun secureRandomBytes(count: Int): ByteArray = ByteArray(count) { it.toByte() }
        override fun readUiTheme(): UiTheme = storedTheme
        override fun writeUiTheme(theme: UiTheme) {
            storedTheme = theme
        }
        override fun readKeepScreenOn(): Boolean = storedKeepScreenOn
        override fun writeKeepScreenOn(enabled: Boolean) {
            storedKeepScreenOn = enabled
        }
        override fun readHapticsEnabled(): Boolean = storedHaptics
        override fun writeHapticsEnabled(enabled: Boolean) {
            storedHaptics = enabled
        }
        override fun readDeviceVerifier(deviceKey: String): ByteArray? = verifiers[deviceKey]?.copyOf()
        override fun writeDeviceVerifier(deviceKey: String, verifier: ByteArray?) {
            if (verifier == null) verifiers.remove(deviceKey)
            else verifiers[deviceKey] = verifier.copyOf()
        }
        override fun openBluetoothSettings(): Boolean {
            openedSettings = true
            return true
        }
        override fun keepConnectionAlive(active: Boolean) {
            keepAlive = active
        }
        override fun notifyOperator(title: String, body: String) {
            alerts += title to body
        }
    }

    private class FakeTransport(
        private val completeWritesImmediately: Boolean = true,
    ) : DplsTransport {
        private lateinit var listener: DplsTransportListener
        private val writes = mutableListOf<ByteArray>()
        private var connected = false
        var scanEnabled: Boolean = true
        var scanning: Boolean = false
            private set
        var clearSelectionOnLastDisconnect = false
            private set
        var lastPriority: Boolean = false
            private set
        var lastFlush: Boolean = false
            private set
        var rssiValue: Int = -40

        override fun setListener(listener: DplsTransportListener) {
            this.listener = listener
        }

        override fun startScan(): Boolean {
            if (!scanEnabled) return false
            scanning = true
            return true
        }
        override fun stopScan() {
            scanning = false
        }
        override fun connect(address: String): Boolean = true
        override fun reconnect(): Boolean = true

        override fun send(bytes: ByteArray, priority: Boolean, flush: Boolean): Boolean {
            if (flush) writes.clear()
            lastPriority = priority
            lastFlush = flush
            writes += bytes.copyOf()
            if (completeWritesImmediately) listener.onWriteComplete(null)
            return true
        }

        fun completeWrite() = listener.onWriteComplete(null)

        override fun readRssi(): Boolean {
            listener.onRssi(rssiValue)
            return true
        }

        override fun disconnect(clearSelection: Boolean) {
            connected = false
            clearSelectionOnLastDisconnect = clearSelection
        }

        override fun hasConnection(): Boolean = connected
        override fun close() = Unit

        fun discover(device: DplsTransportDevice) = listener.onDiscovered(device)
        fun rssi(value: Int) {
            rssiValue = value
            listener.onRssi(value)
        }
        fun connected() = listener.onConnected()
        fun subscribed() {
            connected = true
            listener.onSubscribed(244)
        }
        fun bluetoothUnavailable() {
            connected = false
            scanning = false
            listener.onBluetoothUnavailable()
        }

        fun bluetoothAvailable() = listener.onBluetoothAvailable()

        fun dropped(reason: String = "dropped") {
            connected = false
            listener.onDisconnected(reason)
        }

        fun transportError(message: String) = listener.onTransportError(message)

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

        fun lastFrame(): DplsProtocol.Frame = frames().last()

        fun frames(): List<DplsProtocol.Frame> = writes.map {
            (decodeFrame(it) as DplsProtocol.DecodeResult.Success).frame
        }

        fun staleBond() = listener.onStaleBond()
    }
}
