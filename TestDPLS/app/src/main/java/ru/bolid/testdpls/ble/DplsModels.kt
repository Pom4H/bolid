package ru.bolid.testdpls.ble

import java.util.Calendar

enum class DplsMode(
    val wire: Int,
    val title: String,
    val dangerous: Boolean,
    /** Короткое пояснение: какой порт затрагивается. */
    val portHint: String = "",
    /** Ожидаемая реакция контроллера С2000-КДЛ (ТЗ 7.4.2). */
    val controllerEffect: String = "",
) {
    NORMAL(0, "Норма", false, "Штатное прохождение линии"),
    OPEN_T(1, "Обрыв +Т", true, "Ответвление +Т", "КДЛ: потеря устройств ответвления"),
    OPEN_MAIN(2, "Обрыв магистрали", true, "Магистраль +1 ↔ +2", "КДЛ: «Нет связи» с устройствами за разрывом"),
    SHORT_1(3, "КЗ +1", true, "Порт +1", "КДЛ: «Короткое замыкание ДПЛС»"),
    SHORT_2(4, "КЗ +2", true, "Порт +2", "КДЛ: «Короткое замыкание ДПЛС»"),
    SHORT_T(5, "КЗ +Т", true, "Ответвление +Т", "КДЛ: «Короткое замыкание ДПЛС»");

    companion object {
        fun fromWire(value: Int) = entries.firstOrNull { it.wire == value }
    }
}

enum class PowerSource(val title: String) { DPLS("ДПЛС"), RESERVE("Резерв") }

enum class RssiQuality(val title: String) {
    UNKNOWN("Нет данных"),
    GOOD("Хорошая связь"),
    MEDIUM("Средняя связь"),
    WEAK("Слабая связь"),
}

fun rssiQuality(rssi: Int?): RssiQuality = when {
    rssi == null -> RssiQuality.UNKNOWN
    rssi >= -65 -> RssiQuality.GOOD
    rssi >= -80 -> RssiQuality.MEDIUM
    else -> RssiQuality.WEAK
}

/** Пароль по ТЗ: не менее 8 символов, только латинские буквы и цифры. */
fun isValidDplsPassword(value: CharSequence): Boolean =
    value.length >= 8 && value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }

/**
 * Отметка времени записи журнала. Для событий текущего запуска (sequence не
 * раньше последней загрузки) есть календарные дата/время — момент загрузки
 * синхронизирован с часами телефона. Для прошлых запусков момент загрузки
 * неизвестен, поэтому показываем относительное время «от запуска» без выдуманной
 * даты (ТЗ 7.5.2).
 */
data class DplsEventTime(val dateLabel: String?, val time: String, val full: String)

fun dplsEventTime(e: EventRecord, currentRunFirstSeq: Long, bootEpochSec: Long?): DplsEventTime {
    if (e.sequence >= currentRunFirstSeq && bootEpochSec != null) {
        val cal = Calendar.getInstance().apply { timeInMillis = (bootEpochSec + e.timestampSeconds) * 1000L }
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val mo = cal.get(Calendar.MONTH) + 1
        val y = cal.get(Calendar.YEAR)
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val mi = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)
        val time = "%02d:%02d:%02d".format(h, mi, s)
        return DplsEventTime(
            dateLabel = "%02d.%02d.%04d".format(d, mo, y),
            time = time,
            full = "%02d.%02d.%04d %s".format(d, mo, y, time),
        )
    }
    val t = e.timestampSeconds
    val rel = "+%02d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
    return DplsEventTime(dateLabel = null, time = rel, full = "$rel (от запуска)")
}

/** Человекочитаемое название события журнала — общее для экрана и выгрузки. */
fun dplsEventTitle(type: Int, parameter: Int): String = when (type) {
    1 -> "Запуск устройства"
    2 -> "BLE подключение"
    3 -> "BLE отключение"
    4 -> "Успешный вход"
    5 -> "Ошибка входа · попытка $parameter"
    6 -> "Вход заблокирован"
    7 -> "Режим: ${DplsMode.fromWire(parameter)?.title ?: "код $parameter"}"
    8 -> dplsAutoReturnTitle(parameter)
    9 -> "Идентификация начата"
    10 -> "Идентификация остановлена"
    11 -> "Пароль установлен"
    12 -> "Питание: ${if (parameter == 0) "от ДПЛС" else "от резерва"}"
    13 -> "Резерв: ${if (parameter == 0) "норма" else "низкий заряд"}"
    14 -> "Автоизоляция КЗ: ${if (parameter == 0) "снята" else "активна"}"
    else -> "Событие $type · $parameter"
}

private fun dplsAutoReturnTitle(reason: Int): String = when (reason) {
    0 -> "Автовозврат в «Норма» (команда оператора)"
    1 -> "Автовозврат в «Норма» (таймер)"
    2 -> "Автовозврат в «Норма» (таймаут сессии)"
    3 -> "Автовозврат в «Норма» (отключение BLE)"
    4 -> "Автовозврат в «Норма» (низкий резерв)"
    5 -> "Автовозврат в «Норма» (ошибка)"
    6 -> "Автовозврат в «Норма» (перезапуск)"
    7 -> "Автовозврат в «Норма» (автоизоляция КЗ)"
    else -> "Автовозврат в «Норма»"
}

