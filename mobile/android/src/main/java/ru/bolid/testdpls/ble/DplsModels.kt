package ru.bolid.testdpls.ble

typealias DplsMode = ru.bolid.testdpls.core.domain.DplsMode
typealias PowerSource = ru.bolid.testdpls.core.domain.PowerSource
typealias ConnectionPhase = ru.bolid.testdpls.core.domain.ConnectionPhase
typealias DiscoveredDevice = ru.bolid.testdpls.core.domain.DiscoveredDevice
typealias DeviceState = ru.bolid.testdpls.core.domain.DeviceState
typealias EventRecord = ru.bolid.testdpls.core.domain.EventRecord
typealias DeviceInfo = ru.bolid.testdpls.core.domain.DeviceInfo
typealias SettingsOp = ru.bolid.testdpls.core.domain.SettingsOp
typealias DplsUiState = ru.bolid.testdpls.core.domain.DplsUiState

/** Android presentation of a journal timestamp. Calendar conversion stays at the UI edge. */
data class DplsEventTime(val dateLabel: String?, val time: String, val full: String)

fun dplsEventTime(e: EventRecord, currentRunFirstSeq: Long, bootEpochSec: Long?): DplsEventTime {
    if (e.sequence >= currentRunFirstSeq && bootEpochSec != null) {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = (bootEpochSec + e.timestampSeconds) * 1000L
        }
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val mo = cal.get(java.util.Calendar.MONTH) + 1
        val y = cal.get(java.util.Calendar.YEAR)
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val mi = cal.get(java.util.Calendar.MINUTE)
        val s = cal.get(java.util.Calendar.SECOND)
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

/** UTF-8 truncation without splitting a surrogate pair. */
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
