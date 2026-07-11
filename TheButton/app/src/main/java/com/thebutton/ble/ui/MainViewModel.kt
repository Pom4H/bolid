package com.thebutton.ble.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.core.content.ContextCompat
import com.thebutton.ble.BleConnectionService
import com.thebutton.ble.DplsApplication
import com.thebutton.ble.ble.BleClient
import com.thebutton.ble.ble.DplsMode
import com.thebutton.ble.ble.DplsUiState
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
    fun disconnect() {
        client.disconnect()
        app.stopService(Intent(app, BleConnectionService::class.java))
    }

    fun eventLogCsv(): String = buildString {
        appendLine("sequence;timestamp_seconds;event_type;parameter")
        uiState.value.eventLog.forEach { appendLine("${it.sequence};${it.timestampSeconds};${it.type};${it.parameter}") }
    }

    fun eventLogTxt(): String = buildString {
        appendLine("Журнал событий Тест-ДПЛС")
        uiState.value.eventLog.forEach {
            appendLine("#${it.sequence}  t=${it.timestampSeconds} с  событие ${it.type}  параметр ${it.parameter}")
        }
    }

    private fun startConnectionService() {
        ContextCompat.startForegroundService(app, Intent(app, BleConnectionService::class.java))
    }
}
