package ru.bolid.testdpls.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.TimeZone

class AndroidComplianceRulesTest {

    @Test
    fun passwordRequiresEightAsciiLettersOrDigits() {
        assertTrue(isValidDplsPassword("Password1"))
        assertTrue(isValidDplsPassword("12345678"))
        assertFalse(isValidDplsPassword("short1"))
        assertFalse(isValidDplsPassword("пароль123"))
        assertFalse(isValidDplsPassword("Password-1"))
        assertFalse(isValidDplsPassword("Password 1"))
    }

    @Test
    fun rssiQualityUsesDocumentedThresholds() {
        assertEquals(RssiQuality.GOOD, rssiQuality(-65))
        assertEquals(RssiQuality.MEDIUM, rssiQuality(-66))
        assertEquals(RssiQuality.MEDIUM, rssiQuality(-80))
        assertEquals(RssiQuality.WEAK, rssiQuality(-81))
        assertEquals(RssiQuality.UNKNOWN, rssiQuality(null))
    }

    @Test
    fun parsesFirmwareLockoutMessage() {
        assertEquals(300, AndroidComplianceRules.parseLockoutSeconds("Аутентификация заблокирована на 300 с"))
        assertEquals(0, AndroidComplianceRules.parseLockoutSeconds("Неверный пароль"))
        assertEquals(0, AndroidComplianceRules.parseLockoutSeconds(null))
    }

    @Test
    fun exportNameContainsDeviceDateAndTime() {
        val name = AndroidComplianceRules.exportFileName(
            deviceId = 0x1A2B3C4D,
            extension = ".csv",
            now = Date(0),
            timeZone = TimeZone.getTimeZone("UTC"),
        )
        assertEquals("Test-DPLS-1A2B3C4D_1970-01-01_000000.csv", name)
    }

    @Test
    fun csvEscapesQuotes() {
        assertEquals("\"a\"\"b\"", AndroidComplianceRules.csv("a\"b"))
    }
}
