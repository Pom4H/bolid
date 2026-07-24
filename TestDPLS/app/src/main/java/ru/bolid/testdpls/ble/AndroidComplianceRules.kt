package ru.bolid.testdpls.ble

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AndroidComplianceRules {
    const val CONNECTION_TIMEOUT_MILLIS = 10_000L
    const val IDENTIFY_DURATION_SECONDS = 60
    const val SESSION_LOSS_RETURN_SECONDS = 10

    private val lockoutPattern = Regex("заблокирована на\\s+(\\d+)\\s+с", RegexOption.IGNORE_CASE)

    fun parseLockoutSeconds(message: String?): Int =
        message?.let { lockoutPattern.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() } ?: 0

    fun exportFileName(
        deviceId: Long?,
        extension: String,
        now: Date = Date(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).apply {
            this.timeZone = timeZone
        }.format(now)
        val device = deviceId?.let { "Test-DPLS-%08X".format(it) } ?: "Test-DPLS"
        return "${device}_${timestamp}.${extension.trimStart('.')}"
    }

    fun csv(value: Any?): String = "\"${value?.toString().orEmpty().replace("\"", "\"\"")}\""
}
