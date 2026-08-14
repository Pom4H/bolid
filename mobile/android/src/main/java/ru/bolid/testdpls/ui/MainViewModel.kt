package ru.bolid.testdpls.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import ru.bolid.testdpls.BleConnectionService
import ru.bolid.testdpls.DplsApplication
import ru.bolid.testdpls.ble.BleClient
import ru.bolid.testdpls.ble.currentRunFirstSeq
import ru.bolid.testdpls.ble.formatEventLogCsv
import ru.bolid.testdpls.ble.formatEventLogTxt
import ru.bolid.testdpls.core.app.DplsController
import ru.bolid.testdpls.core.domain.DplsMode
import ru.bolid.testdpls.core.domain.DplsUiState

class MainViewModel(application: Application) : AndroidViewModel(application), DplsController {
    private val app = application as DplsApplication
    private val client: BleClient = app.bleClient
    override val uiState: StateFlow<DplsUiState> = client.uiState

    override fun startScan() = client.startScan()
    fun resumeOrScan() { if (uiState.value.selectedDevice == null) client.startScan() }
    fun permissionsDenied() = client.showExternalError("Нет разрешений Bluetooth")
    fun bluetoothDisabled() = client.showExternalError("Включите Bluetooth")
    override fun stopScan() = client.stopScan()
    override fun connect(address: String) { startConnectionService(); client.connect(address) }
    override fun identify(address: String) { startConnectionService(); client.identify(address) }
    override fun stopIdentify() = client.stopIdentify()
    override fun confirmIdentifiedDevice() = client.confirmIdentifiedDevice()
    override fun updateSetupName(name: String) = client.updateSetupName(name)
    override fun updateSetupPassword(password: String) = client.updateSetupPassword(password)
    override fun updateSetupRepeatPassword(password: String) = client.updateSetupRepeatPassword(password)
    override fun authenticate(password: String) = client.authenticate(password.toCharArray())
    override fun setup(name: String, password: String) = client.setup(name, password.toCharArray())
    override fun requestMode(mode: DplsMode) = client.requestMode(mode)
    override fun cancelMode() = client.cancelMode()
    override fun confirmMode() = client.confirmMode()
    override fun returnToNormal() = client.returnToNormal()
    override fun loadEventLog() = client.loadEventLog()
    override fun refreshState() = client.refreshState()
    override fun requestDeviceInfo() = client.requestDeviceInfo()
    override fun clearSettingsOp() = client.clearSettingsOp()
    override fun setDeviceName(name: String) = client.setDeviceName(name)
    override fun changePassword(current: String, newPassword: String) = client.changePassword(current.toCharArray(), newPassword.toCharArray())
    override fun disconnect() { client.disconnect(); app.stopService(Intent(app, BleConnectionService::class.java)) }

    override fun eventLogCsv(): String {
        val events = uiState.value.eventLog
        return formatEventLogCsv(events, currentRunFirstSeq(events), uiState.value.deviceBootEpochSeconds)
    }

    override fun eventLogTxt(): String {
        val events = uiState.value.eventLog
        val name = uiState.value.deviceInfo?.userName ?: uiState.value.selectedDevice?.userName ?: "—"
        return formatEventLogTxt(events, currentRunFirstSeq(events), uiState.value.deviceBootEpochSeconds, name)
    }

    private fun startConnectionService() {
        ContextCompat.startForegroundService(app, Intent(app, BleConnectionService::class.java))
    }
}
