package ru.bolid.testdpls.core.app

private val MONTHS_RU = arrayOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)

internal fun formatUptimeClock(value: Long): String {
    fun two(part: Long): String = part.toString().padStart(2, '0')
    val seconds = value.coerceAtLeast(0L)
    return "${two(seconds / 3600)}:${two((seconds % 3600) / 60)}:${two(seconds % 60)}"
}

internal fun formatUtcDateTime(epochSeconds: Long): String {
    val z = epochSeconds.coerceAtLeast(0L)
    val days = z / 86_400L
    var rem = (z % 86_400L).toInt()
    val hour = rem / 3600
    rem %= 3600
    val minute = rem / 60
    val second = rem % 60
    val date = civilFromUnixDays(days)
    fun two(part: Int): String = part.toString().padStart(2, '0')
    return "${date.day} ${MONTHS_RU[date.month - 1]} ${date.year}, ${two(hour)}:${two(minute)}:${two(second)}"
}

private data class CivilDate(val year: Int, val month: Int, val day: Int)

/** Howard Hinnant civil_from_days: Unix day 0 is 1970-01-01. */
private fun civilFromUnixDays(unixDays: Long): CivilDate {
    var z = unixDays + 719468L
    val era = (if (z >= 0) z else z - 146096L) / 146097L
    val doe = z - era * 146097L
    val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
    val y = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val d = doy - (153L * mp + 2L) / 5L + 1L
    val m = mp + if (mp < 10L) 3L else -9L
    val year = y + if (m <= 2L) 1L else 0L
    return CivilDate(year.toInt(), m.toInt(), d.toInt())
}
