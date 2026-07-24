package ru.bolid.testdpls.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.bolid.testdpls.BleConnectionService
import ru.bolid.testdpls.DplsApplication
import ru.bolid.testdpls.ble.AndroidComplianceRules
import ru.bolid.testdpls.ble.BleClient
import ru.bolid.testdpls.ble.ConnectionPhase
import ru.bolid.testdpls.ble.DplsMode
import ru.bolid.testdpls.ble.DplsUiState
import ru.bolid.testdpls.ble.dplsEventTime
import ru.bolid.testdpls.ble.dplsEventTitle
import ru.bolid.testdpls.ble.isValidDplsPassword
import java.util.Date

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DplsApplication
    private val client: BleClient = app.bleClient

    private var sourceState: DplsUiState = client.uiState.value
    private val _uiState = MutableStateFlow(sourceState)
    val uiState: StateFlow<DplsUiState> = _uiState.asStateFlow()

    private var connectionTimeoutJob: Job? = null
    private var identifyCountdownJob: Job? = null
    private var lockoutJob: Job? = null
    private var identifyRemainingSeconds = 0
    private var authLockoutSeconds = 0
    private var localValidationError: String? = null
    private var permissionRecoveryRequired = false
    private var lockoutSourceMessage: String? = null

    init {
        viewModelScope.launch {
            client.uiState.collect { state ->
                sourceState = state

                if (state.phase !in CONNECTION_PHASES) {
                    connectionTimeoutJob?.cancel()
                    connectionTimeoutJob = null
                }

                if (state.identifyLedLive && state.identifyActive && identifyCountdownJob == null) {
                    startIdentifyCountdown()
                } else if (!state.identifyActive) {
                    identifyCountdownJob?.cancel()
                    identifyCountdownJob = null
                    identifyRemainingSeconds = 0
                }

                val sourceMessage = state.error ?: state.statusText
                val lockout = AndroidComplianceRules.parseLockoutSeconds(sourceMessage)
                if (lockout > 0 && sourceMessage != lockoutSourceMessage) {
                    lockoutSourceMessage = sourceMessage
                    startLockout(lockout)
                }
                if (state.authenticated) {
                    lockoutJob?.cancel()
                    lockoutJob = null
                    authLockoutSeconds = 0
                    lockoutSourceMessage = null
                }
                publish()
            }
        }
    }

    fun startScan() {
        localValidationError = null
        client.startScan()
    }

    fun resumeOrScan() {
        if (uiState.value.selectedDevice == null) client.startScan()
    }

    fun permissionsDenied() {
        permissionRecoveryRequired = true
        client.showExternalError("Нет разрешений Bluetooth. Откройте настройки приложения и разрешите доступ.")
        publish()
    }

    fun permissionsGranted() {
        permissionRecoveryRequired = false
        publish()
    }

    fun bluetoothDisabled() = client.showExternalError("Включите Bluetooth")
    fun stopScan() = client.stopScan()

    fun selectDevice(address: String) {
        localValidationError = null
        startConnectionService()
        startConnectionTimeout()
        client.connect(address)
    }

    fun authenticate(password: CharArray) {
        if (authLockoutSeconds > 0) {
            password.fill('\u0000')
            localValidationError = "Повторный ввод будет доступен через $authLockoutSeconds с"
            publish()
            return
        }
        if (!isValidDplsPassword(password.concatToString())) {
            password.fill('\u0000')
            localValidationError = PASSWORD_RULE
            publish()
            return
        }
        localValidationError = null
        publish()
        client.authenticate(password)
    }

    fun setup(name: String, password: CharArray) {
        if (name.isBlank()) {
            password.fill('\u0000')
            localValidationError = "Введите имя устройства"
            publish()
            return
        }
        if (!isValidDplsPassword(password.concatToString())) {
            password.fill('\u0000')
            localValidationError = PASSWORD_RULE
            publish()
            return
        }
        localValidationError = null
        publish()
        client.setup(name.trim(), password)
    }

    fun identify(address: String) {
        localValidationError = null
        startConnectionService()
        startConnectionTimeout()
        client.identify(address)
    }

    fun stopIdentify() {
        identifyCountdownJob?.cancel()
        identifyCountdownJob = null
        identifyRemainingSeconds = 0
        client.stopIdentify()
        publish()
    }

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
    fun requestDeviceInfo() = client.requestDeviceInfo()

    fun clearSettingsOp() {
        localValidationError = null
        client.clearSettingsOp()
        publish()
    }

    fun setDeviceName(name: String) {
        localValidationError = null
        client.setDeviceName(name)
    }

    fun changePassword(current: CharArray, new: CharArray) {
        if (current.isEmpty()) {
            current.fill('\u0000')
            new.fill('\u0000')
            localValidationError = "Введите текущий пароль"
            publish()
            return
        }
        if (!isValidDplsPassword(new.concatToString())) {
            current.fill('\u0000')
            new.fill('\u0000')
            localValidationError = PASSWORD_RULE
            publish()
            return
        }
        localValidationError = null
        publish()
        client.changePassword(current, new)
    }

    fun disconnect() {
        connectionTimeoutJob?.cancel()
        identifyCountdownJob?.cancel()
        lockoutJob?.cancel()
        connectionTimeoutJob = null
        identifyCountdownJob = null
        lockoutJob = null
        client.disconnect()
        app.stopService(Intent(app, BleConnectionService::class.java))
    }

    fun exportFileName(extension: String, now: Date = Date()): String =
        AndroidComplianceRules.exportFileName(
            deviceId = uiState.value.deviceInfo?.deviceId ?: uiState.value.selectedDevice?.deviceId,
            extension = extension,
            now = now,
        )

    private fun currentRunFirstSeq(): Long =
        uiState.value.eventLog.filter { it.type == 1 }.maxOfOrNull { it.sequence } ?: 0L

    fun eventLogCsv(): String = buildString {
        val boot = uiState.value.deviceBootEpochSeconds
        val firstSeq = currentRunFirstSeq()
        appendLine("sequence;datetime;uptime_seconds;event_type;parameter;event")
        uiState.value.eventLog.forEach { event ->
            val ts = dplsEventTime(event, firstSeq, boot)
            appendLine(
                listOf(
                    event.sequence,
                    AndroidComplianceRules.csv(ts.full),
                    event.timestampSeconds,
                    event.type,
                    event.parameter,
                    AndroidComplianceRules.csv(dplsEventTitle(event.type, event.parameter)),
                ).joinToString(";"),
            )
        }
    }

    fun eventLogTxt(): String = buildString {
        val boot = uiState.value.deviceBootEpochSeconds
        val firstSeq = currentRunFirstSeq()
        appendLine("Журнал событий Тест-ДПЛС")
        appendLine("Устройство: ${uiState.value.deviceInfo?.userName ?: uiState.value.selectedDevice?.userName ?: "—"}")
        appendLine("Записей: ${uiState.value.eventLog.size}")
        appendLine("—".repeat(32))
        uiState.value.eventLog.forEach { event ->
            val ts = dplsEventTime(event, firstSeq, boot)
            appendLine(
                "#${event.sequence}  ${ts.full}  тип=${event.type}  параметр=${event.parameter}  " +
                    dplsEventTitle(event.type, event.parameter),
            )
        }
    }

    private fun startConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = viewModelScope.launch {
            delay(AndroidComplianceRules.CONNECTION_TIMEOUT_MILLIS)
            if (sourceState.phase in CONNECTION_PHASES) {
                client.disconnect()
                client.showExternalError("Не удалось подключиться к устройству за 10 секунд")
            }
        }
    }

    private fun startIdentifyCountdown() {
        identifyCountdownJob?.cancel()
        identifyCountdownJob = viewModelScope.launch {
            for (remaining in AndroidComplianceRules.IDENTIFY_DURATION_SECONDS downTo 1) {
                if (!sourceState.identifyActive) break
                identifyRemainingSeconds = remaining
                publish()
                delay(1_000)
            }
            if (sourceState.identifyActive) client.stopIdentify()
            identifyRemainingSeconds = 0
            identifyCountdownJob = null
            publish()
        }
    }

    private fun startLockout(seconds: Int) {
        lockoutJob?.cancel()
        lockoutJob = viewModelScope.launch {
            for (remaining in seconds downTo 1) {
                authLockoutSeconds = remaining
                localValidationError = null
                publish()
                delay(1_000)
            }
            authLockoutSeconds = 0
            lockoutJob = null
            publish()
        }
    }

    private fun publish() {
        val hideExpiredLockoutError = authLockoutSeconds == 0 &&
            AndroidComplianceRules.parseLockoutSeconds(sourceState.error) > 0
        _uiState.value = sourceState.copy(
            identifyRemainingSeconds = maxOf(identifyRemainingSeconds, sourceState.identifyRemainingSeconds),
            authLockoutSeconds = maxOf(authLockoutSeconds, sourceState.authLockoutSeconds),
            connectionRssi = sourceState.connectionRssi ?: sourceState.selectedDevice?.rssi,
            localValidationError = localValidationError,
            permissionRecoveryRequired = permissionRecoveryRequired,
            error = if (hideExpiredLockoutError) null else sourceState.error,
            statusText = if (hideExpiredLockoutError) "Можно повторить ввод пароля" else sourceState.statusText,
        )
    }

    private fun startConnectionService() {
        ContextCompat.startForegroundService(app, Intent(app, BleConnectionService::class.java))
    }

    companion object {
        private const val PASSWORD_RULE = "Пароль: не менее 8 символов, только латинские буквы и цифры"
        private val CONNECTION_PHASES = setOf(
            ConnectionPhase.CONNECTING,
            ConnectionPhase.PAIRING,
            ConnectionPhase.NEGOTIATING_MTU,
            ConnectionPhase.DISCOVERING,
            ConnectionPhase.SUBSCRIBING,
        )
    }
}
