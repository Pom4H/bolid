package ru.bolid.testdpls.core.app

import kotlinx.coroutines.flow.StateFlow
import ru.bolid.testdpls.core.domain.DplsMode
import ru.bolid.testdpls.core.domain.DplsUiState
import ru.bolid.testdpls.core.domain.EventRecord
import ru.bolid.testdpls.core.domain.UiTheme

/**
 * UI -> product boundary shared by Compose on Android and iOS.
 *
 * This is deliberately a command surface, not a second state model. Implementations
 * must expose the single read-only [uiState] projection and keep lifecycle authority
 * inside the product runtime.
 */
interface DplsController {
    val uiState: StateFlow<DplsUiState>

    fun startScan()
    fun stopScan()
    fun resumeSession()
    fun connect(address: String)
    fun identify(address: String)
    fun stopIdentify()
    fun confirmIdentifiedDevice()
    fun updateSetupName(name: String)
    fun updateSetupPassword(password: String)
    fun updateSetupRepeatPassword(password: String)
    fun authenticate(password: String)
    fun setup(name: String, password: String)
    fun requestMode(mode: DplsMode)
    fun cancelMode()
    fun confirmMode()
    fun returnToNormal()
    fun loadEventLog()
    fun refreshEventLog()
    fun loadMoreEventLog()
    fun loadRemainingEventLog()
    fun loadLogHistogram()
    fun refreshState()
    fun requestDeviceInfo()
    fun clearSettingsOp()
    fun setDeviceName(name: String)
    fun changePassword(current: String, newPassword: String)
    fun setUiTheme(theme: UiTheme)
    fun setKeepScreenOn(enabled: Boolean)
    fun setHapticsEnabled(enabled: Boolean)
    fun forgetSavedPassword()
    fun disconnect()
    fun openBluetoothSettings()
    fun canOpenBluetoothSettings(): Boolean
    fun eventLogCsv(): String
    fun eventLogTxt(): String
    fun formatEventTime(record: EventRecord): String
}
