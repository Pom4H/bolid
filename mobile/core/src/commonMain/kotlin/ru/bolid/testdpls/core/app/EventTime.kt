package ru.bolid.testdpls.core.app

import ru.bolid.testdpls.core.protocol.DplsProtocol

internal fun eventTimestampBasis(timestampSeconds: Long): String =
    if (timestampSeconds in DplsProtocol.TIME_MIN_UNIX_SECONDS..DplsProtocol.TIME_MAX_UNIX_SECONDS) "utc" else "uptime"

internal fun eventTimestampText(timestampSeconds: Long): String =
    if (eventTimestampBasis(timestampSeconds) == "utc") formatUnixUtc(timestampSeconds)
    else "+${formatDuration(timestampSeconds)}"

private fun formatDuration(seconds: Long): String {
    fun two(value: Long): String = value.toString().padStart(2, '0')
    return "${two(seconds / 3600)}:${two((seconds % 3600) / 60)}:${two(seconds % 60)}"
}

/* Gregorian conversion adapted from the civil-from-days algorithm. Keeping it
 * in commonMain avoids platform date APIs and guarantees identical Android/iOS
 * journal exports. The accepted protocol range is positive Unix time. */
private fun formatUnixUtc(seconds: Long): String {
    val days = seconds / 86_400L
    val secondOfDay = seconds % 86_400L
    var z = days + 719_468L
    val era = z / 146_097L
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    var year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
    val month = monthPrime + if (monthPrime < 10L) 3L else -9L
    if (month <= 2L) year += 1L

    val hour = secondOfDay / 3_600L
    val minute = (secondOfDay % 3_600L) / 60L
    val second = secondOfDay % 60L
    fun two(value: Long): String = value.toString().padStart(2, '0')
    return "${year.toString().padStart(4, '0')}-${two(month)}-${two(day)} ${two(hour)}:${two(minute)}:${two(second)} UTC"
}
