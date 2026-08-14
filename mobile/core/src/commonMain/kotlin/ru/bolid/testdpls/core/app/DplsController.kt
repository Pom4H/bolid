package ru.bolid.testdpls.core.app

import kotlinx.coroutines.flow.StateFlow
import ru.bolid.testdpls.core.domain.DplsMode
import ru.bolid.testdpls.core.domain.DplsUiState

interface DplsController {
    val uiState: StateFlow<DplsUiState>

    fun startScan()
    fun stopScan()
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
    fun refreshState()
    fun requestDeviceInfo()
    fun clearSettingsOp()
    fun setDeviceName(name: String)
    fun changePassword(current: String, newPassword: String)
    fun disconnect()
    fun eventLogCsv(): String
    fun eventLogTxt(): String
}
