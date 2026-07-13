package com.thebutton.ble

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import com.thebutton.ble.ble.BleClient

class DplsApplication : Application() {
    val bleClient: BleClient by lazy { BleClient(this) }
    private var e2eReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        e2eReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_E2E_FILL_SETUP -> {
                        val name = intent.getStringExtra(EXTRA_E2E_NAME) ?: return
                        val password = intent.getStringExtra(EXTRA_E2E_PASSWORD) ?: return
                        bleClient.fillSetupFormForE2e(name, password)
                    }
                    ACTION_E2E_FILL_LOGIN -> {
                        val password = intent.getStringExtra(EXTRA_E2E_PASSWORD) ?: return
                        bleClient.fillLoginFormForE2e(password)
                    }
                    ACTION_E2E_RUN_MODE -> {
                        val wire = intent.getIntExtra(EXTRA_E2E_MODE_WIRE, -1)
                        bleClient.runTestModeForE2e(wire)
                    }
                    ACTION_E2E_IDENTIFY -> {
                        val address = intent.getStringExtra(EXTRA_E2E_ADDRESS) ?: return
                        bleClient.identify(address)
                    }
                    ACTION_E2E_CONFIRM -> bleClient.confirmIdentifiedDevice()
                    ACTION_E2E_EXPORT_CSV -> bleClient.exportLogCsvForE2e()
                    ACTION_E2E_UNPAIR_ALL -> bleClient.unpairDplsBondsForE2e()
                    ACTION_E2E_LOAD_JOURNAL -> bleClient.loadEventLog()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_E2E_FILL_SETUP)
            addAction(ACTION_E2E_FILL_LOGIN)
            addAction(ACTION_E2E_RUN_MODE)
            addAction(ACTION_E2E_IDENTIFY)
            addAction(ACTION_E2E_CONFIRM)
            addAction(ACTION_E2E_EXPORT_CSV)
            addAction(ACTION_E2E_UNPAIR_ALL)
            addAction(ACTION_E2E_LOAD_JOURNAL)
        }
        registerReceiver(e2eReceiver, filter, RECEIVER_EXPORTED)
    }

    companion object {
        const val ACTION_E2E_FILL_SETUP = "com.thebutton.ble.E2E_FILL_SETUP"
        const val ACTION_E2E_FILL_LOGIN = "com.thebutton.ble.E2E_FILL_LOGIN"
        const val ACTION_E2E_RUN_MODE = "com.thebutton.ble.E2E_RUN_MODE"
        const val ACTION_E2E_IDENTIFY = "com.thebutton.ble.E2E_IDENTIFY"
        const val ACTION_E2E_CONFIRM = "com.thebutton.ble.E2E_CONFIRM"
        const val ACTION_E2E_EXPORT_CSV = "com.thebutton.ble.E2E_EXPORT_CSV"
        const val ACTION_E2E_UNPAIR_ALL = "com.thebutton.ble.E2E_UNPAIR_ALL"
        const val ACTION_E2E_LOAD_JOURNAL = "com.thebutton.ble.E2E_LOAD_JOURNAL"
        const val EXTRA_E2E_NAME = "name"
        const val EXTRA_E2E_PASSWORD = "password"
        const val EXTRA_E2E_MODE_WIRE = "wire"
        const val EXTRA_E2E_ADDRESS = "address"
    }
}
