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
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_E2E_FILL_SETUP)
            addAction(ACTION_E2E_FILL_LOGIN)
        }
        registerReceiver(e2eReceiver, filter, RECEIVER_EXPORTED)
    }

    companion object {
        const val ACTION_E2E_FILL_SETUP = "com.thebutton.ble.E2E_FILL_SETUP"
        const val ACTION_E2E_FILL_LOGIN = "com.thebutton.ble.E2E_FILL_LOGIN"
        const val EXTRA_E2E_NAME = "name"
        const val EXTRA_E2E_PASSWORD = "password"
    }
}
