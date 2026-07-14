package ru.bolid.testdpls.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.core.content.ContextCompat
import ru.bolid.testdpls.BleConnectionService
import ru.bolid.testdpls.DplsApplication
import ru.bolid.testdpls.ble.BleClient
import ru.bolid.testdpls.ble.DplsMode
import ru.bolid.testdpls.ble.DplsUiState
import ru.bolid.testdpls.ble.dplsEventTitle
import ru.bolid.testdpls.ble.dplsEventTime
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DplsApplication
    private val client: BleClient = app.bleClient
    val uiState: StateFlow<DplsUiState> = client.uiState

    fun startScan() = client.startScan()
    fun resumeOrScan() {
        if (uiState.value.selectedDevice == null) client.startScan()
    }
    fun permissionsDenied() = client.showExternalError("Нет разрешений Bluetooth")
    fun bluetoothDisabled() = client.showExternalError("Включите Bluetooth")
    fun stopScan() = client.stopScan()
    fun selectDevice(address: String) {
        startConnectionService()
        client.connect(address)
    }
    fun authenticate(password: CharArray) = client.authenticate(password)
    fun setup(name: String, password: CharArray) = client.setup(name, password)
    fun identify(address: String) {
        startConnectionService()
        client.identify(address)
    }

    fun stopIdentify() = client.stopIdentify()
    fun confirmIdentifiedDevice() = client.confirmIdentifiedDevice()
    fun fillLoginFormForE2e(password: String) = client.fillLoginFormForE2e(password)
    fun fillSetupFormForE2e(name: String, password: String) = client.fillSetupFormForE2e(name, password)
    fun updateSetupName(name: String) = client.updateSetupName(name)
    fun updateSetupPassword(password: String) = client.updateSetupPassword(password)
    fun updateSetupRepeatPassword(password: String) = client.updateSetupRepeatPassword(password)
    fun requestMode(mode: DplsMode) = client.requestMode(mode)
    fun cancelMode() = client.cancelMode()
    fun confirmMode() = client.confirmMode()
    fun returnToNormal() = client.returnToNormal()
    fun loadEventLog() = client.loadEventLog()
    fun refreshState() = client.refreshState()
    fun requestDeviceInfo() = client.requestDeviceInfo()
    fun clearSettingsOp() = client.clearSettingsOp()
    fun setDeviceName(name: String) = client.setDeviceName(name)
    fun changePassword(current: CharArray, new: CharArray) = client.changePassword(current, new)
    fun disconnect() {
        client.disconnect()
        app.stopService(Intent(app, BleConnectionService::class.java))
    }

    private fun currentRunFirstSeq(): Long =
        uiState.value.eventLog.filter { it.type == 1 }.maxOfOrNull { it.sequence } ?: 0L

    fun eventLogCsv(): String = buildString {
        val boot = uiState.value.deviceBootEpochSeconds
        val firstSeq = currentRunFirstSeq()
        appendLine("sequence;datetime;uptime_seconds;event_type;parameter;event")
        uiState.value.eventLog.forEach {
            val ts = dplsEventTime(it, firstSeq, boot)
            appendLine("${it.sequence};${ts.full};${it.timestampSeconds};${it.type};${it.parameter};\"${dplsEventTitle(it.type, it.parameter)}\"")
        }
    }

    fun eventLogTxt(): String = buildString {
        val boot = uiState.value.deviceBootEpochSeconds
        val firstSeq = currentRunFirstSeq()
        appendLine("Журнал событий Тест-ДПЛС")
        appendLine("Устройство: ${uiState.value.deviceInfo?.userName ?: uiState.value.selectedDevice?.userName ?: "—"}")
        appendLine("Записей: ${uiState.value.eventLog.size}")
        appendLine("—".repeat(32))
        uiState.value.eventLog.forEach {
            val ts = dplsEventTime(it, firstSeq, boot)
            appendLine("#${it.sequence}  ${ts.full}  ${dplsEventTitle(it.type, it.parameter)}")
        }
    }

    private fun startConnectionService() {
        ContextCompat.startForegroundService(app, Intent(app, BleConnectionService::class.java))
    }
}
