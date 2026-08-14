package ru.bolid.testdpls

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import ru.bolid.testdpls.ble.AndroidBleTransport
import ru.bolid.testdpls.core.app.DplsClient

class DplsApplication : Application() {
    val transport: AndroidBleTransport by lazy { AndroidBleTransport(this) }
    val client: DplsClient by lazy { DplsClient(transport, AndroidPlatformServices()) }

    private val e2eDriver: AndroidE2eDriver by lazy { AndroidE2eDriver(this, client, transport) }
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
                        e2eDriver.fillSetup(name, password)
                    }
                    ACTION_E2E_FILL_LOGIN -> {
                        val password = intent.getStringExtra(EXTRA_E2E_PASSWORD) ?: return
                        e2eDriver.fillLogin(password)
                    }
                    ACTION_E2E_RUN_MODE -> e2eDriver.runMode(intent.getIntExtra(EXTRA_E2E_MODE_WIRE, -1))
                    ACTION_E2E_IDENTIFY -> {
                        val address = intent.getStringExtra(EXTRA_E2E_ADDRESS) ?: return
                        e2eDriver.identify(address)
                    }
                    ACTION_E2E_CONFIRM -> e2eDriver.confirmIdentify()
                    ACTION_E2E_EXPORT_CSV -> e2eDriver.exportCsv()
                    ACTION_E2E_UNPAIR_ALL -> e2eDriver.unpairAll()
                    ACTION_E2E_LOAD_JOURNAL -> e2eDriver.loadJournal()
                    ACTION_E2E_SET_NAME -> {
                        val name = intent.getStringExtra(EXTRA_E2E_NAME) ?: return
                        e2eDriver.setName(name)
                    }
                    ACTION_E2E_SET_PASSWORD -> {
                        val current = intent.getStringExtra(EXTRA_E2E_PASSWORD) ?: return
                        val next = intent.getStringExtra(EXTRA_E2E_NEW_PASSWORD) ?: return
                        e2eDriver.setPassword(current, next)
                    }
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
            addAction(ACTION_E2E_SET_NAME)
            addAction(ACTION_E2E_SET_PASSWORD)
        }
        registerReceiver(e2eReceiver, filter, RECEIVER_EXPORTED)
    }

    companion object {
        const val ACTION_E2E_FILL_SETUP = "ru.bolid.testdpls.E2E_FILL_SETUP"
        const val ACTION_E2E_FILL_LOGIN = "ru.bolid.testdpls.E2E_FILL_LOGIN"
        const val ACTION_E2E_RUN_MODE = "ru.bolid.testdpls.E2E_RUN_MODE"
        const val ACTION_E2E_IDENTIFY = "ru.bolid.testdpls.E2E_IDENTIFY"
        const val ACTION_E2E_CONFIRM = "ru.bolid.testdpls.E2E_CONFIRM"
        const val ACTION_E2E_EXPORT_CSV = "ru.bolid.testdpls.E2E_EXPORT_CSV"
        const val ACTION_E2E_UNPAIR_ALL = "ru.bolid.testdpls.E2E_UNPAIR_ALL"
        const val ACTION_E2E_LOAD_JOURNAL = "ru.bolid.testdpls.E2E_LOAD_JOURNAL"
        const val ACTION_E2E_SET_NAME = "ru.bolid.testdpls.E2E_SET_NAME"
        const val ACTION_E2E_SET_PASSWORD = "ru.bolid.testdpls.E2E_SET_PASSWORD"
        const val EXTRA_E2E_NAME = "name"
        const val EXTRA_E2E_PASSWORD = "password"
        const val EXTRA_E2E_NEW_PASSWORD = "new_password"
        const val EXTRA_E2E_MODE_WIRE = "wire"
        const val EXTRA_E2E_ADDRESS = "address"
    }
}
