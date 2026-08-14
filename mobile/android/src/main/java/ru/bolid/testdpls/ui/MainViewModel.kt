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
import ru.bolid.testdpls.ble.currentRunFirstSeq
import ru.bolid.testdpls.ble.formatEventLogCsv
import ru.bolid.testdpls.ble.formatEventLogTxt
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

    fun eventLogCsv(): String {
        val events = uiState.value.eventLog
        return formatEventLogCsv(events, currentRunFirstSeq(events), uiState.value.deviceBootEpochSeconds)
    }

    fun eventLogTxt(): String {
        val events = uiState.value.eventLog
        val name = uiState.value.deviceInfo?.userName
            ?: uiState.value.selectedDevice?.userName
            ?: "—"
        return formatEventLogTxt(events, currentRunFirstSeq(events), uiState.value.deviceBootEpochSeconds, name)
    }

    private fun startConnectionService() {
        ContextCompat.startForegroundService(app, Intent(app, BleConnectionService::class.java))
    }
}
