package ru.bolid.testdpls

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import ru.bolid.testdpls.core.app.AndroidBleTransport
import ru.bolid.testdpls.core.app.DplsClient
import ru.bolid.testdpls.core.app.title
import ru.bolid.testdpls.core.domain.DplsMode

/** Debug-only ADB/broadcast driver. Product behavior remains in shared [DplsClient]. */
internal class AndroidE2eDriver(
    context: Context,
    private val client: DplsClient,
    private val transport: AndroidBleTransport,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var modeTarget: DplsMode? = null
    private var modePhase = ModePhase.DONE
    private var modeDeadlineMs = 0L

    private enum class ModePhase { APPLY, RETURN, DONE }

    fun fillSetup(name: String, password: String) {
        client.updateSetupName(name)
        client.updateSetupPassword(password)
        client.updateSetupRepeatPassword(password)
        handler.post { client.setup(name, password) }
    }

    fun fillLogin(password: String) {
        client.updateSetupPassword(password)
        handler.post { client.authenticate(password) }
    }

    fun identify(address: String) = client.identify(address)
    fun confirmIdentify() = client.confirmIdentifiedDevice()
    fun loadJournal() = client.loadEventLog()
    fun setName(name: String) = client.setDeviceName(name)
    fun setPassword(current: String, next: String) = client.changePassword(current, next)
    fun unpairAll() = transport.unpairDplsBondsForE2e()

    fun exportCsv() {
        val records = client.uiState.value.eventLog
        if (records.isEmpty()) {
            Log.e(TAG, "E2E export empty log")
            return
        }
        File(appContext.cacheDir, "e2e-export.csv").writeText(client.eventLogCsv(), Charsets.UTF_8)
        Log.i(TAG, "E2E export done records=${records.size}")
    }

    fun runMode(wire: Int) {
        val mode = DplsMode.fromWire(wire)
        if (mode == null || !mode.dangerous) {
            Log.e(TAG, "E2E mode invalid wire=$wire")
            return
        }
        handler.removeCallbacks(modeRunner)
        modeTarget = mode
        modePhase = ModePhase.APPLY
        modeDeadlineMs = System.currentTimeMillis() + MODE_TIMEOUT_MS
        handler.post(modeStarter)
    }

    private val modeStarter = object : Runnable {
        override fun run() {
            val target = modeTarget ?: return
            if (System.currentTimeMillis() > modeDeadlineMs) {
                Log.e(TAG, "E2E mode blocked: controls unavailable")
                modeTarget = null
                modePhase = ModePhase.DONE
                return
            }
            if (!client.uiState.value.controlsEnabled) {
                client.refreshState()
                handler.postDelayed(this, MODE_POLL_MS)
                return
            }
            client.requestMode(target)
            client.confirmMode()
            handler.post(modeRunner)
        }
    }

    private val modeRunner = object : Runnable {
        override fun run() {
            val target = modeTarget ?: return
            if (System.currentTimeMillis() > modeDeadlineMs) {
                Log.e(TAG, "E2E mode timeout: ${target.title}")
                modeTarget = null
                modePhase = ModePhase.DONE
                return
            }
            val state = client.uiState.value
            when (modePhase) {
                ModePhase.APPLY -> {
                    if (state.commandInProgress || state.state?.mode != target) {
                        handler.postDelayed(this, MODE_POLL_MS)
                        return
                    }
                    modePhase = ModePhase.RETURN
                    client.returnToNormal()
                    handler.postDelayed(this, MODE_POLL_MS)
                }
                ModePhase.RETURN -> {
                    if (state.commandInProgress || state.state?.mode != DplsMode.NORMAL) {
                        handler.postDelayed(this, MODE_POLL_MS)
                        return
                    }
                    Log.i(TAG, "E2E mode done: ${target.title}")
                    modeTarget = null
                    modePhase = ModePhase.DONE
                }
                ModePhase.DONE -> Unit
            }
        }
    }

    companion object {
        private const val TAG = "TestDplsE2e"
        private const val MODE_TIMEOUT_MS = 30_000L
        private const val MODE_POLL_MS = 40L
    }
}
