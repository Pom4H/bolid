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
    fun requestMode(mode: DplsMode) = client.requestMode(mode)
    fun cancelMode() = client.cancelMode()
    fun confirmMode() = client.confirmMode()
    fun returnToNormal() = client.returnToNormal()
    fun loadEventLog() = client.loadEventLog()
    fun disconnect() {
        client.disconnect()
        app.stopService(Intent(app, BleConnectionService::class.java))
    }

    fun eventLogCsv(): String = buildString {
        appendLine("sequence;timestamp_seconds;event_type;parameter")
        uiState.value.eventLog.forEach { appendLine("${it.sequence};${it.timestampSeconds};${it.type};${it.parameter}") }
    }

    fun eventLogJson(): String = uiState.value.eventLog.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") {
        "  {\"sequence\":${it.sequence},\"timestampSeconds\":${it.timestampSeconds},\"eventType\":${it.type},\"parameter\":${it.parameter}}"
    }

    private fun startConnectionService() {
        ContextCompat.startForegroundService(app, Intent(app, BleConnectionService::class.java))
    }
}