enum class ConnectionPhase {
    IDLE, SCANNING, CONNECTING, PAIRING, NEGOTIATING_MTU, DISCOVERING,
    SUBSCRIBING, AUTHENTICATING, SYNCHRONIZING, READY, RECONNECTING, ERROR,
}

data class DiscoveredDevice(
    val address: String,
    val advertisedName: String,
    val userName: String?,
    val deviceId: Long?,
    val rssi: Int,
    /** Advertising текущей прошивки не передаёт этот признак, поэтому null = неизвестно до HELLO. */
    val initialized: Boolean? = null,
)

data class DeviceState(
    val mode: DplsMode,
    val voltageMv: Int,
    val powerSource: PowerSource,
    val reserveLow: Boolean,
    /** Hardware is isolating a real downstream short circuit (BRIZ-T function). */
    val realShort: Boolean,
    val automaticReturnSeconds: Int,
    val uptimeSeconds: Long,
    val revision: Long,
    /** Wall-clock millis when this snapshot was received from the device. */
    val receivedAtMillis: Long = 0L,
    /** STATE_REPORT validity mask. */
    val lineVoltageValid: Boolean = true,
    val reserveValid: Boolean = true,
    val powerValid: Boolean = true,
    val autoIsoValid: Boolean = true,
    val adcCalibrated: Boolean = false,
    val port1VoltageMv: Int = voltageMv,
    val port2VoltageMv: Int = 0,
    val portTVoltageMv: Int = 0,
    val reserveVoltageMv: Int = 0,
    val port1VoltageValid: Boolean = lineVoltageValid,
    val port2VoltageValid: Boolean = false,
    val portTVoltageValid: Boolean = false,
    val reserveVoltageValid: Boolean = false,
)

data class EventRecord(
    val sequence: Long,
    val timestampSeconds: Long,
    val type: Int,
    val parameter: Int,
)

data class DeviceInfo(
    val deviceId: Long,
    val protocolVersion: Int,
    val firmwareVersion: String,
    val hardwareRevision: Int,
    val adcPresent: Boolean,
    val hardwareReadback: Boolean,
    val adcCalibrated: Boolean,
    val userName: String,
    val multiVoltageReport: Boolean = false,
) {
    val shortId: String get() = "DPLS-%08X".format(deviceId)
}

enum class SettingsOp { NONE, IN_PROGRESS, DONE, FAILED }

/** UTF-8 truncation without splitting a multi-byte code point. */
internal fun utf8Truncate(value: String, maxBytes: Int): ByteArray {
    var end = 0
    var bytes = 0
    while (end < value.length) {
        val next = if (Character.isHighSurrogate(value[end]) && end + 1 < value.length) end + 2 else end + 1
        val step = value.substring(end, next).encodeToByteArray().size
        if (bytes + step > maxBytes) break
        bytes += step
        end = next
    }
    return value.substring(0, end).encodeToByteArray()
}

data class DplsUiState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val statusText: String = "Готово к поиску",
    val devices: List<DiscoveredDevice> = emptyList(),
    val selectedDevice: DiscoveredDevice? = null,
    val initialized: Boolean = false,
    val credentialsReady: Boolean = false,
    val authenticated: Boolean = false,
    val state: DeviceState? = null,
    val pendingMode: DplsMode? = null,
    val commandInProgress: Boolean = false,
    val staleState: Boolean = false,
    val lastAckMillis: Long? = null,
    val eventLog: List<EventRecord> = emptyList(),
    val deviceBootEpochSeconds: Long? = null,
    val logProgress: Float? = null,
    val identifyActive: Boolean = false,
    val identifyLedLive: Boolean = false,
    val identifyRemainingSeconds: Int = 0,
    val setupName: String = "",
    val setupPassword: String = "",
    val setupRepeatPassword: String = "",
    val awaitingUserPassword: Boolean = false,
    val deviceInfo: DeviceInfo? = null,
    val settingsOp: SettingsOp = SettingsOp.NONE,
    val settingsError: String? = null,
    val localValidationError: String? = null,
    val authLockoutSeconds: Int = 0,
    val connectionRssi: Int? = null,
    val connectionRssiUpdatedAtMillis: Long? = null,
    val permissionRecoveryRequired: Boolean = false,
    val error: String? = null,
) {
    val controlsEnabled: Boolean
        get() = phase == ConnectionPhase.READY && authenticated && !commandInProgress

    val passwordInputEnabled: Boolean
        get() = authLockoutSeconds <= 0

    val setupFormReady: Boolean
        get() = credentialsReady &&
            isValidDplsPassword(setupPassword) &&
            (initialized || (setupRepeatPassword == setupPassword && setupName.isNotBlank()))
}
