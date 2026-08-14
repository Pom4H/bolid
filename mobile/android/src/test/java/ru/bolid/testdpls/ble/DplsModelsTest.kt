package ru.bolid.testdpls.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DplsModelsTest {

    @Test
    fun modeFromWire_andTitles() {
        assertEquals(DplsMode.OPEN_MAIN, DplsMode.fromWire(2))
        assertNull(DplsMode.fromWire(99))
        assertTrue(DplsMode.SHORT_1.dangerous)
        assertFalse(DplsMode.NORMAL.dangerous)
        assertEquals("ДПЛС", PowerSource.DPLS.title)
        assertEquals("Резерв", PowerSource.RESERVE.title)
    }

    @Test
    fun eventTitles_coverSpecCodes() {
        assertEquals("Запуск устройства", dplsEventTitle(1, 0))
        assertEquals("BLE подключение", dplsEventTitle(2, 0))
        assertEquals("BLE отключение", dplsEventTitle(3, 0))
        assertEquals("Успешный вход", dplsEventTitle(4, 0))
        assertTrue(dplsEventTitle(5, 3).contains("3"))
        assertEquals("Вход заблокирован", dplsEventTitle(6, 0))
        assertEquals("Режим: Норма", dplsEventTitle(7, 0))
        assertTrue(dplsEventTitle(7, 99).contains("код 99"))
        assertTrue(dplsEventTitle(8, 1).contains("таймер"))
        assertTrue(dplsEventTitle(8, 7).contains("автоизоляция"))
        assertTrue(dplsEventTitle(8, 99).contains("Норма"))
        assertEquals("Идентификация начата", dplsEventTitle(9, 0))
        assertEquals("Идентификация остановлена", dplsEventTitle(10, 0))
        assertEquals("Пароль установлен", dplsEventTitle(11, 0))
        assertTrue(dplsEventTitle(12, 0).contains("ДПЛС"))
        assertTrue(dplsEventTitle(12, 1).contains("резерва"))
        assertTrue(dplsEventTitle(13, 0).contains("норма"))
        assertTrue(dplsEventTitle(13, 1).contains("низкий"))
        assertTrue(dplsEventTitle(14, 0).contains("снята"))
        assertTrue(dplsEventTitle(14, 1).contains("активна"))
        assertTrue(dplsEventTitle(99, 1).startsWith("Событие"))
        for (reason in 0..7) {
            assertTrue(dplsEventTitle(8, reason).contains("Норма"))
        }
    }

    @Test
    fun eventTime_calendarWhenBootKnown() {
        val event = EventRecord(sequence = 10, timestampSeconds = 0, type = 1, parameter = 6)
        val ts = dplsEventTime(event, currentRunFirstSeq = 10, bootEpochSec = 1_700_000_000L)
        assertEquals(ts.full, "${ts.dateLabel} ${ts.time}")
        assertNotNullDate(ts.dateLabel)
        val relative = dplsEventTime(event, currentRunFirstSeq = 11, bootEpochSec = 1_700_000_000L)
        assertNull(relative.dateLabel)
        assertTrue(relative.full.contains("от запуска"))
        val noBoot = dplsEventTime(EventRecord(1, 3661, 2, 0), 1, null)
        assertEquals("+01:01:01", noBoot.time)
    }

    private fun assertNotNullDate(value: String?) {
        assertTrue(value != null && value.length == 10)
    }

    @Test
    fun uiState_controlAndSetupFlags() {
        val idle = DplsUiState()
        assertFalse(idle.controlsEnabled)
        assertFalse(idle.setupFormReady)
        val ready = idle.copy(
            phase = ConnectionPhase.READY,
            authenticated = true,
            commandInProgress = false,
        )
        assertTrue(ready.controlsEnabled)
        val setup = idle.copy(
            credentialsReady = true,
            initialized = false,
            setupPassword = "password1",
            setupRepeatPassword = "password1",
            setupName = "Kit",
        )
        assertTrue(setup.setupFormReady)
        val login = idle.copy(
            credentialsReady = true,
            initialized = true,
            setupPassword = "password1",
        )
        assertTrue(login.setupFormReady)
    }

    @Test
    fun uiState_periodicRefresh_onlyInTestOrOffReady() {
        val snap = DeviceState(
            mode = DplsMode.NORMAL,
            voltageMv = 24000,
            powerSource = PowerSource.DPLS,
            reserveLow = false,
            realShort = false,
            automaticReturnSeconds = 0,
            uptimeSeconds = 1,
            revision = 1,
        )
        val idle = DplsUiState()
        assertFalse(idle.needsPeriodicStateRefresh)
        val norma = idle.copy(
            phase = ConnectionPhase.READY,
            authenticated = true,
            state = snap,
        )
        assertFalse(norma.needsPeriodicStateRefresh)
        val test = norma.copy(state = snap.copy(mode = DplsMode.SHORT_1))
        assertTrue(test.needsPeriodicStateRefresh)
        val busy = test.copy(commandInProgress = true)
        assertFalse(busy.needsPeriodicStateRefresh)
        val error = norma.copy(phase = ConnectionPhase.ERROR)
        assertTrue(error.needsPeriodicStateRefresh)
        val logging = test.copy(logProgress = 0.4f)
        assertFalse(logging.needsPeriodicStateRefresh)
    }
}
