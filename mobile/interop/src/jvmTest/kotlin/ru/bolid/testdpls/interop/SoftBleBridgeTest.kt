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
    fun journalLoadReturnsEvents() {
        val dpls = client ?: return
        bringToReady(dpls)
        dpls.requestMode(DplsMode.SHORT_1)
        dpls.confirmMode()
        awaitCondition("short_1 live") { dpls.uiState.value.state?.mode == DplsMode.SHORT_1 }
        dpls.returnToNormal()
        awaitCondition("back to normal") { dpls.uiState.value.state?.mode == DplsMode.NORMAL }

        dpls.loadEventLog()
        awaitCondition("journal loaded") {
            dpls.uiState.value.logProgress == null && dpls.uiState.value.eventLog.isNotEmpty()
        }
        assertTrue(dpls.uiState.value.eventLog.isNotEmpty())
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
    fun lowReserveForcesReturnToNormal() {
        val dpls = client ?: return
        val ble = requireNotNull(transport)
        bringToReady(dpls)
        dpls.requestMode(DplsMode.SHORT_2)
        dpls.confirmMode()
        awaitCondition("short_2 live") { dpls.uiState.value.state?.mode == DplsMode.SHORT_2 }
        ble.inject("RESERVE_LOW 1")
        ble.tick(20)
        dpls.refreshState()
        awaitCondition("normal after low reserve") {
            dpls.uiState.value.state?.mode == DplsMode.NORMAL
        }
    }

    @Test
    fun disconnectClearsAuthentication() {
        val dpls = client ?: return
        bringToReady(dpls)
        dpls.disconnect()
        assertFalse(dpls.uiState.value.authenticated)
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
