package ru.bolid.testdpls.interop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import ru.bolid.testdpls.core.app.DplsClient
import ru.bolid.testdpls.core.domain.ConnectionPhase
import ru.bolid.testdpls.core.domain.DplsMode
import ru.bolid.testdpls.core.domain.SettingsOp
import ru.bolid.testdpls.core.protocol.DplsProtocol

/**
 * Phone-E2E-like scenarios over soft-BLE: real `dpls_simulator` + real `DplsClient`.
 *
 * Requires `DPLS_SIMULATOR` (path to cmake `dpls_simulator`). Tests no-op when unset.
 */
class SoftBleBridgeTest {
    private var transport: SimulatorBleTransport? = null
    private var platform: SoftBlePlatform? = null
    private var client: DplsClient? = null

    @BeforeTest
    fun setUp() {
        val path = System.getenv("DPLS_SIMULATOR") ?: return
        val ble = SimulatorBleTransport(path)
        ble.start()
        val services = SoftBlePlatform()
        val dpls = DplsClient(
            ble,
            services,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        ble.notifyRadioReady()
        transport = ble
        platform = services
        client = dpls
    }

    @AfterTest
    fun tearDown() {
        client?.close()
        transport?.close()
        client = null
        transport = null
        platform = null
    }

    @Test
    fun connectAuthenticateAndReachReady() {
        val dpls = client ?: return
        bringToReady(dpls)
        assertEquals(ConnectionPhase.READY, dpls.uiState.value.phase)
        assertTrue(dpls.uiState.value.authenticated)
        assertEquals(DplsMode.NORMAL, dpls.uiState.value.state?.mode)
        assertEquals("Test-DPLS-SIM", dpls.uiState.value.deviceInfo?.userName)
        assertTrue(requireNotNull(platform).hasVerifier("node:4660"))
    }

    @Test
    fun threeWrongPasswordsThenLoginLikePhoneE2e() {
        val dpls = client ?: return
        val ble = requireNotNull(transport)
        dpls.startScan()
        dpls.connect(SimulatorBleTransport.ADDRESS)
        ble.completeLink()
        awaitCondition("login challenge") {
            dpls.uiState.value.awaitingUserPassword ||
                dpls.uiState.value.phase == ConnectionPhase.AUTHENTICATING
        }

        repeat(3) { attempt ->
            dpls.authenticate(WRONG_PASSWORD)
            awaitCondition("wrong password ${attempt + 1}") {
                dpls.uiState.value.error == "Неверный пароль" &&
                    dpls.uiState.value.awaitingUserPassword &&
                    !dpls.uiState.value.authenticated
            }
            /* Firmware rate-limits AUTH_PROOF to 1 Hz; phone E2E sleeps 1.2 s. */
            ble.tick(1_200)
        }
        assertFalse(dpls.uiState.value.error.orEmpty().contains("заблокирована"))

        dpls.authenticate(PASSWORD)
        awaitCondition("READY after 3 wrong then correct") {
            dpls.uiState.value.phase == ConnectionPhase.READY &&
                dpls.uiState.value.authenticated
        }
    }

    @Test
    fun changePasswordRoundTripLikePhoneE2e() {
        val dpls = client ?: return
        val ble = requireNotNull(transport)
        bringToReady(dpls)

        dpls.changePassword(PASSWORD, PASSWORD_NEW)
        awaitCondition("password saved") {
            dpls.uiState.value.settingsOp == SettingsOp.DONE
        }
        dpls.disconnect()
        ble.tick(1_200)
        reconnectWithPassword(dpls, PASSWORD_NEW)
        dpls.changePassword(PASSWORD_NEW, PASSWORD)
        awaitCondition("password restored") {
            dpls.uiState.value.settingsOp == SettingsOp.DONE
        }
        dpls.disconnect()
        ble.tick(1_200)
        reconnectWithPassword(dpls, PASSWORD)
        assertEquals(ConnectionPhase.READY, dpls.uiState.value.phase)
    }

    @Test
    fun allTestModesRoundTripLikePhoneE2e() {
        val dpls = client ?: return
        bringToReady(dpls)

        for (mode in PHONE_E2E_MODES) {
            roundTripMode(dpls, mode)
        }
    }

    @Test
    fun renameDeviceOverSoftBle() {
        val dpls = client ?: return
        bringToReady(dpls)
        dpls.setDeviceName("LabBridge")
        awaitCondition("name saved") {
            dpls.uiState.value.deviceInfo?.userName == "LabBridge" ||
                dpls.uiState.value.settingsOp == ru.bolid.testdpls.core.domain.SettingsOp.DONE
        }
        assertEquals("LabBridge", dpls.uiState.value.deviceInfo?.userName)
    }

    @Test
    fun journalPostSyncDatesAndIncrementalRefreshAreEndToEnd() {
        val dpls = client ?: return
        val services = requireNotNull(platform)
        bringToReady(dpls)
        roundTripMode(dpls, DplsMode.SHORT_1)

        dpls.loadEventLog()
        awaitCondition("journal first page loaded") {
            dpls.uiState.value.logProgress == null && dpls.uiState.value.eventLog.isNotEmpty()
        }
        val first = dpls.uiState.value
        val unknown = first.eventLog.firstOrNull { event -> event.timestampSeconds == 0L }
            ?: fail("journal contains no pre-TIME_SYNC event: ${first.eventLog}")
        assertEquals("Время не установлено", dpls.formatEventTime(unknown))
        assertTrue(
            first.eventLog.all { event ->
                event.timestampSeconds == 0L ||
                    event.timestampSeconds in DplsProtocol.TIME_MIN_UNIX_SECONDS..DplsProtocol.TIME_MAX_UNIX_SECONDS
            },
            "journal must contain only UTC or zero: ${first.eventLog}",
        )
        val synced = first.eventLog.firstOrNull { event ->
            event.timestampSeconds in DplsProtocol.TIME_MIN_UNIX_SECONDS..DplsProtocol.TIME_MAX_UNIX_SECONDS
        } ?: fail("journal contains no post-TIME_SYNC event: ${first.eventLog}")
        val caption = dpls.formatEventTime(synced)
        assertEquals(services.formatLocalDateTime(synced.timestampSeconds), caption)
        assertFalse(caption.contains("2083"), "post-sync timestamp was transformed twice: $caption")

        val previousHead = first.eventLog.maxOf { it.sequence }
        roundTripMode(dpls, DplsMode.SHORT_2)
        dpls.refreshEventLog()
        awaitCondition("journal incremental refresh") {
            val state = dpls.uiState.value
            state.logProgress == null &&
                (state.eventLog.maxOfOrNull { it.sequence } ?: 0L) > previousHead
        }
        val refreshed = dpls.uiState.value.eventLog
        assertEquals(refreshed.size, refreshed.map { it.sequence }.distinct().size)
        assertEquals(refreshed.sortedByDescending { it.sequence }, refreshed)
    }

    @Test
    fun journalPaginationLoadsEveryEventWithoutDuplicates() {
        val dpls = client ?: return
        bringToReady(dpls)
        repeat(8) { index ->
            roundTripMode(dpls, if (index % 2 == 0) DplsMode.SHORT_1 else DplsMode.OPEN_T)
        }

        dpls.loadEventLog()
        awaitCondition("journal newest page loaded") {
            val state = dpls.uiState.value
            state.logProgress == null && state.eventLog.isNotEmpty() && state.logTotal > 15
        }
        assertTrue(dpls.uiState.value.logHasMore)

        dpls.loadRemainingEventLog()
        awaitCondition("journal all pages loaded") {
            val state = dpls.uiState.value
            state.logProgress == null && !state.logHasMore &&
                state.logTotal > 15 && state.eventLog.size == state.logTotal
        }
        val records = dpls.uiState.value.eventLog
        assertTrue(records.size > 15)
        assertEquals(records.size, records.map { it.sequence }.distinct().size)
        assertEquals(records.sortedByDescending { it.sequence }, records)
    }

    @Test
    fun identifyLedBecomesLiveAfterAck() {
        val dpls = client ?: return
        val ble = requireNotNull(transport)
        dpls.startScan()
        dpls.identify(SimulatorBleTransport.ADDRESS)
        ble.completeLink()
        awaitCondition("identify LED live") { dpls.uiState.value.identifyLedLive }
        assertTrue(dpls.uiState.value.identifyLedLive)
        dpls.stopIdentify()
        assertFalse(dpls.uiState.value.identifyLedLive)
    }

    @Test
    fun realShortRejectsDangerousMode() {
        val dpls = client ?: return
        val ble = requireNotNull(transport)
        bringToReady(dpls)
        ble.inject("REAL_SHORT 1")
        ble.tick(10)
        dpls.requestMode(DplsMode.OPEN_T)
        dpls.confirmMode()
        awaitCondition("command finished after real-short reject") {
            !dpls.uiState.value.commandInProgress
        }
        assertEquals(DplsMode.NORMAL, dpls.uiState.value.state?.mode)
    }

    @Test
    fun lowReserveDisablesTestControlsAndForcesReturnToNormal() {
        val dpls = client ?: return
        val ble = requireNotNull(transport)
        bringToReady(dpls)
        dpls.requestMode(DplsMode.SHORT_2)
        dpls.confirmMode()
        awaitCondition("short_2 live") { dpls.uiState.value.state?.mode == DplsMode.SHORT_2 }

        ble.inject("RESERVE_LOW 1")
        ble.tick(20)
        dpls.refreshState()
        awaitCondition("normal and controls disabled after low reserve") {
            val state = dpls.uiState.value
            state.state?.mode == DplsMode.NORMAL &&
                state.state?.reserveLow == true &&
                !state.controlsEnabled
        }
        assertFalse(dpls.uiState.value.controlsEnabled)

        ble.inject("RESERVE_LOW 0")
        ble.tick(20)
        dpls.refreshState()
        awaitCondition("controls restored after reserve recovery") {
            val state = dpls.uiState.value
            state.state?.reserveLow == false && state.controlsEnabled
        }
    }

    @Test
    fun disconnectClearsAuthentication() {
        val dpls = client ?: return
        bringToReady(dpls)
        dpls.disconnect()
        assertFalse(dpls.uiState.value.authenticated)
    }

    private fun roundTripMode(dpls: DplsClient, mode: DplsMode) {
        dpls.requestMode(mode)
        dpls.confirmMode()
        awaitCondition("mode $mode applied") {
            dpls.uiState.value.state?.mode == mode && !dpls.uiState.value.commandInProgress
        }
        dpls.returnToNormal()
        awaitCondition("return to NORMAL after $mode") {
            dpls.uiState.value.state?.mode == DplsMode.NORMAL &&
                !dpls.uiState.value.commandInProgress
        }
    }

    private fun bringToReady(dpls: DplsClient) {
        val ble = requireNotNull(transport)
        dpls.startScan()
        dpls.connect(SimulatorBleTransport.ADDRESS)
        ble.completeLink()
        awaitCondition("auth challenge ready") {
            dpls.uiState.value.phase == ConnectionPhase.AUTHENTICATING ||
                dpls.uiState.value.awaitingUserPassword ||
                dpls.uiState.value.authenticated
        }
        if (!dpls.uiState.value.authenticated) {
            dpls.authenticate(PASSWORD)
        }
        awaitCondition("READY after soft-BLE sync") {
            dpls.uiState.value.phase == ConnectionPhase.READY &&
                dpls.uiState.value.authenticated &&
                dpls.uiState.value.state != null
        }
    }

    private fun reconnectWithPassword(dpls: DplsClient, password: String) {
        val ble = requireNotNull(transport)
        dpls.startScan()
        dpls.connect(SimulatorBleTransport.ADDRESS)
        ble.completeLink()
        awaitCondition("auth after reconnect") {
            dpls.uiState.value.phase == ConnectionPhase.AUTHENTICATING ||
                dpls.uiState.value.awaitingUserPassword ||
                dpls.uiState.value.authenticated
        }
        if (!dpls.uiState.value.authenticated) {
            dpls.authenticate(password)
        }
        awaitCondition("READY after reconnect") {
            dpls.uiState.value.phase == ConnectionPhase.READY &&
                dpls.uiState.value.authenticated &&
                dpls.uiState.value.state != null
        }
    }

    private fun awaitCondition(label: String, timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            transport?.drain()
            if (predicate()) return
            Thread.sleep(5)
        }
        transport?.drain()
        val snapshot = client?.uiState?.value
        fail(
            "$label timed out; phase=${snapshot?.phase} auth=${snapshot?.authenticated} " +
                "mode=${snapshot?.state?.mode} settings=${snapshot?.settingsOp} " +
                "settingsError=${snapshot?.settingsError} status=${snapshot?.statusText} " +
                "error=${snapshot?.error} name=${snapshot?.deviceInfo?.userName}",
        )
    }

    companion object {
        private const val PASSWORD = "TestDpls01"
        private const val PASSWORD_NEW = "NewDpls01"
        private const val WRONG_PASSWORD = "WrongPwd1"
        private val PHONE_E2E_MODES = listOf(
            DplsMode.SHORT_1,
            DplsMode.SHORT_2,
            DplsMode.SHORT_T,
            DplsMode.OPEN_T,
            DplsMode.OPEN_MAIN,
        )
    }
}
