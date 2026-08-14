package ru.bolid.testdpls.ui

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import ru.bolid.testdpls.BleConnectionService
import ru.bolid.testdpls.DplsApplication
import ru.bolid.testdpls.core.app.DplsController

class MainViewModel(application: Application) :
    AndroidViewModel(application),
    DplsController by (application as DplsApplication).client {

    private val app = application as DplsApplication
    private val client get() = app.client

    fun resumeOrScan() {
        if (uiState.value.selectedDevice == null) client.startScan()
    }

    fun permissionsDenied() = platformMessage("Нет разрешений Bluetooth")
    fun bluetoothDisabled() = platformMessage("Включите Bluetooth")

    override fun connect(address: String) {
        startConnectionService()
        client.connect(address)
    }

    override fun identify(address: String) {
        startConnectionService()
        client.identify(address)
    }

    override fun disconnect() {
        client.disconnect()
        app.stopService(Intent(app, BleConnectionService::class.java))
    }

    private fun platformMessage(message: String) {
        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }

    private fun startConnectionService() {
        ContextCompat.startForegroundService(app, Intent(app, BleConnectionService::class.java))
    }
}
